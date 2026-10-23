package com.tcgtrainer.engine

import kotlin.test.*

class TrainingListsTest {
    @Test fun `incomplete training preserves card counts and match requires sixty`() {
        val engine = RulesEngine(TrainerCatalog)
        val cards = listOf("me01-076") + List(19) { "sv01-258" }
        val game = engine.newGame(321, listOf(cards, cards), Mode.TRAINING)
        game.players.forEach { assertEquals(20, it.hand.size + it.deck.size) }
        assertFails { engine.newGame(321, listOf(cards, cards), Mode.MATCH) }
        assertFails { engine.newGame(321, listOf(cards.take(13), cards), Mode.TRAINING) }
    }
    @Test fun `different seeds vary openings and same seed remains reproducible`() {
        val engine = RulesEngine(TrainerCatalog)
        val decks = listOf(TrainingDecks.all.first().cards, TrainingDecks.all.first().cards)
        val hands = (1L..12L).map { engine.newGame(it, decks).players[0].hand.map { c -> c.id } }
        assertTrue(hands.distinct().size > 1)
        assertEquals(engine.newGame(42, decks), engine.newGame(42, decks))
    }
}
