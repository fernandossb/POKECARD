package com.tcgtrainer.engine

data class CpuSearchTelemetry(
    var candidatesGenerated: Int = 0,
    var candidatesPruned: Int = 0,
    var sequencesEvaluated: Int = 0,
    var maxSequenceDepth: Int = 0,
    var statesFromCache: Int = 0,
    var searchTimeMs: Long = 0,
    var maximumActionDepthReached: Int = 0,
    var averageActionDepth: Double = 0.0,
    var numberOfCompleteTurnSequences: Int = 0,
    var bestLine: List<GameAction> = emptyList(),
    var bestScore: Double = 0.0,
    var alternativeScore: Double? = null
)

/** Searches complete tactical lines on a public position, then replans after each real action. */
internal class CpuTurnSearch(
    private val catalog: CardCatalog,
    private val strategy: CpuStrategy = CpuStrategy.MASTER,
    private val tuning: CpuTuning = CpuTuning(),
    private val budget: CpuSearchBudget = CpuSearchBudget(null),
    private val config: AiDifficultyConfig = AiDifficultyConfig()
) {
    companion object {
        private const val UNKNOWN = "cpu-hidden-card"
        internal fun publicPosition(original: GameState, actor: Int): CpuPublicPosition? {
            val publicIds = (original.players[actor].hand + original.players.flatMap { player -> player.discard + player.pokemon().flatMap { it.cards + it.energies + it.tools } } + listOfNotNull(original.stadium)).map { it.id }.toSet() + original.players.flatMap { it.pokemon() }.map { it.id }
            val actorChoiceIds = if (original.resolution != null && original.currentPlayer == actor) {
                (original.players[actor].deck.map { it.id } + original.players[actor].hand.map { it.id }).toSet()
            } else emptySet()
            val allPublicIds = publicIds + actorChoiceIds
            if (original.resolution?.choices.orEmpty().any { choice ->
                choice.candidates.any { it !in allPublicIds && it !in choice.optionLabels } ||
                    choice.visibleCards.any { it !in allPublicIds } ||
                    choice.pendingCards.any { it !in allPublicIds } ||
                    (choice.selectedEnergy != null && choice.selectedEnergy !in allPublicIds)
            }) return null
            fun hidden(zone: String, size: Int) = List(size) { CardInstance("hidden-$zone-$it", UNKNOWN) }
            return CpuPublicPosition(original.copy(gameId = "public-position", seed = 0, rng = RngState(0),
                players = original.players.mapIndexed { owner, player -> player.copy(
                    hand = if (owner == actor) player.hand else hidden("$owner-hand", player.hand.size),
                    deck = if (original.resolution != null && owner == actor) player.deck.map { it.copy(definitionId = UNKNOWN) }
                           else hidden("$owner-deck", player.deck.size),
                    prizes = hidden("$owner-prizes", player.prizes.size),
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
    private val transpositionTable = HashMap<DominanceKey, Pair<Double, Int>>()
    private var activePlan = CpuDeckPlan.forDeck("custom")
    private data class Node(val state: GameState, val first: GameAction?, val sequence: List<GameAction>, val depth: Int)
    private data class Line(val first: GameAction, val sequence: List<GameAction>, val value: Double, val wins: Boolean,
        val state: GameState, val attack: GameAction.Attack?)
    private var completed: GameAction? = null
    var telemetry = CpuSearchTelemetry()
        private set

    fun choose(input: CpuInput, fallback: GameAction): GameAction {
        completed = null
        attackCache.clear()
        replyCache.clear()
        developmentCache.clear()
        transpositionTable.clear()
        telemetry = CpuSearchTelemetry()
        val started = System.currentTimeMillis()
        val chosen = try { search(input, fallback) } catch (_: CpuSearchExpired) { completed ?: fallback }
        telemetry.searchTimeMs = System.currentTimeMillis() - started
        return chosen
    }

    private fun search(input: CpuInput, fallback: GameAction): GameAction {
        budget.check()
        val actor = input.observation.viewer
        val maskedInitial = input.position?.state ?: return fallback
        activePlan = CpuDeckPlan.forDeck(input.ownDeckId, maskedInitial.deckLists.getOrNull(actor).orEmpty(), catalog)

        // Materialize virtual simulation deck from known unseen pool if decklist is provided
        val unseen = if (maskedInitial.deckLists.getOrNull(actor).orEmpty().isNotEmpty()) {
            CpuDrawOdds.pool(maskedInitial, actor) ?: maskedInitial.deckLists.getOrNull(actor)?.groupingBy { it }?.eachCount()
        } else null
        val initial = if (unseen != null && unseen.isNotEmpty() && maskedInitial.players[actor].deck.isNotEmpty()) {
            val virtualCards = unseen.flatMap { (defId, count) ->
                List(count) { CardInstance("sim-deck-$defId-$it", defId) }
            }.sortedBy { it.id }
            val actorDeckSize = maskedInitial.players[actor].deck.size
            val simDeck = virtualCards.take(actorDeckSize)
            maskedInitial.copy(players = maskedInitial.players.mapIndexed { idx, p ->
                if (idx == actor) p.copy(deck = simDeck) else p
            })
        } else maskedInitial

        // Step 1: Search for immediate lethal sequence before normal positional evaluation
        val lethalLine = findLethal(initial, actor)
        if (lethalLine != null && lethalLine.isNotEmpty()) {
            val firstAction = lethalLine.first()
            if (engine.validate(initial, firstAction) == Legality.Legal) {
                telemetry.bestLine = lethalLine
                telemetry.bestScore = 100_000.0
                telemetry.maximumActionDepthReached = lethalLine.size
                telemetry.numberOfCompleteTurnSequences = 1
                completed = firstAction
                return firstAction
            }
        }

        var frontier = listOf(Node(initial, null, emptyList(), 0))
        var best: Line? = null
        val finalists = mutableListOf<Line>()
        var transitions = 0
        val visited = hashSetOf(initial.copy(history = emptyList()))
        val directValue = input.actions.filterIsInstance<GameAction.Attack>().maxOfOrNull { attackValue(initial, actor, it).first } ?: 0.0
        val baseline = directValue + development(initial, actor)
        fun remember(line: Line) {
            if (line.wins || line.value > baseline - 20.0) completed = line.first
        }

        var maxDepth = 0
        val depthSum = mutableListOf<Int>()

        for (depth in 0 until config.maxActionsPerSequence) {
            budget.check()
            if (budget.preparationFinished()) break
            val children = mutableListOf<Node>()
            val dominanceSeen = mutableMapOf<DominanceKey, GameState>()

            for (node in frontier) {
                budget.check()
                if (budget.preparationFinished()) break
                if (transitions >= config.maxTransitions) break
                val state = node.state
                if (engine.actingPlayer(state) != actor || state.turn != initial.turn) continue
                maxDepth = maxOf(maxDepth, node.depth)

                val actions = when {
                    node.first == null && state.phase == Phase.MAIN -> input.actions
                    state.phase == Phase.RESOLVE_CHOICE -> choices(state, input.ownDeckId)
                    state.phase == Phase.MAIN -> engine.cpuLegalActions(state)
                    else -> emptyList()
                }

                telemetry.candidatesGenerated += actions.size

                // Terminal sequence evaluation for Attacks
                for (attack in actions.filterIsInstance<GameAction.Attack>()) {
                    budget.check()
                    val (value, wins) = attackValue(state, actor, attack)
                    val firstSearchIdx = node.sequence.indexOfFirst { it is GameAction.PlayTrainer && isSearchCard(initial, it.cardId) }
                    val firstAttachIdx = node.sequence.indexOfFirst { it is GameAction.AttachEnergy }
                    val searchBeforeAttachBonus = if (firstSearchIdx != -1 && firstAttachIdx != -1 && firstSearchIdx < firstAttachIdx) 10.0 else 0.0

                    val score = value + development(state, actor) - node.depth * 0.5 + searchBeforeAttachBonus
                    val line = Line(node.first ?: attack, node.sequence + attack, score, wins, state, attack)
                    finalists += line
                    telemetry.sequencesEvaluated++
                    depthSum += node.depth + 1
                    if (best == null || score > best!!.value) { best = line; remember(line) }
                    if (wins) {
                        telemetry.bestLine = line.sequence
                        telemetry.bestScore = score
                        telemetry.maximumActionDepthReached = maxDepth
                        return line.first
                    }
                }

                // Terminal sequence evaluation for EndTurn
                if (state.phase == Phase.MAIN && GameAction.EndTurn in actions) {
                    val firstSearchIdx = node.sequence.indexOfFirst { it is GameAction.PlayTrainer && isSearchCard(initial, it.cardId) }
                    val firstDrawIdx = node.sequence.indexOfFirst { it is GameAction.PlayTrainer && isDrawCard(initial, it.cardId) }
                    val initialHasSearch = initial.players[actor].hand.any { isSearchCard(initial, it.id) }

                    val thinningAdjustment = when {
                        firstSearchIdx != -1 && firstDrawIdx != -1 && firstSearchIdx < firstDrawIdx -> 120.0
                        firstDrawIdx != -1 && (firstSearchIdx == -1 || firstDrawIdx < firstSearchIdx) && initialHasSearch -> -180.0
                        else -> 0.0
                    }

                    val score = development(state, actor) - replyRisk(state, actor, null) - node.depth * 0.5 + thinningAdjustment
                    val line = Line(node.first ?: GameAction.EndTurn, node.sequence + GameAction.EndTurn, score, false, state, null)
                    finalists += line
                    telemetry.sequencesEvaluated++
                    depthSum += node.depth + 1
                    if (best == null || score > best!!.value) { best = line; remember(line) }
                }

                val groups = prioritizedPreparationGroups(state, actions)
                val candidates = buildList {
                    val longest = groups.maxOfOrNull { it.size } ?: 0
                    for (index in 0 until longest) groups.forEach { group -> group.getOrNull(index)?.let(::add) }
                }.take(if (node.first == null) config.rootBranches else config.childBranches)

                for (action in candidates) {
                    budget.check()
                    if (budget.preparationFinished()) break
                    if (++transitions > config.maxTransitions) break
                    val result = engine.apply(state, action)
                    if (result.legality == Legality.Legal && lethal.provenWin(result.state, actor)) {
                        telemetry.bestLine = node.sequence + action
                        telemetry.bestScore = 100_000.0
                        return node.first ?: action
                    }
                    if (result.legality != Legality.Legal || !knownStep(state, result.state, actor)) {
                        telemetry.candidatesPruned++
                        continue
                    }

                    // Dominance Pruning: check if an equivalent state was reached with better preserved resources
                    val domKey = dominanceKey(result.state, actor)
                    val existing = dominanceSeen[domKey]
                    if (existing != null && isDominated(result.state, existing, actor)) {
                        telemetry.candidatesPruned++
                        continue
                    }
                    dominanceSeen[domKey] = result.state

                    if (!visited.add(result.state.copy(history = emptyList()))) {
                        telemetry.statesFromCache++
                        continue
                    }
                    children += Node(result.state, node.first ?: action, node.sequence + action, node.depth + 1)
                }
            }

            // Intermediate Evaluation for beam search
            val scored = mutableListOf<Pair<Node, Double>>()
            for (node in children) {
                budget.check()
                if (budget.preparationFinished()) break
                scored += node to intermediateEvaluation(node, node.state, actor)
            }
            frontier = diverseBeam(scored, config.beamWidth)
            if (frontier.isEmpty()) break
        }

        telemetry.maximumActionDepthReached = maxDepth
        telemetry.averageActionDepth = if (depthSum.isNotEmpty()) depthSum.average() else 0.0
        telemetry.numberOfCompleteTurnSequences = finalists.size

        var comparisonValue = directValue + development(initial, actor)
        val plan = if (strategy == CpuStrategy.MASTER && config.replyDepth > 0) {
            val counterplay = CpuCounterplay(engine, maskedCatalog, budget, config)
            val ranked = finalists.sortedByDescending { it.value }.distinctBy { it.first }
            val candidates = (ranked.take(config.finalistCount) + listOfNotNull(ranked.firstOrNull { it.first == fallback }) + listOfNotNull(ranked.firstOrNull { it.first is GameAction.Attack })).distinctBy { it.first }
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
                    val riskCorrection = if (response.risk >= 100_000 || response.wins) previousRisk-response.risk
                        else (previousRisk-response.risk)*tuning.counterplay
                    line.copy(value = line.value + riskCorrection + response.prizeRace*tuning.prizeRace -
                        response.winningReplyCount*config.winningReplyPenalty, wins = response.wins)
                }
                if (result.first == fallback) {
                    confirmedBaseline = result.value
                    if (confirmedBest < result.value) {
                        confirmedBest = result.value
                        completed = fallback
                    }
                } else if (result.value > confirmedBest) {
                    confirmedBest = result.value
                    completed = result.first
                }
                result
            }
            evaluated.firstOrNull { it.first == fallback }?.let { comparisonValue = it.value }
                ?: evaluated.filter { it.first is GameAction.Attack }.maxOfOrNull { it.value }?.let { comparisonValue = it }
            val sortedEvaluated = evaluated.sortedByDescending { it.value }
            if (sortedEvaluated.size > 1) telemetry.alternativeScore = sortedEvaluated[1].value
            sortedEvaluated.firstOrNull() ?: best
        } else best

        if (plan == null) return fallback
        if (initial.phase == Phase.MAIN && plan.first !in input.actions) return fallback
        if (engine.validate(initial, plan.first) != Legality.Legal) return fallback
        if (plan.wins) {
            telemetry.bestLine = plan.sequence
            telemetry.bestScore = plan.value
            return plan.first
        }

        telemetry.bestLine = plan.sequence
        telemetry.bestScore = plan.value
        return if (plan.value >= comparisonValue) plan.first else fallback
    }

    /** Intermediate evaluation keeps promising preparation lines alive without penalizing temporary card loss. */
    private fun intermediateEvaluation(node: Node, state: GameState, actor: Int): Double {
        val tactical = if (state.phase == Phase.MAIN) {
            engine.cpuLegalActions(state).filterIsInstance<GameAction.Attack>()
                .maxOfOrNull { attackValue(state, actor, it).first } ?: 0.0
        } else 220.0

        val inProgressBonus = if (state.phase == Phase.RESOLVE_CHOICE) 250.0 else 0.0

        // Objective bonuses
        var objectiveBonus = 0.0
        val player = state.players[actor]
        val opponent = state.players[1 - actor]

        // Reward energy on ready attackers
        player.pokemon().forEach { p ->
            if (p.energies.isNotEmpty()) objectiveBonus += 25.0 * p.energies.size
        }

        // Reward gusting multi-prize target
        if (opponent.active != null) {
            val targetDef = maskedCatalog.get(opponent.active!!.top.definitionId)
            if (targetDef.prizes >= 2) objectiveBonus += 120.0
        }

        // Search Sequencing Bonus: playing search as first action to assemble attacker
        val firstActionIsSearch = node.sequence.firstOrNull()?.let { it is GameAction.PlayTrainer && isSearchCard(state, it.cardId) } == true
        if (firstActionIsSearch) objectiveBonus += 40.0

        // Deck Thinning sequencing check during beam search
        val firstSearchIdx = node.sequence.indexOfFirst { it is GameAction.PlayTrainer && isSearchCard(state, it.cardId) }
        val firstDrawIdx = node.sequence.indexOfFirst { it is GameAction.PlayTrainer && isDrawCard(state, it.cardId) }
        val initialHasSearch = player.hand.any { isSearchCard(state, it.id) }
        if (firstDrawIdx != -1 && (firstSearchIdx == -1 || firstDrawIdx < firstSearchIdx) && initialHasSearch) {
            objectiveBonus -= 180.0
        } else if (firstSearchIdx != -1 && firstDrawIdx != -1 && firstSearchIdx < firstDrawIdx) {
            objectiveBonus += 80.0
        }

        val depthPenalty = node.depth * 0.3
        return tactical + development(state, actor) + inProgressBonus + objectiveBonus - depthPenalty
    }

    /** Dominance Pruning Key */
    private data class DominanceKey(
        val activeDef: String?,
        val activeHp: Int,
        val activeEnergies: Int,
        val benchSig: String,
        val oppActiveDef: String?,
        val oppActiveHp: Int,
        val supporter: Boolean,
        val energyAttached: Boolean,
        val ownPrizes: Int,
        val oppPrizes: Int
    )

    private fun dominanceKey(state: GameState, actor: Int): DominanceKey {
        val p = state.players[actor]
        val opp = state.players[1 - actor]
        val bSig = p.bench.map { "${it.top.definitionId}:${it.damage}:${it.energies.size}" }.sorted().joinToString(";")
        return DominanceKey(
            activeDef = p.active?.top?.definitionId,
            activeHp = p.active?.let { (engine.effectiveHp(state, it) - it.damage) } ?: 0,
            activeEnergies = p.active?.energies?.size ?: 0,
            benchSig = bSig,
            oppActiveDef = opp.active?.top?.definitionId,
            oppActiveHp = opp.active?.let { (engine.effectiveHp(state, it) - it.damage) } ?: 0,
            supporter = p.flags.supporter,
            energyAttached = p.flags.manualEnergy,
            ownPrizes = p.prizes.size,
            oppPrizes = opp.prizes.size
        )
    }

    private fun isDominated(candidate: GameState, existing: GameState, actor: Int): Boolean {
        val candP = candidate.players[actor]
        val existP = existing.players[actor]
        return existP.hand.size > candP.hand.size && existP.deck.size >= candP.deck.size
    }

    /** Extremely aggressive lethal finder: explores if ANY legal sequence of actions wins this turn. */
    internal fun findLethal(initial: GameState, actor: Int): List<GameAction>? {
        budget.check()
        val neededPrizes = initial.players[actor].prizes.size

        // Direct attack check
        if (initial.phase == Phase.MAIN) {
            for (attack in engine.cpuLegalActions(initial).filterIsInstance<GameAction.Attack>()) {
                val preview = forecast.preview(initial, attack) ?: continue
                val wins = preview.knockout && !preview.uncertain && preview.prizes >= neededPrizes
                if (wins) return listOf(attack)
            }
        }

        // Focused BFS search for lethal lines
        data class LethalNode(val state: GameState, val sequence: List<GameAction>, val depth: Int)
        var frontier = listOf(LethalNode(initial, emptyList(), 0))
        val seen = hashSetOf(initial.copy(history = emptyList()))
        var steps = 0

        while (frontier.isNotEmpty() && steps < 300) {
            budget.check()
            val next = mutableListOf<LethalNode>()
            for (node in frontier) {
                budget.check()
                if (++steps > 300) break
                val state = node.state
                if (state.phase == Phase.GAME_OVER && state.winner == actor) return node.sequence
                if (engine.actingPlayer(state) != actor || state.turn != initial.turn) continue

                val actions = when (state.phase) {
                    Phase.MAIN -> engine.cpuLegalActions(state)
                    Phase.RESOLVE_CHOICE -> choices(state, activePlan.id)
                    else -> emptyList()
                }

                // Check attacks in this state
                for (attack in actions.filterIsInstance<GameAction.Attack>()) {
                    val preview = forecast.preview(state, attack) ?: continue
                    val wins = preview.knockout && !preview.uncertain && preview.prizes >= state.players[actor].prizes.size
                    if (wins) return node.sequence + attack
                }

                // Prioritize actions that unlock lethal (gusts, attachments, evolution, tools, switch)
                val prep = actions.filter { it !is GameAction.Attack && it != GameAction.EndTurn }
                    .sortedByDescending { action ->
                        when (action) {
                            is GameAction.PlayTrainer -> {
                                val def = maskedCatalog.get(state.players[actor].hand.firstOrNull { it.id == action.cardId }?.definitionId ?: "")
                                when (def.trainerEffect) {
                                    is TrainerEffect.Switch -> if ((def.trainerEffect as TrainerEffect.Switch).opponent) 200 else 130
                                    is TrainerEffect.DamageBoost, is TrainerEffect.OpponentExDamageBoost -> 180
                                    is TrainerEffect.CounterGain -> 170
                                    is TrainerEffect.Search, TrainerEffect.BuddyBuddyPoffin -> 150
                                    else -> 100
                                }
                            }
                            is GameAction.AttachEnergy -> 160
                            is GameAction.Evolve -> 150
                            is GameAction.Retreat -> 140
                            is GameAction.UseAbility -> 130
                            is GameAction.ChooseCards -> 120
                            else -> 50
                        }
                    }.take(10)

                for (action in prep) {
                    budget.check()
                    val result = engine.apply(state, action)
                    if (result.legality != Legality.Legal || !knownStep(state, result.state, actor)) continue
                    if (!seen.add(result.state.copy(history = emptyList()))) continue
                    if (result.state.phase == Phase.GAME_OVER && result.state.winner == actor) return node.sequence + action
                    next += LethalNode(result.state, node.sequence + action, node.depth + 1)
                }
            }
            frontier = next.take(12)
        }
        return null
    }

    /** Keep promising lines from different opening decisions alive through the whole turn search. */
    private fun diverseBeam(scored: List<Pair<Node, Double>>, width: Int): List<Node> {
        val ranked = scored.sortedByDescending { it.second }
        if (ranked.size <= width) return ranked.map { it.first }
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
        if (after.phase !in listOf(Phase.MAIN, Phase.RESOLVE_CHOICE)) return false
        if (after.rng != before.rng) {
            val wasShuffle = before.resolution?.shuffleAfter == true ||
                (before.phase == Phase.RESOLVE_CHOICE && after.phase == Phase.MAIN)
            if (!wasShuffle) return false
        }
        if (after.players[actor].hand.any { it.definitionId == UNKNOWN }) return false
        val pending = after.resolution?.choices.orEmpty()
        val unknownIds = before.players.flatMap { it.deck + it.hand }.filter { it.definitionId == UNKNOWN }.map { it.id }.toSet()
        if (pending.any { c -> c.candidates.any { it in unknownIds } || c.visibleCards.any { it in unknownIds } }) return false
        return true
    }

    private fun choices(state: GameState, ownDeckId: String): List<GameAction> {
        val choice = state.resolution?.choices?.firstOrNull() ?: return emptyList()
        val candidates = mutableListOf<GameAction>()
        val actor = engine.actingPlayer(state)
        runCatching { heuristic.chooseHeuristic(engine.cpuInput(state).copy(ownDeckId = ownDeckId)) }.getOrNull()?.let(candidates::add)
        if (choice.min == 0) candidates += GameAction.ChooseCards(emptyList())

        when {
            choice.min <= 1 && choice.max == 1 -> {
                choice.candidates.take(config.choiceCandidates).forEach {
                    candidates += GameAction.ChooseCards(listOf(it))
                }
            }
            choice.min == 2 && choice.max == 2 -> {
                val player = state.players[actor]
                val handCards = player.hand.filter { it.id in choice.candidates }
                if (handCards.size >= 2) {
                    val sorted = handCards.sortedBy { discardCost(state, actor, it) }
                    for (i in 0 until minOf(sorted.size, 5)) {
                        for (j in i + 1 until minOf(sorted.size, 5)) {
                            candidates += GameAction.ChooseCards(listOf(sorted[i].id, sorted[j].id))
                        }
                    }
                } else if (choice.candidates.size >= 2) {
                    val pool = choice.candidates.take(6)
                    for (i in pool.indices) {
                        for (j in i + 1 until pool.size) {
                            candidates += GameAction.ChooseCards(listOf(pool[i], pool[j]))
                        }
                    }
                }
            }
            choice.max >= 2 -> {
                choice.candidates.take(config.choiceCandidates).forEach {
                    candidates += GameAction.ChooseCards(listOf(it))
                }
                val pool = choice.candidates.take(8)
                for (i in pool.indices) {
                    for (j in i + 1 until pool.size) {
                        candidates += GameAction.ChooseCards(listOf(pool[i], pool[j]))
                    }
                }
            }
        }
        return candidates.distinct().filter { engine.validate(state, it) == Legality.Legal }
    }

    /** Evaluates the penalty/cost of discarding a card; lower is safer to discard. */
    internal fun discardCost(state: GameState, actor: Int, card: CardInstance): Double {
        val def = maskedCatalog.get(card.definitionId)
        val player = state.players[actor]
        val opponent = state.players[1 - actor]

        val isGust = def.trainerEffect is TrainerEffect.Switch && (def.trainerEffect as TrainerEffect.Switch).opponent
        val isCriticalGust = isGust && opponent.bench.isNotEmpty()

        val duplicateInHand = player.hand.count { it.definitionId == card.definitionId } > 1
        val energyCount = player.hand.count { maskedCatalog.get(it.definitionId).category == Category.ENERGY }
        val isExcessEnergy = def.category == Category.ENERGY && energyCount > 1
        val canAccelerateFromDiscard = def.category == Category.ENERGY && def.basicEnergy &&
            player.pokemon().any { p -> maskedCatalog.get(p.top.definitionId).abilities.any { it.attachBasicEnergyFromDiscard || it.attachBasicEnergyFromDiscardType != null } }

        var cost = 50.0
        if (isCriticalGust) cost += 500.0
        if (def.category == Category.TRAINER && def.trainerType == TrainerType.SUPPORTER) cost += 80.0
        if (def.category == Category.POKEMON && def.stage != Stage.BASIC) cost += 60.0
        if (duplicateInHand) cost -= 40.0
        if (isExcessEnergy) cost -= 45.0
        if (canAccelerateFromDiscard) cost -= 60.0

        return cost
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

    private fun isSearchCard(state: GameState, cardId: String): Boolean {
        val defId = state.players.flatMap { it.hand + it.discard }.firstOrNull { it.id == cardId }?.definitionId ?: return false
        val effect = maskedCatalog.get(defId).trainerEffect ?: return false
        return effect is TrainerEffect.Search || effect is TrainerEffect.GreatBall || effect is TrainerEffect.BuddyBuddyPoffin
    }

    private fun isDrawCard(state: GameState, cardId: String): Boolean {
        val defId = state.players.flatMap { it.hand + it.discard }.firstOrNull { it.id == cardId }?.definitionId ?: return false
        val effect = maskedCatalog.get(defId).trainerEffect ?: return false
        return effect is TrainerEffect.DrawCards || effect is TrainerEffect.ShuffleHandDraw || effect is TrainerEffect.DrawTo
    }

    private fun trainerPriority(state: GameState, cardId: String): Int {
        val player = state.players[state.currentPlayer]
        val definition = player.hand.firstOrNull { it.id == cardId }?.let { maskedCatalog.get(it.definitionId) } ?: return 0
        val opponent = state.players[1-state.currentPlayer]
        val effect = definition.trainerEffect

        // Check if hand has a draw supporter and we can thin first
        val hasDrawCardInHand = player.hand.any { h ->
            val d = maskedCatalog.get(h.definitionId)
            d.trainerType == TrainerType.SUPPORTER && (d.trainerEffect is TrainerEffect.DrawCards || d.trainerEffect is TrainerEffect.ShuffleHandDraw || d.trainerEffect is TrainerEffect.DrawTo)
        }
        val hasSearchInHand = player.hand.any { isSearchCard(state, it.id) }

        return when (effect) {
            is TrainerEffect.Switch -> if (effect.opponent && opponent.bench.isNotEmpty()) 155
                else if (!effect.opponent && player.bench.isNotEmpty()) 115 else 45
            is TrainerEffect.DamageBoost -> if (player.active?.let { maskedCatalog.get(it.top.definitionId).type == effect.type } == true) 150 else 45
            is TrainerEffect.OpponentExDamageBoost -> if (opponent.active?.let { maskedCatalog.get(it.top.definitionId).name.endsWith(" ex") ||
                    effect.includeV && maskedCatalog.get(it.top.definitionId).name.endsWith(" V") } == true) 148 else 45
            is TrainerEffect.NonRuleBoxDamageBoost -> if (player.hand.size == 2 && player.active?.let { !maskedCatalog.get(it.top.definitionId).hasRuleBox } == true) 148 else 45
            is TrainerEffect.Search, TrainerEffect.GreatBall, TrainerEffect.BuddyBuddyPoffin, TrainerEffect.SearchEachEvolutionStage ->
                if (player.deck.isNotEmpty()) (if (hasDrawCardInHand) 170 else 150) else 35
            is TrainerEffect.DrawTo -> if (hasSearchInHand) 25 else if (player.deck.isNotEmpty() && player.hand.size-1 < (effect.teamRocketSize ?: effect.size)) 120 else 40
            is TrainerEffect.ShuffleHandDraw -> if (hasSearchInHand) 25 else if (player.deck.size > 2 && player.hand.size < 6) 115 else 40
            is TrainerEffect.DrawCards -> if (hasSearchInHand) 25 else if (player.deck.size > effect.count+1 && player.hand.size < 9) 112 else 38
            is TrainerEffect.DrawCardsIfOpponentPrizesAtMost -> if (hasSearchInHand) 25 else if (player.deck.isNotEmpty() && player.hand.size < 9) 112 else 38
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
