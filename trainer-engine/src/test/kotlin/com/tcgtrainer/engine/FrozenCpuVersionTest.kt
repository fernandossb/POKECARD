package com.tcgtrainer.engine

import java.io.File
import kotlin.test.*

class FrozenCpuVersionTest {
    @Test fun `frozen engine loads its own classes and returns legal deterministic decisions`() {
        val path = System.getProperty("cpu.arena.baselineJar", "")
        if (path.isBlank()) return // Explicit snapshot integration test; regular suite needs no local binaries.
        FrozenCpuVersion(File(path)).use { frozen ->
            val choose = frozen.player()
            val engine = RulesEngine(TrainerCatalog)
            val deck = TrainingDecks.all.single { it.id == "lucario" }
            var state = engine.newGame(58000003, listOf(deck.cards, deck.cards), Mode.MATCH).copy(
                deckLists = listOf(deck.cards, deck.cards), humanDeckId = deck.id, cpuDeckId = deck.id)
            val current = CpuPlayer(TrainerCatalog)
            repeat(30) {
                if (state.phase == Phase.GAME_OVER) return@repeat
                val previous = choose(state).first
                assertEquals(previous, choose(state).first, "Frozen decisions must be deterministic")
                assertEquals(Legality.Legal, engine.validate(state, previous))
                // Strategic improvements may intentionally differ from the frozen decision.
                val candidate = current.choose(engine.cpuInput(state))
                assertEquals(Legality.Legal, engine.validate(state, candidate))
                state = engine.apply(state, previous).state
            }
            assertEquals(64, frozen.sha256.length)
        }
    }
}
