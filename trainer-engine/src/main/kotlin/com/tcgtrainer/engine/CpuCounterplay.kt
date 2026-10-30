package com.tcgtrainer.engine

/** Counterplay uses public abilities, paid retreats and attacks. Unknown hand cards remain anonymous. */
internal class CpuCounterplay(private val engine: RulesEngine, private val catalog: CardCatalog, private val budget: CpuSearchBudget = CpuSearchBudget(null)) {
    data class Evaluation(val risk: Double, val prizeRace: Double, val wins: Boolean = false)
    private val heuristic = CpuPlayer(catalog, CpuStrategy.TACTICAL)
    private val race = CpuPrizeRace(engine, catalog)
    private val board = CpuPositionEvaluator(engine, catalog)
    private val forecast = CpuForecast(catalog)

    fun evaluate(before: GameState, actor: Int, attack: GameAction.Attack?): Evaluation {
        budget.check()
        val post = postAction(before, actor, attack)
        if (post.phase == Phase.GAME_OVER) return Evaluation(if (post.winner == actor) -100_000.0 else if (post.winner == 1-actor) 100_000.0 else 0.0, 0.0, post.winner == actor)
        val opponent = 1-actor
        var frontier = listOf(post)
        var worst = 0.0
        var continuation: Double? = null
        var continuations = 0
        var explored = 0
        val visited = hashSetOf(post.copy(history = emptyList()))
        repeat(4) {
            budget.check()
            val children = mutableListOf<Pair<GameState, Double>>()
            for (state in frontier) {
                budget.check()
                if (explored >= 28 || engine.actingPlayer(state) != opponent) continue
                val actions = publicActions(state)
                for (action in actions.filterIsInstance<GameAction.Attack>()) {
                    budget.check()
                    worst = maxOf(worst, threat(state, actor, action))
                    val preview = forecast.preview(state, action) ?: continue
                    if (preview.uncertain || continuations >= 4) continue
                    continuations++
                    val reply = postAction(state, opponent, action)
                    if (reply.phase == Phase.MAIN && reply.currentPlayer == actor && reply.rng == state.rng) {
                        val value = nextTurnValue(reply, actor)
                        continuation = continuation?.let { minOf(it, value) } ?: value
                    }
                }
                for (action in actions.filter { it !is GameAction.Attack && it != GameAction.EndTurn }.take(8)) {
                    budget.check()
                    if (++explored > 28) break
                    val result = engine.apply(state, action)
                    val after = result.state
                    if (result.legality != Legality.Legal || after.rng != state.rng || after.turn > post.turn+1) continue
                    if (after.phase == Phase.GAME_OVER) {
                        if (after.winner == opponent) worst = 100_000.0
                        continue
                    }
                    if (engine.actingPlayer(after) != opponent || after.players[opponent].hand.size > state.players[opponent].hand.size) continue
                    if (!visited.add(after.copy(history = emptyList()))) continue
                    val danger = publicActions(after).filterIsInstance<GameAction.Attack>().maxOfOrNull { threat(after, actor, it) } ?: 0.0
                    children += after to danger
                    worst = maxOf(worst, danger)
                }
            }
            frontier = children.sortedByDescending { it.second }.take(4).map { it.first }
        }
        return Evaluation(worst, continuation?.let { race.value(post, actor)*.5 + it*.5 } ?: race.value(post, actor))
    }

    /** Reaches our following turn through a real public reply, then prepares using known cards only. */
    internal fun nextTurnValue(initial: GameState, actor: Int): Double {
        val values = java.util.IdentityHashMap<GameState, Double>()
        fun position(state: GameState): Double {
            budget.check()
            return values.getOrPut(state) { race.value(state, actor) + board.value(state, actor)*.3 }
        }
        var frontier = listOf(initial)
        var best = position(initial)
        var expanded = 0
        val seen = hashSetOf(initial.copy(history = emptyList()))
        repeat(3) {
            budget.check()
            val children = mutableListOf<Pair<GameState, Double>>()
            for (state in frontier) {
                budget.check()
                if (state.phase != Phase.MAIN || state.currentPlayer != actor) continue
                for (action in engine.cpuLegalActions(state)) {
                    budget.check()
                    if (action is GameAction.Attack) {
                        val preview = forecast.preview(state, action) ?: continue
                        val certainKo = preview.knockout && !preview.uncertain
                        val win = certainKo && (preview.prizes >= state.players[actor].prizes.size || state.players[1-actor].pokemon().size == 1)
                        best = maxOf(best, position(state) + minOf(preview.damage, preview.effectiveHp-preview.currentDamage).coerceAtLeast(0)*.5 +
                            (if (certainKo) preview.prizes*300.0 else 0.0) + if (win) 2000.0 else 0.0)
                    } else if (action is GameAction.AttachEnergy || action is GameAction.Evolve || action is GameAction.PlayBasic || action is GameAction.Retreat) {
                        if (expanded >= 24) continue
                        expanded++
                        val result = engine.apply(state, action)
                        val after = result.state
                        if (result.legality != Legality.Legal || after.rng != state.rng || after.phase != Phase.MAIN || after.turn != initial.turn) continue
                        if (after.players[actor].hand.any { card -> card.definitionId == "cpu-hidden-card" && state.players[actor].hand.none { it.id == card.id } }) continue
                        if (seen.add(after.copy(history = emptyList()))) children += after to position(after)
                    }
                }
            }
            frontier = children.sortedByDescending { it.second }.take(2).map { it.first }
        }
        return best
    }

