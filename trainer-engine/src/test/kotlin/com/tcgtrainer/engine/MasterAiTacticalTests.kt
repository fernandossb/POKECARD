package com.tcgtrainer.engine

import kotlin.test.*

class MasterAiTacticalTests {
    private fun c(id: String, def: String = "energy") = CardInstance(id, def)

    private val energy = CardDefinition("energy", "Energy", "Test", "1", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
    private val extraEnergy = CardDefinition("extra-energy", "Extra Energy", "Test", "1b", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
    private val hero = CardDefinition("hero", "Hero", "Test", "2", category = Category.POKEMON, hp = 200,
        attacks = listOf(AttackDefinition("hit", "Hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(120)))))
    private val small = CardDefinition("small", "Small", "Test", "3", category = Category.POKEMON, hp = 100,
        attacks = listOf(AttackDefinition("chip", "Chip", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(30)))))
    private val ex = CardDefinition("ex", "Prize target ex", "Test", "4", category = Category.POKEMON, hp = 100, prizes = 2,
        attacks = listOf(AttackDefinition("ex-hit", "Ex Hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(50)))))
    private val wall = CardDefinition("wall", "Wall", "Test", "5", category = Category.POKEMON, hp = 350,
        attacks = listOf(AttackDefinition("bonk", "Bonk", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(10)))))
    private val boss = CardDefinition("boss", "Boss's Orders", "Test", "6", category = Category.TRAINER,
        trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Switch(opponent = true))
    private val switchCard = CardDefinition("switch", "Switch", "Test", "7", category = Category.TRAINER,
        trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.Switch(opponent = false))
    private val ultraBall = CardDefinition("ultra-ball", "Ultra Ball", "Test", "8", category = Category.TRAINER,
        trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.Search(listOf(CardFilter.POKEMON), discardCost = 2))
    private val drawSupporter = CardDefinition("research", "Professor's Research", "Test", "9", category = Category.TRAINER,
        trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.DrawCards(3))
    private val nestBall = CardDefinition("nest-ball", "Nest Ball", "Test", "10", category = Category.TRAINER,
        trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.Search(listOf(CardFilter.BASIC_POKEMON)))
    private val engineMon = CardDefinition("engine-mon", "Engine Mon", "Test", "11", category = Category.POKEMON, hp = 60,
        abilities = listOf(AbilityDefinition("draw-one", "Draw", "Draw a card", draw = 1)))
    private val bigThreat = CardDefinition("big-threat", "Big Threat", "Test", "12", category = Category.POKEMON, hp = 400,
        attacks = listOf(AttackDefinition("annihilate", "Annihilate", emptyList(), listOf(Effect.Damage(300)))))
    private val stage1Hero = CardDefinition("stage1-hero", "Stage 1 Hero", "Test", "13", category = Category.POKEMON,
        stage = Stage.STAGE_ONE, evolvesFrom = "small", hp = 220,
        attacks = listOf(AttackDefinition("smash", "Smash", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(150)))))
    private val iono = CardDefinition("iono", "Iono", "Test", "14", category = Category.TRAINER,
        trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.ShuffleBothHandsDraw(4))
    private val rareCandy = CardDefinition("rare-candy", "Rare Candy", "Test", "15", category = Category.TRAINER,
        trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.RareCandy)
    private val stage2Mon = CardDefinition("stage2-sweeper", "Stage 2 Sweeper", "Test", "16", category = Category.POKEMON,
        stage = Stage.STAGE_TWO, evolvesFrom = "stage1-hero", canonicalName = "Stage 2 Sweeper", rareCandyBasicEvolvesFromName = "Small", hp = 310,
        attacks = listOf(AttackDefinition("sweep", "Sweep", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(280)))))
    private val heavyWall = CardDefinition("heavy-wall", "Heavy Wall", "Test", "17", category = Category.POKEMON,
        hp = 300, retreatCost = 3, attacks = listOf(AttackDefinition("stall", "Stall", listOf(EnergyType.COLORLESS, EnergyType.COLORLESS, EnergyType.COLORLESS), listOf(Effect.Damage(20)))))

    private fun catalog(vararg extras: CardDefinition) = object : CardCatalog {
        val entries = (listOf(energy, extraEnergy, hero, small, ex, wall, boss, switchCard, ultraBall,
            drawSupporter, nestBall, engineMon, bigThreat, stage1Hero, iono, rareCandy, stage2Mon, heavyWall) + extras).associateBy { it.id }
        override fun get(id: String) = entries[id] ?: error("Unknown $id")
    }

    private fun pokemon(id: String, defId: String, energies: Int = 0, damage: Int = 0) =
        PokemonInstance(listOf(c(id, defId)), energies = List(energies) { c("$id-e$it", "energy") }, damage = damage, enteredPlayTurn = 0)

    // =========================================================================
    // PUZZLE 1: LETHAL COM BOSS
    // =========================================================================
    @Test fun `puzzle 1 - lethal with boss chooses gust and winning knockout`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        val game = GameState("p1", 1, RngState(1), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "small"), bench = listOf(pokemon("h-ex", "ex"))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(2) { c("c-p$it") },
                hand = listOf(c("boss-1", "boss")), active = pokemon("c-hero", "hero", 1))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val action = cpu.choose(rules.cpuInput(game))
        assertEquals(GameAction.PlayTrainer("boss-1"), action, "CPU must play Boss's Orders to target bench ex for lethal")
    }

    // =========================================================================
    // PUZZLE 2: NÃO ATACAR CEGAMENTE
    // =========================================================================
    @Test fun `puzzle 2 - do not attack blindly when attacking causes loss`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        // Opponent has 1 prize left. Opponent active deals 300 damage (annihilate).
        // CPU active is small (100 HP). If CPU attacks, small dies next turn giving opponent their last prize (LOSS!).
        // CPU bench is wall (350 HP). Survives 300 damage with 50 HP remaining.
        // Retreating to wall prevents immediate game loss.
        val game = GameState("p2", 2, RngState(2), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(1) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-boss", "big-threat")),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(6) { c("c-p$it") },
                hand = emptyList(), active = pokemon("c-small", "small", 1), bench = listOf(pokemon("c-wall", "wall", 0)))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val action = cpu.choose(rules.cpuInput(game))
        assertNotEquals(GameAction.Attack("chip"), action, "CPU must NOT attack blindly into lethal loss")
        assertIs<GameAction.Retreat>(action, "CPU should retreat to wall to prevent game loss")
    }

    // =========================================================================
    // PUZZLE 3: SETUP COMPLEXO
    // =========================================================================
    @Test fun `puzzle 3 - complex setup discovers search and setup sequence`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        val deckCards = listOf("stage1-hero", "hero", "energy", "energy")
        val game = GameState("p3", 3, RngState(3), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "small")),
            PlayerState(deck = deckCards.mapIndexed { i, d -> c("c-deck-$i", d) }, prizes = List(2) { c("c-p$it") },
                hand = listOf(c("ub-1", "ultra-ball"), c("d1", "extra-energy"), c("d2", "extra-energy"), c("hand-e", "energy")),
                active = pokemon("c-small", "small", 0), bench = emptyList())
        ), 0, 1, turn = 4, phase = Phase.MAIN,
           deckLists = listOf(emptyList(), deckCards))

        val action = cpu.choose(rules.cpuInput(game))
        assertEquals(GameAction.PlayTrainer("ub-1"), action, "CPU must initiate Ultra Ball search combo")
    }

    // =========================================================================
    // PUZZLE 4: DECK THINNING
    // =========================================================================
    @Test fun `puzzle 4 - deck thinning plays search before draw`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        val deckCards = List(10) { "small" }
        val game = GameState("p4", 4, RngState(4), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "wall")),
            PlayerState(deck = deckCards.mapIndexed { i, d -> c("c-deck$i", d) }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("nest-1", "nest-ball"), c("res-1", "research")),
                active = pokemon("c-small", "small", 0), bench = emptyList())
        ), 0, 1, turn = 4, phase = Phase.MAIN,
           deckLists = listOf(emptyList(), deckCards))

        val action = cpu.choose(rules.cpuInput(game))
        assertEquals(GameAction.PlayTrainer("nest-1"), action, "CPU must thin deck with Nest Ball before using Research")
    }

    // =========================================================================
    // PUZZLE 5: PRESERVAR BOSS
    // =========================================================================
    @Test fun `puzzle 5 - preserve boss holds gust when no knockout is possible`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        val game = GameState("p5", 5, RngState(5), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "hero", 0, damage = 180), bench = listOf(pokemon("h-wall", "wall"))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(2) { c("c-p$it") },
                hand = listOf(c("boss-1", "boss")), active = pokemon("c-hero", "hero", 1))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val action = cpu.choose(rules.cpuInput(game))
        assertNotEquals(GameAction.PlayTrainer("boss-1"), action, "CPU must preserve Boss's Orders instead of wasting it")
        assertEquals(GameAction.Attack("hit"), action, "CPU should attack active for immediate knockout")
    }

    // =========================================================================
    // PUZZLE 6: DESCARTE CORRETO
    // =========================================================================
    @Test fun `puzzle 6 - smart discard preserves critical resource during ultra ball`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        val choice = ChoiceRequest(ChoiceKind.DISCARD, "Discard 2 cards",
            listOf("crit-boss", "dup-small", "excess-e"), 2, 2)
        val game = GameState("p6", 6, RngState(6), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(2) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "small"), bench = listOf(pokemon("h-ex", "ex"))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(2) { c("c-p$it") },
                hand = listOf(c("crit-boss", "boss"), c("dup-small", "small"), c("excess-e", "energy")),
                active = pokemon("c-hero", "hero", 1), bench = listOf(pokemon("c-bench-small", "small")))
        ), 0, 1, turn = 4, phase = Phase.RESOLVE_CHOICE, resolution = Resolution(listOf(choice)))

        val action = cpu.choose(rules.cpuInput(game))
        assertIs<GameAction.ChooseCards>(action)
        assertFalse("crit-boss" in action.cardIds, "Critical Boss's Orders must NOT be discarded! Got: ${action.cardIds}")
        assertTrue("dup-small" in action.cardIds && "excess-e" in action.cardIds,
            "Redundant basic and excess energy should be discarded. Got: ${action.cardIds}")
    }

    // =========================================================================
    // PUZZLE 7: NEXT ATTACKER
    // =========================================================================
    @Test fun `puzzle 7 - next attacker prepares bench reserve instead of overcharging active`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        // CPU active already has 2 energies (attack requires 1).
        // Active has 180 damage (20 HP left); opponent active will KO it next turn.
        // Retreat is already used this turn.
        // CPU bench has reserve with 0 energy.
        // CPU has 1 energy in hand.
        val game = GameState("p7", 7, RngState(7), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-threat", "hero", 2)),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                flags = TurnFlags(retreat = true),
                hand = listOf(c("hand-e", "energy")),
                active = pokemon("c-active", "hero", 2, damage = 180),
                bench = listOf(pokemon("c-reserve", "hero", 0)))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val action = cpu.choose(rules.cpuInput(game))
        assertIs<GameAction.AttachEnergy>(action, "CPU should attach energy")
        assertEquals("c-reserve", action.pokemonId, "CPU must attach energy to reserve nextAttacker, NOT doomed active")
    }

    // =========================================================================
    // PUZZLE 8: ELIMINAR ENGINE NO BANCO
    // =========================================================================
    @Test fun `puzzle 8 - gust and eliminate engine on bench`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        val game = GameState("p8", 8, RngState(8), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-wall", "wall"), bench = listOf(pokemon("h-engine", "engine-mon"))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("boss-1", "boss")), active = pokemon("c-hero", "hero", 1))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val action = cpu.choose(rules.cpuInput(game))
        assertEquals(GameAction.PlayTrainer("boss-1"), action, "CPU must Boss the bench engine instead of hitting the wall")
    }

    // =========================================================================
    // PUZZLE 9: MULTI-PRIZE
    // =========================================================================
    @Test fun `puzzle 9 - multi-prize targets 2-prize ex over 1-prize active`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        val game = GameState("p9", 9, RngState(9), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-small", "small"), bench = listOf(pokemon("h-ex", "ex"))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("boss-1", "boss")), active = pokemon("c-hero", "hero", 1))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val action = cpu.choose(rules.cpuInput(game))
        assertEquals(GameAction.PlayTrainer("boss-1"), action, "CPU must gust the 2-prize ex rather than attacking 1-prize active")
    }

    // =========================================================================
    // PUZZLE 10: LINHA DE 8+ AÇÕES
    // =========================================================================
    @Test fun `puzzle 10 - deep intra-turn sequence search executes 8+ actions combo`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat)
        val deckCards = listOf("stage1-hero", "hero", "energy", "energy")
        val game = GameState("p10", 10, RngState(10), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-wall", "wall"), bench = listOf(pokemon("h-ex", "ex"))),
            PlayerState(deck = deckCards.mapIndexed { i, d -> c("c-d-$i", d) }, prizes = List(2) { c("c-p$it") },
                hand = listOf(c("ub-1", "ultra-ball"), c("e1", "extra-energy"), c("e2", "extra-energy"),
                              c("hand-e", "energy"), c("sw-1", "switch"), c("boss-1", "boss")),
                active = pokemon("c-pivot", "wall", 0),
                bench = listOf(pokemon("c-small", "small", 0)))
        ), 0, 1, turn = 4, phase = Phase.MAIN,
           deckLists = listOf(emptyList(), deckCards))

        var state = game
        var actionCount = 0
        val actionsTaken = mutableListOf<GameAction>()
        while (state.phase != Phase.GAME_OVER && actionCount < 16) {
            val input = rules.cpuInput(state)
            val action = cpu.choose(input)
            actionsTaken += action
            actionCount++
            val result = rules.apply(state, action)
            assertEquals(Legality.Legal, result.legality, "Action $action must be legal at step $actionCount")
            state = result.state
            if (action is GameAction.Attack) break
        }

        assertTrue(actionCount >= 8, "Expected sequence of at least 8 actions, but got $actionCount: $actionsTaken")
        assertTrue(actionsTaken.any { it is GameAction.PlayTrainer }, "Must play trainers")
        assertTrue(actionsTaken.any { it is GameAction.AttachEnergy }, "Must attach energy")
        assertTrue(actionsTaken.any { it is GameAction.Attack }, "Must finish with attack")
    }

    // =========================================================================
    // PUZZLE 11: ANTI-LETHAL (PREVENIR DERROTA NO PRÓXIMO TURNO)
    // =========================================================================
    @Test fun `puzzle 11 - anti-lethal prioritizes defense to prevent next turn loss`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.GRANDMASTER)
        val game = GameState("p11", 11, RngState(11), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(2) { c("h-p$it") },
                hand = listOf(c("opp-e", "energy"), c("opp-boss", "boss")),
                active = pokemon("h-hero", "hero", 1), bench = emptyList()),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("iono-1", "iono")),
                active = pokemon("c-small", "small", 1), bench = listOf(pokemon("c-ex", "ex", 0)))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val input = rules.cpuInput(game, CpuKnowledge.PLAYER_HAND_AND_DECK)
        val action = cpu.choose(input)
        assertEquals(GameAction.PlayTrainer("iono-1"), action, "CPU must play Iono to disrupt lethal combo")
    }

    // =========================================================================
    // PUZZLE 12: IONO CONTRA MÃO FORTE DO OPONENTE
    // =========================================================================
    @Test fun `puzzle 12 - iono punishes high threat opponent hand`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.GRANDMASTER)
        val game = GameState("p12", 12, RngState(12), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = listOf(c("opp-e", "energy"), c("opp-boss", "boss"), c("opp-candy", "rare-candy"), c("opp-s2", "stage2-sweeper")),
                active = pokemon("h-active", "small", 0), bench = listOf(pokemon("h-small", "small"))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("iono-1", "iono")),
                active = pokemon("c-hero", "hero", 1), bench = emptyList())
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val input = rules.cpuInput(game, CpuKnowledge.PLAYER_HAND_AND_DECK)
        val action = cpu.choose(input)
        assertEquals(GameAction.PlayTrainer("iono-1"), action, "CPU must play Iono against high-threat hand")
    }

    // =========================================================================
    // PUZZLE 13: HOLD IONO QUANDO MÃO DO OPONENTE ESTÁ MORTA
    // =========================================================================
    @Test fun `puzzle 13 - hold iono when opponent hand is dead`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.GRANDMASTER)
        val game = GameState("p13", 13, RngState(13), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = listOf(c("dead-card", "stage2-sweeper")),
                active = pokemon("h-active", "small", 0, damage = 70), bench = emptyList()),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("iono-1", "iono")),
                active = pokemon("c-hero", "hero", 1), bench = emptyList())
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val input = rules.cpuInput(game, CpuKnowledge.PLAYER_HAND_AND_DECK)
        val action = cpu.choose(input)
        assertNotEquals(GameAction.PlayTrainer("iono-1"), action, "CPU must NOT bail out a dead hand by playing Iono")
        assertEquals(GameAction.Attack("hit"), action, "CPU should attack active for knockout")
    }

    // =========================================================================
    // PUZZLE 14: RESOURCE EXHAUSTION BOARD LOCK
    // =========================================================================
    @Test fun `puzzle 14 - resource exhaustion executes board lock when opponent has zero switches`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.GRANDMASTER)
        val oppDeckList = listOf("switch", "hero", "energy", "energy")
        val game = GameState("p14", 14, RngState(14), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                discard = listOf(c("used-sw", "switch")),
                hand = emptyList(),
                active = pokemon("h-hero", "hero", 1),
                bench = listOf(pokemon("h-heavy", "heavy-wall", 0))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("boss-1", "boss")),
                active = pokemon("c-hero", "hero", 1), bench = emptyList())
        ), 0, 1, turn = 4, phase = Phase.MAIN,
           deckLists = listOf(oppDeckList, emptyList()))

        val input = rules.cpuInput(game, CpuKnowledge.PLAYER_HAND_AND_DECK)
        val action = cpu.choose(input)
        assertEquals(GameAction.PlayTrainer("boss-1"), action, "CPU must play Boss's Orders to execute board lock on heavy retreat Pokémon")
    }

    // =========================================================================
    // PUZZLE 15: SETUP BREAK
    // =========================================================================
    @Test fun `puzzle 15 - setup break targets 95-threat basic when evolution and candy in hand`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.GRANDMASTER)
        val game = GameState("p15", 15, RngState(15), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = listOf(c("opp-candy", "rare-candy"), c("opp-s2", "stage2-sweeper")),
                active = pokemon("h-wall", "wall", 0),
                bench = listOf(pokemon("h-small", "small", 0))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("boss-1", "boss")),
                active = pokemon("c-hero", "hero", 1), bench = emptyList())
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val input = rules.cpuInput(game, CpuKnowledge.PLAYER_HAND_AND_DECK)
        val action = cpu.choose(input)
        assertEquals(GameAction.PlayTrainer("boss-1"), action, "CPU must Boss the 95-threat basic to break setup before it sweeps")
    }

    // =========================================================================
    // PUZZLE 16: EXACT OUTS EXPLOITATION
    // =========================================================================
    @Test fun `puzzle 16 - deck counter analyzer calculates exact outs and odds`() {
        val cat = catalog()
        val oppDeckList = listOf("energy", "energy", "energy", "boss", "hero")
        val game = GameState("p16", 16, RngState(16), listOf(
            PlayerState(deck = List(3) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                discard = listOf(c("used-e1", "energy"), c("used-e2", "energy")),
                hand = emptyList(),
                active = pokemon("h-active", "hero", 0), bench = emptyList()),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = emptyList(), active = pokemon("c-hero", "hero", 1), bench = emptyList())
        ), 0, 1, turn = 4, phase = Phase.MAIN,
           deckLists = listOf(oppDeckList, emptyList()))

        val (outs, odds) = DeckCounterAnalyzer.calculateOuts(game, 1, cat) { it.category == Category.ENERGY }
        assertEquals(1, outs, "Opponent should have exactly 1 energy out remaining")
        assertTrue(odds in 0.3..0.35, "Odds should reflect 1 out of 3 cards in deck")
    }

    // =========================================================================
    // PUZZLE 17: SMART SACRIFICE / PRIZE DENIAL
    // =========================================================================
    @Test fun `puzzle 17 - smart sacrifice retreats 2-prize ex to 1-prize pokemon to deny lethal`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.GRANDMASTER)
        val game = GameState("p17", 17, RngState(17), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(2) { c("h-p$it") },
                hand = emptyList(),
                active = pokemon("h-hero", "hero", 1), bench = emptyList()),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = emptyList(),
                active = pokemon("c-ex", "ex", 1),
                bench = listOf(pokemon("c-small", "small", 0)))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val input = rules.cpuInput(game, CpuKnowledge.PLAYER_HAND_AND_DECK)
        val action = cpu.choose(input)
        assertIs<GameAction.Retreat>(action, "CPU must retreat 2-prize ex to 1-prize small to deny lethal win")
        assertEquals("c-small", action.pokemonId)
    }

    // =========================================================================
    // PUZZLE 18: ORACLE - PREVENÇÃO DE LETHAL DA PRÓXIMA COMPRA
    // =========================================================================
    @Test fun `puzzle 18 - oracle sees next draw lethal and disrupts with iono`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.ORACLE)
        val game = GameState("p18", 18, RngState(18), listOf(
            PlayerState(deck = listOf(c("top-boss", "boss")) + List(9) { c("opp-d$it", "energy") },
                prizes = List(2) { c("h-p$it") },
                hand = listOf(c("opp-useless", "extra-energy")),
                active = pokemon("h-hero", "hero", 1), bench = emptyList()),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("iono-1", "iono")),
                active = pokemon("c-hero", "hero", 1), bench = listOf(pokemon("c-ex", "ex", 0)))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val input = rules.cpuInput(game, CpuKnowledge.PERFECT_INFORMATION)
        val action = cpu.choose(input)
        assertEquals(GameAction.PlayTrainer("iono-1"), action, "Oracle CPU must disrupt next-draw Boss with Iono")
    }

    // =========================================================================
    // PUZZLE 19: DESTRUIR ENGINE NO BANCO VS DANO SUPERFICIAL
    // =========================================================================
    @Test fun `puzzle 19 - destroy engine prioritizes knocking out engine over chip damage`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.GRANDMASTER)
        val game = GameState("p19", 19, RngState(19), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(),
                active = pokemon("h-wall", "wall", 0),
                bench = listOf(pokemon("h-engine", "engine-mon", 0))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("boss-1", "boss")),
                active = pokemon("c-hero", "hero", 1), bench = emptyList())
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val input = rules.cpuInput(game, CpuKnowledge.PLAYER_HAND_AND_DECK)
        val action = cpu.choose(input)
        assertEquals(GameAction.PlayTrainer("boss-1"), action, "CPU must Boss the draw engine rather than chipping the 350 HP wall")
    }

    // =========================================================================
    // PUZZLE 20: ADVERSARIAL SEARCH / MINIMAX BEST RESPONSE
    // =========================================================================
    @Test fun `puzzle 20 - adversarial search chooses line minimizing opponent best response potential`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.GRANDMASTER)
        val game = GameState("p20", 20, RngState(20), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = listOf(c("opp-e", "energy"), c("opp-boss", "boss")),
                active = pokemon("h-hero", "hero", 1), bench = listOf(pokemon("h-small", "small"))),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = listOf(c("iono-1", "iono")),
                active = pokemon("c-hero", "hero", 1), bench = listOf(pokemon("c-small", "small", 0)))
        ), 0, 1, turn = 4, phase = Phase.MAIN)

        val input = rules.cpuInput(game, CpuKnowledge.PLAYER_HAND_AND_DECK)
        val action = cpu.choose(input)
        val telemetry = cpu.lastTelemetry
        assertNotNull(telemetry)
        assertTrue(telemetry.playerHandThreat > 0.0, "Telemetry should record player hand threat: ${telemetry.playerHandThreat}")
        assertTrue(telemetry.opponentPotentialAfter <= telemetry.opponentPotentialBefore,
            "Chosen counter line should reduce or match opponent potential: before=${telemetry.opponentPotentialBefore}, after=${telemetry.opponentPotentialAfter}")
    }

    // =========================================================================
    // AUTOMATED BENCHMARK RUNNER (20/20 TARGET, >= 18/20 REQUIRED)
    // =========================================================================
    @Test fun `master ai tactical benchmark reports score across all 20 puzzles`() {
        val puzzles = listOf(
            "Puzzle 1  [Lethal com Boss]" to { `puzzle 1 - lethal with boss chooses gust and winning knockout`() },
            "Puzzle 2  [Não Atacar Cegamente]" to { `puzzle 2 - do not attack blindly when attacking causes loss`() },
            "Puzzle 3  [Setup Complexo]" to { `puzzle 3 - complex setup discovers search and setup sequence`() },
            "Puzzle 4  [Deck Thinning]" to { `puzzle 4 - deck thinning plays search before draw`() },
            "Puzzle 5  [Preservar Boss]" to { `puzzle 5 - preserve boss holds gust when no knockout is possible`() },
            "Puzzle 6  [Descarte Correto]" to { `puzzle 6 - smart discard preserves critical resource during ultra ball`() },
            "Puzzle 7  [Next Attacker]" to { `puzzle 7 - next attacker prepares bench reserve instead of overcharging active`() },
            "Puzzle 8  [Eliminar Engine no Banco]" to { `puzzle 8 - gust and eliminate engine on bench`() },
            "Puzzle 9  [Multi-Prize]" to { `puzzle 9 - multi-prize targets 2-prize ex over 1-prize active`() },
            "Puzzle 10 [Linha de 8+ Ações]" to { `puzzle 10 - deep intra-turn sequence search executes 8+ actions combo`() },
            "Puzzle 11 [Anti-Lethal Defense]" to { `puzzle 11 - anti-lethal prioritizes defense to prevent next turn loss`() },
            "Puzzle 12 [Iono vs Mão Forte]" to { `puzzle 12 - iono punishes high threat opponent hand`() },
            "Puzzle 13 [Hold Iono vs Mão Morta]" to { `puzzle 13 - hold iono when opponent hand is dead`() },
            "Puzzle 14 [Board Lock 0-Switch]" to { `puzzle 14 - resource exhaustion executes board lock when opponent has zero switches`() },
            "Puzzle 15 [Setup Break 95-Threat]" to { `puzzle 15 - setup break targets 95-threat basic when evolution and candy in hand`() },
            "Puzzle 16 [Exact Outs Calculation]" to { `puzzle 16 - deck counter analyzer calculates exact outs and odds`() },
            "Puzzle 17 [Smart Sacrifice Denial]" to { `puzzle 17 - smart sacrifice retreats 2-prize ex to 1-prize pokemon to deny lethal`() },
            "Puzzle 18 [Oracle Next-Draw Disrupt]" to { `puzzle 18 - oracle sees next draw lethal and disrupts with iono`() },
            "Puzzle 19 [Destroy Engine on Bench]" to { `puzzle 19 - destroy engine prioritizes knocking out engine over chip damage`() },
            "Puzzle 20 [Minimax Adversarial Search]" to { `puzzle 20 - adversarial search chooses line minimizing opponent best response potential`() }
        )

        var passed = 0
        val results = mutableListOf<Pair<String, Boolean>>()

        for ((name, test) in puzzles) {
            try {
                test()
                passed++
                results += name to true
            } catch (t: Throwable) {
                results += name to false
                println("FAILED $name: ${t.message}")
            }
        }

        println("============================================================")
        println("          POKECARD AI TACTICAL BENCHMARK (20 PUZZLES)       ")
        println("============================================================")
        for ((name, ok) in results) {
            val status = if (ok) "PASS" else "FAIL"
            println(String.format("%-44s : %s", name, status))
        }
        val pct = (passed.toDouble() / puzzles.size) * 100.0
        println("------------------------------------------------------------")
        println(String.format("FINAL SCORE: %d/%d (%.1f%%) - %s",
            passed, puzzles.size, pct, if (passed >= 18) "TARGET MET/EXCEEDED" else "NEEDS WORK"))
        println("============================================================")

        assertTrue(passed >= 18, "Tactical benchmark requires at least 18/20 passed puzzles, got $passed/20")
    }
}
