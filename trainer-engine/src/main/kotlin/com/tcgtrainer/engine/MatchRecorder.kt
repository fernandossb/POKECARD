package com.tcgtrainer.engine

class MatchRecorder(
    private val catalog: CardCatalog = TrainerCatalog
) {
    var recording: MatchRecording? = null
        private set

    val isRecordingActive: Boolean get() = recording != null

    fun getCompletedRecording(): MatchRecording? = recording

    private var currentTurn: RecordedTurn? = null
    private var actionStep = 0

    fun startRecording(state: GameState, difficulty: String) {
        actionStep = 0
        currentTurn = null
        recording = MatchRecording(
            gameId = state.gameId,
            seed = state.seed,
            engineVersion = state.catalogVersion,
            appVersion = "5.99.48",
            difficulty = difficulty,
            humanDeckId = state.humanDeckId,
            cpuDeckId = state.cpuDeckId,
            mode = state.mode.name,
            deckLists = state.deckLists,
            deckNames = state.deckNames,
            winner = state.winner,
            winReason = state.winReason,
            totalTurns = state.turn
        )
    }

    fun startMatch(
        gameId: String,
        appVersion: String,
        engineVersion: String,
        difficulty: String,
        initialState: GameState,
        humanDeckId: String,
        humanDeckName: String,
        cpuDeckId: String,
        cpuDeckName: String,
        humanDeckList: List<String>,
        cpuDeckList: List<String>
    ) {
        actionStep = 0
        currentTurn = null
        recording = MatchRecording(
            gameId = gameId,
            seed = initialState.seed,
            engineVersion = engineVersion,
            appVersion = appVersion,
            difficulty = difficulty,
            humanDeckId = humanDeckId,
            cpuDeckId = cpuDeckId,
            mode = initialState.mode.name,
            deckLists = listOf(humanDeckList, cpuDeckList),
            deckNames = listOf(humanDeckName, cpuDeckName),
            winner = initialState.winner,
            winReason = initialState.winReason,
            totalTurns = initialState.turn
        )
    }

    fun recordAction(
        beforeState: GameState,
        action: GameAction,
        resultState: GameState,
        events: List<GameEvent>,
        cpuTelemetry: CpuSearchTelemetry? = null
    ) {
        val currentRec = recording ?: return
        val actor = beforeState.currentPlayer
        val turnNumber = beforeState.turn

        // Check if we need to start a new RecordedTurn
        if (currentTurn == null || currentTurn?.turnNumber != turnNumber || currentTurn?.player != actor) {
            // Close previous turn if exists
            currentTurn?.let { turn ->
                val lastPlayer = beforeState.players.getOrNull(turn.player)
                val finalHand = lastPlayer?.hand?.map { toRecordedCard(it) } ?: emptyList()
                val completedTurn = turn.copy(handAtEnd = finalHand)
                currentRec.turns.removeAll { it.turnNumber == completedTurn.turnNumber && it.player == completedTurn.player }
                currentRec.turns.add(completedTurn)
            }

            val startPlayer = beforeState.players.getOrNull(actor)
            val handAtStart = startPlayer?.hand?.map { toRecordedCard(it) } ?: emptyList()
            currentTurn = RecordedTurn(
                turnNumber = turnNumber,
                player = actor,
                handAtStart = handAtStart,
                handAtEnd = handAtStart
            )
            currentRec.turns.add(currentTurn!!)
        }

        val turnRef = currentTurn ?: return
        actionStep++

        val boardBefore = toRecordedBoardState(beforeState)
        val boardAfter = toRecordedBoardState(resultState)

        val cpuDecision = cpuTelemetry?.let { t ->
            val target = when (action) {
                is GameAction.AttachEnergy -> action.pokemonId
                is GameAction.Attack -> action.targetId
                is GameAction.Retreat -> action.pokemonId
                else -> null
            }
            val energyBefore = when (action) {
                is GameAction.AttachEnergy -> beforeState.players.flatMap { it.pokemon() }.firstOrNull { it.id == action.pokemonId }?.energies?.size
                else -> null
            }
            val energyAfter = when (action) {
                is GameAction.AttachEnergy -> resultState.players.flatMap { it.pokemon() }.firstOrNull { it.id == action.pokemonId }?.energies?.size
                else -> null
            }

            CpuDecisionTelemetry(
                turn = turnNumber,
                difficulty = currentRec.difficulty,
                objective = t.bestLine.firstOrNull()?.toString() ?: "EXECUTE",
                legalActionsCount = t.candidatesGenerated,
                legalAttacksCount = beforeState.players[1].active?.let { active ->
                    engineAvailableAttacks(beforeState, 1, active).size
                } ?: 0,
                playableTrainersCount = beforeState.players[1].hand.count { card ->
                    runCatching { catalog.get(card.definitionId).category == Category.TRAINER }.getOrDefault(false)
                },
                selectedAction = action.toString(),
                target = target,
                energyBefore = energyBefore,
                energyAfter = energyAfter,
                alternativeActions = listOfNotNull(t.alternativeScore.takeIf { it != 0.0 }?.let { "Alt score: $it" }),
                decisionScore = t.bestScore,
                alternativeScores = listOfNotNull(t.alternativeScore.takeIf { it != 0.0 }),
                searchDepth = t.maximumActionDepthReached,
                sequencesEvaluated = t.sequencesEvaluated,
                searchTimeMs = t.searchTimeMs,
                opponentThreatBefore = t.opponentPotentialBefore,
                opponentThreatAfter = t.opponentPotentialAfter
            )
        }

        val recordedAction = RecordedAction(
            stepIndex = actionStep,
            turn = turnNumber,
            player = actor,
            actionType = action::class.simpleName ?: "Action",
            actionDetails = action.toString(),
            stateBefore = boardBefore,
            stateAfter = boardAfter,
            events = events,
            cpuDecision = cpuDecision
        )

        turnRef.actions.add(recordedAction)
        if (action is GameAction.Attack) {
            turnRef.attackExecuted = true
        }

        // Update hand at end of this action
        val playerNow = resultState.players.getOrNull(actor)
        val currentHand = playerNow?.hand?.map { toRecordedCard(it) } ?: emptyList()
        val updatedTurn = turnRef.copy(handAtEnd = currentHand)
        val turnIdx = currentRec.turns.indexOfFirst { it.turnNumber == turnNumber && it.player == actor }
        if (turnIdx != -1) {
            currentRec.turns[turnIdx] = updatedTurn
            currentTurn = updatedTurn
        }

        currentRec.totalTurns = resultState.turn
        if (resultState.phase == Phase.GAME_OVER) {
            currentRec.winner = resultState.winner
            currentRec.winReason = resultState.winReason
            currentRec.finishedAtEpochMs = System.currentTimeMillis()
        }
    }

    fun recordAction(
        playerIndex: Int,
        action: GameAction,
        beforeState: GameState,
        afterState: GameState,
        events: List<GameEvent>,
        telemetry: CpuDecisionTelemetry? = null
    ) {
        val currentRec = recording ?: return
        val actor = playerIndex
        val turnNumber = beforeState.turn

        if (currentTurn == null || currentTurn?.turnNumber != turnNumber || currentTurn?.player != actor) {
            currentTurn?.let { turn ->
                val lastPlayer = beforeState.players.getOrNull(turn.player)
                val finalHand = lastPlayer?.hand?.map { toRecordedCard(it) } ?: emptyList()
                val completedTurn = turn.copy(handAtEnd = finalHand)
                currentRec.turns.removeAll { it.turnNumber == completedTurn.turnNumber && it.player == completedTurn.player }
                currentRec.turns.add(completedTurn)
            }

            val startPlayer = beforeState.players.getOrNull(actor)
            val handAtStart = startPlayer?.hand?.map { toRecordedCard(it) } ?: emptyList()
            currentTurn = RecordedTurn(
                turnNumber = turnNumber,
                player = actor,
                handAtStart = handAtStart,
                handAtEnd = handAtStart
            )
            currentRec.turns.add(currentTurn!!)
        }

        val turnRef = currentTurn ?: return
        actionStep++

        val boardBefore = toRecordedBoardState(beforeState)
        val boardAfter = toRecordedBoardState(afterState)

        val recordedAction = RecordedAction(
            stepIndex = actionStep,
            turn = turnNumber,
            player = actor,
            actionType = action::class.simpleName ?: "Action",
            actionDetails = action.toString(),
            stateBefore = boardBefore,
            stateAfter = boardAfter,
            events = events,
            cpuDecision = telemetry
        )

        turnRef.actions.add(recordedAction)
        if (action is GameAction.Attack) {
            turnRef.attackExecuted = true
        }

        val playerNow = afterState.players.getOrNull(actor)
        val currentHand = playerNow?.hand?.map { toRecordedCard(it) } ?: emptyList()
        val updatedTurn = turnRef.copy(handAtEnd = currentHand)
        val turnIdx = currentRec.turns.indexOfFirst { it.turnNumber == turnNumber && it.player == actor }
        if (turnIdx != -1) {
            currentRec.turns[turnIdx] = updatedTurn
            currentTurn = updatedTurn
        }

        currentRec.totalTurns = afterState.turn
        if (afterState.phase == Phase.GAME_OVER) {
            currentRec.winner = afterState.winner
            currentRec.winReason = afterState.winReason
            currentRec.finishedAtEpochMs = System.currentTimeMillis()
        }
    }

    fun recordUndo() {
        val currentRec = recording ?: return
        if (currentRec.turns.isEmpty()) return
        val lastTurn = currentRec.turns.lastOrNull() ?: return
        if (lastTurn.actions.isNotEmpty()) {
            lastTurn.actions.removeLastOrNull()
            actionStep = (actionStep - 1).coerceAtLeast(0)
            if (lastTurn.actions.isEmpty()) {
                currentRec.turns.removeLastOrNull()
                currentTurn = currentRec.turns.lastOrNull()
            }
        }
    }

    fun recordUndo(state: GameState) {
        recordUndo()
    }

    fun recordGameEnd(state: GameState, winner: Int?) {
        recordGameEnd(winner, state.winReason)
    }

    fun recordGameEnd(winner: Int?, winReason: String?) {
        val currentRec = recording ?: return
        currentRec.winner = winner
        currentRec.winReason = winReason
        currentRec.finishedAtEpochMs = System.currentTimeMillis()
    }

    private fun toRecordedCard(instance: CardInstance): RecordedCard {
        val def = runCatching { catalog.get(instance.definitionId) }.getOrNull()
        return RecordedCard(
            id = instance.id,
            definitionId = instance.definitionId,
            name = def?.name ?: instance.definitionId,
            category = def?.category?.name ?: "UNKNOWN",
            energyType = def?.type?.name
        )
    }

    private fun toRecordedPokemon(instance: PokemonInstance): RecordedPokemon {
        val def = runCatching { catalog.get(instance.top.definitionId) }.getOrNull()
        val maxHp = def?.hp ?: 100
        return RecordedPokemon(
            id = instance.id,
            definitionId = instance.top.definitionId,
            name = def?.name ?: instance.top.definitionId,
            hp = (maxHp - instance.damage).coerceAtLeast(0),
            maxHp = maxHp,
            damage = instance.damage,
            energies = instance.energies.map { toRecordedCard(it) },
            tools = instance.tools.map { toRecordedCard(it) },
            conditions = instance.conditions.toList()
        )
    }

    private fun toRecordedPlayerState(player: PlayerState, index: Int): RecordedPlayerState {
        return RecordedPlayerState(
            playerIndex = index,
            active = player.active?.let { toRecordedPokemon(it) },
            bench = player.bench.map { toRecordedPokemon(it) },
            hand = player.hand.map { toRecordedCard(it) },
            handCount = player.hand.size,
            prizesRemaining = player.prizes.size,
            deckCount = player.deck.size,
            discardCount = player.discard.size,
            discard = player.discard.map { toRecordedCard(it) }
        )
    }

    private fun toRecordedBoardState(state: GameState): RecordedBoardState {
        return RecordedBoardState(
            turn = state.turn,
            phase = state.phase,
            currentPlayer = state.currentPlayer,
            stadium = state.stadium?.let { toRecordedCard(it) },
            players = state.players.mapIndexed { idx, p -> toRecordedPlayerState(p, idx) }
        )
    }

    private fun engineAvailableAttacks(state: GameState, actor: Int, pokemon: PokemonInstance): List<AttackDefinition> {
        val def = runCatching { catalog.get(pokemon.top.definitionId) }.getOrNull() ?: return emptyList()
        return def.attacks
    }
}

