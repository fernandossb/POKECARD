package com.tcgtrainer.engine

/** Searches complete tactical lines on a public position, then replans after each real action. */
internal class CpuTurnSearch(private val catalog: CardCatalog, private val strategy: CpuStrategy = CpuStrategy.MASTER) {
    companion object {
        private const val UNKNOWN = "cpu-hidden-card"
        internal fun publicPosition(original: GameState, actor: Int): CpuPublicPosition? {
            val publicIds = (original.players[actor].hand + original.players.flatMap { player -> player.discard + player.pokemon().flatMap { it.cards + it.energies + it.tools } } + listOfNotNull(original.stadium)).map { it.id }.toSet() + original.players.flatMap { it.pokemon() }.map { it.id }
            if (original.resolution?.choices.orEmpty().any { choice ->
                choice.candidates.any { it !in publicIds && it !in choice.optionLabels } ||
                    choice.visibleCards.any { it !in publicIds } ||
                    choice.pendingCards.any { it !in publicIds } ||
                    (choice.selectedEnergy != null && choice.selectedEnergy !in publicIds)
            }) return null
            fun hidden(zone: String, size: Int) = List(size) { CardInstance("hidden-$zone-$it", UNKNOWN) }
            return CpuPublicPosition(original.copy(gameId = "public-position", seed = 0, rng = RngState(0),
                players = original.players.mapIndexed { owner, player -> player.copy(
                    hand = if (owner == actor) player.hand else hidden("$owner-hand", player.hand.size),
                    deck = hidden("$owner-deck", player.deck.size), prizes = hidden("$owner-prizes", player.prizes.size),
                    prizePositions = emptyList(), faceUpPrizeIds = emptyList()) },
                bonusCardIds = emptyList(),
                deckLists = emptyList(), deckNames = emptyList(), humanDeckId = "", cpuDeckId = "",
                history = original.history.filter { it.action !is GameAction.ChooseCards && it.action !is GameAction.MulliganDraw }))
        }
    }
    private val maskedCatalog = object : CardCatalog {
        private val unknown = CardDefinition(UNKNOWN, "Carta desconhecida", "CPU", "?", category = Category.ENERGY)
        override fun get(id: String) = if (id == UNKNOWN) unknown else catalog.get(id)
    }
    private val engine = RulesEngine(maskedCatalog)
    private val forecast = CpuForecast(maskedCatalog)
    private val heuristic = CpuPlayer(maskedCatalog)
    private val attackCache = java.util.IdentityHashMap<GameState, MutableMap<GameAction.Attack, Pair<Double, Boolean>>>()
    private val replyCache = java.util.IdentityHashMap<GameState, MutableMap<String, Double>>()
    private val developmentCache = java.util.IdentityHashMap<GameState, MutableMap<Int, Double>>()
    private data class Node(val state: GameState, val first: GameAction?, val depth: Int)
    private data class Line(val first: GameAction, val value: Double, val wins: Boolean,
        val state: GameState, val attack: GameAction.Attack?)

