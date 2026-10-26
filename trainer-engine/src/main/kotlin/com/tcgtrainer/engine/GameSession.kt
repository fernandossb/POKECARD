package com.tcgtrainer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class SessionSnapshot(val state: GameState, val undoStates: List<GameState> = emptyList(), val undoHistorySizes: List<Int> = emptyList())

/** Snapshot includes hidden zones and RNG. Keep it private to the controller/persistence. */
class GameSession(private val engine: RulesEngine, snapshot: SessionSnapshot) {
    // Normalize legacy checkpoint positions before any automatic CPU actions append history.
    var snapshot = snapshot.copy(undoHistorySizes = snapshot.undoStates.indices.map { index ->
        snapshot.undoHistorySizes.getOrNull(index) ?: (snapshot.state.history.size-snapshot.undoStates.size+index).coerceAtLeast(0)
    }); private set
    fun dispatch(action: GameAction, recordUndo: Boolean = true): Transition {
        val before = snapshot.state
        val result = engine.apply(before, action)
        if (result.legality == Legality.Legal) snapshot = if (before.mode == Mode.TRAINING && recordUndo)
            SessionSnapshot(result.state, snapshot.undoStates + before.copy(history = emptyList()),
                snapshot.undoHistorySizes + before.history.size)
            else snapshot.copy(state = result.state)
        return result
    }
    fun undo(): Boolean {
        if (snapshot.state.mode != Mode.TRAINING || snapshot.undoStates.isEmpty()) return false
        val size = snapshot.undoHistorySizes.lastOrNull() ?: (snapshot.state.history.size-1).coerceAtLeast(0)
        val previous = snapshot.undoStates.last().copy(history = snapshot.state.history.take(size))
        snapshot = SessionSnapshot(previous, snapshot.undoStates.dropLast(1), snapshot.undoHistorySizes.dropLast(1))
        return true
    }
    companion object {
        private val json = Json { encodeDefaults = true }
        fun encode(snapshot: SessionSnapshot): String = json.encodeToString(SessionSnapshot.serializer(), snapshot)
        fun decode(text: String): SessionSnapshot = json.decodeFromString(SessionSnapshot.serializer(), text).also {
            require(it.state.schemaVersion == 1 && it.state.catalogVersion in listOf("test-v1", "lucario-v1")) { "Save incompatível." }
        }
        fun replay(engine: RulesEngine, initial: GameState, actions: List<GameAction>): GameState = actions.fold(initial) { state, action ->
            val result = engine.apply(state, action)
            require(result.legality == Legality.Legal) { "Replay contém ação ilegal: $action" }
            result.state
        }
    }
}

/** Public information plus the viewer's own hand. Never includes deck order or prize identities. */
data class PlayerObservation(
    val viewer: Int, val ownHand: List<CardInstance>, val ownDeckCount: Int, val ownPrizeCount: Int,
    val opponentHandCount: Int, val opponentDeckCount: Int, val opponentPrizeCount: Int,
    val ownField: List<PokemonInstance>, val opponentField: List<PokemonInstance>,
    val ownDiscard: List<CardInstance>, val opponentDiscard: List<CardInstance>,
    val ownActiveId: String? = null, val opponentActiveId: String? = null,
    val ownLastKnockedOutByAttackDamageTurn: Int = -1, val ownLastKnockedOutTurn: Int = -1
) {
    companion object {
        fun from(state: GameState, viewer: Int): PlayerObservation {
            val own = state.players[viewer]; val other = state.players[1-viewer]
            return PlayerObservation(viewer, own.hand, own.deck.size, own.prizes.size, other.hand.size, other.deck.size,
                other.prizes.size, own.pokemon(), if (state.turn == 0) emptyList() else other.pokemon(), own.discard, other.discard,
                own.active?.id, if (state.turn == 0) null else other.active?.id, own.lastKnockedOutByAttackDamageTurn, own.lastKnockedOutTurn)
        }
    }
}
