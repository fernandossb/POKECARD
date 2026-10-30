package com.tcgtrainer.engine

import kotlin.test.*

class CpuForecastTest {
    private fun c(id: String, def: String) = CardInstance(id, def)
    private fun fixture(effect: Effect): Pair<RulesEngine, GameState> {
        val source = CardDefinition("source", "Source", "Test", "1", category = Category.POKEMON, hp = 200,
            attacks = listOf(AttackDefinition("hidden", "Hidden dependency", emptyList(), listOf(Effect.Damage(10), effect))))
        val target = CardDefinition("target", "Target", "Test", "2", category = Category.POKEMON, hp = 100)
        val energy = CardDefinition("energy", "Energy", "Test", "3", category = Category.ENERGY, basicEnergy = true)
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