    fun choose(input: CpuInput, fallback: GameAction): GameAction {
        val initial = input.position?.state ?: return fallback
        val actor = input.observation.viewer
        var frontier = listOf(Node(initial, null, 0))
        var best: Line? = null
        val finalists = mutableListOf<Line>()
        var transitions = 0
        val visited = hashSetOf(initial.copy(history = emptyList()))
        val directValue = input.actions.filterIsInstance<GameAction.Attack>().maxOfOrNull { attackValue(initial, actor, it).first } ?: 0.0
        repeat(7) {
            val children = mutableListOf<Node>()
            for (node in frontier) {
                if (transitions >= 260) break
                val state = node.state
                if (engine.actingPlayer(state) != actor || state.turn != initial.turn) continue
                val actions = when {
                    node.first == null && state.phase == Phase.MAIN -> input.actions
                    state.phase == Phase.RESOLVE_CHOICE -> choices(state)
                    state.phase == Phase.MAIN -> engine.cpuLegalActions(state)
                    else -> emptyList()
                }
                for (attack in actions.filterIsInstance<GameAction.Attack>()) {
                    val (value, wins) = attackValue(state, actor, attack)
                    val score = value + development(state, actor) - node.depth * 2.0
                    val line = Line(node.first ?: attack, score, wins, state, attack)
                    finalists += line
                    if (best == null || score > best!!.value) best = line
                }
                if (state.phase == Phase.MAIN && GameAction.EndTurn in actions) {
                    val score = development(state, actor) - replyRisk(state, actor, null) - node.depth*2
                    val line = Line(node.first ?: GameAction.EndTurn, score, false, state, null)
                    finalists += line
                    if (best == null || score > best!!.value) best = line
                }
                val groups = actions.filter { it !is GameAction.Attack && it != GameAction.EndTurn }
                    .distinctBy { equivalentAction(state, it) }.groupBy { it.javaClass.simpleName }.values
                    .sortedByDescending { group -> actionPriority(state, group.first()) }
                // Keep different kinds of preparation alive instead of spending the budget on identical Energy copies.
                val candidates = buildList {
                    val longest = groups.maxOfOrNull { it.size } ?: 0
                    for (index in 0 until longest) groups.forEach { group -> group.getOrNull(index)?.let(::add) }
                }.take(if (node.first == null) 24 else 12)
                for (action in candidates) {
                    if (++transitions > 260) break
                    val result = engine.apply(state, action)
                    if (result.legality != Legality.Legal || !knownStep(state, result.state, actor)) continue
                    if (!visited.add(result.state.copy(history = emptyList()))) continue
                    children += Node(result.state, node.first ?: action, node.depth + 1)
                }
            }
            frontier = children.sortedByDescending { node ->
                val state = node.state
                val tactical = if (state.phase == Phase.MAIN) engine.cpuLegalActions(state).filterIsInstance<GameAction.Attack>()
                    .maxOfOrNull { attackValue(state, actor, it).first } ?: 0.0 else 200.0
                tactical + development(state, actor) - node.depth * 2
            }.take(6)
            if (frontier.isEmpty()) return@repeat
        }
        var comparisonValue = directValue + development(initial, actor)
        val plan = if (strategy == CpuStrategy.MASTER) {
            val counterplay = CpuCounterplay(engine, maskedCatalog)
            val ranked = finalists.sortedByDescending { it.value }.distinctBy { it.first }
            val candidates = (ranked.take(4) + listOfNotNull(ranked.firstOrNull { it.first == fallback }, ranked.firstOrNull { it.first is GameAction.Attack })).distinctBy { it.first }
            val evaluated = candidates.map { line ->
                if (line.wins) line else {
                    val oldTarget = line.attack?.let { action -> line.state.players[1-actor].pokemon().firstOrNull { it.id == action.targetId }
                        ?: line.state.players[1-actor].active }
                    val ko = line.attack?.let { forecast.preview(line.state, it) }?.let { it.knockout && !it.uncertain } == true
                    val previousRisk = replyRisk(line.state, actor, if (ko) oldTarget?.id else null)
                    val response = counterplay.evaluate(line.state, actor, line.attack)
                    // Forced losses outrank material. Other forecasts are uncertain and must not outweigh a confirmed knockout.
                    val riskCorrection = if (response.risk >= 100_000 || response.wins) previousRisk-response.risk
                        else (previousRisk-response.risk)*.35
                    line.copy(value = line.value + riskCorrection + response.prizeRace*.15, wins = response.wins)
                }
            }
            evaluated.firstOrNull { it.first == fallback }?.let { comparisonValue = it.value }
                ?: evaluated.filter { it.first is GameAction.Attack }.maxOfOrNull { it.value }?.let { comparisonValue = it }
            evaluated.maxByOrNull { it.value } ?: best
        } else best
        if (plan == null) return fallback
        if (initial.phase == Phase.MAIN && plan.first !in input.actions) return fallback
        if (engine.validate(initial, plan.first) != Legality.Legal) return fallback
        if (plan.wins) return plan.first
        // Drawing/searching may improve the position, but unknown cards must not be fabricated in simulations.
        if (plan.first is GameAction.Attack && fallback !is GameAction.Attack) return fallback
        return if (plan.value > comparisonValue + 80) plan.first else fallback
    }

