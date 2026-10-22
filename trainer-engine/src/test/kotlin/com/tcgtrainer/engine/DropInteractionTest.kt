package com.tcgtrainer.engine

import kotlin.test.*

class DropInteractionTest {
    private val rules = RulesEngine(TrainerCatalog)
    private val drag = DropInteraction(rules)
    private fun c(id: String, definition: String) = CardInstance(id, definition)
    private fun state(card: CardInstance): GameState {
        val own = PlayerState(List(12) { c("deck$it", "sv01-258") }, hand = listOf(card),
            prizes = List(6) { c("prize$it", "sv01-258") }, active = PokemonInstance(listOf(c("active", "me01-076")), enteredPlayTurn = 0),
            bench = listOf(PokemonInstance(listOf(c("bench", "me01-072")), enteredPlayTurn = 0)))
        val other = own.copy(hand = emptyList(), active = PokemonInstance(listOf(c("op-active", "me01-076")), enteredPlayTurn = 0), bench = listOf(PokemonInstance(listOf(c("op-bench", "me01-072")), enteredPlayTurn = 0)))
        return GameState("drag", 42, RngState(42), listOf(own, other), 0, 0, turn = 3, phase = Phase.MAIN, catalogVersion = "lucario-v1")
    }
    private fun accepted(s: GameState, target: BoardTarget) = assertIs<DropDecision.Accepted>(drag.resolve(s, "hand", target))
    @Test fun `energy drop targets exact own instance and does not mutate preview`() {
        val s = state(c("hand", "sv01-258"))
        val decision = accepted(s, BoardTarget.Pokemon(0, "bench"))
        assertEquals(listOf(GameAction.AttachEnergy("hand", "bench")), decision.actions)
        assertEquals(RngState(42), s.rng); assertTrue(s.players[0].bench.single().energies.isEmpty())
        assertIs<DropDecision.Rejected>(drag.resolve(s, "hand", BoardTarget.Pokemon(1, "op-active")))
        assertIs<DropDecision.Rejected>(drag.resolve(s, "hand", BoardTarget.Activation(0)))
    }
    @Test fun `used energy and wrong phase are rejected by same rules engine`() {
        val s = state(c("hand", "sv01-258"))
        val used = s.copy(players = listOf(s.players[0].copy(flags = TurnFlags(manualEnergy = true)), s.players[1]))
        assertIs<DropDecision.Rejected>(drag.resolve(used, "hand", BoardTarget.Pokemon(0, "active")))
        assertIs<DropDecision.Rejected>(drag.resolve(s.copy(phase = Phase.SELECT_PRIZES), "hand", BoardTarget.Pokemon(0, "active")))
    }
    @Test fun `basic drops on bench and empty setup active use ordinary actions`() {
        val s = state(c("hand", "me01-076"))
        assertEquals(listOf(GameAction.PlayBasic("hand")), accepted(s, BoardTarget.Bench(0)).actions)
        val setup = s.copy(turn = 0, phase = Phase.SELECT_ACTIVE, setupPlayer = 0, players = listOf(s.players[0].copy(active = null), s.players[1]))
        assertEquals(listOf(GameAction.ChooseActive("hand")), accepted(setup, BoardTarget.Active(0)).actions)
        assertIs<DropDecision.Rejected>(drag.resolve(s, "hand", BoardTarget.Bench(1)))
    }
    @Test fun `evolution drop validates compatibility and evolution timing`() {
        val s = state(c("hand", "me01-077"))
        assertEquals(listOf(GameAction.Evolve("hand", "active")), accepted(s, BoardTarget.Pokemon(0, "active")).actions)
        assertIs<DropDecision.Rejected>(drag.resolve(s, "hand", BoardTarget.Pokemon(0, "bench")))
        assertIs<DropDecision.Rejected>(drag.resolve(s.copy(turn = 1), "hand", BoardTarget.Pokemon(0, "active")))
    }
    @Test fun `item near pokemon starts effect without guessing payment or search`() {
        val card = c("hand", "me01-131")
        val base = state(card)
        val s = base.copy(players = listOf(base.players[0].copy(hand = listOf(card, c("cost1", "sv01-258"), c("cost2", "sv01-258"))), base.players[1]))
        val d = accepted(s, BoardTarget.Activation(0))
        assertEquals(listOf(GameAction.PlayTrainer("hand")), d.actions)
        assertEquals(ChoiceKind.DISCARD, rules.apply(s, d.actions.single()).state.resolution!!.choices.first().kind)
        assertEquals(3, s.players[0].hand.size)
    }
    @Test fun `tool drop supplies only explicitly chosen legal pokemon target`() {
        val s = state(c("hand", "sv10.5b-079"))
        val d = accepted(s, BoardTarget.Pokemon(0, "bench"))
        assertEquals(listOf(GameAction.PlayTrainer("hand"), GameAction.ChooseCards(listOf("bench"))), d.actions)
        val end = d.actions.fold(s) { n, action -> rules.apply(n, action).also { assertEquals(Legality.Legal, it.legality) }.state }
        assertEquals("hand", end.players[0].bench.single().tool!!.id)
        assertNull(end.players[0].active!!.tool)
    }
    @Test fun `boss drop on opponent bench respects supporter budget`() {
        val s = state(c("hand", "sv02-172"))
        val d = accepted(s, BoardTarget.Pokemon(1, "op-bench"))
        assertEquals(GameAction.ChooseCards(listOf("op-bench")), d.actions.last())
        val used = s.copy(players = listOf(s.players[0].copy(flags = TurnFlags(supporter = true)), s.players[1]))
        assertIs<DropDecision.Rejected>(drag.resolve(used, "hand", BoardTarget.Pokemon(1, "op-bench")))
    }
    @Test fun `invalid source and target reject without altering rng or hand`() {
        val s = state(c("hand", "me01-124"))
        assertIs<DropDecision.Rejected>(drag.resolve(s, "missing", BoardTarget.Activation(0)))
        assertIs<DropDecision.Rejected>(drag.resolve(s, "hand", BoardTarget.Active(99)))
        assertEquals(1, s.players[0].hand.size); assertEquals(0, s.rng.position)
    }
}
