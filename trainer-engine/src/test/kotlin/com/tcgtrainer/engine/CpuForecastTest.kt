package com.tcgtrainer.engine

import kotlin.test.*

class CpuForecastTest {
    @Test fun `opponent hypotheses use revealed cards and never actual hidden hands`() {
        val (_, original) = fixture(Effect.CoinMiss)
        val basic = TrainingDecks.all.first().cards.first { TrainerCatalog.get(it).category == Category.POKEMON }
        val opponent = original.players[1].copy(active = PokemonInstance(listOf(c("revealed", basic)), enteredPlayTurn = 0))
        val game = original.copy(players = listOf(original.players[0], opponent))
        val odds = assertNotNull(CpuDrawOdds.opponentCategories(game, TrainerCatalog))
        assertEquals(1.0, odds.values.sum(), .000001)
        val changed = game.copy(players = listOf(game.players[0], opponent.copy(hand = opponent.hand.map { it.copy(definitionId = basic) }, deck = opponent.deck.reversed())))
        assertEquals(odds, CpuDrawOdds.opponentCategories(changed, TrainerCatalog))
    }
    @Test fun `draw odds sample without replacement`() {
        assertEquals(13.0/28, CpuDrawOdds.atLeastOne(8, 2, 2), .000001)
        assertEquals(0.0, CpuDrawOdds.atLeastOne(8, 0, 3))
        assertEquals(1.0, CpuDrawOdds.atLeastOne(8, 2, 8))
        assertEquals(0.0, CpuDrawOdds.atLeastOne(0, 0, 0))
    }

    @Test fun `top deck Energy damage follows known composition instead of a midpoint`() {
        val (engine, original) = fixture(Effect.DiscardTopDeckEnergyDamage(2, EnergyType.FIGHTING, 60))
        val sparse = original.copy(deckLists = listOf(listOf("source") + List(3) { "energy" } + List(9) { "trainer" }, emptyList()))
        val dense = sparse.copy(deckLists = listOf(listOf("source") + List(12) { "energy" }, emptyList()))
        val a = engine.cpuInput(sparse).previews.values.single()
        val b = engine.cpuInput(dense).previews.values.single()
        assertEquals(40, a.damage)
        assertEquals(130, b.damage)
        assertTrue(a.uncertain)
    }

    @Test fun `own unseen pool uses the registered list rather than real deck and Prize identities`() {
        val (_, original) = fixture(Effect.CoinMiss)
        val listed = original.copy(deckLists = listOf(listOf("source") + List(3) { "energy" } + List(9) { "trainer" }, emptyList()))
        val changed = listed.copy(players = listed.players.map { p -> p.copy(deck = p.deck.reversed().map { it.copy(definitionId = "trainer") },
            prizes = p.prizes.map { it.copy(definitionId = "trainer") }) })
        assertEquals(mapOf("energy" to 3, "trainer" to 9), CpuDrawOdds.pool(listed, 0))
        assertEquals(CpuDrawOdds.pool(listed, 0), CpuDrawOdds.pool(changed, 0))
        assertNull(CpuDrawOdds.pool(listed, 1))
    }
    private fun c(id: String, def: String) = CardInstance(id, def)
    private fun fixture(effect: Effect): Pair<RulesEngine, GameState> {
        val source = CardDefinition("source", "Source", "Test", "1", category = Category.POKEMON, hp = 200,
            attacks = listOf(AttackDefinition("hidden", "Hidden dependency", emptyList(), listOf(Effect.Damage(10), effect))))
        val target = CardDefinition("target", "Target", "Test", "2", category = Category.POKEMON, hp = 100)
        val energy = CardDefinition("energy", "Energy", "Test", "3", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
        val trainer = CardDefinition("trainer", "Trainer", "Test", "4", category = Category.TRAINER, trainerType = TrainerType.ITEM,
            trainerEffect = TrainerEffect.DrawCards(1))
        val future = target.copy(id = "future", name = "Iron Probe", canonicalName = "Iron Probe")
        val catalog = object : CardCatalog {
            val entries = listOf(source, target, energy, trainer, future).associateBy { it.id }
            override fun get(id: String) = entries.getValue(id)
        }
        fun player(id: String, definition: String) = PlayerState(deck = List(10) { c("$id-d$it", "energy") },
            prizes = List(2) { c("$id-p$it", "energy") }, active = PokemonInstance(listOf(c(id, definition)), enteredPlayTurn = 0))
        val state = GameState("forecast", 10, RngState(10), listOf(player("cpu", "source"),
            player("opponent", "target").copy(hand = List(2) { c("secret$it", "energy") })), 0, 0, turn = 3, phase = Phase.MAIN)
        return RulesEngine(catalog) to state
    }

    @Test fun `CPU hand dependent damage cannot reveal opponent Energy identities`() {
        val (engine, original) = fixture(Effect.OpponentHandEnergyCountDamage(60))
        val changed = original.copy(players = listOf(original.players[0], original.players[1].copy(
            hand = original.players[1].hand.map { it.copy(definitionId = "trainer") })))
        assertNotEquals(engine.preview(original, "hidden")!!.damage, engine.preview(changed, "hidden")!!.damage)
        assertEquals(engine.cpuInput(original), engine.cpuInput(changed))
        val preview = engine.cpuInput(original).previews.values.single()
        assertTrue(preview.uncertain)
        assertEquals(10, preview.minDamage)
        assertEquals(130, preview.maxDamage)
        assertFalse(preview.knockout)
    }

    @Test fun `CPU hand dependent damage cannot reveal opponent Trainer identities`() {
        val (engine, original) = fixture(Effect.OpponentHandTrainerCountDamage(60))
        val changed = original.copy(players = listOf(original.players[0], original.players[1].copy(
            hand = original.players[1].hand.map { it.copy(definitionId = "trainer") })))
        assertEquals(engine.cpuInput(original), engine.cpuInput(changed))
        assertTrue(engine.cpuInput(original).previews.values.single().uncertain)
    }

    @Test fun `CPU top deck Future damage is a range independent of unrevealed cards`() {
        val (engine, original) = fixture(Effect.DiscardTopDeckFutureDamage(3, 70))
        val changed = original.copy(players = original.players.map { p -> p.copy(deck = p.deck.reversed().map { it.copy(definitionId = "future") }) })
        assertEquals(engine.cpuInput(original), engine.cpuInput(changed))
        val forecast = engine.cpuInput(original).previews.values.single()
        assertTrue(forecast.uncertain)
        assertTrue(forecast.maxDamage > forecast.minDamage)
    }

    @Test fun `a coin miss attack cannot be forecast as a guaranteed win`() {
        val (engine, original) = fixture(Effect.CoinMiss)
        val forecast = engine.cpuInput(original).previews.values.single()
        assertTrue(forecast.uncertain)
        assertEquals(0, forecast.minDamage)
        assertFalse(forecast.knockout)
    }
}
