package com.tcgtrainer.engine

import kotlin.test.*

class AcademyCardsTest {
    private val rules = RulesEngine(TrainerCatalog)
    private fun c(id: String, def: String = "swsh12.5-153") = CardInstance(id, def)
    private fun p(id: String, def: String = "swsh8-43", energy: String = "swsh12.5-153", n: Int = 3) = PokemonInstance(listOf(c(id, def)), energies = List(n) { c("$id-e$it", energy) }, enteredPlayTurn = 0)
    private fun state(hand: List<CardInstance> = emptyList()): GameState {
        fun player(id: String) = PlayerState(deck = List(15) { c("$id-d$it") }, hand = hand, prizes = List(6) { c("$id-p$it") }, active = p(id), bench = listOf(p("$id-b", "swsh8-29", n = 0)))
        return GameState("academy", 42, RngState(42), listOf(player("a"), player("b").copy(hand = emptyList())), 0, 0, 3, Phase.MAIN, catalogVersion = "lucario-v1")
    }
    private fun own(s: GameState, transform: (PlayerState) -> PlayerState) = s.copy(players = listOf(transform(s.players[0]), s.players[1]))
    private fun play(s: GameState, a: GameAction) = rules.apply(s, a).also { assertEquals(Legality.Legal, it.legality, "$a") }.state
    @Test fun `Cinderace discards two Fire energies before ending turn and preserves manual attachment flag`() {
        val start = state()
        val attack = play(start, GameAction.Attack("swsh8-43-a0"))
        assertEquals(Phase.RESOLVE_CHOICE, attack.phase)
        assertEquals(210, attack.players[1].active!!.damage)
        assertEquals(ChoiceKind.DISCARD_ATTACK_ENERGY, attack.resolution!!.choices.first().kind)
        val done = play(attack, GameAction.ChooseCards(listOf("a-e0", "a-e1")))
        assertEquals(1, done.players[0].active!!.energies.size)
        assertEquals(2, done.players[0].discard.size)
        assertEquals(1, done.currentPlayer)
    }
    @Test fun `Pikachu charge searches only Lightning attaches to itself and shuffles without manual energy`() {
        val start = own(state()) { it.copy(active = p("a", "swsh4-43", "swsh12.5-155", 1), deck = listOf(c("light1", "swsh12.5-155"), c("fire"), c("light2", "swsh12.5-155"))) }
        val charge = play(start, GameAction.Attack("swsh4-43-a0"))
        assertEquals(listOf("light1", "light2"), charge.resolution!!.choices.first().candidates)
        assertIs<Legality.Illegal>(rules.validate(charge, GameAction.ChooseCards(listOf("fire"))))
        val done = play(charge, GameAction.ChooseCards(listOf("light1", "light2")))
        assertEquals(3, done.players[0].active!!.energies.size)
        assertFalse(done.players[0].flags.manualEnergy)
    }
    @Test fun `Thunderbolt discards every attached energy after damage`() {
        val start = own(state()) { it.copy(active = p("a", "swsh4-43", "swsh12.5-155")) }
        val done = play(start, GameAction.Attack("swsh4-43-a1"))
        assertEquals(200, done.players[1].active!!.damage)
        assertTrue(done.players[0].active!!.energies.isEmpty())
        assertEquals(3, done.players[0].discard.size)
    }
    @Test fun `coin preview never consumes RNG and attack uses exactly four seeded flips`() {
        val start = own(state()) { it.copy(active = p("a", "swsh3.5-35", "swsh12.5-158", 1)) }
        val before = start.rng
        repeat(3) { val preview = rules.preview(start, "swsh3.5-35-a0")!!; assertTrue(preview.uncertain); assertEquals(0, preview.minDamage); assertEquals(40, preview.maxDamage) }
        assertEquals(before, start.rng)
        val expected = SeededRng(before); val heads = List(4) { expected.nextInt(2) == 0 }.count { it }
        val done = play(start, GameAction.Attack("swsh3.5-35-a0"))
        assertEquals(heads*10, done.players[1].active!!.damage)
        assertEquals(heads*10, done.history.last().events.first { it.type == "AttackDeclared" }.amount)
        assertEquals(expected.state, done.rng)
        assertEquals(done, play(start, GameAction.Attack("swsh3.5-35-a0")))
    }
    @Test fun `Eevee bonus depends on own Supporter flag and Collect draws three without extra turn draw`() {
        val start = own(state()) { it.copy(active = p("a", "swshp-SWSH065"), flags = TurnFlags(supporter = true)) }
        assertEquals(160, rules.preview(start, "swshp-SWSH065-a1")!!.damage)
        assertEquals(80, rules.preview(own(start) { it.copy(flags = TurnFlags()) }, "swshp-SWSH065-a1")!!.damage)
        val done = play(start, GameAction.Attack("swshp-SWSH065-a0"))
        assertEquals(3, done.players[0].hand.size); assertEquals(12, done.players[0].deck.size)
    }
    @Test fun `Great Ball limits visibility and selection to top seven before shuffling`() {
        val start = own(state(listOf(c("ball", "swsh1-164")))) { it.copy(deck = List(7) { c("e$it") } + c("deep", "swsh8-29")) }
        val search = play(start, GameAction.PlayTrainer("ball"))
        assertTrue(search.resolution!!.choices.first().candidates.isEmpty())
        assertIs<Legality.Illegal>(rules.validate(search, GameAction.ChooseCards(listOf("deep"))))
        assertEquals((0..6).map { "e$it" }.toSet(), rules.cpuInput(search).selectableCards.map { it.id }.toSet())
        assertTrue(rules.cpuInput(search).selectableCards.none { it.id == "deep" })
        val done = play(search, GameAction.ChooseCards(emptyList()))
        assertTrue(done.rng.position > start.rng.position)
        assertEquals(1, done.players[0].discard.size)
    }
    @Test fun `Sonia cannot mix Pokemon and energy but permits failed hidden search`() {
        val start = own(state(listOf(c("sonia", "swsh2-167")))) { it.copy(deck = listOf(c("basic", "swsh8-29"), c("energy"), c("stage1", "swsh8-30"))) }
        val choice = play(start, GameAction.PlayTrainer("sonia"))
        assertIs<Legality.Illegal>(rules.validate(choice, GameAction.ChooseCards(listOf("basic", "energy"))))
        assertIs<Legality.Illegal>(rules.validate(choice, GameAction.ChooseCards(listOf("stage1"))))
        assertEquals(Legality.Legal, rules.validate(choice, GameAction.ChooseCards(emptyList())))
        val action = CpuPlayer().choose(rules.cpuInput(choice))
        assertEquals(Legality.Legal, rules.validate(choice, action))
    }
    @Test fun `Potion heals bench but Cook only heals active and both clamp damage at zero`() {
        val start = own(state(listOf(c("potion", "swsh1-177"), c("cook", "swsh8-228")))) { it.copy(active = it.active!!.copy(damage = 20), bench = it.bench.map { p -> p.copy(damage = 40) }) }
        val potion = play(start, GameAction.PlayTrainer("potion"))
        val healed = play(potion, GameAction.ChooseCards(listOf("a-b")))
        assertEquals(10, healed.players[0].bench.single().damage)
        val cook = play(healed, GameAction.PlayTrainer("cook"))
        assertEquals(listOf("a"), cook.resolution!!.choices.first().candidates)
        assertEquals(0, play(cook, GameAction.ChooseCards(listOf("a"))).players[0].active!!.damage)
    }
    @Test fun `energy retrieval and recycling move only selected basic energies to the right zone`() {
        val start = own(state(listOf(c("retrieval", "swsh1-160"), c("recycler", "swsh5-124")))) { it.copy(discard = listOf(c("e1"), c("e2"), c("e3"), c("pokemon", "swsh8-29"))) }
        val recovery = play(start, GameAction.PlayTrainer("retrieval"))
        assertIs<Legality.Illegal>(rules.validate(recovery, GameAction.ChooseCards(listOf("pokemon"))))
        val recovered = play(recovery, GameAction.ChooseCards(listOf("e1", "e2")))
        assertTrue(recovered.players[0].hand.any { it.id == "e1" })
        val recycle = play(recovered, GameAction.PlayTrainer("recycler"))
        val done = play(recycle, GameAction.ChooseCards(listOf("e3")))
        assertTrue(done.players[0].deck.any { it.id == "e3" }); assertTrue(done.players[0].discard.any { it.id == "pokemon" })
    }
    @Test fun `Bug Catcher draws two plus seeded heads bonus and consumes supporter once`() {
        for (seed in 0L..7L) {
            val start = state(listOf(c("bug", "swsh8-226"))).copy(rng = RngState(seed))
            val rng = SeededRng(start.rng); val heads = rng.nextInt(2) == 0
            val done = play(start, GameAction.PlayTrainer("bug"))
            assertEquals(if (heads) 4 else 2, done.players[0].hand.size)
            assertEquals(rng.state, done.rng)
            assertTrue(done.players[0].flags.supporter)
        }
    }
    @Test fun `Pokemon Catcher switches only on seeded heads and never consumes a supporter`() {
        for (seed in 0L..7L) {
            val start = state(listOf(c("catcher", "swsh1-175"))).copy(rng = RngState(seed))
            val rng = SeededRng(start.rng); val heads = rng.nextInt(2) == 0
            val choice = play(start, GameAction.PlayTrainer("catcher"))
            assertEquals(rng.state, choice.rng)
            assertFalse(choice.players[0].flags.supporter)
            if (heads) {
                assertEquals(Phase.RESOLVE_CHOICE, choice.phase)
                val done = play(choice, GameAction.ChooseCards(listOf("b-b")))
                assertEquals("b-b", done.players[1].active!!.id)
            } else { assertEquals(Phase.MAIN, choice.phase); assertEquals("b", choice.players[1].active!!.id) }
        }
    }
}
