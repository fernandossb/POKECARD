package com.tcgtrainer.engine

import kotlin.test.*

class TrainerCardsTest {
    private val engine = RulesEngine(TrainerCatalog)
    private fun c(id: String, definition: String) = CardInstance(id, definition)
    private fun energy(id: String) = c(id, "sv01-258")
    private fun poke(id: String, definition: String = "me01-077", energies: Int = 2, damage: Int = 0) = PokemonInstance(
        listOf(c(id, definition)), damage, List(energies) { energy("$id-e$it") }, enteredPlayTurn = 0)
    private fun state(hand: List<CardInstance> = emptyList()): GameState {
        fun player(prefix: String) = PlayerState(
            deck = List(20) { energy("$prefix-deck$it") }, hand = hand,
            prizes = List(6) { energy("$prefix-prize$it") }, active = poke(prefix), bench = listOf(poke("$prefix-bench", "me01-076", 0)))
        return GameState("cards", 12, RngState(12), listOf(player("a"), player("o").copy(hand = emptyList())), 0, 0, 3, Phase.MAIN, catalogVersion = "lucario-v1")
    }
    private fun play(s: GameState, a: GameAction): GameState = engine.apply(s, a).also { assertEquals(Legality.Legal, it.legality, "$a") }.state
    private fun trainer(s: GameState, id: String) = play(s, GameAction.PlayTrainer(id))
    private fun choose(s: GameState, vararg ids: String) = play(s, GameAction.ChooseCards(ids.toList()))
    private fun withPlayer(s: GameState, index: Int = 0, transform: (PlayerState) -> PlayerState) = s.copy(players = s.players.mapIndexed { i, p -> if (i == index) transform(p) else p })
    private fun conserved(s: GameState): Set<String> {
        val all = s.players.flatMap { it.deck + it.hand + it.discard + it.prizes + it.pokemon().flatMap { p -> p.cards + p.energies + listOfNotNull(p.tool) } } + listOfNotNull(s.stadium) + listOfNotNull(s.resolution?.toolCard?.takeIf { card -> s.players.none { p -> p.pokemon().any { it.tool?.id == card.id } } })
        assertEquals(all.size, all.map { it.id }.toSet().size)
        return all.map { it.id }.toSet()
    }

