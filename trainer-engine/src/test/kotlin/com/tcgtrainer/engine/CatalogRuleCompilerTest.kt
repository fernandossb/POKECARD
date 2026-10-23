package com.tcgtrainer.engine

import kotlinx.serialization.json.*
import kotlin.test.*

class CatalogRuleCompilerTest {
    private fun attack(effect: String, damage: String = "20") = buildJsonObject {
        put("name", "Teste"); put("effect", effect); put("damage", damage)
        put("cost", buildJsonArray { add("Colorless") })
    }
    @Test fun `unknown clauses and damage formulas reject the whole attack`() {
        assertNull(CatalogRuleCompiler.compileAttack("test", 0, attack("Draw 2 cards. Your opponent is Poisoned.")))
        assertNull(CatalogRuleCompiler.compileAttack("test", 0, attack("", "20+")))
        assertNull(CatalogRuleCompiler.compileAttack("test", 0, attack("Flip 2 coins. This attack does 20 damage for each heads.", "30×")))
        assertNull(CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-006"), emptyMap()))
    }
    @Test fun `healing executes and never creates negative damage`() {
        val move = CatalogRuleCompiler.compileAttack("test", 0, attack("Heal 30 damage from this Pokémon."))!!
        val def = CardDefinition("test", "Test", "T", "1", category = Category.POKEMON, hp = 200, attacks = listOf(move))
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "test") def else TrainerCatalog.get(id) }
        val engine = RulesEngine(catalog)
        fun card(id: String) = CardInstance(id, "sv01-258")
        fun player(id: String, damage: Int) = PlayerState(deck = List(10) { card("$id-d$it") },
            prizes = List(6) { card("$id-p$it") }, active = PokemonInstance(listOf(CardInstance(id, "test")),
                damage = damage, energies = listOf(card("$id-e")), enteredPlayTurn = 0))
        for (damage in listOf(0, 10, 60)) {
            val state = GameState("test", 42, RngState(42), listOf(player("a", damage), player("b", 0)), 0, 0, 3, Phase.MAIN)
            val result = engine.apply(state, GameAction.Attack(move.id))
            assertEquals(Legality.Legal, result.legality)
            assertEquals((damage - 30).coerceAtLeast(0), result.state.players[0].active!!.damage)
            assertEquals(20, result.state.players[1].active!!.damage)
            assertEquals(1, result.state.currentPlayer)
        }
    }
    @Test fun `known effects compile exactly and preserve card instructions`() {
        assertEquals(listOf(Effect.Damage(20), Effect.Draw(2)), CatalogRuleCompiler.compileAttack("t", 0, attack("Draw 2 cards."))!!.effects)
        assertEquals(listOf(Effect.Damage(20), Effect.Recoil(10)), CatalogRuleCompiler.compileAttack("t", 0, attack("This Pokémon also does 10 damage to itself."))!!.effects)
        assertEquals(listOf(Effect.CoinDamage(2, 30)), CatalogRuleCompiler.compileAttack("t", 0, attack("Flip 2 coins. This attack does 30 damage for each heads.", "30×"))!!.effects)
    }
    @Test fun `special energy is never classified as unlimited basic energy`() {
        assertFalse(CardLibrary.byId.getValue("me02-124").basicEnergy)
        assertFalse(CardLibrary.byId.getValue("me02.5-216").basicEnergy)
        assertTrue(CardLibrary.byId.getValue("sv01-258").basicEnergy)
    }
    @Test fun `coverage reports executable prints independently of browsing metadata`() {
        val supported = CardLibrary.entries.filter { it.implemented }
        assertTrue(supported.size > 100)
        assertTrue(supported.all { TrainerCatalog.get(it.id).implemented })
        java.io.File("build/rules-coverage.txt").writeText("Executable prints: ${supported.size}\nStandard executable: ${supported.count { it.standard }}\nCatalogue: ${CardLibrary.entries.size}\n" + supported.joinToString("\n") { "${it.id} ${it.name}" })
    }
}
