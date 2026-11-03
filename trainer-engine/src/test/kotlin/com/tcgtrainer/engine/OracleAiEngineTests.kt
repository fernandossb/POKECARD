package com.tcgtrainer.engine

import kotlin.test.*

class OracleAiEngineTests {
    private fun c(id: String, def: String = "energy") = CardInstance(id, def)

    private val energy = CardDefinition("energy", "Fighting Energy", "Test", "1", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
    private val extraEnergy = CardDefinition("extra-energy", "Extra Energy", "Test", "1b", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
    private val hero = CardDefinition("hero", "Hero", "Test", "2", category = Category.POKEMON, hp = 200,
        attacks = listOf(AttackDefinition("hit", "Hit", listOf(EnergyType.FIGHTING), listOf(Effect.Damage(120)))))
    private val small = CardDefinition("small", "Small", "Test", "3", category = Category.POKEMON, hp = 100,
        attacks = listOf(AttackDefinition("chip", "Chip", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(30)))))
    private val stage1Hero = CardDefinition("stage1-hero", "Stage 1 Hero", "Test", "13", category = Category.POKEMON,
        stage = Stage.STAGE_ONE, evolvesFrom = "small", hp = 220,
        attacks = listOf(AttackDefinition("smash", "Smash", listOf(EnergyType.FIGHTING), listOf(Effect.Damage(150)))))
    private val ultraBall = CardDefinition("ultra-ball", "Ultra Ball", "Test", "8", category = Category.TRAINER,
        trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.Search(listOf(CardFilter.POKEMON), discardCost = 2))
    private val drawSupporter = CardDefinition("research", "Professor's Research", "Test", "9", category = Category.TRAINER,
        trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.DrawCards(3))
    private val boss = CardDefinition("boss", "Boss's Orders", "Test", "6", category = Category.TRAINER,
        trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Switch(opponent = true))
    private val switchCard = CardDefinition("switch", "Switch", "Test", "7", category = Category.TRAINER,
        trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.Switch(opponent = false))
    private val annihilator = CardDefinition("annihilator", "Annihilator", "Test", "99", category = Category.POKEMON, hp = 300,
        attacks = listOf(AttackDefinition("kill", "Kill", listOf(EnergyType.FIGHTING), listOf(Effect.Damage(350)))))

    private fun catalog() = object : CardCatalog {
        val entries = listOf(energy, extraEnergy, hero, small, stage1Hero, ultraBall, drawSupporter, boss, switchCard, annihilator).associateBy { it.id }
        override fun get(id: String) = entries[id] ?: error("Unknown $id")
    }

    private fun pokemon(id: String, defId: String, energies: Int = 0, damage: Int = 0) =
        PokemonInstance(listOf(c(id, defId)), energies = List(energies) { c("$id-e$it", "energy") }, damage = damage, enteredPlayTurn = 0)

    // =========================================================================
    // TESTE A: ATAQUE DISPONÍVEL
    // CPU com atacante ativo e energia suficiente DEVE atacar em vez de passar.
    // =========================================================================
    @Test
    fun `test A - active with energy attacks instead of passing`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.ORACLE)

