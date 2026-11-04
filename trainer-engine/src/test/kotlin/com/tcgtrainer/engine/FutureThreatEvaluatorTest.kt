package com.tcgtrainer.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class FutureThreatEvaluatorTest {
    @Test fun `threat requires the correct energy types rather than just enough cards`() {
        val attacker = CardDefinition("attacker", "Attacker", "Test", "1", category = Category.POKEMON,
            hp = 100, attacks = listOf(AttackDefinition("hit", "Hit", listOf(EnergyType.PSYCHIC, EnergyType.COLORLESS), listOf(Effect.Damage(120)))))
        val fire = CardDefinition("fire", "Fire", "Test", "2", category = Category.ENERGY, type = EnergyType.FIRE, basicEnergy = true)
        val psychic = fire.copy(id = "psychic", type = EnergyType.PSYCHIC)
        val cards = listOf(attacker, fire, psychic).associateBy { it.id }
        val catalog = object : CardCatalog { override fun get(id: String) = cards.getValue(id) }
        fun energy(id: String, type: String) = CardInstance(id, type)
        val pokemon = PokemonInstance(listOf(CardInstance("p", "attacker")), energies = listOf(energy("e1", "fire"), energy("e2", "fire")), enteredPlayTurn = 0)
        val state = GameState("test", 1, RngState(1), listOf(PlayerState(emptyList()), PlayerState(emptyList(), active = pokemon)), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(80.0, FutureThreatEvaluator.evaluatePokemonThreat(state, pokemon, 0, catalog))
        val ready = pokemon.copy(energies = listOf(energy("e1", "psychic"), energy("e2", "fire")))
        val readyState = state.copy(players = listOf(state.players[0], state.players[1].copy(active = ready)))
        assertEquals(90.0, FutureThreatEvaluator.evaluatePokemonThreat(readyState, ready, 0, catalog))
    }
}