    private fun equivalentAction(state: GameState, action: GameAction): String {
        val hand = state.players[state.currentPlayer].hand.associateBy { it.id }
        fun definition(id: String) = hand[id]?.definitionId ?: id
        return when(action) {
            is GameAction.AttachEnergy -> "energy:${definition(action.cardId)}:${action.pokemonId}"
            is GameAction.Evolve -> "evolve:${definition(action.cardId)}:${action.pokemonId}"
            is GameAction.PlayTrainer -> "trainer:${definition(action.cardId)}"
            is GameAction.PlayBasic -> "basic:${definition(action.cardId)}"
            else -> action.toString()
        }
    }

    private fun knownStep(before: GameState, after: GameState, actor: Int): Boolean {
        if (after.turn != before.turn || after.currentPlayer != actor || engine.actingPlayer(after) != actor) return false
        if (after.phase !in listOf(Phase.MAIN, Phase.RESOLVE_CHOICE) || after.rng != before.rng) return false
        if (after.players[actor].hand.any { it.definitionId == UNKNOWN }) return false
        val pending = after.resolution?.choices.orEmpty()
        val unknownIds = before.players.flatMap { it.deck + it.hand }.filter { it.definitionId == UNKNOWN }.map { it.id }.toSet()
        if (pending.any { c -> c.candidates.any { it in unknownIds } || c.visibleCards.any { it in unknownIds } }) return false
        return true
    }

    private fun choices(state: GameState): List<GameAction> {
        val choice = state.resolution?.choices?.firstOrNull() ?: return emptyList()
        val candidates = mutableListOf<GameAction>()
        runCatching { heuristic.chooseHeuristic(engine.cpuInput(state)) }.getOrNull()?.let(candidates::add)
        if (choice.min == 0) candidates += GameAction.ChooseCards(emptyList())
        if (choice.min <= 1 && choice.max >= 1) choice.candidates.take(24).forEach { candidates += GameAction.ChooseCards(listOf(it)) }
        return candidates.distinct().filter { engine.validate(state, it) == Legality.Legal }
    }

    private fun actionPriority(state: GameState, action: GameAction): Int = when(action) {
        is GameAction.Evolve -> 110
        is GameAction.AttachEnergy -> 105
        is GameAction.UseAbility -> 100
        is GameAction.ChooseCards -> 100
        is GameAction.PlayTrainer -> if (state.players[state.currentPlayer].hand.firstOrNull { it.id == action.cardId }
                ?.let { maskedCatalog.get(it.definitionId).trainerType } == TrainerType.TOOL) 104 else 95
        is GameAction.Retreat -> 90
        is GameAction.PlayBasic -> 60
        else -> 0
    }

    private fun attacks(state: GameState, actor: Int): List<GameAction.Attack> {
        val active = state.players[actor].active ?: return emptyList()
        return engine.availableAttacks(state, actor, active).map { GameAction.Attack(it.id) }
            .filter { engine.validate(state, it) == Legality.Legal }
    }

    private fun replyRisk(state: GameState, actor: Int, knockedOut: String?): Double {
        return replyCache.getOrPut(state) { mutableMapOf() }.getOrPut("$actor:$knockedOut") { computeReplyRisk(state, actor, knockedOut) }
    }

    private fun computeReplyRisk(state: GameState, actor: Int, knockedOut: String?): Double {
        val opponent = 1-actor
        val target = state.players[actor].active ?: return 0.0
        val enemies = state.players[opponent].pokemon().filter { it.id != knockedOut }
        var risk = 0.0
        // Consider a ready replacement after a knockout, rather than assuming the opponent has no reply.
        for (source in enemies.filter { knockedOut != null || it.id == state.players[opponent].active?.id }) {
            val reply = state.copy(currentPlayer = opponent, turn = state.turn + 1, phase = Phase.MAIN, resolution = null,
                players = state.players.mapIndexed { owner, player -> if (owner != opponent) player else player.copy(
                    active = source, bench = enemies.filter { it.id != source.id }, flags = TurnFlags()) })
            for (action in attacks(reply, opponent)) {
                val preview = forecast.preview(reply, action) ?: continue
                val sourceCard = maskedCatalog.get(target.top.definitionId)
                val lethal = preview.knockout && !preview.uncertain
                val loss = minOf(preview.damage, preview.effectiveHp-preview.currentDamage).coerceAtLeast(0) * .45 +
                    if (lethal) 750.0 * sourceCard.prizes + (if (sourceCard.prizes >= state.players[opponent].prizes.size) 30_000.0 else 0.0) else 0.0
                risk = maxOf(risk, loss)
            }
        }
        return risk
    }

