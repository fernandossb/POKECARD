package com.tcgtrainer.engine

import kotlin.test.*

class MasterCpuTest {
    @Test fun `reusing a search item plan does not inspect masked deck cards`() {
        val poffin = CardDefinition("poffin", "Poffin", "Test", "6", category = Category.TRAINER,
            trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.BuddyBuddyPoffin)
        val basic = small.copy(id = "basic", hp = 60)
        val catalog = catalog(poffin, basic)
        val engine = RulesEngine(catalog)
        val original = state()
        val game = original.copy(players = listOf(original.players[0], original.players[1].copy(
            deck = listOf(c("target", "basic")), hand = listOf(c("poffin-card", "poffin")),
            active = pokemon("cpu", "hero"))))
        val action = GameAction.PlayTrainer("poffin-card")
        for (strategy in listOf(CpuStrategy.MASTER, CpuStrategy.ORACLE)) {
            val knowledge = CpuKnowledge.forStrategy(strategy)
            val input = engine.cpuInput(game, knowledge)
            assertTrue(action in input.actions)
            val cpu = CpuPlayer(catalog, strategy)
            val field = CpuPlayer::class.java.getDeclaredField("currentTurnPlan")
            field.isAccessible = true
            field.set(cpu, CpuTurnPlan(CpuTurnObjective.FIND_ATTACKER, listOf(action, GameAction.ChooseCards(listOf("target"))), initialTurn = game.turn, actor = 1))
            assertEquals(action, cpu.choose(input))
            assertEquals(Legality.Legal, engine.validate(game, action))
            val after = engine.apply(game, action).state
            val choice = cpu.choose(engine.cpuInput(after, knowledge))
            assertEquals(Legality.Legal, engine.validate(after, choice))
        }
    }
    @Test fun `a reused expired search never returns a decision from an earlier position`() {
        val catalog = catalog()
        val engine = RulesEngine(catalog)
        var clock = 0L
        val search = CpuTurnSearch(catalog, budget = CpuSearchBudget(800) { clock })
        val original = state()
        val input = engine.cpuInput(original)
        val heuristic = CpuPlayer(catalog, CpuStrategy.HEURISTIC)
        val first = search.choose(input, heuristic.choose(input))
        clock = 1_000_000_000
        val changed = original.copy(players = listOf(original.players[0], original.players[1].copy(hand = emptyList())))
        val nextInput = engine.cpuInput(changed)
        val fallback = heuristic.choose(nextInput)
        assertNotEquals(first, fallback)
        val action = search.choose(nextInput, fallback)
        assertEquals(fallback, action)
        assertEquals(Legality.Legal, engine.validate(changed, action))
    }
    @Test fun `adaptive policy reserves more time for critical positions`() {
        val input = RulesEngine(catalog()).cpuInput(state())
        assertEquals(2000L, CpuSearchBudget.limitMillis(input))
        val normal = input.copy(observation = input.observation.copy(ownPrizeCount = 6, opponentPrizeCount = 6))
        assertEquals(800L, CpuSearchBudget.limitMillis(normal.copy(actions = List(3) { GameAction.EndTurn })))
        assertEquals(200L, CpuSearchBudget.limitMillis(normal.copy(actions = listOf(GameAction.EndTurn))))
    }
    @Test fun `master search ranks a decisive gust ahead of a crowded hand of low value trainers`() {
        val lowValueTrainers = (0 until 30).map { index ->
            CardDefinition("low-$index", "Low value $index", "Test", "$index", category = Category.TRAINER,
                trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.HpModifier(Stage.BASIC, 20))
        }
        val catalog = catalog(*lowValueTrainers.toTypedArray())
        val game = state().let { current -> current.copy(players = current.players.mapIndexed { index, player ->
            if (index != current.currentPlayer) player else player.copy(hand = player.hand + lowValueTrainers.mapIndexed { n, card ->
                c("low-card-$n", card.id)
            })
        }) }
        val actions = game.players[game.currentPlayer].hand.map { GameAction.PlayTrainer(it.id) }
        val groups = CpuTurnSearch(catalog).prioritizedPreparationGroups(game, actions)
        val ordered = groups.flatten()
        assertEquals(GameAction.PlayTrainer("boss-card"), ordered.first())
        assertTrue(GameAction.PlayTrainer("boss-card") in ordered.take(24))
    }
    @Test fun `expired and interrupted searches return a legal fallback without leaking cancellation`() {
        val catalog = catalog()
        val engine = RulesEngine(catalog)
        val game = state()
        val input = engine.cpuInput(game)
        val fallback = CpuPlayer(catalog, CpuStrategy.HEURISTIC).choose(input)
        val expired = CpuTurnSearch(catalog, budget = CpuSearchBudget(0) { 0 }).choose(input, fallback)
        assertEquals(fallback, expired)
        assertEquals(Legality.Legal, engine.validate(game, expired))
        Thread.currentThread().interrupt()
        try {
            assertEquals(fallback, CpuTurnSearch(catalog).choose(input, fallback))
        } finally { Thread.interrupted() }
    }
    @Test fun `deadline during exploration retains a legal completed decision`() {
        val catalog = catalog()
        val engine = RulesEngine(catalog)
        val input = engine.cpuInput(state())
        val fallback = CpuPlayer(catalog, CpuStrategy.HEURISTIC).choose(input)
        var clock = 0L
        val budget = CpuSearchBudget(800) { clock.also { clock += 25_000_000 } }
        val action = CpuTurnSearch(catalog, budget = budget).choose(input, fallback)
        assertEquals(Legality.Legal, engine.validate(state(), action))
        assertTrue(clock < 5_000_000_000)
    }
    @Test fun `all CPU profiles can evaluate legal attacks from previous Evolutions`() {
        val basic = hero.copy(attacks = listOf(AttackDefinition("old", "Previous attack", emptyList(), listOf(Effect.Damage(100)))))
        val evolved = hero.copy(id = "evolved", name = "Evolved hero", stage = Stage.STAGE_ONE,
            attacks = listOf(AttackDefinition("new", "Printed attack", emptyList(), listOf(Effect.Damage(10)))))
        val memory = small.copy(id = "memory", abilities = listOf(AbilityDefinition("memory", "Memory", "", allowsPreviousEvolutionAttacks = true)))
        val catalog = catalog(basic, evolved, memory, ex.copy(hp = 400))
        val engine = RulesEngine(catalog)
        val game = state().let { it.copy(players = listOf(
            it.players[0].copy(active = pokemon("human", "ex"), bench = emptyList()),
            it.players[1].copy(hand = emptyList(), active = pokemon("cpu", "hero").copy(cards = listOf(c("cpu", "hero"), c("evolution", "evolved"))),
                bench = listOf(pokemon("memory-source", "memory"))))) }
        for (profile in CpuStrategy.entries) {
            val action = CpuPlayer(catalog, profile).choose(engine.cpuInput(game))
            assertEquals(GameAction.Attack("old"), action, profile.name)
            assertEquals(Legality.Legal, engine.validate(game, action))
        }
    }
    @Test fun `CPU bounds retreat payment enumeration while keeping legal options`() {
        val source = hero.copy(retreatCost = 2)
        val engine = RulesEngine(catalog(source))
        val game = state().let { it.copy(players = listOf(it.players[0], it.players[1].copy(
            active = pokemon("cpu", "hero", 18), bench = List(5) { n -> pokemon("bench$n", "small") }))) }
        val actions = engine.cpuInput(game).actions
        assertTrue(actions.filterIsInstance<GameAction.Retreat>().size in 5..10)
        assertTrue(actions.all { engine.validate(game, it) == Legality.Legal })
    }
    @Test fun `master completes a real Lucario match without illegal moves`() {
        val engine = RulesEngine(TrainerCatalog)
        val cpu = CpuPlayer(TrainerCatalog)
        val deck = TrainingDecks.all.first { it.id == "lucario" }.cards
        var game = engine.newGame(42, listOf(deck, deck), Mode.MATCH)
        var steps = 0
        val started = System.nanoTime()
        while (game.phase != Phase.GAME_OVER) {
            assertTrue(steps++ < 800, "Stalled real match at turn ${game.turn}")
            val input = engine.cpuInput(game)
            val action = if (engine.actingPlayer(game) == 1) cpu.choose(input) else cpu.chooseHeuristic(input)
            val result = engine.apply(game, action)
            assertEquals(Legality.Legal, result.legality, "turn=${game.turn} $action")
            game = result.state
        }
        println("REAL_MASTER_MATCH winner=${game.winner} steps=$steps turns=${game.turn} elapsedMs=${(System.nanoTime()-started)/1_000_000}")
    }
    @Test fun `master and heuristic finish seeded matches with legal actions and conserved cards`() {
        val engine = RulesEngine(TestCatalog)
        val cpu = CpuPlayer(TestCatalog)
        val started = System.nanoTime()
        var wins = 0
        var decisions = 0
        for (seed in listOf(7L, 42L)) for (master in 0..1) {
            var game = engine.newGame(seed, mode = Mode.MATCH)
            var moves = 0
            while (game.phase != Phase.GAME_OVER) {
                assertTrue(moves++ < 1000, "Stalled seed=$seed seat=$master turn=${game.turn}")
                val input = engine.cpuInput(game)
                val action = if (engine.actingPlayer(game) == master) cpu.choose(input) else cpu.chooseHeuristic(input)
                val result = engine.apply(game, action)
                assertEquals(Legality.Legal, result.legality, "seed=$seed seat=$master $action")
                game = result.state
                decisions++
                val cards = game.players.flatMap { p -> p.deck + p.hand + p.discard + p.prizes + p.pokemon().flatMap { it.cards + it.energies + it.tools } }
                assertEquals(120, cards.size)
                assertEquals(120, cards.map { it.id }.distinct().size)
            }
            if (game.winner == master) wins++
        }
        println("MASTER_BENCHMARK games=4 wins=$wins decisions=$decisions elapsedMs=${(System.nanoTime()-started)/1_000_000}")
    }
    private fun c(id: String, def: String = "energy") = CardInstance(id, def)
    private val energy = CardDefinition("energy", "Energy", "Test", "1", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
    private val hero = CardDefinition("hero", "Hero", "Test", "2", category = Category.POKEMON, hp = 200,
        attacks = listOf(AttackDefinition("hit", "Hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(120)))))
    private val small = CardDefinition("small", "Small", "Test", "3", category = Category.POKEMON, hp = 100)
    private val ex = CardDefinition("ex", "Prize target ex", "Test", "4", category = Category.POKEMON, hp = 100, prizes = 2)
    private val boss = CardDefinition("boss", "Boss", "Test", "5", category = Category.TRAINER, trainerType = TrainerType.SUPPORTER,
        trainerEffect = TrainerEffect.Switch(opponent = true))
    private fun catalog(vararg cards: CardDefinition) = object : CardCatalog {
        val entries = (listOf(hero, small, ex, energy, boss) + cards).associateBy { it.id }
        override fun get(id: String) = entries[id] ?: error("Unknown $id")
    }
    private fun pokemon(id: String, definition: String, energies: Int = 0) = PokemonInstance(listOf(c(id, definition)),
        energies = List(energies) { c("$id-e$it") }, enteredPlayTurn = 0)
    private fun state() = GameState("master", 49, RngState(49), listOf(
        PlayerState(deck = List(12) { c("human-deck$it") }, prizes = List(6) { c("human-prize$it") },
            hand = listOf(c("secret")), active = pokemon("human", "small"), bench = listOf(pokemon("prize", "ex"))),
        PlayerState(deck = List(12) { c("cpu-deck$it") }, prizes = List(2) { c("cpu-prize$it") },
            hand = listOf(c("boss-card", "boss")), active = pokemon("cpu", "hero", 1))
    ), 0, 1, turn = 4, phase = Phase.MAIN)

    @Test fun `master finds a gust and winning knockout instead of immediately taking one prize`() {
        val catalog = catalog()
        val engine = RulesEngine(catalog)
        val cpu = CpuPlayer(catalog)
        var game = state()
        val input = engine.cpuInput(game)
        assertEquals(GameAction.PlayTrainer("boss-card"), cpu.choose(input))
        repeat(8) {
            if (game.phase == Phase.GAME_OVER) return@repeat
            val action = cpu.choose(engine.cpuInput(game))
            val result = engine.apply(game, action)
            assertEquals(Legality.Legal, result.legality)
            game = result.state
        }
        assertEquals(Phase.GAME_OVER, game.phase)
        assertEquals(1, game.winner)
    }

    @Test fun `master combines manual Energy and Counter Gain to unlock a winning attack`() {
        val tool = CardDefinition("tool", "Counter Gain", "Test", "6", category = Category.TRAINER,
            trainerType = TrainerType.TOOL, trainerEffect = TrainerEffect.CounterGain)
        val costly = hero.copy(attacks = hero.attacks.map { it.copy(cost = List(2) { EnergyType.COLORLESS }) })
        val catalog = catalog(costly, tool)
        val engine = RulesEngine(catalog)
        val cpu = CpuPlayer(catalog)
        var game = state().let { it.copy(players = listOf(
            it.players[0].copy(active = pokemon("human", "ex"), bench = emptyList(), prizes = listOf(c("op-prize"))),
            it.players[1].copy(active = pokemon("cpu", "hero"), hand = listOf(c("e"), c("tool-card", "tool"))))) }
        val seen = mutableListOf<GameAction>()
        repeat(12) {
            if (game.phase == Phase.GAME_OVER) return@repeat
            val action = cpu.choose(engine.cpuInput(game))
            seen += action
            val result = engine.apply(game, action)
            assertEquals(Legality.Legal, result.legality)
            game = result.state
        }
        assertEquals(1, game.winner, seen.toString())
        assertTrue(seen.any { it is GameAction.AttachEnergy })
        assertTrue(seen.any { it is GameAction.PlayTrainer })
    }

    @Test fun `master finds ability plus gust victory missed by the old heuristic`() {
        val ability = AbilityDefinition("boost", "Prepare knockout", "", selfDamageCountersForAttackBoost = 2,
            attackDamageBoostAfterSelfDamage = 100)
        val catalog = catalog(hero.copy(abilities = listOf(ability)), ex.copy(hp = 200))
        val engine = RulesEngine(catalog)
        val cpu = CpuPlayer(catalog)
        var game = state()
        assertIs<GameAction.Attack>(cpu.chooseHeuristic(engine.cpuInput(game)))
        val seen = mutableListOf<GameAction>()
        repeat(10) {
            if (game.phase == Phase.GAME_OVER) return@repeat
            val action = cpu.choose(engine.cpuInput(game))
            seen += action
            val result = engine.apply(game, action)
            assertEquals(Legality.Legal, result.legality)
            game = result.state
        }
        assertEquals(1, game.winner, seen.toString())
        assertTrue(seen.any { it is GameAction.UseAbility })
        assertTrue(seen.any { it is GameAction.PlayTrainer })
        assertTrue(seen.first() !is GameAction.Attack)
    }

    @Test fun `public search never receives hidden identities deck order seed or RNG`() {
        val engine = RulesEngine(catalog())
        val original = state()
        val changed = original.copy(seed = 999, rng = RngState(888), players = original.players.mapIndexed { index, player -> player.copy(
            deck = player.deck.reversed().map { it.copy(definitionId = "hero") },
            prizes = player.prizes.map { it.copy(definitionId = "ex") },
            hand = if (index == 0) player.hand.map { it.copy(definitionId = "boss") } else player.hand) })
        assertEquals(engine.cpuInput(original), engine.cpuInput(changed))
        assertEquals(CpuPlayer(catalog()).choose(engine.cpuInput(original)), CpuPlayer(catalog()).choose(engine.cpuInput(changed)))
        val public = engine.cpuInput(original).position!!.state
        assertEquals(0L, public.seed)
        assertTrue(public.players.flatMap { it.deck + it.prizes }.all { it.definitionId == "cpu-hidden-card" })
        assertEquals("cpu-hidden-card", public.players[0].hand.single().definitionId)
    }

    @Test fun `opponent hand is unavailable at every difficulty and input contains no private seed`() {
        val engine = RulesEngine(catalog())
        val game = state()
        val originalSnapshot = GameSession.encode(SessionSnapshot(game))
        val changed = game.copy(players = game.players.mapIndexed { i, p ->
            if (i == 0) p.copy(hand = p.hand.map { it.copy(definitionId = "boss") }) else p
        })
        for (difficulty in AiDifficulty.entries) {
            val cpu = CpuPlayer(catalog(), config = AiDifficultyConfig.forDifficulty(difficulty))
            assertEquals(cpu.choose(engine.cpuInput(game)), cpu.choose(engine.cpuInput(changed)), difficulty.name)
        }
        assertEquals(originalSnapshot, GameSession.encode(SessionSnapshot(game)))
        assertTrue(engine.cpuInput(game).position!!.state.players[0].hand.all { it.definitionId == "cpu-hidden-card" })
    }

    @Test fun `known hand reply search uses an available Energy attachment before attacking`() {
        val catalog = catalog()
        val engine = RulesEngine(catalog)
        val reply = state().let { current -> current.copy(
            currentPlayer = 1,
            turn = current.turn+1,
            phase = Phase.MAIN,
            players = current.players.mapIndexed { owner, player -> if (owner == 1) player.copy(
                active = pokemon("cpu", "hero"), hand = listOf(c("known-energy")), flags = TurnFlags()
            ) else player.copy(active = pokemon("human", "small")) }
        ) }
        val positions = CpuTurnSearch(catalog).knownReplyPreparations(reply, actor = 1)

        assertTrue(positions.any { position ->
            position.players[1].active?.energies?.isNotEmpty() == true &&
                engine.cpuLegalActions(position).any { it is GameAction.Attack }
        })
    }

    @Test fun `master retreats a fragile Active when public attached Energy enables a knockout`() {
        val weak = small.copy(hp = 100)
        val wall = small.copy(id = "wall", name = "Wall", hp = 300)
        val attacker = hero.copy(attacks = listOf(AttackDefinition("hit", "Hit", listOf(EnergyType.COLORLESS),
            listOf(Effect.Damage(120)))))
        val catalog = catalog(weak, wall, attacker)
        val engine = RulesEngine(catalog)
        val base = state()
        val game = base.copy(players = listOf(
            base.players[0].copy(active = pokemon("opponent-attacker", "hero", 1), hand = emptyList()),
            base.players[1].copy(active = pokemon("cpu-fragile", "small"), bench = listOf(pokemon("cpu-wall", "wall")), hand = emptyList())
        ))
        val action = CpuPlayer(catalog).choose(engine.cpuInput(game))

        assertIs<GameAction.Retreat>(action)
        assertEquals("cpu-wall", action.pokemonId)
    }

    @Test fun `master sacrifices a one prize wall rather than exposing the last two prizes`() {
        val attacker = ex.copy(hp = 400, attacks = listOf(AttackDefinition("threat", "Threat", emptyList(), listOf(Effect.Damage(250)))))
        val fragile = hero.copy(prizes = 2)
        val wall = small.copy(hp = 300)
        val catalog = catalog(attacker, fragile, wall)
        val engine = RulesEngine(catalog)
        val game = state().let { it.copy(players = listOf(
            it.players[0].copy(active = pokemon("human", "ex"), bench = emptyList(), prizes = List(2) { n -> c("op$n") }),
            it.players[1].copy(hand = emptyList(), active = pokemon("cpu", "hero", 1), bench = listOf(pokemon("wall", "small"))))) }
        val action = CpuPlayer(catalog).choose(engine.cpuInput(game))
        assertIs<GameAction.Retreat>(action)
        assertEquals("wall", action.pokemonId)
        assertEquals(Legality.Legal, engine.validate(game, action))
    }
}
