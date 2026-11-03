package com.tcgtrainer.engine

import kotlin.test.*

class CpuLethalDetectorTest {
    private val engine = RulesEngine(TrainerCatalog)
    private fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
    private fun pokemon(id: String, definition: String, damage: Int = 0, energy: Int = 0) =
        PokemonInstance(listOf(card(id, definition)), damage = damage, enteredPlayTurn = 0,
            energies = List(energy) { card("$id-energy-$it") })
    private fun state(opponentPrizes: Int = 6) = GameState("ability-lethal", 12, RngState(12), listOf(
        PlayerState(deck = List(10) { card("op-deck-$it") }, prizes = List(opponentPrizes) { card("op-prize-$it") },
            active = pokemon("op-active", "me01-075"),
            bench = listOf(pokemon("target", "me01-077", damage = TrainerCatalog.get("me01-077").hp-50))),
        PlayerState(deck = List(10) { card("own-deck-$it") }, prizes = List(2) { card("own-prize-$it") },
            active = pokemon("attacker", "me01-077", energy = 2),
            bench = listOf(pokemon("ability-source", "sv06.5-019")))
    ), 0, 1, turn = 4, phase = Phase.MAIN)

    private fun blast(state: GameState): GameState {
        val ability = TrainerCatalog.get("sv06.5-019").abilities.single()
        val used = engine.apply(state, GameAction.UseAbility("ability-source", ability.id))
        assertEquals(Legality.Legal, used.legality)
        val target = engine.apply(used.state, GameAction.ChooseCards(listOf("target")))
        assertEquals(Legality.Legal, target.legality)
        return target.state
    }

    @Test fun `search finds Cursed Blast prize victory instead of a one prize attack`() {
        val game = state()
        val before = GameSession.encode(SessionSnapshot(game))
        val cpu = CpuPlayer()
        assertIs<GameAction.Attack>(cpu.chooseHeuristic(engine.cpuInput(game)))
        val action = cpu.choose(engine.cpuInput(game))
        assertIs<GameAction.UseAbility>(action)
        assertEquals("ability-source", action.pokemonId)
        val after = engine.apply(game, action).state
        assertEquals(GameAction.ChooseCards(listOf("target")), cpu.choose(engine.cpuInput(after)))
        assertTrue(CpuLethalDetector(engine, CpuSearchBudget(null)).provenWin(blast(game), 1))
        assertEquals(before, GameSession.encode(SessionSnapshot(game)))
    }

    @Test fun `simultaneous last prizes are not claimed as a proven win`() {
        val result = blast(state(opponentPrizes = 1))
        assertFalse(CpuLethalDetector(engine, CpuSearchBudget(null)).provenWin(result, 1))
    }
}
