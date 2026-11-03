package com.tcgtrainer.engine

import kotlin.test.*

class CpuCounterplayTest {
    @Test fun `focused preparation generator matches legal energy and evolution actions`() {
        val evolved = hero.copy(id = "evolved", stage = Stage.STAGE_ONE, evolvesFrom = hero.id)
        val catalog = catalog(evolved)
        val engine = RulesEngine(catalog)
        val original = state()
        val game = original.copy(players = listOf(original.players[0].copy(hand = listOf(
            c("energy-a"), c("evolution", "evolved"), c("unknown", "cpu-hidden-card"))), original.players[1]))
        val expected = engine.cpuLegalActions(game).filter { it is GameAction.AttachEnergy || it is GameAction.Evolve }
        assertTrue(expected.any { it is GameAction.Evolve })
        assertTrue(expected.any { it is GameAction.AttachEnergy })
        assertEquals(expected, engine.cpuKnownPreparationActions(game))
        val after = engine.apply(game, expected.first { it is GameAction.AttachEnergy }).state
        assertEquals(engine.cpuLegalActions(after).filter { it is GameAction.AttachEnergy || it is GameAction.Evolve },
            engine.cpuKnownPreparationActions(after))
        assertTrue(engine.cpuKnownPreparationActions(after).none { it is GameAction.AttachEnergy })
    }

    @Test fun `reply preparation diversity preserves distinct energy targets`() {
        val original = state()
        val reserve = PokemonInstance(listOf(c("reserve", "hero")), enteredPlayTurn = 0)
        val game = original.copy(players = listOf(original.players[0].copy(bench = listOf(reserve),
            hand = listOf(c("energy-a"), c("energy-b"))), original.players[1]))
        val engine = RulesEngine(catalog())
        val choices = cpuReplyPreparations(game, engine.cpuLegalActions(game), 8).filterIsInstance<GameAction.AttachEnergy>()
        assertEquals(2, choices.size)
        assertEquals(setOf("hero", "reserve"), choices.map { it.pokemonId }.toSet())
        assertTrue(choices.all { engine.validate(game, it) == Legality.Legal })
    }