    @Test fun `catalog contains 60 real cards 21 prints and no unsupported attack`() {
        assertEquals(60, TrainerCatalog.deck().size); assertEquals(21, TrainerCatalog.deck().distinct().size)
        assertTrue(TrainerCatalog.definitions.size > 65)
        assertTrue(TrainerCatalog.definitions.values.all { it.verified && it.artAsset != null && it.source.contains("tcgdex") })
        assertTrue(TrainerCatalog.definitions.values.flatMap { it.attacks }.all { engine.effects.supported(it) })
        assertEquals(3, TrainerCatalog.get("me01-077").prizes)
        assertEquals(2, TrainerCatalog.get("sv06-141").prizes)
        assertEquals(340, TrainerCatalog.get("me01-077").hp)
    }
    @Test fun `aura jab chooses up to three fighting energies from discard to bench`() {
        val s = withPlayer(state()) { it.copy(discard = listOf(energy("d1"), energy("d2"), energy("d3"), energy("d4"), c("non-energy", "me01-076"))) }
        val before = conserved(s)
        var n = play(s, GameAction.Attack("me01-077-a0"))
        assertEquals(130, n.players[1].active!!.damage); assertEquals(Phase.RESOLVE_CHOICE, n.phase)
        assertEquals(setOf("d1", "d2", "d3", "d4"), n.resolution!!.choices.first().candidates.toSet())
        n = choose(n, "d2"); assertEquals(listOf("a-bench"), n.resolution!!.choices.first().candidates)
        n = choose(n, "a-bench"); n = choose(n, "d3"); n = choose(n, "a-bench"); n = choose(n, "d4"); n = choose(n, "a-bench")
        assertEquals(4, n.turn); assertEquals(3, n.players[0].bench.single().energies.size)
        assertFalse(n.players[0].flags.manualEnergy)
        assertEquals(before, conserved(n))
    }
    @Test fun `aura jab may attach none and does not take from hand or deck`() {
        val s = withPlayer(state(listOf(energy("hand-energy")))) { it.copy(discard = listOf(energy("discard-energy"))) }
        val n = choose(play(s, GameAction.Attack("me01-077-a0")))
        assertEquals(4, n.turn); assertEquals(0, n.players[0].bench.single().energies.size)
        assertTrue(n.players[0].hand.any { it.id == "hand-energy" })
    }
    @Test fun `mega brave restriction is instance scoped and expires after next own turn`() {
        val s = state(); var n = play(s, GameAction.Attack("me01-077-a1")); n = play(n, GameAction.EndTurn)
        assertEquals(5, n.turn)
        assertIs<Legality.Illegal>(engine.validate(n, GameAction.Attack("me01-077-a1")))
        assertEquals(Legality.Legal, engine.validate(n, GameAction.Attack("me01-077-a0")))
        val other = withPlayer(n) { it.copy(active = poke("different")) }
        assertEquals(Legality.Legal, engine.validate(other, GameAction.Attack("me01-077-a1")))
        n = play(n, GameAction.EndTurn); n = play(n, GameAction.EndTurn)
        assertEquals(Legality.Legal, engine.validate(n, GameAction.Attack("me01-077-a1")))
    }
    @Test fun `switch clears effects of previous attack without consuming retreat`() {
        val s = withPlayer(state(listOf(c("switch", "me01-130")))) { it.copy(active = poke("a").copy(restrictions = listOf(AttackRestriction("me01-077-a1", 3)))) }
        val n = choose(trainer(s, "switch"), "a-bench")
        assertTrue(n.players[0].bench.first { it.id == "a" }.restrictions.isEmpty())
        assertFalse(n.players[0].flags.retreat)
    }
    @Test fun `ultra ball pays two other chosen cards then offers pokemon only and shuffles`() {
        val s = withPlayer(state(listOf(c("ultra", "me01-131"), energy("cost1"), energy("cost2")))) { it.copy(deck = it.deck + c("target", "me01-076") + c("item", "me01-116")) }
        val before = conserved(s)
        var n = trainer(s, "ultra")
        assertIs<Legality.Illegal>(engine.validate(n, GameAction.ChooseCards(listOf("cost1"))))
        n = choose(n, "cost1", "cost2")
        assertEquals(listOf("target"), n.resolution!!.choices.first().candidates)
        n = choose(n, "target")
        assertEquals(Phase.MAIN, n.phase); assertTrue(n.players[0].hand.any { it.id == "target" })
        assertTrue(n.rng.position > s.rng.position); assertEquals(3, n.players[0].discard.size)
        assertEquals(before, conserved(n))
    }
    @Test fun `ultra ball requires two cards and may fail hidden search`() {
        val insufficient = state(listOf(c("ultra", "me01-131"), energy("only")))
        assertIs<Legality.Illegal>(engine.validate(insufficient, GameAction.PlayTrainer("ultra")))
        val s = state(listOf(c("ultra", "me01-131"), energy("one"), energy("two")))
        val n = choose(choose(trainer(s, "ultra"), "one", "two"))
        assertEquals(Phase.MAIN, n.phase); assertTrue(n.rng.position > 0)
    }
    @Test fun `boss orders selects opponent bench and shares supporter limit`() {
        val s = state(listOf(c("boss", "sv02-172"), c("lillie", "me01-119")))
        val n = choose(trainer(s, "boss"), "o-bench")
        assertEquals("o-bench", n.players[1].active!!.id); assertTrue(n.players[0].flags.supporter)
        assertIs<Legality.Illegal>(engine.validate(n, GameAction.PlayTrainer("lillie")))
        assertIs<Legality.Illegal>(engine.validate(s.copy(turn = 1), GameAction.PlayTrainer("boss")))
    }
    @Test fun `lillie draws eight at six prizes otherwise six and returns other hand cards`() {
        val s = state(listOf(c("lillie", "me01-119"), energy("old")))
        val n = trainer(s, "lillie"); assertEquals(8, n.players[0].hand.size); assertEquals(1, n.players[0].discard.size)
        val fewer = withPlayer(s) { it.copy(prizes = it.prizes.drop(1)) }
        assertEquals(6, trainer(fewer, "lillie").players[0].hand.size)
        assertEquals(conserved(s), conserved(n))
    }
    @Test fun `iris chooses discard cost then draws up to six`() {
        val s = state(listOf(c("iris", "sv09-149"), energy("cost")))
        val n = choose(trainer(s, "iris"), "cost")
        assertEquals(6, n.players[0].hand.size); assertEquals(2, n.players[0].discard.size)
        assertTrue(n.players[0].flags.supporter)
    }
    @Test fun `surfer switches then draws up to five`() {
        val s = state(listOf(c("surfer", "sv08-187")))
        val n = choose(trainer(s, "surfer"), "a-bench")
        assertEquals(5, n.players[0].hand.size); assertEquals("a-bench", n.players[0].active!!.id)
    }
    @Test fun `fighting gong filters for basic fighting pokemon or basic fighting energy`() {
        val s = withPlayer(state(listOf(c("gong", "me01-116")))) { it.copy(deck = listOf(c("riolu", "me01-076"), c("mega", "me01-077"), c("fez", "sv06.5-038"), energy("energy"))) }
        val n = trainer(s, "gong")
        assertEquals(setOf("riolu", "energy"), n.resolution!!.choices.first().candidates.toSet())
    }
    @Test fun `night stretcher recovers pokemon or energy only`() {
        val s = withPlayer(state(listOf(c("stretcher", "sv06.5-061")))) { it.copy(discard = listOf(c("poke", "me01-077"), energy("energy"), c("trainer", "me01-131"))) }
        val pending = trainer(s, "stretcher"); assertEquals(setOf("poke", "energy"), pending.resolution!!.choices.first().candidates.toSet())
        val n = choose(pending, "energy"); assertTrue(n.players[0].hand.any { it.id == "energy" })
    }
    @Test fun `premium power stacks before weakness and expires on own next turn`() {
        val s = state(listOf(c("p1", "me01-124"), c("p2", "me01-124")))
        var n = trainer(trainer(s, "p1"), "p2")
        assertEquals(190, engine.preview(n, "me01-077-a0")!!.damage)
        n = play(n, GameAction.EndTurn); n = play(n, GameAction.EndTurn)
        assertEquals(130, engine.preview(n, "me01-077-a0")!!.damage)
    }
    @Test fun `air balloon reduces retreat cost and is discarded on knockout`() {
        val s = state(listOf(c("balloon", "sv10.5b-079")))
        var n = choose(trainer(s, "balloon"), "a")
        assertEquals(0, engine.retreatCost(n.players[0].active!!))
        n = play(n, GameAction.Retreat("a-bench", emptyList()))
        assertEquals("balloon", n.players[0].bench.first { it.id == "a" }.tool!!.id)
        assertEquals(conserved(s), conserved(n))
    }
    @Test fun `gravity mountain modifies only stage two hp and is global with owner discard`() {
        val stageTwo = TrainerCatalog.get("me01-077").copy(stage = Stage.STAGE_TWO)
        val custom = RulesEngine(object : CardCatalog { override fun get(id: String) = if (id == "stage2") stageTwo else TrainerCatalog.get(id) })
        val s = withPlayer(state(listOf(c("gravity", "sv08-177")))) { it.copy(active = poke("a", "stage2")) }
        val n = custom.apply(s, GameAction.PlayTrainer("gravity")).state
        assertEquals(310, custom.effects.effectiveHp(n.players[0].active!!, n))
        assertEquals(340, custom.effects.effectiveHp(n.players[1].active!!, n))
        assertEquals("gravity", n.stadium!!.id)
        val same = withPlayer(n) { it.copy(hand = listOf(c("same", "sv08-177"))) }
        assertIs<Legality.Illegal>(custom.validate(same, GameAction.PlayTrainer("same")))
    }
    @Test fun `lunar cycle requires solrock fighting discard and shares once per turn`() {
        val s = withPlayer(state(listOf(energy("cost")))) { it.copy(bench = listOf(poke("lun1", "me01-074", 0), poke("lun2", "me01-074", 0), poke("sol", "me01-075", 0))) }
        val n = choose(play(s, GameAction.UseAbility("lun1", "me01-074-ability0")), "cost")
        assertEquals(3, n.players[0].hand.size)
        assertIs<Legality.Illegal>(engine.validate(n, GameAction.UseAbility("lun2", "me01-074-ability0")))
        val withoutSol = withPlayer(s) { it.copy(bench = it.bench.filterNot { p -> p.id == "sol" }) }
        assertIs<Legality.Illegal>(engine.validate(withoutSol, GameAction.UseAbility("lun1", "me01-074-ability0")))
    }
    @Test fun `solrock requires benched lunatone and ignores weakness resistance`() {
        var s = withPlayer(state()) { it.copy(active = poke("sol", "me01-075", 1)) }
        assertEquals(0, engine.preview(s, "me01-075-a0")!!.damage)
        s = withPlayer(s) { it.copy(bench = listOf(poke("lun", "me01-074", 0))) }
        s = withPlayer(s, 1) { it.copy(active = poke("ursa", "sv06-141", 0)) }
        assertEquals(70, engine.preview(s, "me01-075-a0")!!.damage)
    }
    @Test fun `hariyama evolution optionally gusts and wild press needs three fighting`() {
        val s = withPlayer(state(listOf(c("hari", "me01-073")))) { it.copy(active = poke("maku", "me01-072", 2)) }
        val n = choose(play(s, GameAction.Evolve("hari", "maku")), "o-bench")
        assertEquals("o-bench", n.players[1].active!!.id)
        assertIs<Legality.Illegal>(engine.validate(n, GameAction.Attack("me01-073-a0")))
        val ready = withPlayer(n) { it.copy(active = it.active!!.copy(energies = it.active.energies + energy("third"))) }
        val hit = play(ready, GameAction.Attack("me01-073-a0"))
        assertEquals(70, hit.players[0].active!!.damage)
    }
    @Test fun `wild press simultaneous knockouts award both sides then promote next player first`() {
        var s = withPlayer(state()) { it.copy(active = poke("hari", "me01-073", 3, damage = 80)) }
        s = withPlayer(s, 1) { it.copy(active = poke("o", damage = 130)) }
        var n = play(s, GameAction.Attack("me01-073-a0"))
        assertEquals(3, n.pendingPrizes); assertNull(n.players[0].active); assertNull(n.players[1].active)
        repeat(3) { n = choosePrize(n) }
        assertEquals(1, n.currentPlayer); assertEquals(1, n.pendingPrizes)
        n = choosePrize(n); assertEquals(Phase.PROMOTE, n.phase); assertEquals(1, n.currentPlayer)
        n = play(n, GameAction.Promote("o-bench")); assertEquals(0, n.currentPlayer)
        n = play(n, GameAction.Promote("a-bench")); assertEquals(1, n.currentPlayer); assertEquals(4, n.turn)
    }
    private fun choosePrize(s: GameState) = play(s, GameAction.TakePrize(s.players[s.currentPlayer].prizes.first().id))
    @Test fun `fezandipiti targets bench without weakness and flip script needs previous ko`() {
        val s = withPlayer(state()) { it.copy(active = poke("fez", "sv06.5-038", 3), lastKnockedOutTurn = 2) }
        assertEquals(Legality.Legal, engine.validate(s, GameAction.UseAbility("fez", "sv06.5-038-ability0")))
        assertEquals(100, engine.preview(s, "sv06.5-038-a0", "o-bench")!!.damage)
        assertEquals(Legality.Legal, engine.validate(s, GameAction.Attack("sv06.5-038-a0", "o-bench")))
        val n = play(s, GameAction.Attack("sv06.5-038-a0", "o-bench"))
        assertNotNull(n.players[1].active); assertTrue(n.players[1].bench.isEmpty()); assertEquals(1, n.pendingPrizes)
    }
    @Test fun `blood moon discount uses only prizes already taken by opponent`() {
        var s = withPlayer(state()) { it.copy(active = poke("ursa", "sv06-141", 2)) }
        assertIs<Legality.Illegal>(engine.validate(s, GameAction.Attack("sv06-141-a0")))
        s = withPlayer(s, 1) { it.copy(prizes = it.prizes.take(3)) }
        assertEquals(Legality.Legal, engine.validate(s, GameAction.Attack("sv06-141-a0")))
        var n = play(s, GameAction.Attack("sv06-141-a0")); n = play(n, GameAction.EndTurn)
        assertIs<Legality.Illegal>(engine.validate(n, GameAction.Attack("sv06-141-a0")))
    }
    @Test fun `secret box pays three and searches distinct trainer categories`() {
        val s = withPlayer(state(listOf(c("secret", "sv06-163"), energy("c1"), energy("c2"), energy("c3")))) { it.copy(deck = listOf(c("item", "me01-131"), c("tool", "sv10.5b-079"), c("supporter", "sv02-172"), c("stadium", "sv08-177"), energy("extra"))) }
        var n = choose(trainer(s, "secret"), "c1", "c2", "c3")
        for (id in listOf("item", "tool", "supporter", "stadium")) { assertEquals(listOf(id), n.resolution!!.choices.first().candidates); n = choose(n, id) }
        assertEquals(4, n.players[0].hand.size); assertEquals(Phase.MAIN, n.phase); assertTrue(n.rng.position == 0L) // one-card shuffle consumes no randomness
        assertEquals(conserved(s), conserved(n))
    }
    @Test fun `pending effect save reload undo restores costs rng and hidden zones`() {
        val s = state(listOf(c("ultra", "me01-131"), energy("one"), energy("two")))
        val session = GameSession(engine, SessionSnapshot(s))
        session.dispatch(GameAction.PlayTrainer("ultra")); session.dispatch(GameAction.ChooseCards(listOf("one", "two")))
        val restored = GameSession(engine, GameSession.decode(GameSession.encode(session.snapshot)))
        restored.dispatch(GameAction.ChooseCards(emptyList()))
        assertTrue(restored.undo()); assertEquals(session.snapshot.state, restored.snapshot.state)
        assertEquals(conserved(s), conserved(restored.snapshot.state))
    }
    @Test fun `seeded real deck games preserve copies and replay through all choices`() {
        for (seed in listOf(1L, 2L, 3L, 42L, 77L, 128L, 123456L, 839174625L)) {
            val initial = engine.newGame(seed, listOf(TrainerCatalog.deck(), TrainerCatalog.deck()))
            val allIds = conserved(initial)
            var n = initial; var actions = 0
            while (n.phase != Phase.GAME_OVER) {
                assertTrue(actions++ < 1000, "Partida travada na seed $seed")
                val legal = engine.legalActions(n)
                val action = when (n.phase) {
                    Phase.CHOOSE_STARTER -> GameAction.ChooseStartingPlayer(0)
                    Phase.SELECT_ACTIVE -> legal.filterIsInstance<GameAction.ChooseActive>().first()
                    Phase.SELECT_BENCH -> legal.filterIsInstance<GameAction.PlayBasic>().firstOrNull() ?: GameAction.FinishSetup
                    Phase.MULLIGAN_BONUS -> GameAction.MulliganDraw(0)
                    Phase.BONUS_BENCH -> GameAction.FinishBonusSetup
                    Phase.RESOLVE_CHOICE -> legal.first()
                    Phase.SELECT_PRIZES -> legal.filterIsInstance<GameAction.TakePrize>().first()
                    Phase.PROMOTE -> legal.filterIsInstance<GameAction.Promote>().first()
                    Phase.MAIN -> legal.filterIsInstance<GameAction.AttachEnergy>().firstOrNull()
                        ?: legal.filterIsInstance<GameAction.Evolve>().firstOrNull()
                        ?: legal.filterIsInstance<GameAction.PlayBasic>().firstOrNull()
                        ?: legal.filterIsInstance<GameAction.Attack>().firstOrNull() ?: GameAction.EndTurn
                    Phase.GAME_OVER -> error("Já terminou")
                }
                n = play(n, action)
                assertEquals(allIds, conserved(n), "Cartas perdidas na seed $seed")
            }
            assertEquals(n, GameSession.replay(engine, initial, n.history.map { it.action }))
            assertEquals(n, GameSession.decode(GameSession.encode(SessionSnapshot(n))).state)
        }
    }
    @Test fun `gravity hp knockout awards prizes and resumes main phase without extra draw`() {
        val stageTwo = TrainerCatalog.get("me01-077").copy(stage = Stage.STAGE_TWO)
        val custom = RulesEngine(object : CardCatalog { override fun get(id: String) = if (id == "stage2") stageTwo else TrainerCatalog.get(id) })
        var s = withPlayer(state(listOf(c("gravity", "sv08-177")))) { it.copy(active = poke("a", "stage2", damage = 320), flags = TurnFlags(manualEnergy = true)) }
        s = custom.apply(s, GameAction.PlayTrainer("gravity")).state
        assertNull(s.players[0].active); assertEquals(1, s.currentPlayer); assertEquals(3, s.pendingPrizes)
        repeat(3) { s = custom.apply(s, GameAction.TakePrize(s.players[1].prizes.first().id)).state }
        assertEquals(Phase.PROMOTE, s.phase)
        val beforeDeck = s.players[0].deck
        s = custom.apply(s, GameAction.Promote("a-bench")).state
        assertEquals(Phase.MAIN, s.phase); assertEquals(0, s.currentPlayer); assertEquals(3, s.turn)
        assertEquals(beforeDeck, s.players[0].deck); assertTrue(s.players[0].flags.manualEnergy)
    }
    @Test fun `knockout discards tool as well as full pokemon stack and energies`() {
        val s = withPlayer(state(), 1) { it.copy(active = poke("o", "me01-076", 1).copy(tool = c("tool", "sv10.5b-079"))) }
        val n = play(s, GameAction.Attack("me01-077-a0"))
        assertEquals(setOf("o", "o-e0", "tool"), n.players[1].discard.map { it.id }.toSet())
        assertEquals(conserved(s), conserved(n))
    }
    @Test fun `simultaneous last prizes require tiebreaker and do not award premature win`() {
        var s = withPlayer(state()) { it.copy(active = poke("hari", "me01-073", 3, damage = 80), prizes = it.prizes.take(1)) }
        s = withPlayer(s, 1) { it.copy(active = poke("o", damage = 130), prizes = it.prizes.take(1)) }
        var n = choosePrize(play(s, GameAction.Attack("me01-073-a0")))
        assertEquals(Phase.SELECT_PRIZES, n.phase); assertNull(n.winner)
        n = choosePrize(n)
        assertEquals(Phase.GAME_OVER, n.phase); assertNull(n.winner)
        assertTrue(n.winReason!!.contains("desempate"))
    }
}
