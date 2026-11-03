package com.tcgtrainer.engine

/** Searches complete tactical lines on a public position, then replans after each real action. */
internal class CpuTurnSearch(private val catalog: CardCatalog, private val strategy: CpuStrategy = CpuStrategy.MASTER, private val tuning: CpuTuning = CpuTuning(), private val budget: CpuSearchBudget = CpuSearchBudget(null), private val config: AiDifficultyConfig = AiDifficultyConfig()) {
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
                deckLists = original.players.indices.map { if (it == actor) original.deckLists.getOrNull(actor).orEmpty().sorted() else emptyList() }, deckNames = emptyList(), humanDeckId = "", cpuDeckId = "",
                history = original.history.filter { it.action !is GameAction.ChooseCards && it.action !is GameAction.MulliganDraw }))
        }
    }
    private val maskedCatalog = object : CardCatalog {
        private val unknown = CardDefinition(UNKNOWN, "Carta desconhecida", "CPU", "?", category = Category.ENERGY)
        override fun get(id: String) = if (id == UNKNOWN) unknown else catalog.get(id)
    }
    private val engine = RulesEngine(maskedCatalog)
    private val positionEvaluator = CpuPositionEvaluator(engine, maskedCatalog, config.evaluationWeights)
    private val forecast = CpuForecast(maskedCatalog)
    private val lethal = CpuLethalDetector(engine, budget)
    private val heuristic = CpuPlayer(maskedCatalog)
    private val attackCache = java.util.IdentityHashMap<GameState, MutableMap<GameAction.Attack, Pair<Double, Boolean>>>()
    private val replyCache = java.util.IdentityHashMap<GameState, MutableMap<String, Double>>()
    private val developmentCache = java.util.IdentityHashMap<GameState, MutableMap<Int, Double>>()
    private var activePlan = CpuDeckPlan.forDeck("custom")
    private data class Node(val state: GameState, val first: GameAction?, val depth: Int)
    private data class Line(val first: GameAction, val value: Double, val wins: Boolean,
        val state: GameState, val attack: GameAction.Attack?)
    private var completed: GameAction? = null

    fun choose(input: CpuInput, fallback: GameAction): GameAction {
        completed = null
        attackCache.clear()
        replyCache.clear()
        developmentCache.clear()
        return try { search(input, fallback) } catch (_: CpuSearchExpired) { completed ?: fallback }
    }

    private fun search(input: CpuInput, fallback: GameAction): GameAction {
        budget.check()
        val actor = input.observation.viewer
        val maskedInitial = input.position?.state ?: return fallback
        activePlan = CpuDeckPlan.forDeck(input.ownDeckId, maskedInitial.deckLists.getOrNull(actor).orEmpty(), catalog)
        val initial = maskedInitial
        var frontier = listOf(Node(initial, null, 0))
        var best: Line? = null
        val finalists = mutableListOf<Line>()
        var transitions = 0
        val visited = hashSetOf(initial.copy(history = emptyList()))
        val directValue = input.actions.filterIsInstance<GameAction.Attack>().maxOfOrNull { attackValue(initial, actor, it).first } ?: 0.0
        val baseline = directValue + development(initial, actor)
        fun remember(line: Line) {
            if (line.wins || line.value > baseline+tuning.improvement &&
                !(line.first is GameAction.Attack && fallback !is GameAction.Attack)) completed = line.first
        }
        for (depth in 0 until config.maxActionsPerSequence) {
            budget.check()
            if (budget.preparationFinished()) break
            val children = mutableListOf<Node>()
            for (node in frontier) {
                budget.check()
                if (budget.preparationFinished()) break
                if (transitions >= config.maxTransitions) break
                val state = node.state
                if (engine.actingPlayer(state) != actor || state.turn != initial.turn) continue
                val actions = when {
                    node.first == null && state.phase == Phase.MAIN -> input.actions
                    state.phase == Phase.RESOLVE_CHOICE -> choices(state, input.ownDeckId)
                    state.phase == Phase.MAIN -> engine.cpuLegalActions(state)
                    else -> emptyList()
                }
                for (attack in actions.filterIsInstance<GameAction.Attack>()) {
                    budget.check()
                    val (value, wins) = attackValue(state, actor, attack)
                    val score = value + development(state, actor) - node.depth * 2.0
                    val line = Line(node.first ?: attack, score, wins, state, attack)
                    finalists += line
                    if (best == null || score > best!!.value) { best = line; remember(line) }
                    if (wins) return line.first
                }
                if (state.phase == Phase.MAIN && GameAction.EndTurn in actions) {
                    val score = development(state, actor) - replyRisk(state, actor, null) - node.depth*2
                    val line = Line(node.first ?: GameAction.EndTurn, score, false, state, null)
                    finalists += line
                    if (best == null || score > best!!.value) { best = line; remember(line) }
                }
                val groups = prioritizedPreparationGroups(state, actions)
                // Keep different kinds of preparation alive instead of spending the budget on identical Energy copies.
                val candidates = buildList {
                    val longest = groups.maxOfOrNull { it.size } ?: 0
                    for (index in 0 until longest) groups.forEach { group -> group.getOrNull(index)?.let(::add) }
                }.take(if (node.first == null) config.rootBranches else config.childBranches)
                for (action in candidates) {
                    budget.check()
                    if (budget.preparationFinished()) break
                    if (++transitions > config.maxTransitions) break
                    val result = engine.apply(state, action)
                    // Winning abilities and other preparation effects can finish through
                    // mandatory Prize collection, outside MAIN / RESOLVE_CHOICE.
                    if (result.legality == Legality.Legal && result.state.rng == state.rng &&
                        lethal.provenWin(result.state, actor)) return node.first ?: action
                    if (result.legality != Legality.Legal || !knownStep(state, result.state, actor)) continue
                    if (!visited.add(result.state.copy(history = emptyList()))) continue
                    children += Node(result.state, node.first ?: action, node.depth + 1)
                }
            }
            val scored = mutableListOf<Pair<Node, Double>>()
            for (node in children) {
                budget.check()
                if (budget.preparationFinished()) break
                val state = node.state
                val tactical = if (state.phase == Phase.MAIN) engine.cpuLegalActions(state).filterIsInstance<GameAction.Attack>()
                    .maxOfOrNull { attackValue(state, actor, it).first } ?: 0.0 else 200.0
                scored += node to (tactical + development(state, actor) - node.depth * 2)
            }
            frontier = diverseBeam(scored, config.beamWidth)
            if (frontier.isEmpty()) break
        }
        var comparisonValue = directValue + development(initial, actor)
        val plan = if (strategy == CpuStrategy.MASTER && config.replyDepth > 0) {
            val counterplay = CpuCounterplay(engine, maskedCatalog, budget, config)
            val ranked = finalists.sortedByDescending { it.value }.distinctBy { it.first }
            val candidates = (listOfNotNull(ranked.firstOrNull { it.first == fallback }) + ranked.take(config.finalistCount) + listOfNotNull(ranked.firstOrNull { it.first is GameAction.Attack })).distinctBy { it.first }
            var confirmedBaseline: Double? = null
            var confirmedBest = Double.NEGATIVE_INFINITY
            val evaluated = candidates.map { line ->
                budget.check()
                val result = if (line.wins) line else {
                    val oldTarget = line.attack?.let { action -> line.state.players[1-actor].pokemon().firstOrNull { it.id == action.targetId }
                        ?: line.state.players[1-actor].active }
                    val ko = line.attack?.let { forecast.preview(line.state, it) }?.let { it.knockout && !it.uncertain } == true
                    val previousRisk = replyRisk(line.state, actor, if (ko) oldTarget?.id else null)
                    val response = counterplay.evaluate(line.state, actor, line.attack)
                    // Forced losses outrank material. Other forecasts are uncertain and must not outweigh a confirmed knockout.
                    val riskCorrection = if (response.risk >= 100_000 || response.wins) previousRisk-response.risk
                        else (previousRisk-response.risk)*tuning.counterplay
                    line.copy(value = line.value + riskCorrection + response.prizeRace*tuning.prizeRace -
                        response.winningReplyCount*config.winningReplyPenalty, wins = response.wins)
                }
                if (result.first == fallback) {
                    confirmedBaseline = result.value
                    confirmedBest = result.value
                    completed = fallback
                } else if (result.value > confirmedBest && (result.wins || confirmedBaseline?.let { result.value > it+tuning.improvement } == true) &&
                    (result.wins || !(result.first is GameAction.Attack && fallback !is GameAction.Attack))) {
                    confirmedBest = result.value
                    completed = result.first
                }
                result
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
        return if (plan.value > comparisonValue + tuning.improvement) plan.first else fallback
    }

    /** Keep promising lines from different opening decisions alive through the whole turn search. */
    private fun diverseBeam(scored: List<Pair<Node, Double>>, width: Int): List<Node> {
        val ranked = scored.sortedByDescending { it.second }
        if (ranked.size <= width) return ranked.map { it.first }
        // A beam sorted only by score can collapse to several near-identical ways of doing one thing.
        // Preserve one best line per first action kind, then fill remaining slots by value.
        val selected = mutableListOf<Pair<Node, Double>>()
        val kinds = ranked.mapNotNull { it.first.first?.javaClass?.simpleName }.distinct()
        for (kind in kinds) {
            ranked.firstOrNull { it.first.first?.javaClass?.simpleName == kind }?.let(selected::add)
        }
        ranked.forEach { candidate ->
            if (selected.size >= width) return@forEach
            if (selected.none { it.first === candidate.first }) selected += candidate
        }
        return selected.take(width).map { it.first }
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

    private fun choices(state: GameState, ownDeckId: String): List<GameAction> {
        val choice = state.resolution?.choices?.firstOrNull() ?: return emptyList()
        val candidates = mutableListOf<GameAction>()
        runCatching { heuristic.chooseHeuristic(engine.cpuInput(state).copy(ownDeckId = ownDeckId)) }.getOrNull()?.let(candidates::add)
        if (choice.min == 0) candidates += GameAction.ChooseCards(emptyList())
        if (choice.min <= 1 && choice.max >= 1) choice.candidates.take(config.choiceCandidates).forEach { candidates += GameAction.ChooseCards(listOf(it)) }
        return candidates.distinct().filter { engine.validate(state, it) == Legality.Legal }
    }

    /** Rank actions inside a category too, so a large hand cannot hide a critical Trainer past the beam cap. */
    internal fun prioritizedPreparationGroups(state: GameState, actions: List<GameAction>): List<List<GameAction>> =
        actions.filter { it !is GameAction.Attack && it != GameAction.EndTurn }
            .distinctBy { cpuActionKey(state, it) }
            .groupBy { it.javaClass.simpleName }
            .values
            .map { group -> group.sortedByDescending { actionPriority(state, it) } }
            .sortedByDescending { group -> actionPriority(state, group.first()) }

    private fun actionPriority(state: GameState, action: GameAction): Int = when(action) {
        is GameAction.Evolve -> 110 + state.players[state.currentPlayer].hand.firstOrNull { it.id == action.cardId }?.let { activePlan.priority(it.definitionId) }?.div(5)!!
        is GameAction.AttachEnergy -> 105 + state.players[state.currentPlayer].pokemon().firstOrNull { it.id == action.pokemonId }?.let { activePlan.priority(it.top.definitionId) }?.div(10)!!
        is GameAction.UseAbility -> 100
        is GameAction.ChooseCards -> 100
        is GameAction.PlayTrainer -> trainerPriority(state, action.cardId)
        is GameAction.Retreat -> 90
        is GameAction.PlayBasic -> 60 + state.players[state.currentPlayer].hand.firstOrNull { it.id == action.cardId }?.let { activePlan.priority(it.definitionId) / 3 }!!
        else -> 0
    }

    private fun trainerPriority(state: GameState, cardId: String): Int {
        val player = state.players[state.currentPlayer]
        val definition = player.hand.firstOrNull { it.id == cardId }?.let { maskedCatalog.get(it.definitionId) } ?: return 0
        val opponent = state.players[1-state.currentPlayer]
        val effect = definition.trainerEffect
        return when (effect) {
            is TrainerEffect.Switch -> if (effect.opponent && opponent.bench.isNotEmpty()) 155
                else if (!effect.opponent && player.bench.isNotEmpty()) 115 else 45
            is TrainerEffect.DamageBoost -> if (player.active?.let { maskedCatalog.get(it.top.definitionId).type == effect.type } == true) 150 else 45
            is TrainerEffect.OpponentExDamageBoost -> if (opponent.active?.let { maskedCatalog.get(it.top.definitionId).name.endsWith(" ex") ||
                    effect.includeV && maskedCatalog.get(it.top.definitionId).name.endsWith(" V") } == true) 148 else 45
            is TrainerEffect.NonRuleBoxDamageBoost -> if (player.hand.size == 2 && player.active?.let { !maskedCatalog.get(it.top.definitionId).hasRuleBox } == true) 148 else 45
            is TrainerEffect.Search, TrainerEffect.GreatBall, TrainerEffect.BuddyBuddyPoffin, TrainerEffect.SearchEachEvolutionStage ->
                if (player.deck.isNotEmpty()) 125 else 35
            is TrainerEffect.DrawTo -> if (player.deck.isNotEmpty() && player.hand.size-1 < (effect.teamRocketSize ?: effect.size)) 120 else 40
            is TrainerEffect.ShuffleHandDraw -> if (player.deck.size > 2 && player.hand.size < 6) 115 else 40
            is TrainerEffect.DrawCards -> if (player.deck.size > effect.count+1 && player.hand.size < 9) 112 else 38
            is TrainerEffect.DrawCardsIfOpponentPrizesAtMost -> if (player.deck.isNotEmpty() && player.hand.size < 9) 112 else 38
            is TrainerEffect.RareCandy -> if (state.turn > 2 && player.hand.any { handCard ->
                    val evolution = maskedCatalog.get(handCard.definitionId)
                    evolution.canRareCandyEvolveFrom(player.pokemon().firstOrNull { maskedCatalog.get(it.top.definitionId).stage == Stage.BASIC }
                        ?.let { maskedCatalog.get(it.top.definitionId) } ?: evolution)
                }) 142 else 48
            is TrainerEffect.DiscardOpponentEnergy, TrainerEffect.DiscardOpponentSpecialEnergy,
            TrainerEffect.ReturnOpponentEnergyToHand, TrainerEffect.CoinDiscardOpponentEnergy ->
                if (opponent.pokemon().any { it.energies.isNotEmpty() }) 130 else 50
            else -> if (definition.trainerType == TrainerType.TOOL) 104 else 75
        }
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
        budget.check()
        val opponent = 1-actor
        val ownPokemon = state.players[actor].pokemon()
        if (ownPokemon.isEmpty()) return 0.0
        val enemies = state.players[opponent].pokemon().filter { it.id != knockedOut }
        val canGustOurBench = hasPlayableSwitch(state, opponent, targetsOpponent = true)
        val canSwitchTheirBench = hasPlayableSwitch(state, opponent, targetsOpponent = false)
        val knowsOpponentHand = state.players[opponent].hand.any { it.definitionId != UNKNOWN }
        val targets = if (canGustOurBench) ownPokemon else listOfNotNull(state.players[actor].active)
        var risk = 0.0
        // Simulate known switch Trainers in the correct direction: Boss's Orders exposes our bench;
        // Switch lets the opponent promote one of their own prepared bench attackers.
        for (source in enemies.filter { knockedOut != null || canSwitchTheirBench || it.id == state.players[opponent].active?.id }) {
            budget.check()
            for (target in targets) {
                val reply = state.copy(currentPlayer = opponent, turn = state.turn + 1, phase = Phase.MAIN, resolution = null,
                    players = state.players.mapIndexed { owner, player -> when (owner) {
                        opponent -> player.copy(active = source, bench = enemies.filter { it.id != source.id }, flags = TurnFlags())
                        actor -> player.copy(active = target, bench = ownPokemon.filter { it.id != target.id })
                        else -> player
                    } })
                val replyPositions = if (knowsOpponentHand) knownReplyPreparations(reply, opponent) else listOf(reply)
                for (prepared in replyPositions) {
                    for (action in attacks(prepared, opponent)) {
                        budget.check()
                        val preview = forecast.preview(prepared, action) ?: continue
                        val targetCard = maskedCatalog.get(target.top.definitionId)
                        val lethal = preview.knockout && !preview.uncertain
                        val loss = minOf(preview.damage, preview.effectiveHp-preview.currentDamage).coerceAtLeast(0) * .45 +
                            if (lethal) 750.0 * targetCard.prizes + (if (targetCard.prizes >= state.players[opponent].prizes.size) 30_000.0 else 0.0) else 0.0
                        risk = maxOf(risk, loss)
                    }
                }
            }
        }
        return risk
    }

    /** Simulate only deterministic preparations from the revealed hand; hidden draws remain unknown. */
    internal fun knownReplyPreparations(initial: GameState, actor: Int): List<GameState> {
        val positions = mutableListOf(initial)
        var frontier = listOf(initial)
        val seen = hashSetOf(initial.copy(history = emptyList()))
        for (depth in 0 until 2) {
            val next = mutableListOf<GameState>()
            for (state in frontier) {
                budget.check()
                val actions = engine.cpuKnownPreparationActions(state)
                    .distinctBy { cpuActionKey(state, it) }
                    .take(12)
                for (action in actions) {
                    budget.check()
                    val result = engine.apply(state, action)
                    val after = result.state
                    if (result.legality != Legality.Legal || after.currentPlayer != actor || after.turn != initial.turn ||
                        after.phase != Phase.MAIN || after.rng != initial.rng || !seen.add(after.copy(history = emptyList()))) continue
                    positions += after
                    next += after
                    if (positions.size >= 24) return positions
                }
            }
            frontier = next
            if (frontier.isEmpty()) break
        }
        return positions
    }

    private fun hasPlayableSwitch(state: GameState, owner: Int, targetsOpponent: Boolean): Boolean {
        val player = state.players[owner]
        val nextTurn = state.turn+1
        return player.hand.any { card ->
            val definition = maskedCatalog.get(card.definitionId)
            val effect = definition.trainerEffect as? TrainerEffect.Switch ?: return@any false
            if (effect.opponent != targetsOpponent) return@any false
            when (definition.trainerType) {
                TrainerType.ITEM -> player.itemPlayLockedUntilTurn < nextTurn
                TrainerType.SUPPORTER -> player.supporterPlayLockedUntilTurn < nextTurn
                else -> true
            }
        }
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
        .getOrPut(actor) { positionEvaluator.value(state, actor) + activePlan.boardValue(state.players[actor].pokemon(), maskedCatalog) }

}