    @Test fun `duplicate Trainers do not crowd the winning Energy attachment out of reply search`() {
        val heal = CardDefinition("heal", "Heal", "Test", "10", category = Category.TRAINER,
            trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.HealPokemon(10))
        val attacker = enemy.copy(attacks = listOf(AttackDefinition("reply", "Hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(200)))))
        val catalog = catalog(heal, attacker)
        val engine = RulesEngine(catalog)
        val original = state()
        val game = original.copy(players = listOf(original.players[0], original.players[1].copy(
            active = original.players[1].active!!.copy(damage = 20),
            hand = List(4) { c("heal-$it", "heal") } + c("z-energy"))))
        val input = engine.cpuInput(game, CpuKnowledge.OPPONENT_HAND)
        val config = AiDifficultyConfig(replyDepth = 1, replyBranches = 2, continuationCount = 0)
        assertTrue(CpuCounterplay(engine, catalog, config = config).evaluate(input.position!!.state, 0, null).risk >= 100_000,
            "Two copies of Heal must not hide the known winning energy attachment")
    }

    @Test fun `hand advantage chooses protection against the actual Boss winning response`() {
        val boss = CardDefinition("boss", "Boss", "Test", "10", category = Category.TRAINER,
            trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Switch(opponent = true))
        val wall = hero.copy(hp = 400, attacks = listOf(
            AttackDefinition("greedy", "Damage", emptyList(), listOf(Effect.Damage(250))),
            AttackDefinition("shield", "Protection", emptyList(), listOf(Effect.Damage(20), Effect.PreventDefendingAttackNextTurn))))
        val fragile = hero.copy(id = "fragile", hp = 100)
        val attacker = enemy.copy(attacks = listOf(AttackDefinition("reply", "Hit", emptyList(), listOf(Effect.Damage(100)))))
        val catalog = catalog(boss, wall, fragile, attacker)
        val engine = RulesEngine(catalog)
        val original = state()
        val game = original.copy(players = listOf(original.players[0].copy(bench = listOf(
            PokemonInstance(listOf(c("fragile", "fragile")), enteredPlayTurn = 0))),
            original.players[1].copy(hand = listOf(c("boss", "boss")))))
        val decision = CpuPlayer(catalog).choose(engine.cpuInput(game, CpuKnowledge.OPPONENT_HAND))
        assertEquals(GameAction.Attack("shield"), decision)
        assertEquals(Legality.Legal, engine.validate(game, decision))
    }

    @Test fun `counterplay distinguishes multiple winning attacks without double counting revisited threats`() {
        val first = AttackDefinition("win-a", "First", emptyList(), listOf(Effect.Damage(200)))
        val second = first.copy(id = "win-b", name = "Second")
        val two = catalog(enemy.copy(attacks = listOf(first, second)))
        val one = catalog(enemy.copy(attacks = listOf(first)))
        val config = AiDifficultyConfig(continuationCount = 0)
        val public = CpuTurnSearch.publicPosition(state(), 0)!!.state
        assertEquals(2, CpuCounterplay(RulesEngine(two), two, config = config).evaluate(public, 0, null).winningReplyCount)
        assertEquals(1, CpuCounterplay(RulesEngine(one), one, config = config).evaluate(public, 0, null).winningReplyCount)
        assertFailsWith<IllegalArgumentException> { AiDifficultyConfig(winningReplyPenalty = Double.NaN) }
    }

    @Test fun `hand advantage exposes only the current hand and anticipates Boss lethal`() {
        val boss = CardDefinition("boss", "Boss", "Test", "10", category = Category.TRAINER,
            trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Switch(opponent = true))
        val wall = hero.copy(hp = 400)
        val fragile = hero.copy(id = "fragile", hp = 100)
        val attacker = enemy.copy(attacks = listOf(AttackDefinition("reply", "Hit", emptyList(), listOf(Effect.Damage(100)))))
        val catalog = catalog(boss, wall, fragile, attacker)
        val engine = RulesEngine(catalog)
        val original = state()
        val game = original.copy(players = listOf(original.players[0].copy(bench = listOf(
            PokemonInstance(listOf(c("fragile", "fragile")), enteredPlayTurn = 0))),
            original.players[1].copy(hand = listOf(c("boss", "boss")))))
        val fair = engine.cpuInput(game).position!!.state
        val advantageInput = engine.cpuInput(game, CpuKnowledge.OPPONENT_HAND)
        val advantage = advantageInput.position!!.state
        assertEquals(CpuKnowledge.OPPONENT_HAND, advantageInput.knowledge)
        assertTrue(fair.players[1].hand.all { it.definitionId == "cpu-hidden-card" })
        assertEquals(game.players[1].hand, advantage.players[1].hand)
        assertTrue(advantage.players.all { p -> (p.deck+p.prizes).all { it.definitionId == "cpu-hidden-card" } })
        assertEquals(RngState(0), advantage.rng)
        assertTrue(CpuCounterplay(engine, catalog).evaluate(fair, 0, null).risk < 100_000)
        assertTrue(CpuCounterplay(engine, catalog).evaluate(advantage, 0, null).risk >= 100_000,
            "Boss in the actual hand must unlock the winning bench target response")
        assertEquals("boss", game.players[1].hand.single().definitionId)
        assertEquals(0, game.players[0].flags.let { if (it.supporter) 1 else 0 })
    }

    @Test fun `counterplay follows promotion and unknown turn draw after our knockout`() {
        val hitter = hero.copy(attacks = listOf(AttackDefinition("hit", "Hit", emptyList(), listOf(Effect.Damage(120)))))
        val weak = enemy.copy(hp = 100)
        val finisher = enemy.copy(id = "finisher", attacks = listOf(AttackDefinition("finish", "Finish", emptyList(), listOf(Effect.Damage(200)))))
        val catalog = catalog(hitter, weak, finisher)
        val engine = RulesEngine(catalog)
        val original = state()
        val game = original.copy(players = listOf(original.players[0], original.players[1].copy(
            bench = listOf(PokemonInstance(listOf(c("reserve", "finisher")), enteredPlayTurn = 0)))))
        val public = CpuTurnSearch.publicPosition(game, 0)!!.state
        assertTrue(CpuCounterplay(engine, catalog).evaluate(public, 0, GameAction.Attack("hit")).risk >= 100_000,
            "Promoting the ready reserve after a KO must expose its winning reply")
    }

    @Test fun `following turn planning includes a known Boss and its target choice`() {
        val hitter = hero.copy(attacks = listOf(AttackDefinition("hit", "Hit", emptyList(), listOf(Effect.Damage(120)))))
        val target = enemy.copy(id = "target", hp = 100, prizes = 2)
        val boss = CardDefinition("boss", "Boss", "Test", "10", category = Category.TRAINER,
            trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Switch(opponent = true))
        val catalog = catalog(hitter, target, boss)
        val engine = RulesEngine(catalog)
        val original = state()
        val game = original.copy(players = listOf(original.players[0].copy(prizes = List(2) { c("p$it") }),
            original.players[1].copy(bench = listOf(PokemonInstance(listOf(c("target", "target")), enteredPlayTurn = 0)))))
        val withBoss = game.copy(players = listOf(game.players[0].copy(hand = listOf(c("boss", "boss"))), game.players[1]))
        val search = CpuCounterplay(engine, catalog)
        val value = search.nextTurnValue(CpuTurnSearch.publicPosition(withBoss, 0)!!.state, 0)
        val without = search.nextTurnValue(CpuTurnSearch.publicPosition(game, 0)!!.state, 0)
        assertTrue(value > without+1500, "Must reach Boss -> target -> winning attack, not just value the card in hand")
    }

    @Test fun `healthy final prize bench target is exposed only to a powered visible attacker`() {
        val attacker = enemy.copy(attacks = listOf(AttackDefinition("reply", "Hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(200)))))
        val catalog = catalog(attacker)
        val engine = RulesEngine(catalog)
        val safety = CpuBenchSafety(engine, catalog)
        val original = state()
        val target = PokemonInstance(listOf(c("reserve", "hero")), enteredPlayTurn = 0)
        val game = original.copy(players = listOf(original.players[0].copy(bench = listOf(target)),
            original.players[1].copy(active = original.players[1].active!!.copy(energies = listOf(c("attached"))))))
        assertTrue(safety.exposesFinalPrizes(game, 0, target))
        assertFalse(safety.exposesFinalPrizes(original, 0, target))
        val early = game.copy(players = listOf(game.players[0], game.players[1].copy(prizes = List(6) { c("op$it") })))
        assertFalse(safety.exposesFinalPrizes(early, 0, target))
        assertTrue(CpuPositionEvaluator(engine, catalog).features(game, 0).exposure > 0.0)
        val hiddenChanged = game.copy(players = listOf(game.players[0], game.players[1].copy(hand = listOf(c("secret", "hero")))))
        assertEquals(safety.exposesFinalPrizes(game, 0, target), safety.exposesFinalPrizes(hiddenChanged, 0, target))
    }

    @Test fun `cpu declines unnecessary healthy final prize target but keeps a needed reserve`() {
        val wall = hero.copy(hp = 400, attacks = emptyList())
        val fragile = hero.copy(id = "fragile", hp = 100, attacks = emptyList())
        val attacker = enemy.copy(attacks = listOf(AttackDefinition("reply", "Hit", emptyList(), listOf(Effect.Damage(100)))))
        val catalog = catalog(wall, fragile, attacker)
        val engine = RulesEngine(catalog)
        val original = state()
        val reserve = PokemonInstance(listOf(c("reserve", "hero")), enteredPlayTurn = 0)
        val game = original.copy(players = listOf(original.players[0].copy(hand = listOf(c("fragile", "fragile")), bench = listOf(reserve)), original.players[1]))
        assertEquals(GameAction.EndTurn, CpuPlayer(catalog, CpuStrategy.HEURISTIC).choose(engine.cpuInput(game)))
        assertEquals(GameAction.EndTurn, CpuPlayer(catalog).choose(engine.cpuInput(game)))
        val alone = game.copy(players = listOf(game.players[0].copy(bench = emptyList()), game.players[1]))
        assertEquals(GameAction.PlayBasic("fragile"), CpuPlayer(catalog, CpuStrategy.HEURISTIC).choose(engine.cpuInput(alone)))
    }

    @Test fun `phase weights prioritize setup early and danger when either player can finish`() {
        val base = CpuBoardWeights()
        fun position(a: Int, b: Int) = state().let { game -> game.copy(players = game.players.mapIndexed { index, p ->
            p.copy(prizes = List(if (index == 0) a else b) { c("phase-$index-$it") })
        }) }
        assertTrue(base.forPosition(position(6, 6)).engines > base.engines)
        assertEquals(base, base.forPosition(position(4, 4)))
        val end = base.forPosition(position(6, 2))
        assertTrue(end.exposure > base.exposure)
        assertTrue(end.attackers > base.attackers)
        assertTrue(end.prizeAdvantage > base.prizeAdvantage)
        assertEquals(end, base.forPosition(position(2, 6)))
        assertEquals(0.0, base.copy(exposure = 0.0).forPosition(position(2, 2)).exposure)
        assertEquals(10000.0, base.copy(exposure = 10000.0).forPosition(position(2, 2)).exposure)
    }

    @Test fun `position recovery counts useful healing only when it is playable`() {
        val heal = CardDefinition("heal", "Heal", "Test", "9", category = Category.TRAINER, trainerType = TrainerType.ITEM,
            trainerEffect = TrainerEffect.HealPokemon(60))
        val catalog = catalog(heal)
        val evaluator = CpuPositionEvaluator(RulesEngine(catalog), catalog)
        val game = state().let { it.copy(players = listOf(it.players[0].copy(hand = listOf(c("healing", "heal"))), it.players[1])) }
        assertTrue(evaluator.features(game, 0).recovery > 0)
        val blocked = game.copy(players = listOf(game.players[0].copy(itemPlayLockedUntilTurn = game.turn), game.players[1]))
        assertEquals(0.0, evaluator.features(blocked, 0).recovery)
        assertTrue(evaluator.value(game, 0) > evaluator.value(blocked, 0))
        val healthy = game.copy(players = listOf(game.players[0].copy(active = game.players[0].active!!.copy(damage = 0)), game.players[1]))
        assertEquals(0.0, evaluator.features(healthy, 0).recovery)
    }

    @Test fun `position rewards a ready reserve rather than a restricted reserve`() {
        val catalog = catalog()
        val evaluator = CpuPositionEvaluator(RulesEngine(catalog), catalog)
        val reserve = PokemonInstance(listOf(c("reserve", "hero")), enteredPlayTurn = 0)
        val game = state().let { it.copy(players = listOf(it.players[0].copy(bench = listOf(reserve)), it.players[1])) }
        val restricted = reserve.copy(restrictions = listOf(AttackRestriction("hit", game.turn)))
        val worse = game.copy(players = listOf(game.players[0].copy(bench = listOf(restricted)), game.players[1]))
        assertTrue(evaluator.features(game, 0).attackers > evaluator.features(worse, 0).attackers)
    }

    @Test fun `position penalizes an injured multi prize reserve`() {
        val catalog = catalog()
        val evaluator = CpuPositionEvaluator(RulesEngine(catalog), catalog)
        val reserve = PokemonInstance(listOf(c("reserve", "hero")), enteredPlayTurn = 0)
        val game = state().let { it.copy(players = listOf(it.players[0].copy(bench = listOf(reserve)), it.players[1])) }
        val exposed = game.copy(players = listOf(game.players[0].copy(bench = listOf(reserve.copy(damage = 170))), game.players[1]))
        assertTrue(evaluator.features(exposed, 0).exposure > evaluator.features(game, 0).exposure)
    }
    @Test fun `following turn planning uses a known Energy to prepare an attack`() {
        val attacker = hero.copy(attacks = listOf(AttackDefinition("prepare", "Prepared hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(200)))))
        val catalog = catalog(attacker)
        val engine = RulesEngine(catalog)
        val plain = state()
        val prepared = plain.copy(players = listOf(plain.players[0].copy(hand = listOf(c("known-energy"))), plain.players[1]))
        val search = CpuCounterplay(engine, catalog)
        assertTrue(search.nextTurnValue(CpuTurnSearch.publicPosition(prepared, 0)!!.state, 0) >
            search.nextTurnValue(CpuTurnSearch.publicPosition(plain, 0)!!.state, 0))
    }
    private val hero = CardDefinition("hero", "Hero ex", "Test", "1", category = Category.POKEMON, hp = 200, prizes = 2,
        attacks = listOf(AttackDefinition("hit", "Hit", emptyList(), listOf(Effect.Damage(20)))))
    private val enemy = CardDefinition("enemy", "Enemy", "Test", "2", category = Category.POKEMON, hp = 300,
        attacks = listOf(AttackDefinition("reply", "Reply", emptyList(), listOf(Effect.Damage(60)))))
    private val energy = CardDefinition("energy", "Energy", "Test", "3", category = Category.ENERGY, basicEnergy = true)
    private val unknown = energy.copy(id = "cpu-hidden-card", basicEnergy = false)
    private fun catalog(vararg overrides: CardDefinition) = object : CardCatalog {
        val entries = (listOf(hero, enemy, energy, unknown) + overrides).associateBy { it.id }
        override fun get(id: String) = entries.getValue(id)
    }
    private fun c(id: String, definition: String = "energy") = CardInstance(id, definition)
    private fun state() = GameState("counterplay", 42, RngState(42), listOf(
        PlayerState(deck = List(10) { c("d$it") }, prizes = List(4) { c("p$it") },
            active = PokemonInstance(listOf(c("hero", "hero")), damage = 50, enteredPlayTurn = 0)),
        PlayerState(deck = List(10) { c("od$it") }, prizes = List(2) { c("op$it") },
            active = PokemonInstance(listOf(c("enemy", "enemy")), enteredPlayTurn = 0))
    ), 0, 0, turn = 3, phase = Phase.MAIN)

    @Test fun `counterplay finds a public ability that unlocks the opponents winning reply`() {
        val boosted = enemy.copy(abilities = listOf(AbilityDefinition("boost", "Boost", "", selfDamageCountersForAttackBoost = 1,
            attackDamageBoostAfterSelfDamage = 100)))
        val catalog = catalog(boosted)
        val engine = RulesEngine(catalog)
        val public = CpuTurnSearch.publicPosition(state(), 0)!!.state
        val risk = CpuCounterplay(engine, catalog).evaluate(public, 0, null).risk
        assertTrue(risk >= 100_000, "Expected a winning ability + attack reply, got $risk")
        val plainCatalog = catalog()
        assertTrue(CpuCounterplay(RulesEngine(plainCatalog), plainCatalog).evaluate(public, 0, null).risk < 100_000)
    }

    @Test fun `counterplay respects the defensive effect of the candidate attack`() {
        val defensive = hero.copy(attacks = listOf(hero.attacks.single().copy(effects = listOf(Effect.Damage(20), Effect.PreventDefendingAttackNextTurn))))
        val catalog = catalog(defensive)
        val engine = RulesEngine(catalog)
        val public = CpuTurnSearch.publicPosition(state(), 0)!!.state
        val counterplay = CpuCounterplay(engine, catalog)
        assertTrue(counterplay.evaluate(public, 0, null).risk > 0)
        assertEquals(0.0, counterplay.evaluate(public, 0, GameAction.Attack("hit")).risk)
    }

    @Test fun `losing the last Pokemon is a forced loss even with many opposing Prizes left`() {
        val attacker = enemy.copy(attacks = listOf(AttackDefinition("reply", "Knockout", emptyList(), listOf(Effect.Damage(200)))))
        val catalog = catalog(attacker)
        val engine = RulesEngine(catalog)
        val game = state().let { it.copy(players = listOf(it.players[0], it.players[1].copy(prizes = List(6) { n -> c("op$n") }))) }
        val counterplay = CpuCounterplay(engine, catalog)
        assertTrue(counterplay.evaluate(CpuTurnSearch.publicPosition(game, 0)!!.state, 0, null).risk >= 100_000)
        val reserve = PokemonInstance(listOf(c("reserve", "hero")), enteredPlayTurn = 0)
        val withBench = game.copy(players = listOf(game.players[0].copy(bench = listOf(reserve)), game.players[1]))
        assertTrue(counterplay.evaluate(CpuTurnSearch.publicPosition(withBench, 0)!!.state, 0, null).risk < 100_000)
    }

    @Test fun `master prevents an ability boosted winning reply instead of taking more immediate damage`() {
        val choices = hero.copy(attacks = listOf(
            AttackDefinition("greedy", "Greedy hit", emptyList(), listOf(Effect.Damage(250))),
            AttackDefinition("shield", "Defensive hit", emptyList(), listOf(Effect.Damage(20), Effect.PreventDefendingAttackNextTurn))))
        val boosted = enemy.copy(abilities = listOf(AbilityDefinition("boost", "Boost", "", selfDamageCountersForAttackBoost = 1,
            attackDamageBoostAfterSelfDamage = 100)))
        val catalog = catalog(choices, boosted)
        val engine = RulesEngine(catalog)
        val game = state()
        val input = engine.cpuInput(game)
        assertEquals(GameAction.Attack("greedy"), CpuPlayer(catalog, CpuStrategy.TACTICAL).choose(input))
        val action = CpuPlayer(catalog).choose(input)
        assertEquals(GameAction.Attack("shield"), action)
        assertEquals(Legality.Legal, engine.validate(game, action))
    }

    @Test fun `counterplay resolves discarded attack Energy before evaluating energy scaling retaliation`() {
        val discarder = hero.copy(attacks = listOf(hero.attacks.single().copy(effects = listOf(Effect.Damage(20), Effect.DiscardAttackEnergy(0, all = true)))))
        val scaling = enemy.copy(attacks = listOf(AttackDefinition("reply", "Energy punishment", emptyList(),
            listOf(Effect.Damage(10), Effect.OpponentActiveEnergyCountDamage(60)))))
        val catalog = catalog(discarder, scaling)
        val engine = RulesEngine(catalog)
        val initial = state()
        val charged = initial.players[0].active!!.copy(energies = List(3) { n -> c("attached$n") })
        val game = initial.copy(players = listOf(initial.players[0].copy(active = charged), initial.players[1]))
        val public = CpuTurnSearch.publicPosition(game, 0)!!.state
        val counterplay = CpuCounterplay(engine, catalog)
        assertTrue(counterplay.evaluate(public, 0, null).risk >= 100_000)
        assertTrue(counterplay.evaluate(public, 0, GameAction.Attack("hit")).risk < 100)
    }

    @Test fun `prize route chooses a two prize knockout rather than two single prize targets`() {
        val attacker = hero.copy(attacks = listOf(AttackDefinition("hit", "Hit", emptyList(), listOf(Effect.Damage(120)))))
        val one = enemy.copy(hp = 100, prizes = 1)
        val two = enemy.copy(id = "two", hp = 100, prizes = 2)
        val catalog = catalog(attacker, one, two)
        val engine = RulesEngine(catalog)
        val game = state().let { it.copy(players = listOf(it.players[0].copy(prizes = List(2) { n -> c("p$n") }),
            it.players[1].copy(bench = listOf(PokemonInstance(listOf(c("extra", "enemy")), enteredPlayTurn = 0), PokemonInstance(listOf(c("two", "two")), enteredPlayTurn = 0))))) }
        val race = CpuPrizeRace(engine, catalog)
        assertEquals(2.0, race.turnsToPrizes(game, 0))
        val withoutEx = game.copy(players = listOf(game.players[0], game.players[1].copy(bench = game.players[1].bench.take(1))))
        assertEquals(3.0, race.turnsToPrizes(withoutEx, 0))
    }

    @Test fun `prize route charges access and does not reuse one gust for two targets`() {
        val attacker = hero.copy(attacks = listOf(AttackDefinition("hit", "Hit", emptyList(), listOf(Effect.Damage(120)))))
        val boss = CardDefinition("boss", "Boss", "Test", "10", category = Category.TRAINER,
            trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Switch(opponent = true))
        val two = enemy.copy(id = "two", hp = 100, prizes = 2)
        val catalog = catalog(attacker, boss, two)
        val race = CpuPrizeRace(RulesEngine(catalog), catalog)
        val original = state()
        val game = original.copy(players = listOf(original.players[0].copy(hand = listOf(c("boss1", "boss"))),
            original.players[1].copy(bench = listOf("a", "b").map { PokemonInstance(listOf(c(it, "two")), enteredPlayTurn = 0) })))
        assertEquals(3.15, race.turnsToPrizes(game, 0), 0.0001)
        val noGust = game.copy(players = listOf(game.players[0].copy(hand = emptyList()), game.players[1]))
        assertEquals(4.0, race.turnsToPrizes(noGust, 0))
        val usedSupporter = game.copy(players = listOf(game.players[0].copy(flags = TurnFlags(supporter = true)), game.players[1]))
        assertEquals(4.0, race.turnsToPrizes(usedSupporter, 0))
        val locked = game.copy(players = listOf(game.players[0].copy(supporterPlayLockedUntilTurn = game.turn), game.players[1]))
        assertEquals(4.0, race.turnsToPrizes(locked, 0))
    }

    @Test fun `restricted gust does not grant access to evolution targets`() {
        val attacker = hero.copy(attacks = listOf(AttackDefinition("hit", "Hit", emptyList(), listOf(Effect.Damage(120)))))
        val restricted = CardDefinition("gust", "Restricted", "Test", "11", category = Category.TRAINER,
            trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Switch(opponent = true, opponentBasicOnly = true))
        val two = enemy.copy(id = "two", hp = 100, prizes = 2, stage = Stage.STAGE_ONE)
        val catalog = catalog(attacker, restricted, two)
        val original = state()
        val game = original.copy(players = listOf(original.players[0].copy(prizes = List(2) { c("p$it") }, hand = listOf(c("gust", "gust"))),
            original.players[1].copy(bench = listOf(PokemonInstance(listOf(c("target", "two")), enteredPlayTurn = 0)))))
        assertEquals(2.0, CpuPrizeRace(RulesEngine(catalog), catalog).turnsToPrizes(game, 0))
    }

    @Test fun `counterplay and prize race are invariant to private cards and actual random stream`() {
        val catalog = catalog()
        val engine = RulesEngine(catalog)
        val original = state()
        val changed = original.copy(seed = 800, rng = RngState(1000), players = original.players.map { p ->
            p.copy(deck = p.deck.reversed().map { it.copy(definitionId = "hero") }, prizes = p.prizes.map { it.copy(definitionId = "enemy") }) })
        val counterplay = CpuCounterplay(engine, catalog)
        assertEquals(counterplay.evaluate(CpuTurnSearch.publicPosition(original, 0)!!.state, 0, GameAction.Attack("hit")),
            counterplay.evaluate(CpuTurnSearch.publicPosition(changed, 0)!!.state, 0, GameAction.Attack("hit")))
    }
}
