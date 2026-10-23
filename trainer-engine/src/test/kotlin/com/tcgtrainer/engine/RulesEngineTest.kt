package com.tcgtrainer.engine

import kotlin.test.*

class RulesEngineTest {
    private val engine = RulesEngine()
    private fun card(id: String, def: String = "TEST-001") = CardInstance(id, def)
    private fun field(id: String, def: String = "TEST-001", energy: Int = 0) = PokemonInstance(
        listOf(card(id, def)), energies = List(energy) { card("$id-e$it", "TEST-004") }, enteredPlayTurn = 0)
    private fun main(turn: Int = 3): GameState {
        val deck = List(20) { card("deck$it", "TEST-004") }
        return GameState("test", 12, RngState(12), listOf(
            PlayerState(deck, hand = listOf(card("energy", "TEST-004"), card("evolution", "TEST-002")),
                prizes = List(6) { card("prize$it") }, active = field("a", energy = 1), bench = listOf(field("b"))),
            PlayerState(deck.map { it.copy(id = "op-${it.id}") }, prizes = List(6) { card("op-prize$it") }, active = field("o"), bench = listOf(field("ob")))
        ), 0, 0, turn, Phase.MAIN)
    }
    private fun play(s: GameState, a: GameAction): GameState = engine.apply(s, a).also { assertEquals(Legality.Legal, it.legality) }.state

