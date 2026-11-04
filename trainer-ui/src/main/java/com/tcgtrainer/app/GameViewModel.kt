package com.tcgtrainer.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tcgtrainer.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(val game: GameState? = null, val canContinue: Boolean = false, val busy: Boolean = true, val message: String? = null)
data class DeckUiState(
    val saved: List<SavedDeck> = emptyList(),
    val trainingSaved: List<SavedDeck> = emptyList(),
    val validations: Map<String, DeckValidation> = emptyMap(),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null
)

class GameViewModel(app: Application) : AndroidViewModel(app) {
    private fun loadOwned(context: Application) = try {
        OwnedCollection.decode(java.io.File(context.filesDir, "trainer-owned.json").readText(Charsets.UTF_8))
    } catch (_: Exception) { OwnedCollection(emptyMap(), available = false) }
    var owned = loadOwned(app)
        private set
    fun ownershipMessage(cards: List<String>) = owned.message(cards)
    val engine = RulesEngine(TrainerCatalog)
    private val db = GameDatabase.open(app)
    private var session: GameSession? = null
    private val mutable = MutableStateFlow(UiState())
    val ui = mutable.asStateFlow()
    private val mutableDecks = MutableStateFlow(DeckUiState())
    val deckUi = mutableDecks.asStateFlow()
    private val mutableBinderDecks = MutableStateFlow<List<TrainingDeck>?>(TrainerDeckBridge.loadSyncedDecks(app))
    val binderDecks = mutableBinderDecks.asStateFlow()
    fun refreshSyncedDecks() {
        val context = getApplication<Application>()
        owned = loadOwned(context)
        mutableBinderDecks.value = TrainerDeckBridge.loadSyncedDecks(context)
    }
    fun clearDeckMessage() { mutableDecks.value = mutableDecks.value.copy(message = null) }
    fun playableDecks(): List<TrainingDeck> {
        val synced = mutableBinderDecks.value?.filter { it.cards.isNotEmpty() }
        if (!synced.isNullOrEmpty()) return synced
        return TrainingDecks.all + mutableDecks.value.trainingSaved.map { it.playableDeck() }
    }
    fun deck(id: String) = playableDecks().firstOrNull { it.id == id }
    init {
        viewModelScope.launch {
            try {
                val savedMemory = withContext(Dispatchers.IO) { MatchRecordingStore.loadExperienceMemory(app) }
                if (savedMemory.isNotBlank()) {
                    learningEngine.experienceMemory = CpuExperienceMemory.fromJson(savedMemory)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        viewModelScope.launch {
            try { mutable.value = UiState(canContinue = db.saves().load() != null, busy = false) }
            catch (e: Exception) { mutable.value = UiState(busy = false, message = "Falha ao ler save: ${e.message}") }
        }
        viewModelScope.launch {
            try {
                db.decks().observe().collect { records ->
                    val saved = withContext(Dispatchers.Default) { records.map { DeckCodec.decode(it.payload) } }
                    val validations = withContext(Dispatchers.Default) { saved.associate { it.id to DeckValidator.validate(it) } }
                    val trainable = saved.filter { validations[it.id]?.trainingReady == true }
                    mutableDecks.value = mutableDecks.value.copy(saved = saved, trainingSaved = trainable, validations = validations, loaded = true)
                }
            } catch (e: Exception) { mutableDecks.value = mutableDecks.value.copy(loaded = true, message = "Falha ao ler decks: ${e.message}") }
        }
    }
    fun saveDeck(deck: SavedDeck, done: () -> Unit) {
        if (mutableDecks.value.saving) return
        mutableDecks.value = mutableDecks.value.copy(saving = true, message = null)
        viewModelScope.launch {
            try {
                check(deck.name.isNotBlank()) { "Dê um nome ao deck." }
                check(deck.counts.values.all { it in 1..60 }) { "Quantidade inválida." }
                val saved = deck.copy(name = deck.name.trim(), updatedAt = System.currentTimeMillis())
                db.decks().save(DeckRecord(saved.id, DeckCodec.encode(saved), saved.updatedAt))
                mutableDecks.value = mutableDecks.value.copy(saving = false, message = "Deck salvo no aparelho.")
                done()
            } catch (e: Exception) { mutableDecks.value = mutableDecks.value.copy(saving = false, message = e.message) }
        }
    }
    fun deleteDeck(id: String) { viewModelScope.launch { try { db.decks().delete(id) } catch (e: Exception) { mutableDecks.value = mutableDecks.value.copy(message = e.message) } } }
    private val _difficulty = MutableStateFlow(AiDifficulty.MASTER)
    val difficulty = _difficulty.asStateFlow()
    fun setDifficulty(diff: AiDifficulty) { _difficulty.value = diff }

    val isRecordingEnabled = MutableStateFlow(true)
    fun setRecordingEnabled(enabled: Boolean) { isRecordingEnabled.value = enabled }

    val recorder = MatchRecorder(TrainerCatalog)
    val learningEngine = CpuLearningEngine(TrainerCatalog)

    private var activeCpuPlayer: CpuPlayer? = null
    private var activeCpuDifficulty: AiDifficulty? = null

    private fun getOrCreateCpu(difficulty: AiDifficulty, archetype: String = "general"): CpuPlayer {
        val existing = activeCpuPlayer
        if (existing == null || activeCpuDifficulty != difficulty) {
            val strategy = when (difficulty) {
                AiDifficulty.EASY, AiDifficulty.NORMAL -> CpuStrategy.HEURISTIC
                AiDifficulty.HARD, AiDifficulty.EXPERT, AiDifficulty.MASTER, AiDifficulty.CHAMPION -> CpuStrategy.MASTER
                AiDifficulty.GRANDMASTER -> CpuStrategy.GRANDMASTER
                AiDifficulty.ORACLE -> CpuStrategy.ORACLE
            }
            val adaptiveTuning = learningEngine.getTuningForArchetype(archetype)
            val tuning = CpuTuning(adaptive = adaptiveTuning)
            val newCpu = CpuPlayer(strategy = strategy, tuning = tuning, thinking = CpuThinking.ADAPTIVE, config = difficulty.toConfig())
            activeCpuPlayer = newCpu
            activeCpuDifficulty = difficulty
            return newCpu
        }
        return existing
    }
    fun start(seed: Long, mode: Mode, humanDeck: String = "lucario", cpuDeck: String = "random") {
        activeCpuPlayer?.resetPlan()
        if (mutable.value.busy) return
        mutate {
            val newSession = withContext(Dispatchers.Default) {
                val decks = playableDecks()
                val own = checkNotNull(decks.firstOrNull { it.id == humanDeck }) { "Este deck ainda não pode ser treinado. Confira se tem ao menos 14 cartas, um Pokémon Básico e efeitos implementados." }
                val ownValidation = DeckValidator.validate(own)
                check(if (mode == Mode.TRAINING) ownValidation.trainingReady else ownValidation.playable) {
                    if (mode == Mode.TRAINING) "Este deck ainda não pode ser treinado. Confira as cartas e os efeitos implementados."
                    else "Partida normal exige um deck válido de 60 cartas no formato escolhido."
                }
                val candidates = decks.filter { it.cards.size == 60 && DeckValidator.validate(it).playable }.filter {
                    own.format != DeckFormat.STANDARD || DeckValidator.validate(SavedDeck(it.id, it.name, DeckFormat.STANDARD, it.cards.groupingBy { id -> id }.eachCount())).playable
                }
                val other = if (cpuDeck == "random") {
                    check(candidates.isNotEmpty()) { "Ainda não há adversários implementados elegíveis para este formato." }
                    val masterPool = TrainingDecks.masterOpponents(candidates, own.format)
                    masterPool[java.security.SecureRandom().nextInt(masterPool.size)]
                } else checkNotNull(candidates.firstOrNull { it.id == cpuDeck }) {
                    "Este deck da máquina não é válido para o formato selecionado. Escolha outro baralho."
                }
                check(DeckValidator.validate(other).playable) { "Confira as regras do deck da máquina antes de jogar." }
                check(mode == Mode.TRAINING || owned.allows(own.cards)) { ownershipMessage(own.cards) ?: "Confira sua coleção no PokeCard." }
                GameSession(engine, SessionSnapshot(engine.newGame(seed,
                    decks = listOf(own.cards, other.cards), mode = mode).copy(humanDeckId = humanDeck, cpuDeckId = other.id,
                        deckLists = listOf(own.cards, other.cards), deckNames = listOf(own.name, other.name), deckFormats = listOf(own.format, other.format))))
            }
            session = newSession
            activeCpuPlayer = null
            if (isRecordingEnabled.value) {
                val ownDeck = playableDecks().firstOrNull { it.id == humanDeck }
                recorder.startMatch(
                    gameId = java.util.UUID.randomUUID().toString(),
                    appVersion = "PokeCard 2.0.0",
                    engineVersion = "trainer-engine-v2.0",
                    difficulty = _difficulty.value.name,
                    initialState = newSession.snapshot.state,
                    humanDeckId = humanDeck,
                    humanDeckName = ownDeck?.name ?: humanDeck,
                    cpuDeckId = newSession.snapshot.state.cpuDeckId,
                    cpuDeckName = newSession.snapshot.state.deckNames.getOrNull(1) ?: "CPU",
                    humanDeckList = newSession.snapshot.state.deckLists.getOrNull(0) ?: emptyList(),
                    cpuDeckList = newSession.snapshot.state.deckLists.getOrNull(1) ?: emptyList()
                )
            }
        }
    }
    fun resume() {
        if (mutable.value.busy) return
        mutate {
            val save = db.saves().load() ?: error("Nenhuma partida salva.")
            val snapshot = GameSession.decode(save.payload)
            val cards = snapshot.state.deckLists.firstOrNull() ?: error("Partida sem lista de cartas.")
            check(snapshot.state.mode == Mode.TRAINING || owned.allows(cards)) { ownershipMessage(cards) ?: "Confira sua coleção no PokeCard." }
            val resumedSession = GameSession(engine, snapshot)
            session = resumedSession
            activeCpuPlayer = null
            if (isRecordingEnabled.value && !recorder.isRecordingActive) {
                recorder.startMatch(
                    gameId = snapshot.state.gameId,
                    appVersion = "PokeCard 2.0.0",
                    engineVersion = "trainer-engine-v2.0",
                    difficulty = _difficulty.value.name,
                    initialState = snapshot.state,
                    humanDeckId = snapshot.state.humanDeckId,
                    humanDeckName = snapshot.state.deckNames.getOrNull(0) ?: "Jogador",
                    cpuDeckId = snapshot.state.cpuDeckId,
                    cpuDeckName = snapshot.state.deckNames.getOrNull(1) ?: "CPU",
                    humanDeckList = snapshot.state.deckLists.getOrNull(0) ?: emptyList(),
                    cpuDeckList = snapshot.state.deckLists.getOrNull(1) ?: emptyList()
                )
            }
        }
    }
    fun act(action: GameAction) {
        if (mutable.value.busy || mutable.value.game?.let { engine.actingPlayer(it) != 0 } == true) return
        mutate {
            val current = session ?: return@mutate
            val beforeState = current.snapshot.state
            val result = current.dispatch(action)
            if (result.legality is Legality.Illegal) error((result.legality as Legality.Illegal).reason)
            val afterState = current.snapshot.state
            if (isRecordingEnabled.value) {
                recorder.recordAction(
                    playerIndex = 0,
                    action = action,
                    beforeState = beforeState,
                    afterState = afterState,
                    events = result.events
                )
            }
        }
    }
    fun actSequence(actions: List<GameAction>) {
        if (mutable.value.busy || actions.isEmpty() || mutable.value.game?.let { engine.actingPlayer(it) != 0 } == true) return
        mutate {
            val original = session ?: return@mutate
            val candidate = GameSession(engine, original.snapshot)
            for ((index, action) in actions.withIndex()) {
                check(engine.actingPlayer(candidate.snapshot.state) == 0) { "É o turno da máquina." }
                val beforeState = candidate.snapshot.state
                val result = candidate.dispatch(action, recordUndo = index == 0)
                check(result.legality == Legality.Legal) { (result.legality as? Legality.Illegal)?.reason ?: "Jogada inválida." }
                val afterState = candidate.snapshot.state
                if (isRecordingEnabled.value) {
                    recorder.recordAction(
                        playerIndex = 0,
                        action = action,
                        beforeState = beforeState,
                        afterState = afterState,
                        events = result.events
                    )
                }
            }
            session = candidate
        }
    }
    fun undo() {
        if (!mutable.value.busy) mutate {
            check(session?.undo() == true) { "Não há ação para desfazer." }
            session?.snapshot?.state?.let { state ->
                recorder.recordUndo(state)
            }
        }
    }
    fun menu() { if (!mutable.value.busy) mutable.value = mutable.value.copy(game = null, message = null) }
    private fun mutate(block: suspend () -> Unit) {
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                block()
                suspend fun publish(busy: Boolean) {
                    val snapshot = session?.snapshot ?: return
                    val payload = withContext(Dispatchers.Default) { GameSession.encode(snapshot) }
                    db.saves().save(SavedGame(payload = payload))
                    mutable.value = UiState(game = snapshot.state, canContinue = true, busy = busy)
                }
                publish(busy = true)
                while (session?.snapshot?.state?.let { it.phase != Phase.GAME_OVER && engine.actingPlayer(it) == 1 } == true) {
                    // Give the opening toss the same visible presentation whichever side won.
                    delay(if (session?.snapshot?.state?.phase == Phase.CHOOSE_STARTER) 2200 else 950)
                    val current = checkNotNull(session)
                    val beforeState = current.snapshot.state
                    val currentDiff = _difficulty.value
                    val currentCpu = getOrCreateCpu(currentDiff, beforeState.cpuDeckId)
                    val cpuInput = engine.cpuInput(beforeState, currentDiff.policy.toKnowledge())
                    val action = withContext(Dispatchers.Default) {
                        currentCpu.choose(cpuInput)
                    }
                    val result = current.dispatch(action, recordUndo = false)
                    check(result.legality == Legality.Legal) { "CPU: ${(result.legality as? Legality.Illegal)?.reason}" }
                    val afterState = current.snapshot.state
                    val telemetry = currentCpu.lastTelemetry?.let { t ->
                        CpuDecisionTelemetry(
                            turn = beforeState.turn,
                            difficulty = currentDiff.name,
                            objective = if (currentCpu.currentTurnPlan?.isComplete() == false) "Executing planned sequence" else "Search optimal line",
                            legalActionsCount = cpuInput.actions.size,
                            legalAttacksCount = cpuInput.actions.count { it is GameAction.Attack },
                            playableTrainersCount = cpuInput.actions.count { it is GameAction.PlayTrainer },
                            legalTrainerCardIds = cpuInput.actions.filterIsInstance<GameAction.PlayTrainer>().map { it.cardId },
                            selectedAction = action.toString(),
                            decisionScore = t.bestScore,
                            alternativeScores = listOfNotNull(t.alternativeScore),
                            searchDepth = t.maxSequenceDepth,
                            sequencesEvaluated = t.sequencesEvaluated,
                            searchTimeMs = t.searchTimeMs,
                            opponentThreatBefore = t.opponentPotentialBefore,
                            opponentThreatAfter = t.opponentPotentialAfter
                        )
                    }
                    if (isRecordingEnabled.value) {
                        recorder.recordAction(
                            playerIndex = 1,
                            action = action,
                            beforeState = beforeState,
                            afterState = afterState,
                            events = result.events,
                            telemetry = telemetry
                        )
                    }
                    publish(busy = true)
                }
                val finalState = session?.snapshot?.state
                if (finalState?.phase == Phase.GAME_OVER && recorder.isRecordingActive) {
                    val winner = finalState.winner
                    recorder.recordGameEnd(finalState, winner)
                    val recording = recorder.getCompletedRecording()
                    if (recording != null) {
                        withContext(Dispatchers.IO) {
                            MatchRecordingStore.saveRecording(getApplication(), recording)
                            if (winner == 0 && learningEngine.learningEnabled) {
                                val review = learningEngine.reviewMatch(recording)
                                if (review.lessonsLearned.isNotEmpty()) {
                                    MatchRecordingStore.saveExperienceMemory(
                                        getApplication(),
                                        learningEngine.experienceMemory.toJson()
                                    )
                                    activeCpuPlayer = null
                                }
                            }
                        }
                    }
                }
                mutable.value = mutable.value.copy(busy = false)
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(game = session?.snapshot?.state ?: mutable.value.game, busy = false, message = e.message)
            }
        }
    }

    fun getCompletedRecording(): MatchRecording? = recorder.getCompletedRecording()

    fun shareCurrentRecording(asJson: Boolean) {
        val rec = recorder.getCompletedRecording() ?: return
        MatchRecordingStore.shareRecording(getApplication(), rec, asJson)
    }

    fun listSavedRecordings(): List<RecordingSummary> =
        MatchRecordingStore.listRecordings(getApplication())

    fun shareSavedRecording(gameId: String, asJson: Boolean) {
        val rec = MatchRecordingStore.loadRecording(getApplication(), gameId) ?: return
        MatchRecordingStore.shareRecording(getApplication(), rec, asJson)
    }

    fun deleteSavedRecording(gameId: String) {
        MatchRecordingStore.deleteRecording(getApplication(), gameId)
    }

    fun resetCpuLearning() {
        learningEngine.resetMemory()
        MatchRecordingStore.saveExperienceMemory(getApplication(), learningEngine.experienceMemory.toJson())
        activeCpuPlayer = null
    }

    fun toggleLearning(enabled: Boolean) {
        learningEngine.learningEnabled = enabled
    }

    override fun onCleared() { db.close(); super.onCleared() }
}
