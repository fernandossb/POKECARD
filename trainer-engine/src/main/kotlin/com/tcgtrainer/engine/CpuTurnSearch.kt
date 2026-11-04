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
    var alternativeScore: Double? = null,
    var playerHandThreat: Double = 0.0,
    var playerEstimatedBestLine: List<GameAction> = emptyList(),
    var expectedOpponentPrizes: Int = 0,
    var opponentLethal: Boolean = false,
    var opponentPotentialBefore: Double = 0.0,
    var opponentPotentialAfter: Double = 0.0,
    var counterLineLog: String = ""
) {
    fun formatDiagnostics(): String = buildString {
        appendLine("PLAYER HAND THREAT: ${playerHandThreat.toInt()}/100")
        appendLine("PLAYER NEXT TURN:")
        appendLine("Estimated best line:")
        if (playerEstimatedBestLine.isEmpty()) appendLine("1. None / Pass")
        else playerEstimatedBestLine.forEachIndexed { i, a -> appendLine("${i + 1}. $a") }
        appendLine("Expected prizes: $expectedOpponentPrizes")
        appendLine("Opponent lethal: ${if (opponentLethal) "YES" else "NO"}")
        appendLine()
        appendLine("CPU COUNTER LINE:")
        if (bestLine.isEmpty()) appendLine("1. None")
        else bestLine.forEachIndexed { i, a -> appendLine("${i + 1}. $a") }
        appendLine("Opponent best-turn potential: Before = ${opponentPotentialBefore.toInt()}, After = ${opponentPotentialAfter.toInt()}")
    }
}

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
        internal fun publicPosition(original: GameState, actor: Int, knowledge: CpuKnowledge = CpuKnowledge.FAIR): CpuPublicPosition? {
            val publicIds = (original.players[actor].hand + original.players.flatMap { player -> player.discard + player.pokemon().flatMap { it.cards + it.energies + it.tools } } + listOfNotNull(original.stadium)).map { it.id }.toSet() + original.players.flatMap { it.pokemon() }.map { it.id }
            if (original.resolution?.choices.orEmpty().any { choice ->
                choice.candidates.any { it !in publicIds && it !in choice.optionLabels } ||
                    choice.visibleCards.any { it !in publicIds } ||
                    choice.pendingCards.any { it !in publicIds } ||
                    (choice.selectedEnergy != null && choice.selectedEnergy !in publicIds)
            }) return null
            fun hidden(zone: String, size: Int) = List(size) { CardInstance("hidden-$zone-$it", UNKNOWN) }

            val revealsOpponentHand = knowledge != CpuKnowledge.FAIR
            val revealsDeckList = knowledge in listOf(CpuKnowledge.PLAYER_HAND_AND_DECK, CpuKnowledge.PERFECT_INFORMATION)
            val revealsExactDeckAndPrizes = knowledge == CpuKnowledge.PERFECT_INFORMATION

            return CpuPublicPosition(original.copy(gameId = "public-position", seed = 0, rng = RngState(0),
                players = original.players.mapIndexed { owner, player -> player.copy(
                    hand = if (owner == actor || revealsOpponentHand) player.hand else hidden("$owner-hand", player.hand.size),
                    deck = if (owner != actor && revealsExactDeckAndPrizes) player.deck else hidden("$owner-deck", player.deck.size),
                    prizes = if (owner != actor && revealsExactDeckAndPrizes) player.prizes else hidden("$owner-prizes", player.prizes.size),
                    prizePositions = emptyList(), faceUpPrizeIds = emptyList()) },
                bonusCardIds = emptyList(),
                deckLists = original.players.indices.map {
                    if (it == actor || revealsDeckList) original.deckLists.getOrNull(it).orEmpty().sorted() else emptyList()
                },
                deckNames = emptyList(), humanDeckId = "", cpuDeckId = "",
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
    private data class Line(
        val first: GameAction,
        val sequence: List<GameAction>,
        val value: Double,
        val wins: Boolean,
        val state: GameState,
        val attack: GameAction.Attack?,
        val stateBeforeAttack: GameState? = null
    )
    private var completed: GameAction? = null
    var turnPlan: CpuTurnPlan? = null
        private set
    var telemetry = CpuSearchTelemetry()
        private set
    private var isPrivileged: Boolean = false
    private var currentInitial: GameState? = null
    private var currentDeckResources: ResourceExhaustion? = null
    private var currentHandAnalysis: HandAnalysis? = null
    private var currentPrediction: PredictionResult? = null
    private var currentOracleDraw: CardInstance? = null

    fun choose(input: CpuInput, fallback: GameAction): GameAction {
        completed = null
        turnPlan = null
        isPrivileged = strategy in listOf(CpuStrategy.GRANDMASTER, CpuStrategy.ORACLE)
        currentInitial = null
        currentDeckResources = null
        currentHandAnalysis = null
        currentPrediction = null
        currentOracleDraw = null
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
        val opponent = 1 - actor
        val maskedInitial = input.position?.state ?: return fallback
        activePlan = CpuDeckPlan.forDeck(input.ownDeckId, maskedInitial.deckLists.getOrNull(actor).orEmpty(), catalog)

        // Materialize virtual simulation deck from known unseen pool if decklist is provided
        val fallbackEnergyId = runCatching { catalog.get("energy").id }.getOrNull()
            ?: runCatching { catalog.get("TEST-004").id }.getOrNull()
            ?: runCatching { catalog.get("sve-006").id }.getOrNull()
            ?: maskedInitial.players.flatMap { p -> p.hand + p.discard + p.pokemon().flatMap { it.energies } }
                .map { it.definitionId }
                .firstOrNull { it != UNKNOWN && runCatching { catalog.get(it).category == Category.ENERGY }.getOrDefault(false) }

        val unseen = if (maskedInitial.deckLists.getOrNull(actor).orEmpty().isNotEmpty()) {
            CpuDrawOdds.pool(maskedInitial, actor) ?: maskedInitial.deckLists.getOrNull(actor)?.groupingBy { it }?.eachCount()
        } else if (fallbackEnergyId != null) {
            mapOf(fallbackEnergyId to maskedInitial.players[actor].deck.size)
        } else null

        val initial = if (unseen != null && unseen.isNotEmpty() && maskedInitial.players[actor].deck.isNotEmpty()) {
            val virtualCards = unseen.flatMap { (defId, count) ->
                List(count) { CardInstance("sim-deck-$defId-$it", defId) }
            }.sortedBy { it.id }
            val actorDeckSize = maskedInitial.players[actor].deck.size
            val simDeck = virtualCards.take(actorDeckSize)
            val oppDeck = if (isPrivileged && fallbackEnergyId != null && maskedInitial.players[opponent].deck.any { it.definitionId == UNKNOWN }) {
                List(maskedInitial.players[opponent].deck.size) { CardInstance("sim-opp-deck-$it", fallbackEnergyId) }
            } else maskedInitial.players[opponent].deck
            maskedInitial.copy(players = maskedInitial.players.mapIndexed { idx, p ->
                when (idx) {
                    actor -> p.copy(deck = simDeck)
                    opponent -> p.copy(deck = oppDeck)
                    else -> p
                }
            })
        } else maskedInitial

        currentInitial = initial
        currentOracleDraw = if (strategy == CpuStrategy.ORACLE) initial.players[opponent].deck.firstOrNull() else null
        if (isPrivileged) {
            currentDeckResources = DeckCounterAnalyzer.analyzeDeck(initial, actor, maskedCatalog)
            currentHandAnalysis = PlayerHandAnalyzer.analyze(initial, actor, maskedCatalog, currentOracleDraw)
            currentPrediction = OpponentBestLinePredictor.predict(initial, actor, maskedCatalog, engine, currentOracleDraw)

            telemetry.playerHandThreat = currentPrediction!!.threatScore
            telemetry.playerEstimatedBestLine = currentPrediction!!.bestLine
            telemetry.expectedOpponentPrizes = currentPrediction!!.expectedPrizes
            telemetry.opponentLethal = currentPrediction!!.isLethal
            telemetry.opponentPotentialBefore = currentPrediction!!.threatScore
        }

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
                    val tacticalBonus = tacticalSequenceBonus(initial, state, node.sequence + attack, actor)

                    val score = value + development(state, actor) - node.depth * 0.5 + searchBeforeAttachBonus + tacticalBonus
                    val postState = postAction(state, actor, attack)
                    val line = Line(node.first ?: attack, node.sequence + attack, score, wins, postState, attack, stateBeforeAttack = state)
                    finalists += line
                    telemetry.sequencesEvaluated++
                    depthSum += node.depth + 1
                    if (best == null || score > best!!.value) { best = line; remember(line) }
                    if (wins) {
                        telemetry.bestLine = line.sequence
                        telemetry.bestScore = score
                        telemetry.maximumActionDepthReached = maxDepth
                        turnPlan = CpuTurnPlan(objective = CpuTurnObjective.EXECUTE_ATTACK, plannedSequence = line.sequence, initialTurn = initial.turn, actor = actor)
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
                    val tacticalBonus = tacticalSequenceBonus(initial, state, node.sequence + GameAction.EndTurn, actor)

                    val score = development(state, actor) - replyRisk(state, actor, null) - node.depth * 0.5 + thinningAdjustment + tacticalBonus
                    val postState = postAction(state, actor, null)
                    val line = Line(node.first ?: GameAction.EndTurn, node.sequence + GameAction.EndTurn, score, false, postState, null, stateBeforeAttack = state)
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
                    val result = runCatching { engine.apply(state, action) }.getOrNull() ?: continue
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

        var comparisonValue = directValue + development(initial, actor) - (if (isPrivileged && currentPrediction?.isLethal == true) 100_000.0 else 0.0)
        val plan = if ((strategy in listOf(CpuStrategy.MASTER, CpuStrategy.GRANDMASTER, CpuStrategy.ORACLE)) && config.replyDepth > 0) {
            if (isPrivileged) {
                val ranked = finalists.sortedByDescending { it.value }.distinctBy { it.sequence }
                val candidates = (ranked.take(config.finalistCount) + listOfNotNull(ranked.firstOrNull { it.first == fallback }) + listOfNotNull(ranked.firstOrNull { it.first is GameAction.Attack })).distinctBy { it.sequence }
                var confirmedBaseline: Double? = null
                var confirmedBest = Double.NEGATIVE_INFINITY
                val evaluated = candidates.map { line ->
                    budget.check()
                    val result = if (line.wins) line else {
                        val lineOracleDraw = if (strategy == CpuStrategy.ORACLE) line.state.players[opponent].deck.firstOrNull() else null
                        val replyPrediction = OpponentBestLinePredictor.predict(line.state, actor, maskedCatalog, engine, lineOracleDraw)
                        var penalty = 0.0
                        if (replyPrediction.isLethal) {
                            penalty += 100_000.0
                        } else {
                            penalty += replyPrediction.expectedPrizes * 1500.0
                            penalty += replyPrediction.maxDamage * 0.5
                            penalty += replyPrediction.threatScore * 2.0
                        }

                        // Deduct risk already accounted for in generation to prevent double deduction
                        val stateBefore = line.stateBeforeAttack ?: initial
                        val previousRisk = if (line.attack != null) {
                            val ko = forecast.preview(stateBefore, line.attack)?.let { it.knockout && !it.uncertain } == true
                            val target = line.attack.targetId ?: stateBefore.players[opponent].active?.id
                            replyRisk(stateBefore, actor, if (ko) target else null)
                        } else {
                            replyRisk(stateBefore, actor, null)
                        }
                        val netRiskCorrection = previousRisk - penalty

                        val beforeThreat = currentPrediction?.threatScore ?: 50.0
                        val afterThreat = replyPrediction.threatScore
                        var adjustment = (beforeThreat - afterThreat) * 4.0

                        val playedDisruption = line.sequence.any { isHandDisruptionCard(initial, (it as? GameAction.PlayTrainer)?.cardId.orEmpty()) }
                        if (playedDisruption) {
                            if (currentHandAnalysis?.isDeadHand == true || beforeThreat <= 25.0) {
                                adjustment -= 2500.0
                            } else if (beforeThreat >= 60.0) {
                                adjustment += 600.0
                            }
                        }

                        if (currentDeckResources?.boardLockOpportunity == true && line.state.players[opponent].active?.id == currentDeckResources?.lockTargetId) {
                            adjustment += 1500.0
                        }

                        val knockedOutTarget = line.attack?.let { action ->
                            forecast.preview(line.state, action)?.let { if (it.knockout) action.targetId ?: line.state.players[opponent].active?.id else null }
                        }
                        if (knockedOutTarget != null) {
                            val targetDef = initial.players[opponent].pokemon().firstOrNull { it.id == knockedOutTarget }
                            if (targetDef != null) {
                                val tThreat = FutureThreatEvaluator.evaluatePokemonThreat(initial, targetDef, actor, maskedCatalog, currentHandAnalysis)
                                if (tThreat >= 90.0) {
                                    adjustment += 2000.0
                                }
                            }
                        }

                        if (currentPrediction?.isLethal == true && !replyPrediction.isLethal) {
                            adjustment += 50_000.0
                        }

                        line.copy(value = line.value + netRiskCorrection + adjustment)
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

                val bestAttackCandidate = evaluated.filter { it.attack != null }.maxByOrNull { it.value }
                val bestNoAttackCandidate = evaluated.filter { it.attack == null && it.first != GameAction.EndTurn }.maxByOrNull { it.value }
                val immediateEndTurnCandidate = evaluated.firstOrNull { it.first == GameAction.EndTurn }
                val fallbackLine = finalists.firstOrNull { it.first == fallback } ?: evaluated.firstOrNull() ?: best ?: Line(fallback, listOf(fallback), 0.0, false, initial, null)

                val (preferredLine, preferenceReason) = shouldPreferAttack(
                    bestAttackCandidate,
                    bestNoAttackCandidate,
                    immediateEndTurnCandidate,
                    fallbackLine,
                    initial,
                    actor
                )

                val objective = when {
                    preferredLine.wins -> CpuTurnObjective.TAKE_PRIZES
                    preferredLine.attack != null && forecast.preview(preferredLine.stateBeforeAttack ?: initial, preferredLine.attack)?.knockout == true -> CpuTurnObjective.TAKE_PRIZES
                    preferredLine.attack != null -> CpuTurnObjective.EXECUTE_ATTACK
                    preferredLine.sequence.any { isBossCard(initial, (it as? GameAction.PlayTrainer)?.cardId.orEmpty()) } -> CpuTurnObjective.GUST_TARGET
                    preferredLine.sequence.any { isHandDisruptionCard(initial, (it as? GameAction.PlayTrainer)?.cardId.orEmpty()) } -> CpuTurnObjective.HAND_DISRUPTION
                    preferredLine.sequence.any { it is GameAction.AttachEnergy } -> CpuTurnObjective.BUILD_ATTACKER
                    preferredLine.sequence.any { it is GameAction.PlayTrainer && isSearchCard(initial, it.cardId) || it is GameAction.Evolve } -> CpuTurnObjective.SETUP_ENGINE
                    else -> CpuTurnObjective.PASSIVE_DEVELOPMENT
                }

                turnPlan = CpuTurnPlan(
                    objective = objective,
                    plannedSequence = preferredLine.sequence,
                    initialTurn = initial.turn,
                    actor = actor,
                    stepIndex = 0
                )

                if (strategy in listOf(CpuStrategy.ORACLE, CpuStrategy.GRANDMASTER)) {
                    val diag = formatOracleTurnDiagnostic(
                        initial = initial,
                        actor = actor,
                        deckPlan = activePlan,
                        currentPrediction = currentPrediction,
                        currentHand = currentHandAnalysis,
                        telemetry = telemetry,
                        evaluatedLines = evaluated,
                        bestAttack = bestAttackCandidate,
                        bestNoAttack = bestNoAttackCandidate,
                        preferred = preferredLine,
                        preferenceReason = preferenceReason,
                        objective = objective
                    )
                    telemetry.counterLineLog = diag
                    println(diag)
                }

                val sortedEvaluated = evaluated.sortedByDescending { it.value }
                if (sortedEvaluated.size > 1) telemetry.alternativeScore = sortedEvaluated[1].value
                preferredLine
            } else {
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
                val bestAttackCandidate = evaluated.filter { it.attack != null }.maxByOrNull { it.value }
                val bestNoAttackCandidate = evaluated.filter { it.attack == null && it.first != GameAction.EndTurn }.maxByOrNull { it.value }
                val immediateEndTurnCandidate = evaluated.firstOrNull { it.first == GameAction.EndTurn }
                val fallbackLine = finalists.firstOrNull { it.first == fallback } ?: evaluated.firstOrNull() ?: best ?: Line(fallback, listOf(fallback), 0.0, false, initial, null)

                val (preferredLine, _) = shouldPreferAttack(
                    bestAttackCandidate,
                    bestNoAttackCandidate,
                    immediateEndTurnCandidate,
                    fallbackLine,
                    initial,
                    actor
                )

                val objective = when {
                    preferredLine.wins -> CpuTurnObjective.TAKE_PRIZES
                    preferredLine.attack != null -> CpuTurnObjective.EXECUTE_ATTACK
                    preferredLine.sequence.any { isBossCard(initial, (it as? GameAction.PlayTrainer)?.cardId.orEmpty()) } -> CpuTurnObjective.GUST_TARGET
                    preferredLine.sequence.any { it is GameAction.AttachEnergy } -> CpuTurnObjective.BUILD_ATTACKER
                    preferredLine.sequence.any { it is GameAction.PlayTrainer && isSearchCard(initial, it.cardId) || it is GameAction.Evolve } -> CpuTurnObjective.SETUP_ENGINE
                    else -> CpuTurnObjective.PASSIVE_DEVELOPMENT
                }

                turnPlan = CpuTurnPlan(
                    objective = objective,
                    plannedSequence = preferredLine.sequence,
                    initialTurn = initial.turn,
                    actor = actor,
                    stepIndex = 0
                )

                val sortedEvaluated = evaluated.sortedByDescending { it.value }
                if (sortedEvaluated.size > 1) telemetry.alternativeScore = sortedEvaluated[1].value
                preferredLine
            }
        } else {
            best?.let { b ->
                val objective = if (b.attack != null) CpuTurnObjective.EXECUTE_ATTACK else CpuTurnObjective.PASSIVE_DEVELOPMENT
                turnPlan = CpuTurnPlan(objective = objective, plannedSequence = b.sequence, initialTurn = initial.turn, actor = actor, stepIndex = 0)
            }
            best
        }

        if (plan != null && isPrivileged) {
            val planOracleDraw = if (strategy == CpuStrategy.ORACLE) plan.state.players[opponent].deck.firstOrNull() else null
            val finalPrediction = OpponentBestLinePredictor.predict(plan.state, actor, maskedCatalog, engine, planOracleDraw)
            telemetry.opponentPotentialAfter = finalPrediction.threatScore
            if (telemetry.counterLineLog.isBlank()) telemetry.counterLineLog = telemetry.formatDiagnostics()
        }

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
        return plan.first
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
        val tuningAdaptive = tuning.adaptive

        val benchNeedsEnergy = player.bench.any { b ->
            val bAttacks = engine.availableAttacks(state, actor, b)
            val bCost = bAttacks.maxOfOrNull { engine.attackCost(state, b, it).size } ?: 1
            b.energies.size < bCost
        }

        // Energy Efficiency & Anti-Overattachment
        player.pokemon().forEach { p ->
            val attacks = engine.availableAttacks(state, actor, p)
            val maxCost = attacks.maxOfOrNull { engine.attackCost(state, p, it).size } ?: 1
            val hasScaling = attacks.any { a ->
                a.effects.any { eff ->
                    eff is Effect.DiscardHandEnergyDamageBonus ||
                    eff is Effect.DiscardOwnEnergyDamageBonus ||
                    eff is Effect.ConditionalAttachedEnergyDamage ||
                    (eff is Effect.TargetedDamage && eff.perOwnEnergy > 0) ||
                    (eff is Effect.CoinDamage && eff.perEnergy)
                }
            }

            if (p.energies.isNotEmpty()) {
                val usefulCount = minOf(p.energies.size, maxCost)
                objectiveBonus += 25.0 * usefulCount
                val extraCount = (p.energies.size - maxCost).coerceAtLeast(0)
                if (extraCount > 0) {
                    if (hasScaling) {
                        objectiveBonus += 15.0 * extraCount
                    } else if (benchNeedsEnergy) {
                        // Penalize redundant energy when bench backup needs energy!
                        objectiveBonus -= 50.0 * extraCount * tuningAdaptive.redundantEnergyPenaltyMultiplier
                    }
                }
            }
        }

        // Reward preparing bench backup attacker when active is already prepared
        val activeIsReady = player.active?.let { act ->
            val actAttacks = engine.availableAttacks(state, actor, act)
            val actCost = actAttacks.maxOfOrNull { engine.attackCost(state, act, it).size } ?: 1
            act.energies.size >= actCost
        } ?: false

        if (activeIsReady) {
            val benchBackupWithEnergy = player.bench.count { it.energies.isNotEmpty() }
            if (benchBackupWithEnergy > 0) {
                objectiveBonus += 45.0 * benchBackupWithEnergy * tuningAdaptive.backupAttackerBonusMultiplier
            }
        }

        // Hand Engine Optimization: reward taking legal setup actions with trainers instead of hoarding
        val trainersPlayedCount = node.sequence.count { it is GameAction.PlayTrainer }
        if (trainersPlayedCount > 0) {
            objectiveBonus += 25.0 * trainersPlayedCount * tuningAdaptive.handUtilizationBonusMultiplier
        }

        // Reward gusting multi-prize target
        if (opponent.active != null) {
            val targetDef = maskedCatalog.get(opponent.active!!.top.definitionId)
            if (targetDef.prizes >= 2) objectiveBonus += 120.0
        }

        // Search Sequencing Bonus: playing search as first action to assemble attacker
        val firstActionIsSearch = node.sequence.firstOrNull()?.let { it is GameAction.PlayTrainer && isSearchCard(state, it.cardId) } == true
        if (firstActionIsSearch) objectiveBonus += 40.0 * tuningAdaptive.searchCardBonusMultiplier
        val searchActionsPlayed = node.sequence.count { it is GameAction.PlayTrainer && isSearchCard(state, it.cardId) }
        objectiveBonus += 50.0 * searchActionsPlayed * (tuningAdaptive.searchCardBonusMultiplier - 1.0)

        // Deck Thinning sequencing check during beam search
        val firstSearchIdx = node.sequence.indexOfFirst { it is GameAction.PlayTrainer && isSearchCard(state, it.cardId) }
        val firstDrawIdx = node.sequence.indexOfFirst { it is GameAction.PlayTrainer && isDrawCard(state, it.cardId) }
        val initialHasSearch = player.hand.any { isSearchCard(state, it.id) }
        if (firstDrawIdx != -1 && (firstSearchIdx == -1 || firstDrawIdx < firstSearchIdx) && initialHasSearch) {
            objectiveBonus -= 180.0
        } else if (firstSearchIdx != -1 && firstDrawIdx != -1 && firstSearchIdx < firstDrawIdx) {
            objectiveBonus += 80.0 * tuningAdaptive.thinningBonusMultiplier
        }

        if (isPrivileged) {
            val init = currentInitial ?: state
            val playedDisruption = node.sequence.any { isHandDisruptionCard(init, (it as? GameAction.PlayTrainer)?.cardId.orEmpty()) }
            if (playedDisruption) {
                if (currentPrediction?.isLethal == true || (currentHandAnalysis?.threatScore ?: 0.0) >= 60.0) {
                    objectiveBonus += 250.0
                } else if (currentHandAnalysis?.isDeadHand == true || (currentHandAnalysis?.threatScore ?: 50.0) <= 25.0) {
                    objectiveBonus -= 500.0
                }
            }

            if (currentDeckResources?.boardLockOpportunity == true && opponent.active?.id == currentDeckResources?.lockTargetId) {
                objectiveBonus += 250.0
            }

            if (opponent.active != null) {
                val oppActiveThreat = FutureThreatEvaluator.evaluatePokemonThreat(state, opponent.active!!, actor, maskedCatalog, currentHandAnalysis)
                if (oppActiveThreat >= 90.0 && node.sequence.any { isBossCard(init, (it as? GameAction.PlayTrainer)?.cardId.orEmpty()) }) {
                    objectiveBonus += 300.0
                }
            }

            if (currentPrediction?.isLethal == true && node.sequence.any { it is GameAction.Retreat }) {
                val initialActive = init.players[actor].active
                val newActive = state.players[actor].active
                if (initialActive != null && newActive != null) {
                    val initialPrizes = maskedCatalog.get(initialActive.top.definitionId).prizes
                    val newPrizes = maskedCatalog.get(newActive.top.definitionId).prizes
                    if (initialPrizes > newPrizes && newPrizes < opponent.prizes.size) {
                        objectiveBonus += 350.0
                    }
                }
            }
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
                    val result = runCatching { engine.apply(state, action) }.getOrNull() ?: continue
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
                (before.phase == Phase.RESOLVE_CHOICE && after.phase == Phase.MAIN) ||
                after.history.lastOrNull()?.action is GameAction.PlayTrainer
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
                val candidatesList = if (isPrivileged) {
                    val opp = 1 - actor
                    val oppPokemon = state.players[opp].pokemon().associateBy { it.id }
                    choice.candidates.sortedByDescending { candId ->
                        if (candId == currentDeckResources?.lockTargetId) 1000.0
                        else {
                            val p = oppPokemon[candId]
                            if (p != null) FutureThreatEvaluator.evaluatePokemonThreat(state, p, actor, maskedCatalog, currentHandAnalysis)
                            else 0.0
                        }
                    }
                } else choice.candidates
                candidatesList.take(config.choiceCandidates).forEach {
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

        if (isPrivileged) {
            val init = currentInitial ?: state
            if (isHandDisruptionCard(init, cardId)) {
                if (currentPrediction?.isLethal == true || (currentHandAnalysis?.threatScore ?: 0.0) >= 60.0) {
                    return 190
                } else if (currentHandAnalysis?.isDeadHand == true || (currentHandAnalysis?.threatScore ?: 50.0) <= 25.0) {
                    return 10
                }
            }
            if (isBossCard(init, cardId)) {
                if (currentDeckResources?.boardLockOpportunity == true) {
                    return 185
                }
                val hasHighThreatTarget = opponent.bench.any {
                    FutureThreatEvaluator.evaluatePokemonThreat(state, it, state.currentPlayer, maskedCatalog, currentHandAnalysis) >= 90.0
                }
                if (hasHighThreatTarget) {
                    return 185
                }
            }
        }

        // Check if hand has a draw supporter and we can thin first
        val hasDrawCardInHand = player.hand.any { h ->
            val d = maskedCatalog.get(h.definitionId)
            d.trainerType == TrainerType.SUPPORTER && (d.trainerEffect is TrainerEffect.DrawCards || d.trainerEffect is TrainerEffect.ShuffleHandDraw || d.trainerEffect is TrainerEffect.DrawTo)
        }
        val hasSearchInHand = player.hand.any { isSearchCard(state, it.id) }
        fun learnedSearchPriority(base: Int) = (base * tuning.adaptive.searchCardBonusMultiplier).toInt()

        return when (effect) {
            is TrainerEffect.Switch -> if (effect.opponent && opponent.bench.isNotEmpty()) 155
                else if (!effect.opponent && player.bench.isNotEmpty()) 115 else 45
            is TrainerEffect.DamageBoost -> if (player.active?.let { maskedCatalog.get(it.top.definitionId).type == effect.type } == true) 150 else 45
            is TrainerEffect.OpponentExDamageBoost -> if (opponent.active?.let { maskedCatalog.get(it.top.definitionId).name.endsWith(" ex") ||
                    effect.includeV && maskedCatalog.get(it.top.definitionId).name.endsWith(" V") } == true) 148 else 45
            is TrainerEffect.NonRuleBoxDamageBoost -> if (player.hand.size == 2 && player.active?.let { !maskedCatalog.get(it.top.definitionId).hasRuleBox } == true) 148 else 45
            is TrainerEffect.Search, TrainerEffect.GreatBall, TrainerEffect.BuddyBuddyPoffin, TrainerEffect.SearchEachEvolutionStage ->
                if (player.deck.isNotEmpty()) learnedSearchPriority(if (hasDrawCardInHand) 170 else 150) else 35
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
                    val result = runCatching { engine.apply(state, action) }.getOrNull() ?: continue
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
        val setupBreakBonus = if (isPrivileged && knockout) {
            val oppThreat = FutureThreatEvaluator.evaluatePokemonThreat(state, target, actor, maskedCatalog, currentHandAnalysis)
            if (oppThreat >= 90.0) 800.0 else 0.0
        } else 0.0
        val value = damage.toDouble() + (if (knockout) 700 + preview.prizes*1400 else 0) +
            (if (wins) 100_000 else 0) + control + spread + setupBreakBonus -
            (if (selfKo) maskedCatalog.get(active.top.definitionId).prizes*1200.0 else 0.0) -
            (if (givesLastPrizes) 40_000 else 0) - replyRisk(state, actor, if (knockout) target.id else null)
        return value to wins
    }

    private fun isBossCard(state: GameState, cardId: String): Boolean {
        val defId = state.players.flatMap { it.hand + it.discard }.firstOrNull { it.id == cardId }?.definitionId ?: cardId
        val def = runCatching { maskedCatalog.get(defId) }.getOrNull() ?: return false
        val effect = def.trainerEffect ?: return false
        return effect is TrainerEffect.Switch && effect.opponent
    }

    private fun isHandDisruptionCard(state: GameState, cardId: String): Boolean {
        val defId = state.players.flatMap { it.hand + it.discard }.firstOrNull { it.id == cardId }?.definitionId ?: cardId
        val def = runCatching { maskedCatalog.get(defId) }.getOrNull() ?: return false
        val effect = def.trainerEffect ?: return false
        return effect is TrainerEffect.ShuffleBothHandsDraw ||
            effect is TrainerEffect.DiscardOpponentItemsFromHand ||
            def.name.contains("Iono", ignoreCase = true) ||
            def.name.contains("Judge", ignoreCase = true) ||
            def.name.contains("Roxanne", ignoreCase = true)
    }

    private fun tacticalSequenceBonus(
        initial: GameState,
        state: GameState,
        sequence: List<GameAction>,
        actor: Int
    ): Double {
        if (!isPrivileged) return 0.0
        var bonus = 0.0
        val opponent = 1 - actor

        val playedDisruption = sequence.any { isHandDisruptionCard(initial, (it as? GameAction.PlayTrainer)?.cardId.orEmpty()) }
        if (playedDisruption) {
            if (currentPrediction?.isLethal == true || (currentHandAnalysis?.threatScore ?: 0.0) >= 60.0) {
                bonus += 250.0
            } else if (currentHandAnalysis?.isDeadHand == true || (currentHandAnalysis?.threatScore ?: 50.0) <= 25.0) {
                bonus -= 500.0
            }
        }

        if (currentDeckResources?.boardLockOpportunity == true && state.players[opponent].active?.id == currentDeckResources?.lockTargetId) {
            bonus += 300.0
        }

        val playedBoss = sequence.any { isBossCard(initial, (it as? GameAction.PlayTrainer)?.cardId.orEmpty()) }
        if (playedBoss) {
            val oppActive = state.players[opponent].active
            if (oppActive != null) {
                val oppThreat = FutureThreatEvaluator.evaluatePokemonThreat(state, oppActive, actor, maskedCatalog, currentHandAnalysis)
                if (oppThreat >= 90.0) {
                    bonus += 350.0
                }
            }
        }

        if (currentPrediction?.isLethal == true && sequence.any { it is GameAction.Retreat }) {
            val initialActive = initial.players[actor].active
            val newActive = state.players[actor].active
            if (initialActive != null && newActive != null) {
                val initialPrizes = maskedCatalog.get(initialActive.top.definitionId).prizes
                val newPrizes = maskedCatalog.get(newActive.top.definitionId).prizes
                if (initialPrizes > newPrizes && newPrizes < state.players[opponent].prizes.size) {
                    bonus += 400.0
                }
            }
        }

        return bonus
    }

    private fun development(state: GameState, actor: Int): Double = developmentCache.getOrPut(state) { mutableMapOf() }
        .getOrPut(actor) { positionEvaluator.value(state, actor) + activePlan.boardValue(state.players[actor].pokemon(), maskedCatalog) }

    fun postAction(before: GameState, actor: Int, attack: GameAction.Attack?): GameState {
        val opponent = 1 - actor
        val forecastPreview = attack?.let { forecast.preview(before, it) }

        if (attack == null) {
            val res = runCatching { engine.apply(before, GameAction.EndTurn) }.getOrNull()
            if (res == null || res.legality != Legality.Legal) {
                return before.copy(currentPlayer = opponent, turn = before.turn + 1, phase = Phase.MAIN, resolution = null)
            }
            var s = res.state
            repeat(12) {
                budget.check()
                if (s.phase == Phase.GAME_OVER || (s.phase == Phase.MAIN && s.currentPlayer == opponent)) return s
                val owner = engine.actingPlayer(s)
                val action = when (s.phase) {
                    Phase.SELECT_PRIZES -> s.players[owner].prizes.firstOrNull()?.let { GameAction.TakePrize(it.id) }
                    Phase.PROMOTE -> {
                        val bench = s.players[owner].bench
                        bench.maxByOrNull { it.energies.size * 100 + (maskedCatalog.get(it.top.definitionId).hp - it.damage) }?.let { GameAction.Promote(it.id) }
                    }
                    Phase.RESOLVE_CHOICE -> choices(s, if (owner == actor) activePlan.id else "custom").firstOrNull()
                    else -> null
                } ?: return s
                val stepRes = runCatching { engine.apply(s, action) }.getOrNull() ?: return s
                if (stepRes.legality != Legality.Legal) return s
                s = stepRes.state
            }
            return s
        }

        if (forecastPreview?.uncertain == true) {
            return approximatePostAttack(before, actor, attack, forecastPreview)
        }

        val first = runCatching { engine.apply(before, attack) }.getOrNull()
        if (first == null || first.legality != Legality.Legal) {
            return approximatePostAttack(before, actor, attack, forecastPreview)
        }

        var s = first.state
        repeat(12) {
            budget.check()
            if (s.phase == Phase.GAME_OVER || (s.phase == Phase.MAIN && s.currentPlayer == opponent)) return s
            val owner = engine.actingPlayer(s)
            val action = when (s.phase) {
                Phase.SELECT_PRIZES -> s.players[owner].prizes.firstOrNull()?.let { GameAction.TakePrize(it.id) }
                Phase.PROMOTE -> {
                    val bench = s.players[owner].bench
                    bench.maxByOrNull { it.energies.size * 100 + (maskedCatalog.get(it.top.definitionId).hp - it.damage) }?.let { GameAction.Promote(it.id) }
                }
                Phase.RESOLVE_CHOICE -> choices(s, if (owner == actor) activePlan.id else "custom").firstOrNull()
                else -> null
            } ?: return s
            val stepRes = runCatching { engine.apply(s, action) }.getOrNull() ?: return s
            if (stepRes.legality != Legality.Legal) return s
            s = stepRes.state
        }
        return s
    }

    private fun approximatePostAttack(
        before: GameState,
        actor: Int,
        attack: GameAction.Attack,
        preview: AttackPreview?
    ): GameState {
        val opponent = 1 - actor
        val target = attack.targetId?.let { id -> before.players[opponent].pokemon().firstOrNull { it.id == id } }
            ?: before.players[opponent].active
        val ko = preview?.let { it.knockout && !it.uncertain } == true
        val damageDealt = preview?.damage ?: 0
        val prizeCount = preview?.prizes ?: 1

        val updatedOpponent = before.players[opponent].let { opp ->
            val updatedPokemon = opp.pokemon().map { p ->
                if (p.id == target?.id) p.copy(damage = p.damage + damageDealt) else p
            }
            if (ko && target != null) {
                val remaining = updatedPokemon.filterNot { it.id == target.id }
                if (target.id == opp.active?.id) {
                    opp.copy(active = remaining.firstOrNull(), bench = remaining.drop(1), flags = TurnFlags())
                } else {
                    opp.copy(bench = remaining.filterNot { it.id == opp.active?.id }, flags = TurnFlags())
                }
            } else {
                opp.copy(
                    active = updatedPokemon.firstOrNull { it.id == opp.active?.id },
                    bench = updatedPokemon.filter { it.id != opp.active?.id },
                    flags = TurnFlags()
                )
            }
        }

        val updatedActor = before.players[actor].let { act ->
            val newPrizes = if (ko) act.prizes.drop(prizeCount) else act.prizes
            act.copy(prizes = newPrizes)
        }

        return before.copy(
            currentPlayer = opponent,
            turn = before.turn + 1,
            phase = Phase.MAIN,
            resolution = null,
            players = before.players.mapIndexed { idx, p ->
                when (idx) {
                    actor -> updatedActor
                    opponent -> updatedOpponent
                    else -> p
                }
            }
        )
    }

    private fun shouldPreferAttack(
        bestAttack: Line?,
        bestNoAttack: Line?,
        immediateEndTurn: Line?,
        fallbackLine: Line,
        initial: GameState,
        actor: Int
    ): Pair<Line, String> {
        if (bestAttack == null) {
            val chosen = bestNoAttack ?: immediateEndTurn ?: fallbackLine
            return chosen to "No legal attack candidate available"
        }

        val attackAction = bestAttack.attack ?: return (bestNoAttack ?: bestAttack) to "Missing attack definition"
        val stateBefore = bestAttack.stateBeforeAttack ?: initial
        val preview = forecast.preview(stateBefore, attackAction)

        // 1. Guaranteed lethal win via attack
        if (bestAttack.wins || (preview != null && preview.knockout && !preview.uncertain && preview.prizes >= initial.players[actor].prizes.size)) {
            return bestAttack to "Attack achieves guaranteed win"
        }

        // 2. Check suicidal self-KO that hands match to opponent
        val active = stateBefore.players[actor].active
        val attackDef = active?.let { engine.availableAttacks(stateBefore, actor, it).firstOrNull { a -> a.id == attackAction.attackId } }
        val recoil = attackDef?.effects?.sumOf { when (it) { is Effect.Recoil -> it.damage; is Effect.SelfDamage -> it.amount; else -> 0 } } ?: 0
        val activeHp = active?.let { engine.effectiveHp(stateBefore, it) - it.damage } ?: 0
        val isSuicidalSelfKo = recoil >= activeHp && active != null &&
            maskedCatalog.get(active.top.definitionId).prizes >= initial.players[1 - actor].prizes.size &&
            (preview == null || !preview.knockout || preview.prizes < initial.players[actor].prizes.size)

        if (isSuicidalSelfKo) {
            val alt = bestNoAttack ?: immediateEndTurn
            if (alt != null) return alt to "Attack causes suicidal self-KO conceding last prizes"
        }

        // 3. Defensive evasion of proven opponent lethal
        if (bestNoAttack != null) {
            val attackReply = OpponentBestLinePredictor.predict(bestAttack.state, actor, maskedCatalog, engine, currentOracleDraw)
            val noAttackReply = OpponentBestLinePredictor.predict(bestNoAttack.state, actor, maskedCatalog, engine, currentOracleDraw)

            if (!noAttackReply.isLethal && attackReply.isLethal) {
                return bestNoAttack to "Defensive line successfully evades opponent lethal threat"
            }
        }

        // 4. Default: If attack deals damage, achieves KO, or has positive tactical value, always prefer attack over pass
        val damage = preview?.damage ?: 0
        if (damage > 0 || preview?.knockout == true || (bestAttack.value >= (bestNoAttack?.value ?: Double.NEGATIVE_INFINITY) - 200.0)) {
            val reason = if (preview?.knockout == true) "Attack secures knockout" else "Attack applies active pressure ($damage damage) without suicidal risk"
            return bestAttack to reason
        }

        if (bestNoAttack != null && bestNoAttack.value > bestAttack.value + 300.0) {
            return bestNoAttack to "Defensive preparation significantly outperforms zero-damage attack"
        }

        return bestAttack to "Default attack preference over pass"
    }

    private fun formatOracleTurnDiagnostic(
        initial: GameState,
        actor: Int,
        deckPlan: CpuDeckPlan,
        currentPrediction: PredictionResult?,
        currentHand: HandAnalysis?,
        telemetry: CpuSearchTelemetry,
        evaluatedLines: List<Line>,
        bestAttack: Line?,
        bestNoAttack: Line?,
        preferred: Line,
        preferenceReason: String,
        objective: CpuTurnObjective
    ): String {
        val opponent = 1 - actor
        val player = initial.players[actor]
        val oppPlayer = initial.players[opponent]

        val handStr = player.hand.joinToString(", ") { maskedCatalog.get(it.definitionId).name }
        val activeHp = player.active?.let { "${it.top.definitionId}/${engine.effectiveHp(initial, it) - it.damage}hp" } ?: "None"
        val benchStr = player.bench.joinToString(", ") { "${it.top.definitionId}/${engine.effectiveHp(initial, it) - it.damage}hp" }
        val deckResStr = "Deck: ${player.deck.size}, Energy: ${player.deck.count { runCatching { maskedCatalog.get(it.definitionId).category == Category.ENERGY }.getOrDefault(false) }}"

        val oppThreat = currentPrediction?.threatScore ?: 0.0
        val oppHandStr = oppPlayer.hand.joinToString(", ") { maskedCatalog.get(it.definitionId).name }
        val oppBestLineStr = currentPrediction?.bestLine?.joinToString(" -> ") { it.toString() } ?: "None"
        val oppLethalStr = currentPrediction?.isLethal ?: false

        val candidateLinesStr = evaluatedLines.take(4).mapIndexed { idx, line ->
            val tag = when {
                line.attack != null && line.sequence.size > 1 -> "[Combo]"
                line.attack != null -> "[Attack]"
                line.first == GameAction.EndTurn -> "[EndTurn]"
                else -> "[Defensive]"
            }
            val seqStr = line.sequence.joinToString(" -> ") { it.toString() }
            val replyPred = OpponentBestLinePredictor.predict(line.state, actor, maskedCatalog, engine, currentOracleDraw)
            "${idx + 1}. $tag $seqStr -> Post-attack Opponent Threat: ${"%.1f".format(replyPred.threatScore)}, Net: ${"%.1f".format(line.value)}"
        }.joinToString("\n")

        val bestAttackVal = bestAttack?.let { "${"%.1f".format(it.value)} (Action: ${it.attack})" } ?: "None"
        val bestNoAttackVal = bestNoAttack?.let { "${"%.1f".format(it.value)} (Action: ${it.first})" } ?: "None"
        val attackPreferred = preferred.attack != null

        return buildString {
            appendLine("=== ORACLE TURN DIAGNOSTIC ===")
            appendLine("Turn: ${initial.turn}")
            appendLine("Deck: ${deckPlan.id}")
            appendLine("Hand: $handStr")
            appendLine("Board: Active=$activeHp, Bench=[$benchStr]")
            appendLine("Deck Resources Remaining: $deckResStr")
            appendLine()
            appendLine("Opponent Read:")
            appendLine("- Visible Threat: ${"%.1f".format(oppThreat)}")
            appendLine("- Hand Contents: $oppHandStr")
            appendLine("- Predicted Best Line: $oppBestLineStr")
            appendLine("- Lethal Next Turn: $oppLethalStr")
            appendLine()
            appendLine("Search Execution:")
            appendLine("- Root Candidates: ${telemetry.candidatesGenerated}")
            appendLine("- Sequences Explored: ${telemetry.sequencesEvaluated}")
            appendLine("- Max Depth: ${telemetry.maximumActionDepthReached}")
            appendLine("- Transitions: ${telemetry.statesFromCache + telemetry.candidatesPruned + telemetry.sequencesEvaluated}")
            appendLine("- Time: ${telemetry.searchTimeMs}ms")
            appendLine()
            appendLine("Candidate Lines:")
            appendLine(candidateLinesStr)
            appendLine()
            appendLine("Comparison:")
            appendLine("- Best Attack Value: $bestAttackVal")
            appendLine("- Best No-Attack Value: $bestNoAttackVal")
            appendLine("- Attack Preferred Over Pass: $attackPreferred (Reason: $preferenceReason)")
            appendLine()
            appendLine("Selected Line: ${preferred.sequence.joinToString(" -> ") { it.toString() }}")
            appendLine("Objective: $objective")
            appendLine("First Action: ${preferred.first}")
            append("=== END DIAGNOSTIC ===")
        }
    }
}
