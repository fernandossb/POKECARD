package com.tcgtrainer.engine

import kotlin.test.*

class DeckLibraryTest {
    private fun saved(vararg entries: Pair<String, Int>) = SavedDeck("custom-test", "Meu deck", DeckFormat.TRAINING, linkedMapOf(*entries))
    @Test fun `catalogue metadata is searchable but does not enable unimplemented effects`() {
        assertTrue(CardLibrary.entries.size >= 3051)
        assertTrue(CardLibrary.entries.count { it.implemented } > 65)
        val extra = CardLibrary.entries.first { !it.implemented && it.standard }
        val draft = saved(extra.id to 1, "me01-076" to 1, "sv01-258" to 58)
        assertFalse(DeckValidator.validate(draft).playable)
        assertTrue(extra.id in DeckValidator.validate(draft).unsupported)
        assertTrue(CardLibrary.search(extra.id, null, false).any { it.id == extra.id })
        assertTrue(CardLibrary.search("energia luta", null, true).any { it.id == "sv01-258" })
    }
    @Test fun `all presets validate and academy is separate from current Standard`() {
        TrainingDecks.all.forEach { assertTrue(DeckValidator.validate(it).playable, "${it.id}: ${DeckValidator.validate(it)}") }
        Academy2022.decks.forEach { deck ->
            val asStandard = SavedDeck(deck.id, deck.name, DeckFormat.STANDARD, deck.cards.groupingBy { it }.eachCount())
            assertFalse(DeckValidator.validate(asStandard).playable)
            assertTrue(CardLibrary.byId[deck.cover]!!.standard.not())
        }
        assertTrue(CardLibrary.byId["sv01-258"]!!.standard) // Basic Energy is not rotated out.
        assertTrue(CardLibrary.byId["sv02-172"]!!.standard) // Explicitly reviewed equivalent Boss's Orders reprint.
    }
    @Test fun `draft serialization preserves name format card counts and edits`() {
        val draft = saved("me01-076" to 2, "sv01-258" to 8).copy(name = "Meu rascunho ágil", format = DeckFormat.STANDARD, updatedAt = 123)
        assertEquals(draft, DeckCodec.decode(DeckCodec.encode(draft)))
        assertFalse(DeckValidator.validate(draft).playable)
        val ready = draft.copy(counts = mapOf("me01-076" to 2, "sv01-258" to 58))
        assertTrue(DeckValidator.validate(ready).playable)
        assertEquals(60, ready.playableDeck().cards.size)
    }
    @Test fun `saved format-ineligible list can still be trained without owned copies`() {
        val practice = saved("swsh4-43" to 1, "swsh12.5-155" to 13).copy(format = DeckFormat.STANDARD)
        val validation = DeckValidator.validate(practice)
        assertFalse(validation.playable)
        assertTrue(validation.trainingReady)
        val owned = OwnedCollection(emptyMap(), available = true)
        assertFalse(owned.allows(practice.cards()))

        val opponent = TrainingDecks.all.first { it.cards.size == 60 }
        val state = RulesEngine(TrainerCatalog).newGame(99, listOf(practice.cards(), opponent.cards), Mode.TRAINING)
        assertEquals(14, state.players.first().hand.size + state.players.first().deck.size)
        assertEquals(Mode.TRAINING, state.mode)
    }
    @Test fun `copy limits combine equivalent printed names and exempt only basic Energy`() {
        val copies = saved("sv02-172" to 3, "swsh2-154" to 2, "me01-076" to 1, "sv01-258" to 54)
        assertTrue(DeckValidator.validate(copies).errors.any { it.contains("4 cópias") })
        assertTrue(DeckValidator.validate(saved("me01-076" to 1, "sv01-258" to 59)).playable)
        assertTrue(DeckValidator.validate(saved("sv06-163" to 2, "me01-076" to 1, "sv01-258" to 57)).errors.any { it.contains("ACE SPEC") })
    }
    @Test fun `custom saved decks work for either seat and survive match snapshot`() {
        val own = saved("swsh8-29" to 4, "swsh8-30" to 4, "swsh12.5-153" to 52)
        assertTrue(DeckValidator.validate(own).playable)
        val engine = RulesEngine(TrainerCatalog); val cpu = CpuPlayer()
        val state = engine.newGame(123, listOf(own.cards(), Academy2022.decks[0].cards), Mode.MATCH)
            .copy(humanDeckId = own.id, deckLists = listOf(own.cards(), Academy2022.decks[0].cards), deckNames = listOf(own.name, "Academia"), deckFormats = listOf(DeckFormat.TRAINING, DeckFormat.ACADEMY))
        var result = state
        repeat(500) {
            if (result.phase != Phase.GAME_OVER) {
                val transition = engine.apply(result, cpu.choose(engine.cpuInput(result)))
                assertEquals(Legality.Legal, transition.legality); result = transition.state
            }
        }
        assertEquals(Phase.GAME_OVER, result.phase)
        assertEquals(result, GameSession.decode(GameSession.encode(SessionSnapshot(result))).state)
        assertEquals(own.cards(), result.deckLists[0])
    }
    @Test fun `CPU decisions do not depend on real card or deck identifiers`() {
        val original = RulesEngine(TrainerCatalog)
        val initial = original.newGame(7, listOf(Academy2022.decks[0].cards, TrainerCatalog.deck()))
        val renamedCatalog = object : CardCatalog {
            override fun get(id: String): CardDefinition = TrainerCatalog.get(id.removePrefix("anonymous:")).let { d -> d.copy(
                id = "anonymous:${d.id}", name = "Carta anônima", evolvesFrom = d.evolvesFrom?.let { "anonymous:$it" },
                abilities = d.abilities.map { it.copy(requiresInPlay = it.requiresInPlay?.let { id -> "anonymous:$id" }) },
                attacks = d.attacks.map { a -> a.copy(effects = a.effects.map { if (it is Effect.RequiresBenched) it.copy(definitionId = "anonymous:${it.definitionId}") else it }) }) }
        }
        fun rename(card: CardInstance) = card.copy(definitionId = "anonymous:${card.definitionId}")
        var renamed = initial.copy(players = initial.players.map { it.copy(deck = it.deck.map(::rename), hand = it.hand.map(::rename)) })
        var normal = initial
        val renamedRules = RulesEngine(renamedCatalog); val renamedCpu = CpuPlayer(renamedCatalog)
        val cpu = CpuPlayer()
        var steps = 0
        while (normal.phase != Phase.GAME_OVER) {
            assertTrue(steps++ < 1200)
            val action = cpu.choose(original.cpuInput(normal))
            assertEquals(action, renamedCpu.choose(renamedRules.cpuInput(renamed)), "ID-dependent decision at ${normal.phase}, turn ${normal.turn}")
            normal = original.apply(normal, action).also { assertEquals(Legality.Legal, it.legality) }.state
            renamed = renamedRules.apply(renamed, action).also { assertEquals(Legality.Legal, it.legality) }.state
        }
        assertEquals(normal.winner, renamed.winner)
        assertEquals(normal.rng, renamed.rng)
    }
}