    private fun attackValue(state: GameState, actor: Int, action: GameAction.Attack): Pair<Double, Boolean> {
        return attackCache.getOrPut(state) { mutableMapOf() }.getOrPut(action) { computeAttackValue(state, actor, action) }
    }

    private fun computeAttackValue(state: GameState, actor: Int, action: GameAction.Attack): Pair<Double, Boolean> {
        val active = state.players[actor].active ?: return 0.0 to false
        val target = state.players[1-actor].pokemon().firstOrNull { it.id == action.targetId } ?: state.players[1-actor].active ?: return 0.0 to false
        val attack = engine.availableAttacks(state, actor, active).firstOrNull { it.id == action.attackId } ?: return 0.0 to false
        val preview = forecast.preview(state, action) ?: return 0.0 to false
        val uncertain = preview.uncertain || SpecialCondition.CONFUSED in active.conditions || active.defendingAttackFailsOnTailsTurn == state.turn
        val knockout = preview.knockout && !uncertain
        val specialWin = Effect.WinIfOnePrizeRemaining in attack.effects && state.players[actor].prizes.size == 1 && !uncertain
        val recoil = attack.effects.sumOf { when(it) { is Effect.Recoil -> it.damage; is Effect.SelfDamage -> it.amount; else -> 0 } }
        val selfKo = recoil >= engine.effectiveHp(state, active)-active.damage && recoil > 0
        val givesLastPrizes = selfKo && maskedCatalog.get(active.top.definitionId).prizes >= state.players[1-actor].prizes.size
        val wins = (specialWin || knockout && preview.prizes >= state.players[actor].prizes.size) && !givesLastPrizes
        val damage = minOf(preview.damage, preview.effectiveHp-preview.currentDamage).coerceAtLeast(0)
        val control = (if (Effect.LockOpponentItemsNextTurn in attack.effects && state.players[1-actor].hand.isNotEmpty()) 140 else 0) +
            (if (Effect.PreventDefendingAttackNextTurn in attack.effects) 220 else 0)
        val spread = attack.effects.filterIsInstance<Effect.AllOpponentBenchDamage>().sumOf { effect ->
            state.players[1-actor].bench.sumOf { pokemon ->
                val hp = engine.effectiveHp(state, pokemon)-pokemon.damage
                if (effect.requiresDamageCounters && pokemon.damage == 0) 0.0
                else minOf(effect.amount, hp).coerceAtLeast(0)*.7 + if (effect.amount >= hp) maskedCatalog.get(pokemon.top.definitionId).prizes*900.0 else 0.0
            }
        }
        val value = damage.toDouble() + (if (knockout) 700 + preview.prizes*1400 else 0) +
            (if (wins) 100_000 else 0) + control + spread -
            (if (selfKo) maskedCatalog.get(active.top.definitionId).prizes*1200.0 else 0.0) -
            (if (givesLastPrizes) 40_000 else 0) - replyRisk(state, actor, if (knockout) target.id else null)
        return value to wins
    }

    private fun development(state: GameState, actor: Int): Double = developmentCache.getOrPut(state) { mutableMapOf() }
        .getOrPut(actor) { computeDevelopment(state, actor) }

    private fun computeDevelopment(state: GameState, actor: Int): Double = state.players[actor].pokemon().sumOf { pokemon ->
        val definition = maskedCatalog.get(pokemon.top.definitionId)
        val hp = engine.effectiveHp(state, pokemon)-pokemon.damage
        val energy = pokemon.energies.size.coerceAtMost(definition.attacks.maxOfOrNull { it.cost.size } ?: 0)
        val attackPotential = definition.attacks.maxOfOrNull { attack ->
            val damage = attack.effects.filterIsInstance<Effect.Damage>().sumOf { it.amount }
            damage.toDouble() / (1 + (attack.cost.size-energy).coerceAtLeast(0))
        } ?: 0.0
        attackPotential*.18 + energy*12.0 + hp.coerceAtLeast(0)*.025 + pokemon.tools.size*8
    }
}
