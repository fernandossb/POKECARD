package com.tcgtrainer.engine

import kotlin.test.*

class CpuLearningEngineTests {
    private fun c(id: String, def: String = "energy") = CardInstance(id, def)

    private val energy = CardDefinition("energy", "Basic Energy", "Test", "1", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
    private val activeAttacker = CardDefinition("active-mon", "Active Mon", "Test", "2", category = Category.POKEMON, hp = 150,
        attacks = listOf(AttackDefinition("punch", "Punch", listOf(EnergyType.COLORLESS, EnergyType.COLORLESS), listOf(Effect.Damage(60)))))
    private val backupAttacker = CardDefinition("backup-mon", "Backup Mon", "Test", "3", category = Category.POKEMON, hp = 130,
        attacks = listOf(AttackDefinition("kick", "Kick", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(40)))))
    private val scalingAttacker = CardDefinition("scaling-mon", "Scaling Mon", "Test", "4", category = Category.POKEMON, hp = 180,
        attacks = listOf(AttackDefinition("scale", "Scale", listOf(EnergyType.COLORLESS), listOf(Effect.ConditionalAttachedEnergyDamage(minimum = 2, bonus = 30)))))
    private val searchItem = CardDefinition("nest-ball", "Nest Ball", "Test", "5", category = Category.TRAINER,
        trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.Search(listOf(CardFilter.BASIC_POKEMON)))
    private val drawSupporter = CardDefinition("research", "Professor's Research", "Test", "6", category = Category.TRAINER,
        trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.DrawCards(3))
    private val dummyBasic = CardDefinition("dummy-basic", "Dummy Basic", "Test", "7", category = Category.POKEMON, hp = 70,
        attacks = listOf(AttackDefinition("slap", "Slap", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(10)))))

    private fun catalog(vararg extras: CardDefinition) = object : CardCatalog {
        val entries = (listOf(energy, activeAttacker, backupAttacker, scalingAttacker, searchItem, drawSupporter, dummyBasic) + extras).associateBy { it.id }
        override fun get(id: String) = entries[id] ?: error("Unknown $id")
    }

    private fun pokemon(id: String, defId: String, energies: Int = 0, damage: Int = 0) =
        PokemonInstance(listOf(c(id, defId)), energies = List(energies) { c("$id-e$it", "energy") }, damage = damage, enteredPlayTurn = 0)

    private fun createGameState(
        id: String = "game",
        players: List<PlayerState>,
        turn: Int = 1,
        currentPlayer: Int = 1,
        phase: Phase = Phase.MAIN,
        humanDeckId: String = "lucario",
        cpuDeckId: String = "lucario",
        deckLists: List<List<String>> = emptyList(),
        deckNames: List<String> = emptyList()
    ) = GameState(
        gameId = id,
        seed = 42L,
        rng = RngState(42L),
        players = players,
        startingPlayer = 0,
        currentPlayer = currentPlayer,
        turn = turn,
        phase = phase,
        humanDeckId = humanDeckId,
        cpuDeckId = cpuDeckId,
        deckLists = deckLists,
        deckNames = deckNames
    )

    // =========================================================================
    // TEST 1 — REDUNDANT ENERGY ATTACHMENT
    // =========================================================================
    @Test
    fun test1_redundant_energy_attachment_prefers_backup_attacker() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val tuning = CpuTuning(adaptive = AdaptiveTuningParameters(redundantEnergyPenaltyMultiplier = 2.0, backupAttackerBonusMultiplier = 1.5))
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.MASTER, tuning = tuning)

        // CPU Active already has 2 energy (sufficient for its 2-energy attack)
        // Backup bench mon has 0 energy and needs 1 energy
        // CPU has 1 basic energy in hand
        val state = createGameState(
            id = "test1",
            turn = 3,
            currentPlayer = 1,
            phase = Phase.MAIN,
            players = listOf(
                PlayerState(active = pokemon("p0-active", "dummy-basic", 1), bench = emptyList(), hand = emptyList(), prizes = List(6) { c("p0-pz$it") }, deck = List(10) { c("p0-dk$it") }),
                PlayerState(
                    active = pokemon("cpu-active", "active-mon", energies = 2),
                    bench = listOf(pokemon("cpu-bench", "backup-mon", energies = 0)),
                    hand = listOf(c("hand-energy", "energy")),
                    prizes = List(6) { c("cpu-pz$it") },
                    deck = List(10) { c("cpu-dk$it") }
                )
            )
        )

        val input = rules.cpuInput(state, CpuKnowledge.FAIR)
        val action = cpu.choose(input)

        // Must attach energy to backup bench rather than redundant 3rd energy on active
        assertTrue(action is GameAction.AttachEnergy, "CPU should choose to attach energy. Got: $action")
        val attach = action as GameAction.AttachEnergy
        assertEquals("cpu-bench", attach.pokemonId, "CPU should attach energy to backup attacker on bench, not redundant 3rd energy on active")
    }

    // =========================================================================
    // TEST 2 — HAND WITH USEFUL CARDS (NO HOARDING / UNPRODUCTIVE PASS)
    // =========================================================================
    @Test
    fun test2_hand_with_useful_cards_plays_trainers() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.MASTER)

        // CPU has playable trainer in hand with bench space and deck targets
        val state = createGameState(
            id = "test2",
            turn = 2,
            currentPlayer = 1,
            phase = Phase.MAIN,
            players = listOf(
                PlayerState(active = pokemon("p0-active", "dummy-basic", 1), bench = emptyList(), hand = emptyList(), prizes = List(6) { c("p0-pz$it") }, deck = List(10) { c("p0-dk$it") }),
                PlayerState(
                    active = pokemon("cpu-active", "active-mon", energies = 0),
                    bench = emptyList(),
                    hand = listOf(c("hand-supporter", "research")),
                    prizes = List(6) { c("cpu-pz$it") },
                    deck = List(10) { c("cpu-dk$it", "dummy-basic") }
                )
            )
        )

        val input = rules.cpuInput(state, CpuKnowledge.FAIR)
        val action = cpu.choose(input)

        assertNotEquals(GameAction.EndTurn, action, "CPU must not end turn without playing its useful supporter")
        assertTrue(action is GameAction.PlayTrainer, "CPU should play Professor's Research. Got: $action")
    }

    // =========================================================================
    // TEST 3 — CONTINUOUS LEARNING AFTER DEFEAT
    // =========================================================================
    @Test
    fun test3_learning_after_defeat_generates_lessons_and_adapts_tuning() {
        val cat = catalog()
        val engine = CpuLearningEngine(cat)

        // Construct a recorded match where CPU lost after over-attaching energy
        val recorder = MatchRecorder(cat)
        val state0 = createGameState(
            id = "match-defeat-1",
            turn = 1,
            currentPlayer = 1,
            phase = Phase.MAIN,
            players = listOf(
                PlayerState(active = pokemon("p0", "dummy-basic"), bench = emptyList(), hand = emptyList(), prizes = List(6) { c("pz0-$it") }, deck = emptyList()),
                PlayerState(
                    active = pokemon("cpu-act", "active-mon", energies = 2),
                    bench = listOf(pokemon("cpu-b1", "backup-mon", energies = 0)),
                    hand = listOf(c("e1", "energy")),
                    prizes = List(6) { c("pzc-$it") },
                    deck = emptyList()
                )
            )
        )

        recorder.startRecording(state0, "MASTER")

        // Action: CPU attached a 3rd energy to active-mon (which needs only 2) while bench needed energy
        val stateAfterAttach = state0.copy(
            players = listOf(
                state0.players[0],
                state0.players[1].copy(
                    active = pokemon("cpu-act", "active-mon", energies = 3),
                    hand = emptyList()
                )
            )
        )

        val attachAction = GameAction.AttachEnergy("e1", "cpu-act")
        val telemetry = CpuDecisionTelemetry(
            turn = 1,
            difficulty = "MASTER",
            objective = "ATTACH",
            legalActionsCount = 3,
            legalAttacksCount = 0,
            playableTrainersCount = 0,
            selectedAction = attachAction.toString(),
            target = "cpu-act",
            energyBefore = 2,
            energyAfter = 3
        )

        recorder.recordAction(1, attachAction, state0, stateAfterAttach, emptyList(), telemetry)

        // Conclude match: Human won (winner = 0)
        val finalState = stateAfterAttach.copy(phase = Phase.GAME_OVER, winner = 0, winReason = "All prizes taken")
        recorder.recordGameEnd(finalState, 0)

        val recording = checkNotNull(recorder.getCompletedRecording())
        val reviewReport = engine.reviewMatch(recording)

        assertTrue(reviewReport.cpuLost, "Review should confirm CPU lost")
        assertTrue(reviewReport.generatedLessons.isNotEmpty(), "Review should generate lessons from defeat")
        assertTrue(reviewReport.generatedLessons.any { it.errorType == CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT }, "Should identify redundant energy attachment")

        // Strategy tuner should adapt parameters
        val tuned = engine.getTuningForArchetype("general")
        assertTrue(tuned.redundantEnergyPenaltyMultiplier > 1.0, "Tuner should increase redundant energy penalty multiplier. Current: ${tuned.redundantEnergyPenaltyMultiplier}")
    }

    // =========================================================================
    // TEST 4 — EXPERIENCE MEMORY PERSISTENCE
    // =========================================================================
    @Test
    fun test4_experience_memory_persistence_json_roundtrip() {
        val memory = CpuExperienceMemory()
        val lesson1 = CpuLesson(
            lessonId = "AVOID_REDUNDANT_ENERGY",
            errorType = CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT,
            archetype = "lucario",
            occurrences = 4,
            confidence = 0.85,
            recommendedBehavior = "Do not attach surplus energy to active when benched attackers need energy",
            contextSummary = "Attached 3 energies to active needing 2"
        )
        val lesson2 = CpuLesson(
            lessonId = "PREPARE_BACKUP_ATTACKER",
            errorType = CpuErrorType.FAILURE_TO_PREPARE_BACKUP,
            archetype = "general",
            occurrences = 2,
            confidence = 0.70,
            recommendedBehavior = "Prepare secondary benched attacker before active is knocked out",
            contextSummary = "Active was low HP with no benched attacker powered"
        )

        memory.learn(listOf(lesson1, lesson2), "match-test-4")
        memory.matchesAnalyzed = 12

        val json = memory.toJson()
        assertTrue(json.contains("AVOID_REDUNDANT_ENERGY"))
        assertTrue(json.contains("PREPARE_BACKUP_ATTACKER"))

        val restored = CpuExperienceMemory.fromJson(json)
        assertEquals(2, restored.lessons.size)

        val restoredLesson1 = restored.lessons.find { it.lessonId == "AVOID_REDUNDANT_ENERGY" }
        assertNotNull(restoredLesson1)
        assertEquals("lucario", restoredLesson1.archetype)
        assertEquals(4, restoredLesson1.occurrences)
        assertEquals(0.85, restoredLesson1.confidence)
        assertEquals(CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT, restoredLesson1.errorType)
    }

    // =========================================================================
    // TEST 5 — NON-REGRESSION: LEGITIMATE ENERGY ACCUMULATION
    // =========================================================================
    @Test
    fun test5_non_regression_legitimate_energy_accumulation_not_penalized() {
        val cat = catalog()
        val rules = RulesEngine(cat)
        val tuning = CpuTuning(adaptive = AdaptiveTuningParameters(redundantEnergyPenaltyMultiplier = 2.5))
        val cpu = CpuPlayer(cat, strategy = CpuStrategy.MASTER, tuning = tuning)

        // Scaling mon gains bonus damage per attached energy (ConditionalAttachedEnergyDamage)
        // Active has 1 energy, attack requires at least 2 for scaling effect
        val state = createGameState(
            id = "test5",
            turn = 3,
            currentPlayer = 1,
            phase = Phase.MAIN,
            players = listOf(
                PlayerState(active = pokemon("p0-active", "dummy-basic", 1), bench = emptyList(), hand = emptyList(), prizes = List(6) { c("p0-pz$it") }, deck = List(10) { c("p0-dk$it") }),
                PlayerState(
                    active = pokemon("cpu-active", "scaling-mon", energies = 1),
                    bench = listOf(pokemon("cpu-bench", "backup-mon", energies = 1)),
                    hand = listOf(c("hand-energy", "energy")),
                    prizes = List(6) { c("cpu-pz$it") },
                    deck = List(10) { c("cpu-dk$it") }
                )
            )
        )

        val input = rules.cpuInput(state, CpuKnowledge.FAIR)
        val action = cpu.choose(input)

        // Because scaling-mon benefits directly from extra energy, attaching to active is legitimate and must not be blocked
        assertTrue(action is GameAction.AttachEnergy, "Should attach energy")
        val attach = action as GameAction.AttachEnergy
        assertEquals("cpu-active", attach.pokemonId, "Should attach to active scaling attacker to unlock its high-power scaling threshold")
    }

    // =========================================================================
    // TEST 6 — COMPLETE MATCH RECORDING (JSON & TXT FOR CHATGPT)
    // =========================================================================
    @Test
    fun test6_complete_match_recording_export_json_and_txt() {
        val cat = catalog()
        val recorder = MatchRecorder(cat)

        val state = createGameState(
            id = "match-rec-6",
            turn = 1,
            currentPlayer = 0,
            phase = Phase.MAIN,
            humanDeckId = "lucario-deck",
            cpuDeckId = "charizard-deck",
            deckLists = listOf(listOf("energy", "dummy-basic"), listOf("energy", "active-mon")),
            deckNames = listOf("Lucario Player", "Charizard CPU"),
            players = listOf(
                PlayerState(
                    active = pokemon("p0-act", "dummy-basic", 1),
                    bench = emptyList(),
                    hand = listOf(c("h0-1", "dummy-basic"), c("h0-2", "energy")),
                    prizes = List(6) { c("pz0-$it") },
                    deck = List(10) { c("dk0-$it") }
                ),
                PlayerState(
                    active = pokemon("cpu-act", "active-mon", 1),
                    bench = emptyList(),
                    hand = listOf(c("hc-1", "active-mon"), c("hc-2", "energy")),
                    prizes = List(6) { c("pzc-$it") },
                    deck = List(10) { c("dkc-$it") }
                )
            )
        )

        recorder.startMatch(
            gameId = state.gameId,
            appVersion = "PokeCard 2.0.0",
            engineVersion = "trainer-engine-v2.0",
            difficulty = "ORACLE",
            initialState = state,
            humanDeckId = "lucario-deck",
            humanDeckName = "Lucario Player",
            cpuDeckId = "charizard-deck",
            cpuDeckName = "Charizard CPU",
            humanDeckList = state.deckLists[0],
            cpuDeckList = state.deckLists[1]
        )

        val attackAction = GameAction.Attack("slap")
        val afterAttack = state.copy(
            players = listOf(
                state.players[0],
                state.players[1].copy(active = state.players[1].active?.copy(damage = 10))
            )
        )
        recorder.recordAction(0, attackAction, state, afterAttack, listOf(GameEvent("DamageDealt", "Dano de 10 causado", 10)))

        // CPU turn
        val cpuEndTurn = GameAction.EndTurn
        val afterCpuTurn = afterAttack.copy(currentPlayer = 1)
        recorder.recordAction(1, cpuEndTurn, afterCpuTurn, afterCpuTurn, emptyList())
        recorder.recordGameEnd(afterCpuTurn.copy(phase = Phase.GAME_OVER, winner = 0), 0)

        val recording = checkNotNull(recorder.getCompletedRecording())
        val json = recording.toJson()
        val txt = recording.toReadableTxt()

        // JSON assertions
        assertTrue(json.contains("match-rec-6"))
        assertTrue(json.contains("ORACLE"))
        val reloaded = MatchRecording.fromJson(json)
        assertEquals("match-rec-6", reloaded.gameId)
        assertEquals("ORACLE", reloaded.difficulty)
        assertEquals(2, reloaded.turns.size)

        // TXT assertions (ChatGPT analysis format)
        assertTrue(txt.contains("POKECARD TCG TRAINER"))
        assertTrue(txt.contains("ID da Partida: match-rec-6"))
        assertTrue(txt.contains("Dificuldade: ORACLE"))
        assertTrue(txt.contains("TURNO 1"))
        assertTrue(txt.contains("Mão Inicial"))
        assertTrue(txt.contains("Mão Final"))
        assertTrue(txt.contains("h0-1"))
        assertTrue(txt.contains("hc-1"))
    }

    // =========================================================================
    // TEST 7 — UNDO AND RESUME CONSISTENCY
    // =========================================================================
    @Test
    fun test7_undo_and_resume_preserves_recording_consistency() {
        val cat = catalog()
        val recorder = MatchRecorder(cat)

        val state = createGameState(
            id = "match-undo-7",
            turn = 1,
            currentPlayer = 0,
            phase = Phase.MAIN,
            players = listOf(
                PlayerState(active = pokemon("p0-act", "dummy-basic"), bench = emptyList(), hand = listOf(c("e1", "energy")), prizes = emptyList(), deck = emptyList()),
                PlayerState(active = pokemon("cpu-act", "dummy-basic"), bench = emptyList(), hand = emptyList(), prizes = emptyList(), deck = emptyList())
            )
        )

        recorder.startRecording(state, "MASTER")

        val stateAfterAttach = state.copy(
            players = listOf(
                state.players[0].copy(active = pokemon("p0-act", "dummy-basic", 1), hand = emptyList()),
                state.players[1]
            )
        )
        recorder.recordAction(0, GameAction.AttachEnergy("e1", "p0-act"), state, stateAfterAttach, emptyList())
        assertEquals(1, recorder.recording?.turns?.firstOrNull()?.actions?.size)

        // User triggers Undo
        recorder.recordUndo(state)
        assertEquals(0, recorder.recording?.turns?.firstOrNull()?.actions?.size ?: 0, "Undo should remove the recorded action")

        // Resumed action
        val stateAfterBench = state.copy(
            players = listOf(
                state.players[0].copy(bench = listOf(pokemon("p0-b1", "dummy-basic")), hand = emptyList()),
                state.players[1]
            )
        )
        recorder.recordAction(0, GameAction.PlayBasic("e1"), state, stateAfterBench, emptyList())
        assertEquals(1, recorder.recording?.turns?.firstOrNull()?.actions?.size)
        assertEquals("PlayBasic", recorder.recording?.turns?.firstOrNull()?.actions?.firstOrNull()?.actionType)
    }

    // =========================================================================
    // TEST 8 — INFORMATION POLICY ENFORCEMENT
    // =========================================================================
    @Test
    fun test8_information_policy_enforcement_fair_vs_oracle() {
        val cat = catalog()
        val rules = RulesEngine(cat)

        val state = createGameState(
            id = "match-info-8",
            turn = 2,
            currentPlayer = 1,
            phase = Phase.MAIN,
            players = listOf(
                PlayerState(
                    active = pokemon("p0-act", "dummy-basic", 1),
                    bench = emptyList(),
                    hand = listOf(c("secret-hand-1", "research"), c("secret-hand-2", "energy")),
                    prizes = listOf(c("secret-pz-1", "dummy-basic")),
                    deck = listOf(c("secret-deck-1", "nest-ball"))
                ),
                PlayerState(
                    active = pokemon("cpu-act", "active-mon", 1),
                    bench = emptyList(),
                    hand = listOf(c("cpu-h1", "energy")),
                    prizes = listOf(c("cpu-pz-1", "energy")),
                    deck = listOf(c("cpu-dk-1", "energy"))
                )
            )
        )

        // FAIR mode (MASTER)
        val fairInput = rules.cpuInput(state, CpuKnowledge.FAIR)
        val fairObservation = fairInput.observation
        // Opponent hand count is known, but exact card objects are never exposed in FAIR observation
        assertEquals(2, fairObservation.opponentHandCount)

        // ORACLE mode
        val oracleInput = rules.cpuInput(state, CpuKnowledge.PERFECT_INFORMATION)
        assertNotNull(oracleInput.position, "ORACLE mode must receive enriched position")
        val opponentInOracle = oracleInput.position!!.state.players[0]
        assertEquals(2, opponentInOracle.hand.size, "ORACLE position should see opponent hand")
        assertEquals(1, opponentInOracle.deck.size, "ORACLE position should see opponent deck")
        assertEquals("secret-deck-1", opponentInOracle.deck.first().id, "ORACLE position knows exact deck cards and order")
    }
}
