package com.tcgtrainer.engine

import kotlin.test.*

class CpuCounterplayTest {
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
        assertEquals(1.0, race.turnsToPrizes(game, 0))
        val withoutEx = game.copy(players = listOf(game.players[0], game.players[1].copy(bench = game.players[1].bench.take(1))))
        assertEquals(2.0, race.turnsToPrizes(withoutEx, 0))
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