    @Test fun `seed reproduces opening and unique copies`() {
        val a = engine.newGame(123456); val b = engine.newGame(123456)
        assertEquals(a, b); assertNotEquals(a.players[0].hand, engine.newGame(654321).players[0].hand)
        assertEquals(120, a.players.flatMap { it.deck + it.hand }.map { it.id }.toSet().size)
        assertTrue(a.players.all { p -> p.hand.any { engine.definition(it).category == Category.POKEMON && engine.definition(it).stage == Stage.BASIC } })
    }
    @Test fun `mulligans preserve cards and grant net optional draw`() {
        val sparse = List(59) { "TEST-004" } + "TEST-001"
        val s = engine.newGame(22, listOf(sparse, TestCatalog.deck()))
        assertTrue(s.players[0].mulligans > 0)
        assertEquals((s.players[0].mulligans-s.players[1].mulligans).coerceAtLeast(0), s.players[1].bonusDraws)
        assertTrue(s.players.all { it.deck.size + it.hand.size == 60 })
    }
    @Test fun `setup selects field separates prizes and draws turn card`() {
        var s = engine.newGame(123456)
        s = play(s, GameAction.ChooseStartingPlayer(s.startingPlayer))
        for (i in 0..1) {
            val choice = engine.legalActions(s).filterIsInstance<GameAction.ChooseActive>().first()
            s = play(s, choice); s = play(s, GameAction.FinishSetup)
        }
        s = play(s, GameAction.MulliganDraw(0)); s = play(s, GameAction.FinishBonusSetup)
        s = play(s, GameAction.MulliganDraw(0)); s = play(s, GameAction.FinishBonusSetup)
        assertEquals(Phase.MAIN, s.phase); assertEquals(1, s.turn)
        assertEquals(7, s.players[s.startingPlayer].hand.size)
        assertTrue(s.players.all { it.prizes.size == 6 })
        assertIs<Legality.Illegal>(engine.validate(s, GameAction.Attack("strike")))
    }
    @Test fun `manual energy once and invalid action leaves full state untouched`() {
        val s = play(main(), GameAction.AttachEnergy("energy", "a"))
        val rejected = engine.apply(s, GameAction.AttachEnergy("energy", "b"))
        assertIs<Legality.Illegal>(rejected.legality); assertEquals(s, rejected.state)
        assertEquals(2, s.players[0].active!!.energies.size)
    }
    @Test fun `colored cost cannot be paid by wrong type`() {
        val s = main().let { it.copy(players = listOf(it.players[0].copy(active = field("a").copy(energies = listOf(card("grass", "TEST-005")))), it.players[1])) }
        assertIs<Legality.Illegal>(engine.validate(s, GameAction.Attack("strike")))
    }
    @Test fun `colorless cost accepts any basic energy`() {
        val s = main().let { it.copy(players = listOf(it.players[0].copy(active = field("a", "TEST-003", 1)), it.players[1])) }
        assertEquals(Legality.Legal, engine.validate(s, GameAction.Attack("leaf")))
    }
    @Test fun `evolution preserves damage energy and instance identity`() {
        val s = main().let { it.copy(players = listOf(it.players[0].copy(active = it.players[0].active!!.copy(damage = 20)), it.players[1])) }
        val evolved = play(s, GameAction.Evolve("evolution", "a")).players[0].active!!
        assertEquals("a", evolved.id); assertEquals(20, evolved.damage); assertEquals(1, evolved.energies.size)
        assertEquals(2, evolved.cards.size)
        assertIs<Legality.Illegal>(engine.validate(main(2), GameAction.Evolve("evolution", "a")))
        val entered = main().let { it.copy(players = listOf(it.players[0].copy(active = it.players[0].active!!.copy(enteredPlayTurn = 3)), it.players[1])) }
        assertIs<Legality.Illegal>(engine.validate(entered, GameAction.Evolve("evolution", "a")))
    }
    @Test fun `retreat discards chosen energy and limits once per turn`() {
        val s = play(main(), GameAction.Retreat("b", listOf("a-e0")))
        assertEquals("b", s.players[0].active!!.id); assertEquals("a-e0", s.players[0].discard.single().id)
        assertIs<Legality.Illegal>(engine.validate(s, GameAction.Retreat("a", emptyList())))
    }
    @Test fun `weakness preview matches applied damage and ends turn`() {
        val s = main().let { it.copy(players = listOf(it.players[0].copy(active = field("a", "TEST-003", 1)), it.players[1])) }
        assertEquals(40, engine.preview(s, "leaf")!!.damage)
        val after = play(s, GameAction.Attack("leaf"))
        assertEquals(40, after.players[1].active!!.damage); assertEquals(1, after.currentPlayer); assertEquals(4, after.turn)
    }
    @Test fun `knockout waits for chosen prize and opposing promotion`() {
        val s = main().let { it.copy(players = listOf(it.players[0], it.players[1].copy(active = it.players[1].active!!.copy(damage = 40)))) }
        val ko = play(s, GameAction.Attack("strike"))
        assertEquals(Phase.SELECT_PRIZES, ko.phase); assertEquals(1, ko.pendingPrizes)
        assertNull(ko.players[1].active); assertEquals(6, ko.players[0].prizes.size)
        val prize = play(ko, GameAction.TakePrize("prize4"))
        assertTrue(prize.players[0].hand.any { it.id == "prize4" }); assertEquals(Phase.PROMOTE, prize.phase)
        val next = play(prize, GameAction.Promote("ob"))
        assertEquals(4, next.turn); assertEquals("ob", next.players[1].active!!.id)
    }
    @Test fun `last prize and no pokemon are victory conditions`() {
        val s = main().let { it.copy(players = listOf(it.players[0].copy(prizes = listOf(card("last"))), it.players[1].copy(active = it.players[1].active!!.copy(damage = 40)))) }
        val win = play(play(s, GameAction.Attack("strike")), GameAction.TakePrize("last"))
        assertEquals(0, win.winner); assertEquals(Phase.GAME_OVER, win.phase)
        val emptyBench = s.copy(players = listOf(s.players[0], s.players[1].copy(bench = emptyList())))
        assertEquals(0, play(emptyBench, GameAction.Attack("strike")).winner)
    }
    @Test fun `empty deck loses only at mandatory start draw`() {
        val s = main().let { it.copy(players = listOf(it.players[0], it.players[1].copy(deck = emptyList()))) }
        assertEquals(0, play(s, GameAction.EndTurn).winner)
    }
    @Test fun `save undo and replay reproduce full state including rng`() {
        val initial = main()
        val session = GameSession(engine, SessionSnapshot(initial))
        session.dispatch(GameAction.AttachEnergy("energy", "a"))
        val restored = GameSession.decode(GameSession.encode(session.snapshot))
        assertEquals(session.snapshot, restored)
        assertEquals(session.snapshot.state, GameSession.replay(engine, initial, listOf(GameAction.AttachEnergy("energy", "a"))))
        assertTrue(session.undo()); assertEquals(initial, session.snapshot.state)
        val match = GameSession(engine, SessionSnapshot(initial.copy(mode = Mode.MATCH)))
        match.dispatch(GameAction.EndTurn); assertFalse(match.undo())
    }
    @Test fun `rng resumes from saved position`() {
        val rng = SeededRng(RngState(42)); repeat(12) { rng.nextInt(60) }
        val copy = SeededRng(rng.state)
        assertEquals(List(100) { rng.nextInt(7) }, List(100) { copy.nextInt(7) })
    }
    @Test fun `legal actions always validate and hidden observation has counts`() {
        val s = main(); assertTrue(engine.legalActions(s).all { engine.validate(s, it) == Legality.Legal })
        val view = PlayerObservation.from(s, 0); assertEquals(s.players[1].deck.size, view.opponentDeckCount)
    }
    @Test fun `unsupported attack is rejected before any damage`() {
        val base = TestCatalog.get("TEST-001")
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == base.id) base.copy(attacks = listOf(AttackDefinition("unknown", "Não suportado", emptyList(), listOf(Effect.Damage(999), Effect.Unsupported("texto não confirmado"))))) else TestCatalog.get(id)
        }
        val rules = RulesEngine(catalog); val s = main()
        val result = rules.apply(s, GameAction.Attack("unknown"))
        assertIs<Legality.Illegal>(result.legality); assertEquals(s, result.state)
        assertNull(rules.preview(s, "unknown"))
    }
    @Test fun `effective hp and resistance are used by preview and resolution`() {
        val catalog = object : CardCatalog {
            override fun get(id: String) = TestCatalog.get(id).copy(resistance = EnergyType.FIGHTING, resistanceAmount = 10)
        }
        val rules = RulesEngine(catalog)
        val s = main().let { it.copy(players = listOf(it.players[0], it.players[1].copy(active = it.players[1].active!!.copy(hpModifier = 30, damage = 10)))) }
        val preview = rules.preview(s, "strike")!!
        assertEquals(90, preview.effectiveHp); assertEquals(10, preview.damage); assertEquals(70, preview.remainingHp)
        assertEquals(20, rules.apply(s, GameAction.Attack("strike")).state.players[1].active!!.damage)
    }
    @Test fun `mulligan bonus permits only newly drawn basics before reveal`() {
        val s = main().copy(turn = 0, phase = Phase.MULLIGAN_BONUS, setupPlayer = 0).let { it.copy(players = listOf(it.players[0].copy(
            deck = listOf(card("bonus-basic")) + it.players[0].deck,
            hand = listOf(card("old-basic")), bonusDraws = 1), it.players[1])) }
        val drawn = play(s, GameAction.MulliganDraw(1))
        assertEquals(Phase.BONUS_BENCH, drawn.phase)
        assertIs<Legality.Illegal>(engine.validate(drawn, GameAction.PlayBasic("old-basic")))
        assertEquals(Legality.Legal, engine.validate(drawn, GameAction.PlayBasic("bonus-basic")))
    }
    @Test fun `seeded full game conserves all copies and replay matches`() {
        val initial = engine.newGame(123456)
        var s = play(initial, GameAction.ChooseStartingPlayer(0))
        for (i in 0..1) {
            s = play(s, engine.legalActions(s).filterIsInstance<GameAction.ChooseActive>().first())
            s = play(s, GameAction.FinishSetup)
        }
        repeat(2) { s = play(s, GameAction.MulliganDraw(0)); s = play(s, GameAction.FinishBonusSetup) }
        while (s.phase != Phase.GAME_OVER) {
            assertTrue(s.turn < 120)
            val actions = engine.legalActions(s)
            val action = actions.filterIsInstance<GameAction.AttachEnergy>().firstOrNull()
                ?: actions.filterIsInstance<GameAction.Attack>().firstOrNull() ?: GameAction.EndTurn
            s = play(s, action)
            val all = s.players.flatMap { p -> p.deck + p.hand + p.discard + p.prizes + p.pokemon().flatMap { it.cards + it.energies } }
            assertEquals(120, all.size); assertEquals(120, all.map { it.id }.toSet().size)
        }
        assertEquals(s, GameSession.replay(engine, initial, s.history.map { it.action }))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(GameSession.encode(SessionSnapshot(s)).toByteArray()).joinToString("") { "%02x".format(it) }
        // Reviewed baseline: seed 123456, starter 0, first legal opening, no bench,
        // no bonus draws; attach first available energy and attack, otherwise pass.
        // v0.2 adds serialized effect state and named attack/KO events; conservation and replay above remain invariant.
        // v0.4 serializes selected decks and undo checkpoint sizes; game transitions remain unchanged.
        // v0.5 adds deck identity/list metadata and structured event amounts; replay still matches every transition.
        assertEquals("78ad6a84c068f4a3fb9b425904216703309e225e606350cde54ff74525eb45a3", digest)
    }
}