    private fun publicActions(state: GameState): List<GameAction> {
        if (state.phase == Phase.RESOLVE_CHOICE) {
            val choice = state.resolution?.choices?.firstOrNull() ?: return emptyList()
            val hidden = state.players.flatMap { it.hand + it.deck + it.prizes }.filter { it.definitionId == "cpu-hidden-card" }.map { it.id }.toSet()
            if (choice.candidates.any { it in hidden } || choice.visibleCards.any { it in hidden }) return emptyList()
            val candidates = mutableListOf<GameAction>()
            runCatching { heuristic.chooseHeuristic(engine.cpuInput(state)) }.getOrNull()?.let(candidates::add)
            if (choice.min == 0) candidates += GameAction.ChooseCards(emptyList())
            if (choice.min <= 1 && choice.max >= 1) choice.candidates.take(12).forEach { candidates += GameAction.ChooseCards(listOf(it)) }
            return candidates.distinct().filter { engine.validate(state, it) == Legality.Legal }
        }
        return engine.cpuLegalActions(state).filter { it is GameAction.Attack || it is GameAction.Retreat ||
            it is GameAction.UseAbility || it is GameAction.Promote || it == GameAction.UseStadiumAbility || it == GameAction.EndTurn }
    }

    private fun threat(state: GameState, actor: Int, action: GameAction.Attack): Double {
        val source = state.players[1-actor].active ?: return 0.0
        val target = state.players[actor].pokemon().firstOrNull { it.id == action.targetId } ?: state.players[actor].active ?: return 0.0
        val preview = forecast.preview(state, action) ?: return 0.0
        val certain = !preview.uncertain && SpecialCondition.CONFUSED !in source.conditions && source.defendingAttackFailsOnTailsTurn != state.turn
        val ko = preview.knockout && certain
        val prizes = if (ko) preview.prizes else 0
        val damage = minOf(preview.damage, (preview.effectiveHp-preview.currentDamage).coerceAtLeast(0))
        val attack = engine.availableAttacks(state, 1-actor, source).firstOrNull { it.id == action.attackId }
        val wins = (ko && (prizes >= state.players[1-actor].prizes.size || state.players[actor].pokemon().size == 1)) ||
            (attack?.effects?.contains(Effect.WinIfOnePrizeRemaining) == true && state.players[1-actor].prizes.size == 1 && certain)
        val engineValue = if (engine.effectiveAbilities(state, actor, target).any { it.draw > 0 || it.drawTo > 0 || it.attachHandEnergyType != null }) 120 else 0
        return damage.coerceAtLeast(0)*.45 + prizes*950.0 + (if (wins) 100_000.0 else 0.0) + (if (ko) engineValue else 0)
    }

    private fun postAction(before: GameState, actor: Int, attack: GameAction.Attack?): GameState {
        val preview = attack?.let { forecast.preview(before, it) }
        if (preview?.uncertain == true) return approximate(before, actor, attack)
        val first = engine.apply(before, attack ?: GameAction.EndTurn)
        if (first.legality != Legality.Legal || first.state.rng != before.rng) return approximate(before, actor, attack)
        var state = first.state
        repeat(12) {
            budget.check()
            if (state.phase == Phase.GAME_OVER || state.phase == Phase.MAIN) return state
            val owner = engine.actingPlayer(state)
            val action = when {
                state.phase == Phase.SELECT_PRIZES -> state.players[owner].prizes.firstOrNull()?.let { GameAction.TakePrize(it.id) }
                state.phase == Phase.PROMOTE && owner != actor -> return state
                state.phase == Phase.PROMOTE -> runCatching { heuristic.chooseHeuristic(engine.cpuInput(state)) }.getOrNull()
                state.phase == Phase.RESOLVE_CHOICE -> publicActions(state).firstOrNull()
                else -> null
            } ?: return approximate(before, actor, attack)
            val result = engine.apply(state, action)
            if (result.legality != Legality.Legal || result.state.rng != state.rng) return approximate(before, actor, attack)
            state = result.state
        }
        return approximate(before, actor, attack)
    }

    private fun approximate(before: GameState, actor: Int, attack: GameAction.Attack?): GameState {
        val opponent = 1-actor
        val target = attack?.targetId?.let { id -> before.players[opponent].pokemon().firstOrNull { it.id == id } } ?: before.players[opponent].active
        val preview = attack?.let { forecast.preview(before, it) }
        val ko = preview?.let { it.knockout && !it.uncertain } == true
        val after = before.copy(turn = before.turn+1, currentPlayer = opponent, phase = Phase.MAIN, resolution = null,
            players = before.players.mapIndexed { owner, player -> if (owner == opponent) {
                val field = player.pokemon().map { p -> if (p.id == target?.id && preview != null) p.copy(damage = p.damage+preview.damage) else p }
                    .filterNot { ko && it.id == target?.id }
                // A non-guaranteed coin knockout must not remove the target from the simulated field.
                val capped = field.map { p -> p.copy(damage = minOf(p.damage, engine.effectiveHp(before, p)-1)) }
                player.copy(active = capped.firstOrNull(), bench = capped.drop(1), flags = TurnFlags())
            } else player.copy(prizes = player.prizes.drop(if (ko) preview!!.prizes else 0)) })
        return after
    }
}