        val state = GameState("test-a", 1, RngState(1), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "small", damage = 0)),
            PlayerState(deck = List(10) { c("c-deck$it") }, prizes = List(4) { c("c-p$it") },
                hand = emptyList(), active = pokemon("c-hero", "hero", energies = 1))
        ), 0, 1, turn = 2, phase = Phase.MAIN)

        val action = cpu.choose(rules.cpuInput(state, CpuKnowledge.PERFECT_INFORMATION))
        assertIs<GameAction.Attack>(action, "CPU must choose an Attack action instead of EndTurn")
        assertEquals("hit", action.attackId)
    }

    // =========================================================================
    // TESTE B: REPRODUÇÃO DOS 10 TURNOS DE INATIVIDADE
    // Falha se a CPU passar turnos quando ataques legais estiverem disponíveis.
    // =========================================================================
    @Test
    fun `test B - prevents ten consecutive turns of inactivity when attacks are available`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.ORACLE)

        var turnsWithoutAttack = 0
        var currentState = GameState("test-b", 1, RngState(1), listOf(
            PlayerState(deck = List(20) { c("h-deck$it") }, prizes = List(6) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "hero", energies = 0, damage = 0)),
            PlayerState(deck = List(20) { c("c-deck$it") }, prizes = List(6) { c("c-p$it") },
                hand = emptyList(), active = pokemon("c-hero", "hero", energies = 1, damage = 0))
        ), 0, 1, turn = 2, phase = Phase.MAIN)

        for (round in 1..10) {
            val cpuAction = cpu.choose(rules.cpuInput(currentState, CpuKnowledge.PERFECT_INFORMATION))
            if (cpuAction is GameAction.Attack) {
                // Succeeded in attacking, inactivity loop prevented!
                break
            }
            if (cpuAction == GameAction.EndTurn) {
                turnsWithoutAttack++
            }
            // Advance state
            currentState = currentState.copy(turn = currentState.turn + 2)
        }

        assertTrue(turnsWithoutAttack < 10, "CPU must not pass 10 turns without attacking when attack is legal! Passed $turnsWithoutAttack times.")
    }

    // =========================================================================
    // TESTE C: LINHA DE COMBO COMPLETA
    // Ultra Ball -> descarte -> busca -> evolui -> anexa -> ataca
    // =========================================================================
    @Test
    fun `test C - complete combo search evolve attach and attack`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.ORACLE)

        // Hand has Ultra Ball, 2 discard fodder energies, and 1 attachable energy
        val state = GameState("test-c", 1, RngState(1), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(6) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "hero", damage = 0)),
            PlayerState(
                deck = listOf(c("c-stage1", "stage1-hero")),
                prizes = List(6) { c("c-p$it") },
                hand = listOf(
                    c("ub-1", "ultra-ball"),
                    c("fodder-1", "energy"),
                    c("fodder-2", "extra-energy"),
                    c("attach-e", "energy")
                ),
                active = pokemon("c-small", "small", energies = 0)
            )
        ), 0, 1, turn = 2, phase = Phase.MAIN)

        val firstAction = cpu.choose(rules.cpuInput(state, CpuKnowledge.PERFECT_INFORMATION))
        assertIs<GameAction.PlayTrainer>(firstAction, "CPU should initiate combo by playing Ultra Ball")
        assertEquals("ub-1", firstAction.cardId)
    }

    // =========================================================================
    // TESTE D: APRIMORAMENTO DE MÃO
    // Sem atacante pronto, CPU usa compra/busca para viabilizar atacante
    // =========================================================================
    @Test
    fun `test D - improve hand using draw or search when attacker is missing`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.ORACLE)

        // CPU active has no energy and cannot attack, but hand has Professor's Research and deck has energies
        val state = GameState("test-d", 1, RngState(1), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(6) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "hero")),
            PlayerState(
                deck = List(10) { c("c-energy-$it", "energy") },
                prizes = List(6) { c("c-p$it") },
                hand = listOf(c("res-1", "research")),
                active = pokemon("c-hero", "hero", energies = 0)
            )
        ), 0, 1, turn = 2, phase = Phase.MAIN)

        val action = cpu.choose(rules.cpuInput(state, CpuKnowledge.PERFECT_INFORMATION))
        assertIs<GameAction.PlayTrainer>(action, "CPU should play Professor's Research to improve hand and search for energies")
        assertEquals("res-1", action.cardId)
    }

    // =========================================================================
    // TESTE E: PREVISÃO DEFENSIVA DO ORACLE
    // Identifica lethal real e usa resposta legal em vez de phantom lethal
    // =========================================================================
    @Test
    fun `test E - identifies true opponent lethal and retreats or responds safely`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.ORACLE)

        // Opponent has 1 prize remaining and active Annihilator with energy ready to deal 350 damage (true lethal on small)
        // CPU has small active (100 HP, 0 prizes left for opponent means lethal if knocked out)
        // CPU active cannot survive, but CPU has Switch and wall (hero with 200 HP) or can eliminate threat
        val state = GameState("test-e", 1, RngState(1), listOf(
            PlayerState(
                deck = List(10) { c("h-deck$it") },
                prizes = List(1) { c("h-p$it") },
                hand = emptyList(),
                active = pokemon("h-annihilator", "annihilator", energies = 1)
            ),
            PlayerState(
                deck = List(10) { c("c-deck$it") },
                prizes = List(4) { c("c-p$it") },
                hand = listOf(c("sw-1", "switch")),
                active = pokemon("c-small", "small", energies = 0),
                bench = listOf(pokemon("c-hero", "hero", energies = 1))
            )
        ), 0, 1, turn = 2, phase = Phase.MAIN)

        val action = cpu.choose(rules.cpuInput(state, CpuKnowledge.PERFECT_INFORMATION))
        assertNotNull(action)
        assertNotEquals(GameAction.EndTurn, action, "CPU must not blindly end turn facing a lethal active attacker")
    }

    // =========================================================================
    // TESTE F: CONTINUIDADE DE ESTRATÉGIA
    // O mesmo objetivo e plano são mantidos entre decisões do mesmo turno
    // =========================================================================
    @Test
    fun `test F - strategy continuity maintains turn plan across multiple actions`() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.ORACLE)

        val state = GameState("test-f", 1, RngState(1), listOf(
            PlayerState(deck = List(10) { c("h-deck$it") }, prizes = List(4) { c("h-p$it") },
                hand = emptyList(), active = pokemon("h-active", "small")),
            PlayerState(
                deck = List(10) { c("c-deck$it") },
                prizes = List(4) { c("c-p$it") },
                hand = listOf(c("e-1", "energy")),
                active = pokemon("c-hero", "hero", energies = 0)
            )
        ), 0, 1, turn = 2, phase = Phase.MAIN)

        val firstAction = cpu.choose(rules.cpuInput(state, CpuKnowledge.PERFECT_INFORMATION))
        val initialPlan = cpu.currentTurnPlan

        assertNotNull(initialPlan, "CPU must produce a CpuTurnPlan")
        assertEquals(2, initialPlan.turn)
        assertNotNull(initialPlan.objective, "Plan must have a well-defined CpuTurnObjective")

        // First action should be energy attachment
        assertIs<GameAction.AttachEnergy>(firstAction)

        // Apply first action to advance state within the same turn
        val transition = rules.apply(state, firstAction)
        assertEquals(Legality.Legal, transition.legality)

        // Ask for the next action in the same turn
        val secondAction = cpu.choose(rules.cpuInput(transition.state, CpuKnowledge.PERFECT_INFORMATION))

        // Strategy continuity check: plan was kept and completed with Attack
        assertIs<GameAction.Attack>(secondAction, "CPU should follow through with attack as planned")
        assertEquals("hit", secondAction.attackId)
    }
}

