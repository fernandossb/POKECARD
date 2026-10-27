package com.tcgtrainer.engine

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlin.test.*

class RulesEngineTest {
    @Test fun `Incineroar Hustle Play discounts Colorless cost for each opponent Benched Pokemon`() {
        val active = PokemonInstance(listOf(CardInstance("incineroar", "sv05-034")), enteredPlayTurn = 0)
        val opposingActive = PokemonInstance(listOf(CardInstance("opponent", "me01-076")), enteredPlayTurn = 0)
        val opposingBench = List(2) { PokemonInstance(listOf(CardInstance("bench$it", "me01-076")), enteredPlayTurn = 0) }
        val attack = AttackDefinition("cost-check", "Cost Check",
            listOf(EnergyType.FIRE, EnergyType.COLORLESS, EnergyType.COLORLESS, EnergyType.COLORLESS), emptyList())
        fun state(benchCount: Int) = GameState("hustle-play", 34, RngState(34), listOf(
            PlayerState(deck = emptyList(), active = active),
            PlayerState(deck = emptyList(), active = opposingActive, bench = opposingBench.take(benchCount))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val rules = RulesEngine(TrainerCatalog)
        assertTrue(CardLibrary.byId.getValue("sv05-034").implemented)
        assertTrue(CardLibrary.byId.getValue("sv05-187").implemented)
        assertEquals(attack.cost, rules.attackCost(state(0), active, attack))
        assertEquals(listOf(EnergyType.FIRE, EnergyType.COLORLESS), rules.attackCost(state(2), active, attack))
    }
    @Test fun `Counter Gain discounts only a Colorless cost while behind on Prizes`() {
        val tool = TrainerCatalog.get("me02.5-186")
        assertEquals(TrainerEffect.CounterGain, tool.trainerEffect)
        val active = PokemonInstance(listOf(CardInstance("pokemon", "me01-076")),
            tool = CardInstance("tool", tool.id), enteredPlayTurn = 0)
        val opponent = PokemonInstance(listOf(CardInstance("opponent", "me01-076")), enteredPlayTurn = 0)
        val attack = AttackDefinition("cost-check", "Cost Check",
            listOf(EnergyType.FIGHTING, EnergyType.COLORLESS, EnergyType.COLORLESS), emptyList())
        fun state(ownPrizes: Int, opponentPrizes: Int) = GameState("counter-gain", 33, RngState(33), listOf(
            PlayerState(deck = emptyList(), prizes = List(ownPrizes) { CardInstance("p$it", "sv01-258") }, active = active),
            PlayerState(deck = emptyList(), prizes = List(opponentPrizes) { CardInstance("op$it", "sv01-258") }, active = opponent)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val rules = RulesEngine(TrainerCatalog)
        assertEquals(listOf(EnergyType.FIGHTING, EnergyType.COLORLESS), rules.attackCost(state(6, 4), active, attack))
        assertEquals(attack.cost, rules.attackCost(state(4, 4), active, attack))
    }
    @Test fun `Vikavolt attack can switch only to a Lightning Pokemon on its Bench`() {
        val vikavolt = TrainerCatalog.get("sv07-053").copy(attacks = TrainerCatalog.get("sv07-053").attacks.map { it.copy(cost = emptyList()) })
        val lightning = CardDefinition("lightning-target", "Lightning Target", "Test", "1", category = Category.POKEMON,
            type = EnergyType.LIGHTNING, hp = 100)
        val grass = CardDefinition("grass-target", "Grass Target", "Test", "2", category = Category.POKEMON,
            type = EnergyType.GRASS, hp = 100)
        val enemy = CardDefinition("enemy", "Enemy", "Test", "3", category = Category.POKEMON, hp = 300)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            vikavolt.id -> vikavolt; lightning.id -> lightning; grass.id -> grass; enemy.id -> enemy
            else -> TrainerCatalog.get(id)
        } })
        fun pokemon(instance: String, definition: String) = PokemonInstance(listOf(CardInstance(instance, definition)), enteredPlayTurn = 0)
        val state = GameState("typed-switch", 31, RngState(31), listOf(
            PlayerState(deck = List(10) { CardInstance("d$it", "sv01-258") }, prizes = List(6) { CardInstance("p$it", "sv01-258") },
                active = pokemon("v", vikavolt.id), bench = listOf(pokemon("l", lightning.id), pokemon("g", grass.id))),
            PlayerState(deck = List(10) { CardInstance("od$it", "sv01-258") }, prizes = List(6) { CardInstance("op$it", "sv01-258") },
                active = pokemon("e", enemy.id))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attack = vikavolt.attacks.first { it.effects.any { effect -> effect is Effect.SwitchOwn } }
        assertTrue(attack.effects.contains(Effect.SwitchOwn(targetType = EnergyType.LIGHTNING)))
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(listOf("l"), result.state.resolution!!.choices.single { it.kind == ChoiceKind.SWITCH_OWN }.candidates)
    }
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

    @Test fun `Rare Candy evolves an eligible Basic directly into its Stage 2`() {
        val candy = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-125"), emptyMap())!!
        val basic = CardDefinition("rare-candy-basic", "Practice Basic", "Test", "B", category = Category.POKEMON,
            stage = Stage.BASIC, hp = 100)
        val stage2 = CardDefinition("rare-candy-stage2", "Practice Stage 2", "Test", "S", category = Category.POKEMON,
            stage = Stage.STAGE_TWO, evolvesFromName = "Practice Stage 1", rareCandyBasicEvolvesFromName = basic.canonicalName, hp = 180)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            candy.id -> candy; basic.id -> basic; stage2.id -> stage2; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun c(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val target = PokemonInstance(listOf(c("rare-target", basic.id)), enteredPlayTurn = 1)
        val state = GameState("rare-candy", 735, RngState(735), listOf(
            PlayerState(deck = List(5) { c("rare-a-$it") }, hand = listOf(c("rare-item", candy.id), c("rare-evolution", stage2.id)),
                prizes = List(6) { c("rare-prize-$it") }, active = target),
            PlayerState(deck = List(5) { c("rare-b-$it") }, prizes = List(6) { c("rare-other-prize-$it") },
                active = PokemonInstance(listOf(c("rare-opponent", "me01-076")), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val played = rules.apply(state, GameAction.PlayTrainer("rare-item"))
        assertEquals(Legality.Legal, played.legality)
        assertEquals(ChoiceKind.TRAINER_RARE_CANDY_TARGET, played.state.resolution!!.choices.single().kind)
        val targetChosen = rules.apply(played.state, GameAction.ChooseCards(listOf(target.id)))
        assertEquals(Legality.Legal, targetChosen.legality)
        val evolved = rules.apply(targetChosen.state, GameAction.ChooseCards(listOf("rare-evolution")))
        assertEquals(Legality.Legal, evolved.legality)
        assertEquals(stage2.id, evolved.state.players[0].active!!.top.definitionId)
        assertNull(evolved.state.pendingRareCandyTargetId)
        assertEquals(evolved.state, GameSession.replay(rules, state, evolved.state.history.map { it.action }))
    }

    @Test fun `Handheld Fan resolves after attack damage and moves the chosen Energy to its owner's Bench`() {
        val fan = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-150"), emptyMap())!!
        val attacker = CardDefinition("fan-attacker", "Fan Test Attacker", "Test", "A", category = Category.POKEMON,
            hp = 180, attacks = listOf(AttackDefinition("fan-hit", "Hit", emptyList(), listOf(Effect.Damage(30)))))
        val holder = CardDefinition("fan-holder", "Fan Holder", "Test", "H", category = Category.POKEMON, hp = 180)
        val bench = CardDefinition("fan-bench", "Fan Receiver", "Test", "B", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            fan.id -> fan; attacker.id -> attacker; holder.id -> holder; bench.id -> bench; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun c(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val energy = c("fan-energy")
        val state = GameState("fan-test", 736, RngState(736), listOf(
            PlayerState(deck = List(4) { c("fan-deck-a-$it") }, prizes = List(6) { c("fan-prize-a-$it") },
                active = PokemonInstance(listOf(c("fan-attacker-card", attacker.id)), energies = listOf(energy), enteredPlayTurn = 0)),
            PlayerState(deck = List(4) { c("fan-deck-b-$it") }, prizes = List(6) { c("fan-prize-b-$it") },
                active = PokemonInstance(listOf(c("fan-holder-card", holder.id)), tool = c("fan-tool", fan.id), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(c("fan-bench-card", bench.id)), enteredPlayTurn = 0)))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val triggered = rules.apply(state, GameAction.Attack("fan-hit"))
        assertEquals(Legality.Legal, triggered.legality)
        assertEquals(ChoiceKind.HANDHELD_FAN_ENERGY, triggered.state.resolution!!.choices.single().kind)
        assertEquals(1, triggered.state.currentPlayer)
        val energyChosen = rules.apply(triggered.state, GameAction.ChooseCards(listOf(energy.id)))
        assertEquals(Legality.Legal, energyChosen.legality)
        assertEquals(ChoiceKind.HANDHELD_FAN_TARGET, energyChosen.state.resolution!!.choices.single().kind)
        val moved = rules.apply(energyChosen.state, GameAction.ChooseCards(listOf("fan-bench-card")))
        assertEquals(Legality.Legal, moved.legality)
        assertTrue(moved.state.players[0].active!!.energies.isEmpty())
        assertEquals(listOf(energy.id), moved.state.players[1].bench.single().energies.map { it.id })
        assertEquals(moved.state, GameSession.replay(rules, state, moved.state.history.map { it.action }))
    }

    @Test fun `Battle Cage excludes opposing damage counter effects from Benched targets`() {
        val cage = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02-085"), emptyMap())!!
        val attacker = CardDefinition("cage-attacker", "Cage Test Attacker", "Test", "A", category = Category.POKEMON,
            hp = 180, attacks = listOf(AttackDefinition("cage-hit", "Mark", emptyList(), listOf(Effect.PlaceDamageCounter))))
        val target = CardDefinition("cage-target", "Cage Target", "Test", "T", category = Category.POKEMON, hp = 180)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            cage.id -> cage; attacker.id -> attacker; target.id -> target; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun c(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val state = GameState("cage-test", 737, RngState(737), listOf(
            PlayerState(deck = emptyList(), prizes = List(6) { c("cage-p0-$it") },
                active = PokemonInstance(listOf(c("cage-attacker-card", attacker.id)), enteredPlayTurn = 0)),
            PlayerState(deck = emptyList(), prizes = List(6) { c("cage-p1-$it") },
                active = PokemonInstance(listOf(c("cage-active", target.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(c("cage-bench", target.id)), enteredPlayTurn = 0)))
        ), 0, 0, turn = 3, phase = Phase.MAIN, stadium = c("cage-stadium", cage.id), stadiumOwner = 1)
        val result = rules.apply(state, GameAction.Attack("cage-hit"))
        assertEquals(Legality.Legal, result.legality)
        val choice = result.state.resolution!!.choices.single { it.kind == ChoiceKind.ATTACK_DAMAGE_COUNTER_TARGET }
        assertEquals(listOf("cage-active"), choice.candidates)
    }

    @Test fun `Area Zero expands a Tera Bench and discards down to five when replaced`() {
        val areaZero = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-131"), emptyMap())!!
        val cage = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02-085"), emptyMap())!!
        val tera = CardDefinition("area-zero-tera", "Tera Practice Pokémon", "Test", "T", category = Category.POKEMON,
            hp = 220, isTera = true)
        val basic = CardDefinition("area-zero-basic", "Bench Practice", "Test", "B", category = Category.POKEMON,
            hp = 60, stage = Stage.BASIC)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            areaZero.id -> areaZero; cage.id -> cage; tera.id -> tera; basic.id -> basic; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun c(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val state = GameState("area-zero", 738, RngState(738), listOf(
            PlayerState(deck = emptyList(), hand = listOf(c("area-basic", basic.id), c("area-cage", cage.id)),
                prizes = List(6) { c("area-p0-$it") }, active = PokemonInstance(listOf(c("area-tera", tera.id)), enteredPlayTurn = 0),
                bench = List(7) { PokemonInstance(listOf(c("area-bench-$it", basic.id)), enteredPlayTurn = 0) }),
            PlayerState(deck = emptyList(), prizes = List(6) { c("area-p1-$it") },
                active = PokemonInstance(listOf(c("area-opponent", "me01-076")), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN, stadium = c("area-stadium", areaZero.id), stadiumOwner = 0)
        assertEquals(8, rules.benchCapacity(state, 0))
        assertEquals(5, rules.benchCapacity(state, 1))
        val benched = rules.apply(state, GameAction.PlayBasic("area-basic"))
        assertEquals(Legality.Legal, benched.legality)
        assertEquals(8, benched.state.players[0].bench.size)
        val replaced = rules.apply(benched.state, GameAction.PlayTrainer("area-cage"))
        assertEquals(Legality.Legal, replaced.legality)
        assertEquals(5, replaced.state.players[0].bench.size)
        assertEquals(3, replaced.state.players[0].discard.count { it.definitionId == basic.id })
    }

    @Test fun `Slowking discards own deck top then uses a supported attack from a non rule box Pokemon`() {
        val slowking = TrainerCatalog.get("sv07-058")
        val seek = slowking.attacks.first { Effect.CopyAttackFromOwnDeckTop in it.effects }
        val source = CardDefinition("seek-source", "Source Pokémon", "Test", "S", category = Category.POKEMON,
            hp = 100, attacks = listOf(AttackDefinition("seek-source-hit", "Quick Hit", emptyList(), listOf(Effect.Damage(40)))))
        val psychic = CardDefinition("seek-psychic-energy", "Psychic Energy", "Test", "E", category = Category.ENERGY,
            type = EnergyType.PSYCHIC, basicEnergy = true)
        val target = CardDefinition("seek-target", "Training Target", "Test", "T", category = Category.POKEMON,
            hp = 120, type = EnergyType.COLORLESS)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            slowking.id -> slowking; source.id -> source; psychic.id -> psychic; target.id -> target
            else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun c(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val top = c("seek-top", source.id)
        val state = GameState("slowking-seek", 733, RngState(733), listOf(
            PlayerState(deck = listOf(top) + List(6) { c("seek-a-$it") }, prizes = List(6) { c("seek-p-$it") },
                active = PokemonInstance(listOf(c("seek-slowking", slowking.id)),
                    energies = listOf(c("seek-energy-1", psychic.id), c("seek-energy-2", psychic.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(8) { c("seek-b-$it") }, prizes = List(6) { c("seek-q-$it") },
                active = PokemonInstance(listOf(c("seek-target", target.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val choice = rules.apply(state, GameAction.Attack(seek.id))
        assertEquals(Legality.Legal, choice.legality)
        assertEquals(ChoiceKind.ATTACK_COPY_OWN_DECK_TOP, choice.state.resolution!!.choices.single().kind)
        assertTrue(choice.state.players[0].discard.any { it.id == top.id }, "the revealed card is discarded before choosing")
        assertFalse(choice.state.players[0].deck.any { it.id == top.id })
        val resolved = rules.apply(choice.state, GameAction.ChooseCards(listOf("seek-source-hit")))
        assertEquals(Legality.Legal, resolved.legality)
        assertEquals(40, resolved.state.players[1].active!!.damage,
            "events=${resolved.events}; copied=${resolved.state.pendingCopiedAttack}; history=${resolved.state.history.takeLast(2)}")
        assertNull(resolved.state.pendingCopiedAttackId)
    }

    @Test fun `Legacy Energy reduces one Prize when its Pokemon is knocked out by attack damage`() {
        val legacy = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-167"), emptyMap())!!
        val attacker = CardDefinition("legacy-test-attacker", "Heavy Hitter", "Test", "A", category = Category.POKEMON,
            hp = 200, attacks = listOf(AttackDefinition("legacy-test-hit", "Knockout", emptyList(), listOf(Effect.Damage(200)))))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            legacy.id -> legacy; attacker.id -> attacker; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun c(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val state = GameState("legacy-prize", 734, RngState(734), listOf(
            PlayerState(deck = List(5) { c("legacy-a-$it") }, prizes = List(6) { c("legacy-p-$it") },
                active = PokemonInstance(listOf(c("legacy-attacker", attacker.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(5) { c("legacy-b-$it") }, prizes = List(6) { c("legacy-q-$it") },
                active = PokemonInstance(listOf(c("legacy-victim", "me01-076")), energies = listOf(c("legacy-energy", legacy.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack("legacy-test-hit"))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(0, result.state.prizesOwed[0])
        assertTrue(result.state.legacyEnergyPrizeReductionUsed[1])
        assertTrue(result.events.any { it.type == "LegacyEnergyPrizeReduced" })
    }

    @Test fun `Smooth Coat flips once per damaging attack and prevents all damage on heads`() {
        val shield = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me04-073"), emptyMap())!!
        val attacker = CardDefinition("shield-test-attacker", "Attacker", "Test", "1", category = Category.POKEMON,
            hp = 100, attacks = listOf(AttackDefinition("shield-test-hit", "Hit", emptyList(), listOf(Effect.Damage(50)))))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            shield.id -> shield; attacker.id -> attacker; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun state(seed: Long): GameState {
            fun c(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
            val p0 = PlayerState(deck = List(10) { c("a-deck-$it") }, prizes = List(6) { c("a-prize-$it") },
                active = PokemonInstance(listOf(c("attacker", attacker.id)), enteredPlayTurn = 0))
            val p1 = PlayerState(deck = List(10) { c("b-deck-$it") }, prizes = List(6) { c("b-prize-$it") },
                active = PokemonInstance(listOf(c("shield", shield.id)), enteredPlayTurn = 0))
            return GameState("shield-$seed", seed, RngState(seed), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        }
        val headsSeed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) == 0 }
        val tailsSeed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) == 1 }
        val heads = rules.apply(state(headsSeed), GameAction.Attack("shield-test-hit"))
        val tails = rules.apply(state(tailsSeed), GameAction.Attack("shield-test-hit"))
        assertEquals(Legality.Legal, heads.legality)
        assertEquals(Legality.Legal, tails.legality)
        assertEquals(0, heads.state.players[1].active!!.damage)
        assertEquals(rules.preview(state(tailsSeed), "shield-test-hit")!!.damage, tails.state.players[1].active!!.damage)
    }

    @Test fun `delayed damage counters land on the same Pokemon during the opponent end turn checkup`() {
        val state = main(4).copy(currentPlayer = 1, players = main(4).players.mapIndexed { index, player ->
            if (index == 1) player.copy(active = field("delayed-target").copy(delayedDamageCounters = 1, delayedDamageTurn = 4)) else player
        })
        val transition = engine.nextTurn(state, 0)
        assertEquals(10, transition.state.players[1].active!!.damage)
        assertEquals(0, transition.state.players[1].active!!.delayedDamageCounters)
        assertTrue(transition.events.any { it.type == "DelayedDamageApplied" })
    }

    @Test fun `Snow Camouflage prevents an opponent trainer from switching out its protected active`() {
        val protector = CardDefinition("snow-protector", "Protected Pokémon", "Test", "1", category = Category.POKEMON,
            hp = 100, abilities = listOf(AbilityDefinition("snow-camouflage", "Snow Camouflage", "test",
                preventOpponentItemSupporterEffects = true)))
        val boss = CardDefinition("trainer-switch-opponent", "Switch Order", "Test", "1", category = Category.TRAINER,
            trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Switch(opponent = true))
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) {
                protector.id -> protector
                boss.id -> boss
                else -> TestCatalog.definitions.values.firstOrNull { it.id == id } ?: TrainerCatalog.get(id)
            }
        }
        val rules = RulesEngine(catalog)
        val start = main(4).let { state -> state.copy(players = state.players.mapIndexed { index, player ->
            if (index == 0) player.copy(hand = listOf(card("boss", boss.id)))
            else player.copy(active = field("protected", protector.id), bench = listOf(field("replacement")))
        }) }
        val result = rules.apply(start, GameAction.PlayTrainer("boss"))
        assertEquals(Legality.Legal, result.legality)
        assertEquals("protected", result.state.players[1].active!!.id)
        assertTrue(result.state.players[0].discard.any { it.id == "boss" })
        assertTrue(result.events.any { it.type == "TrainerEffectPrevented" })
    }

    @Test fun `evolution healing trigger removes damage and discards attached Energy from healed evolutions`() {
        val evolved = CardDefinition("evolution-healer", "Evolution Healer", "Test", "1", category = Category.POKEMON,
            stage = Stage.STAGE_ONE, evolvesFrom = "TEST-001", hp = 100,
            abilities = listOf(AbilityDefinition("team-heal", "Team Heal", "test", onEvolveHealAllEvolutionAndDiscardEnergy = true)))
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == evolved.id) evolved else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        }
        val rules = RulesEngine(catalog)
        val start = main(4).let { state -> state.copy(players = state.players.mapIndexed { index, player ->
            if (index == 0) player.copy(hand = listOf(card("evolution-healer", evolved.id)),
                active = field("evolving").copy(damage = 20, energies = listOf(card("active-energy", "TEST-004"))),
                bench = listOf(field("bench-evolution").copy(cards = listOf(card("bench-evolution"), card("bench-stage", "TEST-002")), damage = 30,
                    energies = listOf(card("bench-energy", "TEST-004"))))) else player
        }) }
        val result = rules.apply(start, GameAction.Evolve("evolution-healer", "evolving"))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(0, result.state.players[0].active!!.damage)
        assertTrue(result.state.players[0].active!!.energies.isEmpty())
        assertEquals(0, result.state.players[0].bench.single().damage)
        assertTrue(result.state.players[0].bench.single().energies.isEmpty())
        assertEquals(2, result.state.players[0].discard.count { it.definitionId == "TEST-004" })
    }

    @Test fun `Slight Shift moves an opponent Energy to a different opponent Pokemon`() {
        val attacker = CardDefinition("shift-attacker", "Shift Attacker", "Test", "1", category = Category.POKEMON,
            hp = 100, attacks = listOf(AttackDefinition("shift-energy", "Slight Shift", emptyList(),
                listOf(Effect.MoveEnergyBetweenOpponentPokemon))))
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == attacker.id) attacker else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        }
        val rules = RulesEngine(catalog)
        val start = main(4).let { state -> state.copy(players = state.players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = PokemonInstance(listOf(card("shift-attacker", attacker.id)), enteredPlayTurn = 0))
            else player.copy(active = field("energy-source").copy(energies = listOf(card("moved-energy", "TEST-004"))),
                bench = listOf(field("energy-target")))
        }) }
        val attack = rules.apply(start, GameAction.Attack("shift-energy"))
        assertEquals(Legality.Legal, attack.legality)
        assertEquals(ChoiceKind.ATTACK_OPPONENT_ENERGY, attack.state.resolution!!.choices.first().kind)
        val sourceChoice = rules.apply(attack.state, GameAction.ChooseCards(listOf("moved-energy")))
        assertEquals(Legality.Legal, sourceChoice.legality)
        assertEquals(ChoiceKind.ATTACK_OPPONENT_ENERGY_TARGET, sourceChoice.state.resolution!!.choices.first().kind)
        val moved = rules.apply(sourceChoice.state, GameAction.ChooseCards(listOf("energy-target")))
        assertEquals(Legality.Legal, moved.legality)
        assertTrue(moved.state.players[1].active!!.energies.isEmpty())
        assertEquals("moved-energy", moved.state.players[1].bench.single().energies.single().id)
    }

    @Test fun `Extended Damagriiigus transfers every damage counter from own Bench to opponent`() {
        val attacker = CardDefinition("counter-transfer-attacker", "Counter Transfer", "Test", "1", category = Category.POKEMON,
            hp = 100, attacks = listOf(AttackDefinition("counter-transfer", "Extended Damagriiigus", emptyList(),
                listOf(Effect.MoveOwnBenchDamageCountersToOpponent))))
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == attacker.id) attacker else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        }
        val rules = RulesEngine(catalog)
        val start = main(4).let { state -> state.copy(players = state.players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = PokemonInstance(listOf(card("counter-attacker", attacker.id)), enteredPlayTurn = 0),
                bench = listOf(field("counter-source").copy(damage = 30)))
            else player.copy(active = field("counter-target"))
        }) }
        val attack = rules.apply(start, GameAction.Attack("counter-transfer"))
        assertEquals(Legality.Legal, attack.legality)
        val source = rules.apply(attack.state, GameAction.ChooseCards(listOf("counter-source")))
        assertEquals(Legality.Legal, source.legality)
        val moved = rules.apply(source.state, GameAction.ChooseCards(listOf("counter-target")))
        assertEquals(Legality.Legal, moved.legality)
        assertEquals(0, moved.state.players[0].bench.single().damage)
        assertEquals(30, moved.state.players[1].active!!.damage)
    }

    @Test fun `Bastiodon Ancient Bulwark protects team from attacks with at most two attached Energy`() {
        val bastiodon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me05-062"), emptyMap())!!
        val attackerDef = CardDefinition("bulwark-attacker", "Test Attacker", "Test", "1", category = Category.POKEMON,
            hp = 300, attacks = listOf(AttackDefinition("bulwark-hit", "Hit", emptyList(), listOf(Effect.Damage(100)))))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            bastiodon.id -> bastiodon; attackerDef.id -> attackerDef; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun card(id: String, def: String) = CardInstance(id, def)
        fun state(attackerEnergy: Int, bastiodonOnBench: Boolean = true): GameState {
            val attacker = PokemonInstance(listOf(card("attacker", attackerDef.id)), energies = List(attackerEnergy) { card("energy-$it", "sv01-258") }, enteredPlayTurn = 0)
            val defender = PokemonInstance(listOf(card("target", "me01-077")), enteredPlayTurn = 0)
            val bastiodonPokemon = PokemonInstance(listOf(card("bastiodon", bastiodon.id)), enteredPlayTurn = 0)
            return GameState("bulwark", 12, RngState(12), listOf(
                PlayerState(deck = List(10) { card("d0-$it", "sv01-258") }, prizes = List(6) { card("p0-$it", "sv01-258") }, active = attacker),
                PlayerState(deck = List(10) { card("d1-$it", "sv01-258") }, prizes = List(6) { card("p1-$it", "sv01-258") }, active = defender,
                    bench = if (bastiodonOnBench) listOf(bastiodonPokemon) else emptyList())
            ), 0, 0, turn = 3, phase = Phase.MAIN)
        }
        val attack = attackerDef.attacks.single()
        val protected = state(2)
        assertEquals(0, rules.preview(protected, attack.id)!!.damage)
        val resolved = rules.apply(protected, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, resolved.legality)
        assertEquals(0, resolved.state.players[1].active!!.damage)
        assertEquals(100, rules.preview(state(3), attack.id)!!.damage)
        assertEquals(100, rules.preview(state(2, bastiodonOnBench = false), attack.id)!!.damage)
    }

    @Test fun `Beautifly Energy Straw reveals opposing hand and damages per Energy card`() {
        val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-013"), emptyMap())!!
        val energyStraw = compiled.attacks.single { it.name == "Energy Straw" }.copy(cost = emptyList())
        val beautifly = compiled.copy(attacks = listOf(energyStraw))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            beautifly.id -> beautifly; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val attacker = PokemonInstance(listOf(card("beautifly", beautifly.id)), enteredPlayTurn = 0)
        val target = field("target", "me01-077")
        val hand = listOf(card("energy-a", "sv01-258"), card("energy-b", "sv01-258"), card("other", "me01-001"))
        val state = GameState("energy-straw", 31, RngState(31), listOf(
            PlayerState(deck = List(10) { card("d0-$it", "sv01-258") }, prizes = List(6) { card("p0-$it", "sv01-258") }, active = attacker),
            PlayerState(deck = List(10) { card("d1-$it", "sv01-258") }, hand = hand, prizes = List(6) { card("p1-$it", "sv01-258") }, active = target)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(160, rules.preview(state, energyStraw.id)!!.damage)
        val result = rules.apply(state, GameAction.Attack(energyStraw.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(160, result.state.players[1].active!!.damage)
        assertTrue(result.events.any { it.type == "OpponentHandRevealed" })
    }

    @Test fun `Puppet Pull chooses any card and shuffles the deck after the attack`() {
        val row = CatalogRuleCompiler.rows.getValue("me05-034")
        val attack = CatalogRuleCompiler.compileAttack("puppet-pull", 0, row["attacks"]!!.jsonArray.first().jsonObject)!!.copy(cost = emptyList())
        val banette = CardDefinition("puppet-pull", "Banette", "Test", "1", category = Category.POKEMON, hp = 200, attacks = listOf(attack))
        val sought = card("sought", "me01-001")
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            banette.id -> banette; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("puppet-pull", 42, RngState(42), listOf(
            PlayerState(deck = listOf(sought) + List(9) { card("d0-$it", "sv01-258") }, prizes = List(6) { card("p0-$it", "sv01-258") },
                active = PokemonInstance(listOf(card("banette", banette.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("d1-$it", "sv01-258") }, prizes = List(6) { card("p1-$it", "sv01-258") }, active = field("target", "me01-077"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val hit = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, hit.legality)
        assertEquals(ChoiceKind.ATTACK_ANY_CARD_SEARCH, hit.state.resolution!!.choices.single().kind)
        val chosen = rules.apply(hit.state, GameAction.ChooseCards(listOf(sought.id)))
        assertEquals(Legality.Legal, chosen.legality)
        assertTrue(chosen.state.players[0].hand.any { it.id == sought.id })
        assertFalse(chosen.state.players[0].deck.any { it.id == sought.id })
    }

    @Test fun `Banette Hide n Sneak prevents attack effects but not damage`() {
        val banette = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me05-034"), emptyMap())!!
        val incoming = CardDefinition("effect-attacker", "Effect Attacker", "Test", "1", category = Category.POKEMON, hp = 200,
            attacks = listOf(AttackDefinition("status-hit", "Status Hit", emptyList(), listOf(Effect.Damage(50), Effect.StatusCondition(SpecialCondition.PARALYZED)))))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            banette.id -> banette; incoming.id -> incoming; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("hide-sneak", 45, RngState(45), listOf(
            PlayerState(deck = List(10) { card("d0-$it", "sv01-258") }, prizes = List(6) { card("p0-$it", "sv01-258") }, active = field("attacker", incoming.id)),
            PlayerState(deck = List(10) { card("d1-$it", "sv01-258") }, prizes = List(6) { card("p1-$it", "sv01-258") }, active = PokemonInstance(listOf(card("banette", banette.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val expectedDamage = rules.preview(state, "status-hit")!!.damage
        val result = rules.apply(state, GameAction.Attack("status-hit"))
        assertEquals(Legality.Legal, result.legality)
        assertTrue(expectedDamage > 0)
        assertEquals(expectedDamage, result.state.players[1].active!!.damage)
        assertFalse(SpecialCondition.PARALYZED in result.state.players[1].active!!.conditions)
    }

    @Test fun `Beedrill ex Rumbling Bees scales with Beedrill and ex copies only`() {
        val beedrill = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me04-003"), emptyMap())!!
        val basic = CardDefinition("basic-beedrill", "Beedrill", "Test", "2", category = Category.POKEMON, stage = Stage.BASIC, hp = 70)
        val target = CardDefinition("beedrill-target", "Target", "Test", "1", category = Category.POKEMON, hp = 300)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            beedrill.id -> beedrill; basic.id -> basic; target.id -> target; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val attack = beedrill.attacks.single().copy(cost = emptyList())
        val active = PokemonInstance(listOf(card("ex", beedrill.id)), enteredPlayTurn = 0)
        val bench = PokemonInstance(listOf(card("basic-bee", basic.id)), enteredPlayTurn = 0)
        val other = PokemonInstance(listOf(card("other", target.id)), enteredPlayTurn = 0)
        fun state(benchPokemon: List<PokemonInstance>) = GameState("rumbling-bees", 46, RngState(46), listOf(
            PlayerState(deck = List(10) { card("d0-$it", "sv01-258") }, prizes = List(6) { card("p0-$it", "sv01-258") }, active = active, bench = benchPokemon),
            PlayerState(deck = List(10) { card("d1-$it", "sv01-258") }, prizes = List(6) { card("p1-$it", "sv01-258") }, active = PokemonInstance(listOf(card("target", target.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(220, rules.preview(state(listOf(bench, other)), attack.id)!!.damage)
        assertEquals(110, rules.preview(state(listOf(other)), attack.id)!!.damage)
    }

    @Test fun `Bewear Hyper Lariat adds damage only when both coins are heads`() {
        val bewear = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-112"), emptyMap())!!
        val attack = bewear.attacks.single { it.name == "Hyper Lariat" }.copy(cost = emptyList())
        val attacker = PokemonInstance(listOf(card("bewear", bewear.id)), enteredPlayTurn = 0)
        val target = CardDefinition("coin-target", "Coin Target", "Test", "1", category = Category.POKEMON, hp = 300)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            bewear.id -> bewear.copy(attacks = listOf(attack)); target.id -> target; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("hyper-lariat", 47, RngState(47), listOf(
            PlayerState(deck = List(10) { card("d0-$it", "sv01-258") }, prizes = List(6) { card("p0-$it", "sv01-258") }, active = attacker),
            PlayerState(deck = List(10) { card("d1-$it", "sv01-258") }, prizes = List(6) { card("p1-$it", "sv01-258") }, active = PokemonInstance(listOf(card("target", target.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(100, rules.preview(state, attack.id, rolledHeads = 0)!!.damage)
        assertEquals(100, rules.preview(state, attack.id, rolledHeads = 1)!!.damage)
        assertEquals(200, rules.preview(state, attack.id, rolledHeads = 2)!!.damage)
        val uncertain = rules.preview(state, attack.id)!!
        assertTrue(uncertain.uncertain)
        assertEquals(100, uncertain.minDamage)
        assertEquals(200, uncertain.maxDamage)
    }

    @Test fun `Bewear Power Charger finds and attaches a Basic Energy of any type`() {
        val bewear = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06.5-053"), emptyMap())!!
        val attack = bewear.attacks.first().copy(cost = emptyList())
        val attacker = PokemonInstance(listOf(card("bewear", bewear.id)), enteredPlayTurn = 0)
        val water = card("water-from-deck", "sv01-258")
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            bewear.id -> bewear.copy(attacks = listOf(attack)); else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("power-charger", 48, RngState(48), listOf(
            PlayerState(deck = listOf(water) + List(9) { card("d0-$it", "sv01-258") }, prizes = List(6) { card("p0-$it", "sv01-258") }, active = attacker),
            PlayerState(deck = List(10) { card("d1-$it", "sv01-258") }, prizes = List(6) { card("p1-$it", "sv01-258") }, active = field("target", "me01-077"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val hit = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, hit.legality)
        assertTrue(water.id in hit.state.resolution!!.choices.first().candidates)
        val attached = rules.apply(hit.state, GameAction.ChooseCards(listOf(water.id)))
        assertEquals(Legality.Legal, attached.legality)
        assertTrue(attached.state.players[0].active!!.energies.any { it.id == water.id })
        assertFalse(attached.state.players[0].deck.any { it.id == water.id })
    }

    @Test fun `Bisharp Finishing Blow adds damage only to an already-damaged Active`() {
        val bisharp = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10.5b-065"), emptyMap())!!
        val attack = bisharp.attacks.last().copy(cost = emptyList())
        val attacker = PokemonInstance(listOf(card("bisharp", bisharp.id)), enteredPlayTurn = 0)
        val target = CardDefinition("finishing-target", "Target", "Test", "1", category = Category.POKEMON, hp = 300)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            bisharp.id -> bisharp.copy(attacks = listOf(attack)); target.id -> target; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun state(damage: Int) = GameState("finishing-blow", 49, RngState(49), listOf(
            PlayerState(deck = List(10) { card("d0-$it", "sv01-258") }, prizes = List(6) { card("p0-$it", "sv01-258") }, active = attacker),
            PlayerState(deck = List(10) { card("d1-$it", "sv01-258") }, prizes = List(6) { card("p1-$it", "sv01-258") }, active = PokemonInstance(listOf(card("target", target.id)), damage = damage, enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(60, rules.preview(state(0), attack.id)!!.damage)
        assertEquals(120, rules.preview(state(10), attack.id)!!.damage)
    }

    @Test fun `Black Kyurem Ice Age paralyzes only a Dragon Active`() {
        val blackKyurem = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-048"), emptyMap())!!
        val attack = blackKyurem.attacks.first().copy(cost = emptyList())
        val target = CardDefinition("ice-age-target", "Target", "Test", "1", category = Category.POKEMON, hp = 300)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            blackKyurem.id -> blackKyurem.copy(attacks = listOf(attack)); target.id -> target; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun state(type: EnergyType) = GameState("ice-age", 50, RngState(50), listOf(
            PlayerState(deck = List(10) { card("d0-$it", "sv01-258") }, prizes = List(6) { card("p0-$it", "sv01-258") },
                active = PokemonInstance(listOf(card("black-kyurem", blackKyurem.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("d1-$it", "sv01-258") }, prizes = List(6) { card("p1-$it", "sv01-258") },
                active = PokemonInstance(listOf(card("target", target.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN).let { game ->
            val typedCatalog = object : CardCatalog { override fun get(id: String) = if (id == target.id) target.copy(type = type) else catalog.get(id) }
            RulesEngine(typedCatalog) to game
        }
        for ((type, paralyzed) in listOf(EnergyType.DRAGON to true, EnergyType.WATER to false)) {
            val (typedRules, game) = state(type)
            val result = typedRules.apply(game, GameAction.Attack(attack.id))
            assertEquals(Legality.Legal, result.legality)
            assertEquals(paralyzed, SpecialCondition.PARALYZED in result.state.players[1].active!!.conditions)
            assertEquals(90, result.state.players[1].active!!.damage)
        }
    }

    @Test fun `Resolute Heart prevents attack knockout only at full HP`() {
        val pikachu = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-057"), emptyMap())!!
        val hit = AttackDefinition("huge-hit", "Huge Hit", emptyList(), listOf(Effect.Damage(500)))
        val attacker = CardDefinition("test-attacker", "Test Attacker", "Test", "1", category = Category.POKEMON,
            hp = 300, attacks = listOf(hit))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            pikachu.id -> pikachu; attacker.id -> attacker; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun state(damage: Int) = GameState("resolute", 12, RngState(12), listOf(
            PlayerState(deck = List(10) { card("d0-$it", "TEST-004") }, prizes = List(6) { card("a-prize$it", "TEST-004") },
                active = PokemonInstance(listOf(card("a", attacker.id)), enteredPlayTurn = 0), bench = listOf(field("a-bench"))),
            PlayerState(deck = List(10) { card("d1-$it", "TEST-004") }, prizes = List(6) { card("p$it", "TEST-004") },
                active = PokemonInstance(listOf(card("pikachu", pikachu.id)), damage = damage, enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val fullHp = state(0)
        val preview = rules.preview(fullHp, hit.id)!!
        assertEquals(10, preview.remainingHp)
        assertFalse(preview.knockout)
        assertEquals(0, preview.prizes)
        val survived = rules.apply(fullHp, GameAction.Attack(hit.id))
        assertEquals(Legality.Legal, survived.legality)
        assertEquals(190, survived.state.players[1].active!!.damage)
        assertTrue(survived.events.any { it.type == "KnockoutPrevented" })

        val damaged = state(10)
        assertTrue(rules.preview(damaged, hit.id)!!.knockout)
        val knockedOut = rules.apply(damaged, GameAction.Attack(hit.id))
        assertNull(knockedOut.state.players[1].active)
        assertEquals(2, knockedOut.state.prizesOwed[0])
    }

    @Test fun `Abra Teleporter shuffles its stack and attached cards then requires promotion`() {
        val abra = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-080"), emptyMap())!!
        val tool = TrainerCatalog.get("me01-166")
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            abra.id -> abra; tool.id -> tool; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val abraCard = card("abra", abra.id)
        val energy = card("abra-energy", "TEST-004")
        val toolCard = card("abra-tool", tool.id)
        val active = PokemonInstance(listOf(abraCard), energies = listOf(energy), enteredPlayTurn = 0, tool = toolCard)
        val bench = field("reserve")
        val player = PlayerState(deck = List(10) { card("deck$it", "TEST-004") }, prizes = List(6) { card("prize$it", "TEST-004") }, active = active, bench = listOf(bench))
        val opponent = PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it", "TEST-004") }, active = field("opponent"))
        val state = GameState("teleporter", 44, RngState(44), listOf(player, opponent), 0, 0, turn = 3, phase = Phase.MAIN)
        val used = rules.apply(state, GameAction.UseAbility(active.id, abra.abilities.single().id))
        assertEquals(Legality.Legal, used.legality)
        assertNull(used.state.players[0].active)
        assertEquals(Phase.PROMOTE, used.state.phase)
        assertEquals(1, used.state.players[0].deck.count { it.id == abraCard.id })
        assertEquals(1, used.state.players[0].deck.count { it.id == energy.id })
        assertEquals(1, used.state.players[0].deck.count { it.id == toolCard.id })
        assertEquals(Legality.Legal, rules.validate(used.state, GameAction.Promote(bench.id)))
        val promoted = rules.apply(used.state, GameAction.Promote(bench.id))
        assertEquals(bench.id, promoted.state.players[0].active!!.id)
        assertEquals(Phase.MAIN, promoted.state.phase)
    }

    @Test fun `Alakazam Strange Hacking moves any selected damage counter after confusing Active`() {
        val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-082"), emptyMap())!!
        val move = compiled.attacks.first().copy(cost = emptyList())
        val alakazam = compiled.copy(attacks = listOf(move))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            alakazam.id -> alakazam; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val attacker = PokemonInstance(listOf(card("alakazam", alakazam.id)), enteredPlayTurn = 0)
        val opponentActive = field("op-active").copy(damage = 20)
        val opponentBench = field("op-bench").copy(damage = 40)
        val players = listOf(
            PlayerState(deck = List(10) { card("deck$it", "TEST-004") }, prizes = List(6) { card("prize$it", "TEST-004") }, active = attacker),
            PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it", "TEST-004") }, active = opponentActive, bench = listOf(opponentBench))
        )
        val state = GameState("strange-hacking", 7, RngState(7), players, 0, 0, turn = 3, phase = Phase.MAIN)
        val hit = rules.apply(state, GameAction.Attack(move.id))
        assertEquals(Legality.Legal, hit.legality)
        assertTrue(SpecialCondition.CONFUSED in hit.state.players[1].active!!.conditions)
        assertEquals(ChoiceKind.ATTACK_MOVE_DAMAGE_SOURCE, hit.state.resolution!!.choices.first().kind)
        val source = rules.apply(hit.state, GameAction.ChooseCards(listOf(opponentActive.id)))
        assertEquals(ChoiceKind.ATTACK_MOVE_DAMAGE_DESTINATION, source.state.resolution!!.choices.first().kind)
        val moved = rules.apply(source.state, GameAction.ChooseCards(listOf(opponentBench.id)))
        assertEquals(10, moved.state.players[1].active!!.damage)
        assertEquals(50, moved.state.players[1].bench.single().damage)
        assertEquals(ChoiceKind.ATTACK_MOVE_DAMAGE_SOURCE, moved.state.resolution!!.choices.first().kind)
        val finished = rules.apply(moved.state, GameAction.ChooseCards(emptyList()))
        assertEquals(Legality.Legal, finished.legality)
        assertEquals(10, finished.state.players[1].active!!.damage)
        assertEquals(50, finished.state.players[1].bench.single().damage)
        assertEquals(4, finished.state.turn)
    }

    @Test fun `Alcremie Colorful Confection searches only Pokemon matching attached Basic Energy types`() {
        val alcremie = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-065"), emptyMap())!!
        val grassPokemon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-001"), emptyMap())!!
        val firePokemon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-019"), emptyMap())!!
        val grassEnergy = CardDefinition("basic-grass", "Basic Grass Energy", "Test", "E1", category = Category.ENERGY,
            type = EnergyType.GRASS, basicEnergy = true)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            alcremie.id -> alcremie; grassPokemon.id -> grassPokemon; firePokemon.id -> firePokemon; grassEnergy.id -> grassEnergy
            else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val attacker = PokemonInstance(listOf(card("alcremie", alcremie.id)), energies = listOf(card("grass", grassEnergy.id)), enteredPlayTurn = 0)
        val player = PlayerState(deck = listOf(card("bulba", grassPokemon.id), card("charmander", firePokemon.id)),
            prizes = List(6) { card("prize$it", "TEST-004") }, active = attacker)
        val opponent = PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") },
            prizes = List(6) { card("op-prize$it", "TEST-004") }, active = field("opponent"))
        val state = GameState("colorful-confection", 14, RngState(14), listOf(player, opponent), 0, 0, turn = 3, phase = Phase.MAIN)
        val attack = alcremie.attacks.first().copy(cost = emptyList())
        val attackerDef = alcremie.copy(attacks = listOf(attack))
        val rulesWithFreeAttack = RulesEngine(object : CardCatalog { override fun get(id: String) = if (id == alcremie.id) attackerDef else catalog.get(id) })
        val search = rulesWithFreeAttack.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, search.legality)
        assertEquals(listOf("bulba"), search.state.resolution!!.choices.first().candidates)
        val selected = rulesWithFreeAttack.apply(search.state, GameAction.ChooseCards(listOf("bulba")))
        assertTrue(selected.state.players[0].hand.any { it.id == "bulba" })
        assertTrue(selected.state.players[0].deck.none { it.id == "bulba" })
        assertTrue(selected.events.any { it.type == "PokemonSearchedByEnergyType" })
        assertEquals(4, selected.state.turn)
    }

    @Test fun `Alcremie ex Confectionary Gift heals a selected Pokemon once per turn`() {
        val alcremie = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-075"), emptyMap())!!
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == alcremie.id) alcremie else TrainerCatalog.get(id) }
        val rules = RulesEngine(catalog)
        val active = field("wounded").copy(damage = 50)
        val source = PokemonInstance(listOf(card("alcremie-ex", alcremie.id)), enteredPlayTurn = 0)
        val players = listOf(
            PlayerState(deck = List(10) { card("deck$it", "TEST-004") }, prizes = List(6) { card("prize$it", "TEST-004") }, active = active, bench = listOf(source)),
            PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it", "TEST-004") }, active = field("opponent"))
        )
        val state = GameState("confectionary-gift", 19, RngState(19), players, 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = alcremie.abilities.single()
        val healedChoice = rules.apply(state, GameAction.UseAbility(source.id, ability.id))
        assertEquals(Legality.Legal, healedChoice.legality)
        assertEquals(ChoiceKind.HEAL_TARGET, healedChoice.state.resolution!!.choices.first().kind)
        val healed = rules.apply(healedChoice.state, GameAction.ChooseCards(listOf(active.id)))
        assertEquals(20, healed.state.players[0].active!!.damage)
        assertTrue(healed.events.any { it.type == "DamageHealed" })
        assertIs<Legality.Illegal>(rules.validate(healed.state, GameAction.UseAbility(source.id, ability.id)))
    }

    @Test fun `Alolan Exeggutor Tropical Frenzy distributes any number of hand Basic Energy`() {
        val exeggutor = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-133"), emptyMap())!!
        val attack = exeggutor.attacks.first().copy(cost = emptyList())
        val exeggutorDef = exeggutor.copy(attacks = listOf(attack))
        val tank = CardDefinition("test-tank", "Test Tank", "Test", "T", category = Category.POKEMON, hp = 300)
        val grass = CardDefinition("test-basic-grass", "Basic Grass Energy", "Test", "E1", category = Category.ENERGY, type = EnergyType.GRASS, basicEnergy = true)
        val fire = CardDefinition("test-basic-fire", "Basic Fire Energy", "Test", "E2", category = Category.ENERGY, type = EnergyType.FIRE, basicEnergy = true)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            exeggutor.id -> exeggutorDef; tank.id -> tank; grass.id -> grass; fire.id -> fire; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val energy1 = card("grass-1", grass.id); val energy2 = card("fire-1", fire.id)
        val active = PokemonInstance(listOf(card("exeggutor", exeggutor.id)), enteredPlayTurn = 0)
        val reserve = field("reserve")
        val p0 = PlayerState(deck = List(10) { card("deck$it", "TEST-004") }, hand = listOf(energy1, energy2),
            prizes = List(6) { card("prize$it", "TEST-004") }, active = active, bench = listOf(reserve))
        val p1 = PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it", "TEST-004") },
            active = PokemonInstance(listOf(card("tank", tank.id)), enteredPlayTurn = 0))
        val state = GameState("tropical-frenzy", 23, RngState(23), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        var result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(ChoiceKind.ATTACK_HAND_ENERGY, result.state.resolution!!.choices.first().kind)
        result = rules.apply(result.state, GameAction.ChooseCards(listOf(energy1.id)))
        result = rules.apply(result.state, GameAction.ChooseCards(listOf(reserve.id)))
        assertEquals(1, result.state.players[0].bench.single().energies.size)
        result = rules.apply(result.state, GameAction.ChooseCards(listOf(energy2.id)))
        result = rules.apply(result.state, GameAction.ChooseCards(listOf(active.id)))
        result = rules.apply(result.state, GameAction.ChooseCards(emptyList()))
        assertEquals(listOf(energy2.id), result.state.players[0].active!!.energies.map { it.id })
        assertEquals(listOf(energy1.id), result.state.players[0].bench.single().energies.map { it.id })
        assertFalse(result.state.players[0].flags.manualEnergy)
        assertTrue(result.state.players[0].hand.none { it.id == energy1.id || it.id == energy2.id })
    }

    @Test fun `Altaria Humming Charge distributes up to two Basic Energy from deck to own Pokemon`() {
        val altaria = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-134"), emptyMap())!!
        val attack = altaria.attacks.first().copy(cost = emptyList())
        val altariaDef = altaria.copy(attacks = listOf(attack))
        val tank = CardDefinition("test-tank", "Test Tank", "Test", "T", category = Category.POKEMON, hp = 300)
        val grass = CardDefinition("test-basic-grass", "Basic Grass Energy", "Test", "E1", category = Category.ENERGY, type = EnergyType.GRASS, basicEnergy = true)
        val special = CardDefinition("test-special-energy", "Special Energy", "Test", "E2", category = Category.ENERGY, type = EnergyType.COLORLESS)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            altaria.id -> altariaDef; tank.id -> tank; grass.id -> grass; special.id -> special; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val energy1 = card("grass-1", grass.id); val energy2 = card("grass-2", grass.id); val specialEnergy = card("special", special.id)
        val reserve = field("reserve")
        val state = GameState("humming-charge", 31, RngState(31), listOf(
            PlayerState(deck = listOf(energy1, specialEnergy, energy2), prizes = List(6) { card("prize$it") },
                active = PokemonInstance(listOf(card("altaria", altaria.id)), enteredPlayTurn = 0), bench = listOf(reserve)),
            PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it") },
                active = PokemonInstance(listOf(card("op-active", tank.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        var result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(ChoiceKind.ATTACK_DECK_BASIC_ENERGY_ANY, result.state.resolution!!.choices.first().kind)
        result = rules.apply(result.state, GameAction.ChooseCards(listOf(energy1.id)))
        result = rules.apply(result.state, GameAction.ChooseCards(listOf(reserve.id)))
        result = rules.apply(result.state, GameAction.ChooseCards(listOf(energy2.id)))
        result = rules.apply(result.state, GameAction.ChooseCards(listOf("altaria")))
        result = rules.apply(result.state, GameAction.ChooseCards(emptyList()))
        assertEquals(listOf(energy2.id), result.state.players[0].active!!.energies.map { it.id })
        assertEquals(listOf(energy1.id), result.state.players[0].bench.single().energies.map { it.id })
        assertEquals(listOf(specialEnergy.id), result.state.players[0].deck.map { it.id })
        assertFalse(result.state.players[0].flags.manualEnergy)
        assertEquals(Phase.MAIN, result.state.phase)
    }

    @Test fun `Applin Find a Friend searches a Pokemon and shuffles to finish attack`() {
        val applin = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-126"), emptyMap())!!
        val attack = applin.attacks.first().copy(cost = emptyList())
        val applinDef = applin.copy(attacks = listOf(attack))
        val target = CardDefinition("test-target", "Test Target", "Test", "P", category = Category.POKEMON, hp = 80)
        val grass = CardDefinition("test-basic-grass", "Basic Grass Energy", "Test", "E", category = Category.ENERGY, type = EnergyType.GRASS, basicEnergy = true)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            applin.id -> applinDef; target.id -> target; grass.id -> grass; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val targetCard = card("target", target.id)
        val remaining = card("energy", grass.id)
        val state = GameState("find-a-friend", 37, RngState(37), listOf(
            PlayerState(deck = listOf(targetCard, remaining), prizes = List(6) { card("prize$it") },
                active = PokemonInstance(listOf(card("applin", applin.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it") },
                active = field("op-active"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attackResult = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(ChoiceKind.ATTACK_POKEMON_SEARCH, attackResult.state.resolution!!.choices.single().kind)
        val resolved = rules.apply(attackResult.state, GameAction.ChooseCards(listOf(targetCard.id)))
        assertTrue(resolved.state.players[0].hand.any { it.id == targetCard.id })
        assertEquals(listOf(remaining.id), resolved.state.players[0].deck.map { it.id })
        assertEquals(Phase.MAIN, resolved.state.phase)
    }

    @Test fun `Applin Nutrients heals the selected own Pokemon`() {
        val applin = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-138"), emptyMap())!!
        val attack = applin.attacks.first().copy(cost = emptyList())
        val applinDef = applin.copy(attacks = listOf(attack))
        val target = CardDefinition("test-target", "Test Target", "Test", "P", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            applin.id -> applinDef; target.id -> target; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val damaged = PokemonInstance(listOf(card("reserve", target.id)), damage = 50, enteredPlayTurn = 0)
        val state = GameState("nutrients", 41, RngState(41), listOf(
            PlayerState(deck = List(10) { card("deck$it", "TEST-004") }, prizes = List(6) { card("prize$it") },
                active = PokemonInstance(listOf(card("applin", applin.id)), enteredPlayTurn = 0), bench = listOf(damaged)),
            PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it") },
                active = field("op-active"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(ChoiceKind.ATTACK_HEAL_TARGET, result.state.resolution!!.choices.single().kind)
        val healed = rules.apply(result.state, GameAction.ChooseCards(listOf(damaged.id)))
        assertEquals(20, healed.state.players[0].bench.single().damage)
        assertEquals(Phase.MAIN, healed.state.phase)
    }

    @Test fun `Archaludon Coated Attack protects against Basic attackers of every type for opponent turn`() {
        val archaludon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02-075"), emptyMap())!!
        val attack = archaludon.attacks.first().copy(cost = emptyList())
        val archaludonDef = archaludon.copy(attacks = listOf(attack))
        val basicFire = CardDefinition("basic-fire", "Basic Fire", "Test", "F", category = Category.POKEMON, type = EnergyType.FIRE, stage = Stage.BASIC, hp = 100)
        val basicColorless = CardDefinition("basic-colorless", "Basic Colorless", "Test", "C", category = Category.POKEMON, type = EnergyType.COLORLESS, stage = Stage.BASIC, hp = 100)
        val evolution = CardDefinition("evolution", "Evolution", "Test", "E", category = Category.POKEMON, type = EnergyType.WATER, stage = Stage.STAGE_ONE, hp = 100)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            archaludon.id -> archaludonDef; basicFire.id -> basicFire; basicColorless.id -> basicColorless; evolution.id -> evolution
            else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("coated-attack", 43, RngState(43), listOf(
            PlayerState(deck = List(10) { card("deck$it", "TEST-004") }, prizes = List(6) { card("prize$it") },
                active = PokemonInstance(listOf(card("archaludon", archaludon.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it") },
                active = PokemonInstance(listOf(card("op-active", basicFire.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        val defended = result.state.players[0].active!!
        assertEquals(4, defended.preventBasicPokemonDamageTurn)
        val effects = EffectEngine(catalog)
        val attacker = PokemonInstance(listOf(card("fire", basicFire.id)), enteredPlayTurn = 0)
        val colorless = PokemonInstance(listOf(card("colorless", basicColorless.id)), enteredPlayTurn = 0)
        val evolved = PokemonInstance(listOf(card("evolved", evolution.id)), enteredPlayTurn = 0)
        assertEquals(0, effects.damage(attacker, defended, listOf(Effect.Damage(100)), turn = 4))
        assertEquals(0, effects.damage(colorless, defended, listOf(Effect.Damage(100)), turn = 4))
        assertEquals(100, effects.damage(evolved, defended, listOf(Effect.Damage(100)), turn = 4))
    }

    @Test fun `Archaludon Metal Bridge gives free retreat only to Pokemon with Metal Energy`() {
        val archaludon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-107"), emptyMap())!!
        val activeDef = CardDefinition("bridge-active", "Bridge Active", "Test", "A", category = Category.POKEMON,
            hp = 100, retreatCost = 3)
        val metalEnergy = CardDefinition("metal-energy-def", "Metal Energy", "Test", "M", category = Category.ENERGY, type = EnergyType.METAL)
        val metal = CardInstance("metal-energy", metalEnergy.id)
        val active = PokemonInstance(listOf(card("active", activeDef.id)), energies = listOf(metal), enteredPlayTurn = 0)
        val arch = PokemonInstance(listOf(card("arch", archaludon.id)), enteredPlayTurn = 0)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            archaludon.id -> archaludon; activeDef.id -> activeDef; metalEnergy.id -> metalEnergy; else -> TrainerCatalog.get(id)
        } })
        assertEquals(0, rules.retreatCost(active, listOf(active, arch)))
        assertEquals(3, rules.retreatCost(active.copy(energies = emptyList()), listOf(active.copy(energies = emptyList()), arch)))
    }

    @Test fun `Archaludon ex Assemble Alloy attaches selected discard energies to chosen Metal Pokemon`() {
        val archaludon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-130"), emptyMap())!!
        val duraludon = CardDefinition("test-duraludon", "Duraludon", "Test", "D", category = Category.POKEMON,
            stage = Stage.BASIC, type = EnergyType.METAL, hp = 130)
        val metalEnergy = CardDefinition("test-metal-energy", "Basic Metal Energy", "Test", "M", category = Category.ENERGY,
            type = EnergyType.METAL, basicEnergy = true)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            archaludon.id -> archaludon; duraludon.id -> duraludon; metalEnergy.id -> metalEnergy; else -> TrainerCatalog.get(id)
        } })
        val evolvedCard = card("archaludon-card", archaludon.id)
        val energies = listOf(card("discard-metal-1", metalEnergy.id), card("discard-metal-2", metalEnergy.id))
        val state = GameState("assemble-alloy", 91, RngState(91), listOf(
            PlayerState(deck = List(10) { card("deck-$it") }, hand = listOf(evolvedCard), discard = energies,
                prizes = List(6) { card("prize-$it") },
                active = PokemonInstance(listOf(card("duraludon", duraludon.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck-$it") }, prizes = List(6) { card("op-prize-$it") }, active = field("op"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val evolved = rules.apply(state, GameAction.Evolve(evolvedCard.id, "duraludon"))
        assertEquals(Legality.Legal, evolved.legality)
        assertEquals(ChoiceKind.EVOLUTION_METAL_ENERGY, evolved.state.resolution!!.choices.single().kind)
        val selected = rules.apply(evolved.state, GameAction.ChooseCards(energies.map { it.id }))
        assertEquals(ChoiceKind.EVOLUTION_METAL_ENERGY_TARGET, selected.state.resolution!!.choices.single().kind)
        val first = rules.apply(selected.state, GameAction.ChooseCards(listOf("duraludon")))
        val second = rules.apply(first.state, GameAction.ChooseCards(listOf("duraludon")))
        assertEquals(Legality.Legal, second.legality)
        assertEquals(2, second.state.players[0].active!!.energies.size)
        assertTrue(second.state.players[0].discard.isEmpty())
        assertEquals(Phase.MAIN, second.state.phase)
    }

    @Test fun `Archaludon ex Metal Defender removes weakness for the next opponent turn`() {
        val archaludon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-130"), emptyMap())!!
        val attack = archaludon.attacks.single().copy(cost = emptyList())
        val archDef = archaludon.copy(attacks = listOf(attack))
        val fireAttacker = CardDefinition("fire-attacker", "Fire Attacker", "Test", "F", category = Category.POKEMON,
            type = EnergyType.FIRE, hp = 300)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            archDef.id -> archDef; fireAttacker.id -> fireAttacker; else -> TrainerCatalog.get(id)
        } })
        val state = GameState("metal-defender", 92, RngState(92), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it") }, prizes = List(6) { card("a-prize-$it") },
                active = PokemonInstance(listOf(card("arch", archDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") },
                active = PokemonInstance(listOf(card("fire", fireAttacker.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attacked = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(4, attacked.state.players[0].active!!.weaknessSuppressedTurn)
        val damage = rules.effects.damage(attacked.state.players[1].active!!, attacked.state.players[0].active!!,
            listOf(Effect.Damage(100)), turn = 4)
        assertEquals(100, damage)
    }

    @Test fun `Archeops Ancient Wing devolves opponent evolution and clears effects`() {
        val archeops = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10.5w-051"), emptyMap())!!
        val base = CardDefinition("devolve-base", "Base Pokémon", "Test", "B", category = Category.POKEMON,
            stage = Stage.BASIC, hp = 100)
        val evolved = CardDefinition("devolve-evolution", "Evolution", "Test", "E", category = Category.POKEMON,
            stage = Stage.STAGE_ONE, hp = 180, evolvesFromName = base.canonicalName)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            archeops.id -> archeops; base.id -> base; evolved.id -> evolved; else -> TrainerCatalog.get(id)
        } })
        val target = PokemonInstance(listOf(card("target-base", base.id), card("target-evolution", evolved.id)), damage = 30,
            enteredPlayTurn = 0, conditions = setOf(SpecialCondition.POISONED), weaknessSuppressedTurn = 3,
            incomingDamageReduction = 20, damageReductionTurn = 3)
        val state = GameState("ancient-wing", 93, RngState(93), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it") }, prizes = List(6) { card("a-prize-$it") },
                active = PokemonInstance(listOf(card("archeops", archeops.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") },
                active = target, bench = listOf(field("op-bench")))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val used = rules.apply(state, GameAction.UseAbility("archeops", archeops.abilities.single().id))
        assertEquals(Legality.Legal, used.legality)
        assertEquals(ChoiceKind.ABILITY_DEVOLVE_TARGET, used.state.resolution!!.choices.single().kind)
        val devolved = rules.apply(used.state, GameAction.ChooseCards(listOf("target-base")))
        assertEquals(Legality.Legal, devolved.legality)
        assertEquals(listOf("devolve-base"), devolved.state.players[1].active!!.cards.map { it.definitionId })
        assertEquals(30, devolved.state.players[1].active!!.damage)
        assertTrue(devolved.state.players[1].active!!.conditions.isEmpty())
        assertEquals(-1, devolved.state.players[1].active!!.weaknessSuppressedTurn)
        assertEquals(1, devolved.state.players[1].hand.count { it.id == "target-evolution" })
        assertEquals(Phase.MAIN, devolved.state.phase)

        val lethalState = state.copy(gameId = "ancient-wing-knockout", players = state.players.mapIndexed { index, player ->
            if (index == 1) player.copy(active = target.copy(damage = 100)) else player
        })
        val lethalAbility = rules.apply(lethalState, GameAction.UseAbility("archeops", archeops.abilities.single().id))
        val knockout = rules.apply(lethalAbility.state, GameAction.ChooseCards(listOf("target-base")))
        assertEquals(Legality.Legal, knockout.legality)
        assertNull(knockout.state.players[1].active)
        assertEquals(Phase.SELECT_PRIZES, knockout.state.phase)
        assertEquals(1, knockout.state.prizesOwed[0])
    }

    @Test fun `Ariados Big Net taxes opponent evolution retreat and boosts String Bind`() {
        val ariados = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-005"), emptyMap())!!
        val attack = ariados.attacks.single().copy(cost = emptyList())
        val ariadosDef = ariados.copy(attacks = listOf(attack))
        val evolved = CardDefinition("taxed-evolution", "Taxed Evolution", "Test", "E", category = Category.POKEMON,
            stage = Stage.STAGE_ONE, hp = 200, retreatCost = 2)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            ariadosDef.id -> ariadosDef; evolved.id -> evolved; else -> TrainerCatalog.get(id)
        } })
        val ariadosPokemon = PokemonInstance(listOf(card("ariados", ariadosDef.id)), enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(card("evolved", evolved.id)), enteredPlayTurn = 0)
        assertEquals(3, rules.retreatCost(target, listOf(target), listOf(ariadosPokemon)))
        val state = GameState("big-net", 94, RngState(94), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it") }, prizes = List(6) { card("a-prize-$it") }, active = ariadosPokemon),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") }, active = target)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val preview = rules.preview(state, attack.id)!!
        assertEquals(100, preview.damage)
    }

    @Test fun `Armarouge counts Fire-powered Bench Pokemon and Crimson Blaster discards Fire Energy to hit Bench`() {
        val flameLegion = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me05-012"), emptyMap())!!
        val damageAttack = flameLegion.attacks.single().copy(cost = emptyList())
        val armarouge = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-034"), emptyMap())!!
        val blaster = armarouge.attacks.single { it.name == "Crimson Blaster" }.copy(cost = emptyList())
        val fireEnergy = CardDefinition("fire-energy", "Basic Fire Energy", "Test", "R", category = Category.ENERGY,
            type = EnergyType.FIRE, basicEnergy = true)
        val benchGrass = CardDefinition("fire-bench", "Fire Bench", "Test", "B", category = Category.POKEMON, hp = 100)
        val activeDef = CardDefinition("fire-attacker", "Fire Attacker", "Test", "A", category = Category.POKEMON,
            hp = 200, type = EnergyType.FIRE, attacks = listOf(blaster))
        val targetDef = CardDefinition("bench-target", "Bench Target", "Test", "T", category = Category.POKEMON,
            hp = 300, type = EnergyType.GRASS, weakness = EnergyType.FIRE)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            flameLegion.id -> flameLegion.copy(attacks = listOf(damageAttack)); armarouge.id -> activeDef; activeDef.id -> activeDef
            fireEnergy.id -> fireEnergy; benchGrass.id -> benchGrass; targetDef.id -> targetDef
            else -> TrainerCatalog.get(id)
        } }
        val fire1 = card("bench-fire-1", fireEnergy.id)
        val fire2 = card("bench-fire-2", fireEnergy.id)
        val fire3 = card("bench-fire-3", fireEnergy.id)
        val boostedState = GameState("flame-legion", 95, RngState(95), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it") }, prizes = List(6) { card("a-prize-$it") },
                active = PokemonInstance(listOf(card("flame-ariados", flameLegion.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(card("b1", benchGrass.id)), energies = listOf(fire1), enteredPlayTurn = 0),
                    PokemonInstance(listOf(card("b2", benchGrass.id)), energies = listOf(fire2, fire3), enteredPlayTurn = 0))),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") },
                active = field("op-active"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(120, RulesEngine(catalog).preview(boostedState, damageAttack.id)!!.damage)

        val rules = RulesEngine(catalog)
        val attacker = PokemonInstance(listOf(card("attacker", activeDef.id)), energies = listOf(fire1, fire2, fire3), enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(card("target", targetDef.id)), enteredPlayTurn = 0)
        val state = GameState("crimson-blaster", 96, RngState(96), listOf(
            PlayerState(deck = List(10) { card("c-deck-$it") }, prizes = List(6) { card("c-prize-$it") }, active = attacker),
            PlayerState(deck = List(10) { card("d-deck-$it") }, prizes = List(6) { card("d-prize-$it") },
                active = field("op-active"), bench = listOf(target))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(Legality.Legal, rules.validate(state, GameAction.Attack(blaster.id, target.id)))
        val result = rules.apply(state, GameAction.Attack(blaster.id, target.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(180, result.state.players[1].bench.single().damage)
        assertTrue(result.state.players[0].active!!.energies.isEmpty())
        assertEquals(3, result.state.players[0].discard.count { it.id.startsWith("bench-fire-") })
    }

    @Test fun `Aromatisse Scent Collection searches up to two Basic Psychic Energy and shuffles`() {
        val aromatisse = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me03-036"), emptyMap())!!
        val psychic = CardDefinition("psychic-basic", "Basic Psychic Energy", "Test", "P", category = Category.ENERGY,
            type = EnergyType.PSYCHIC, basicEnergy = true)
        val fire = CardDefinition("fire-basic", "Basic Fire Energy", "Test", "R", category = Category.ENERGY,
            type = EnergyType.FIRE, basicEnergy = true)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            aromatisse.id -> aromatisse; psychic.id -> psychic; fire.id -> fire; else -> TrainerCatalog.get(id)
        } })
        val psychic1 = card("psychic-1", psychic.id)
        val psychic2 = card("psychic-2", psychic.id)
        val fireCard = card("fire-1", fire.id)
        val state = GameState("scent-collection", 97, RngState(97), listOf(
            PlayerState(deck = listOf(psychic1, fireCard, psychic2) + List(7) { card("deck-$it") },
                prizes = List(6) { card("a-prize-$it") }, active = PokemonInstance(listOf(card("aromatisse", aromatisse.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck-$it") }, prizes = List(6) { card("op-prize-$it") }, active = field("op"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = aromatisse.abilities.single()
        val used = rules.apply(state, GameAction.UseAbility("aromatisse", ability.id))
        assertEquals(Legality.Legal, used.legality)
        val choice = used.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.ABILITY_SEARCH_BASIC_ENERGY_TO_HAND, choice.kind)
        assertEquals(setOf(psychic1.id, psychic2.id), choice.candidates.toSet())
        assertEquals(choice.candidates.toSet(), choice.visibleCards.toSet())
        val searched = rules.apply(used.state, GameAction.ChooseCards(choice.candidates))
        assertEquals(Legality.Legal, searched.legality)
        assertEquals(setOf(psychic1.id, psychic2.id), searched.state.players[0].hand.map { it.id }.toSet())
        assertFalse(searched.state.players[0].deck.any { it.id in choice.candidates })
        assertTrue(searched.events.any { it.type == "DeckShuffled" })
        assertIs<Legality.Illegal>(rules.validate(searched.state, GameAction.UseAbility("aromatisse", ability.id)))
    }

    @Test fun `Articuno Frigid Fluttering attaches only selected Basic Water Energy and shuffles`() {
        val articuno = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-032"), emptyMap())!!
        val attack = articuno.attacks.first().copy(cost = emptyList())
        val articunoDef = articuno.copy(attacks = listOf(attack))
        val water = CardDefinition("water-basic", "Basic Water Energy", "Test", "W", category = Category.ENERGY,
            type = EnergyType.WATER, basicEnergy = true)
        val fire = CardDefinition("fire-basic", "Basic Fire Energy", "Test", "R", category = Category.ENERGY,
            type = EnergyType.FIRE, basicEnergy = true)
        val water1 = card("water-1", water.id); val water2 = card("water-2", water.id); val fire1 = card("fire-1", fire.id)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            articunoDef.id -> articunoDef; water.id -> water; fire.id -> fire; else -> TrainerCatalog.get(id)
        } })
        val state = GameState("frigid-fluttering", 98, RngState(98), listOf(
            PlayerState(deck = listOf(water1, fire1, water2) + List(7) { card("deck-$it") },
                prizes = List(6) { card("a-prize-$it") }, active = PokemonInstance(listOf(card("articuno", articunoDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck-$it") }, prizes = List(6) { card("op-prize-$it") }, active = field("op"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attacked = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, attacked.legality)
        val choice = attacked.state.resolution!!.choices.single()
        assertEquals(setOf(water1.id, water2.id), choice.candidates.toSet())
        val attached = rules.apply(attacked.state, GameAction.ChooseCards(choice.candidates))
        assertEquals(Legality.Legal, attached.legality)
        assertEquals(setOf(water1.id, water2.id), attached.state.players[0].active!!.energies.map { it.id }.toSet())
        assertTrue(attached.state.players[0].deck.none { it.id in choice.candidates })
        assertTrue(attached.events.any { it.type == "DeckShuffled" })
    }

    @Test fun `Arven Greedent recovers up to two Arven's Sandwich cards when evolved`() {
        val greedent = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-159"), emptyMap())!!
        val skwovet = CardDefinition("arven-skwovet", "Arven's Skwovet", "Test", "S", category = Category.POKEMON,
            stage = Stage.BASIC, hp = 60)
        val sandwich = CardDefinition("arven-sandwich", "Arven's Sandwich", "Test", "T", category = Category.TRAINER)
        val otherTrainer = CardDefinition("other-trainer", "Other Trainer", "Test", "O", category = Category.TRAINER)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            greedent.id -> greedent; skwovet.id -> skwovet; sandwich.id -> sandwich; otherTrainer.id -> otherTrainer
            else -> TrainerCatalog.get(id)
        } })
        val evolutionCard = card("greedent-card", greedent.id)
        val sandwiches = listOf(card("sandwich-1", sandwich.id), card("sandwich-2", sandwich.id))
        val unrelated = card("unrelated", otherTrainer.id)
        val state = GameState("greedy-order", 99, RngState(99), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it") }, hand = listOf(evolutionCard), discard = sandwiches + unrelated,
                prizes = List(6) { card("a-prize-$it") }, active = PokemonInstance(listOf(card("skwovet", skwovet.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck-$it") }, prizes = List(6) { card("op-prize-$it") }, active = field("op"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val evolved = rules.apply(state, GameAction.Evolve(evolutionCard.id, "skwovet"))
        assertEquals(Legality.Legal, evolved.legality)
        val choice = evolved.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.EVOLUTION_RECOVER_NAMED_TRAINER, choice.kind)
        assertEquals(setOf("sandwich-1", "sandwich-2"), choice.candidates.toSet())
        val recovered = rules.apply(evolved.state, GameAction.ChooseCards(choice.candidates))
        assertEquals(Legality.Legal, recovered.legality)
        assertEquals(setOf("sandwich-1", "sandwich-2"), recovered.state.players[0].hand.map { it.id }.toSet())
        assertEquals(listOf("unrelated"), recovered.state.players[0].discard.map { it.id })
        assertEquals(Phase.MAIN, recovered.state.phase)
    }

    @Test fun `Arven Mabosstiff Vigorous Tackle loses its bonus after taking damage`() {
        val mabosstiff = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-139"), emptyMap())!!
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) =
            if (id == mabosstiff.id) mabosstiff else TrainerCatalog.get(id)
        })
        val attack = mabosstiff.attacks.first()
        fun state(damage: Int) = GameState("vigorous-tackle", 100, RngState(100), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it") }, prizes = List(6) { card("a-prize-$it") },
                active = PokemonInstance(listOf(card("mabosstiff", "sv10-139")), damage = damage, enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") }, active = field("op"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(150, rules.preview(state(0), attack.id)!!.damage)
        assertEquals(30, rules.preview(state(10), attack.id)!!.damage)
    }

    @Test fun `Arven Skwovet Gnaw Through discards the opponent Active Tool before damage`() {
        val skwovet = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-158"), emptyMap())!!
        val attack = skwovet.attacks.single().copy(cost = emptyList())
        val skwovetDef = skwovet.copy(attacks = listOf(attack))
        val activeDef = CardDefinition("tool-target", "Tool Target", "Test", "A", category = Category.POKEMON, hp = 100)
        val toolDef = CardDefinition("target-tool", "Target Tool", "Test", "T", category = Category.TRAINER,
            trainerType = TrainerType.TOOL, trainerEffect = TrainerEffect.RetreatReduction(1))
        val tool = card("tool", toolDef.id)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            skwovetDef.id -> skwovetDef; activeDef.id -> activeDef; toolDef.id -> toolDef; else -> TrainerCatalog.get(id)
        } })
        val state = GameState("gnaw-through", 101, RngState(101), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it") }, prizes = List(6) { card("a-prize-$it") },
                active = PokemonInstance(listOf(card("skwovet", skwovetDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") },
                active = PokemonInstance(listOf(card("target", activeDef.id)), tool = tool, enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertNull(result.state.players[1].active!!.tool)
        assertTrue(result.state.players[1].discard.any { it.id == tool.id })
        assertEquals(10, result.state.players[1].active!!.damage)
        assertTrue(result.events.any { it.type == "DefendingToolsDiscarded" })
    }

    @Test fun `Arven Toedscruel Pull switches in a chosen opposing Benched Pokemon`() {
        val toedscruel = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-110"), emptyMap())!!
        val pull = toedscruel.attacks.first().copy(cost = emptyList())
        val toedscruelDef = toedscruel.copy(attacks = listOf(pull))
        val activeDef = CardDefinition("pull-active", "Active Target", "Test", "A", category = Category.POKEMON, hp = 100)
        val benchDef = CardDefinition("pull-bench", "Bench Target", "Test", "B", category = Category.POKEMON, hp = 100)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            toedscruelDef.id -> toedscruelDef; activeDef.id -> activeDef; benchDef.id -> benchDef; else -> TrainerCatalog.get(id)
        } })
        fun card(id: String, definitionId: String) = CardInstance(id, definitionId)
        val benchPokemon = PokemonInstance(listOf(card("bench-pokemon", benchDef.id)), enteredPlayTurn = 0)
        val state = GameState("pull", 55, RngState(55), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it", "sv01-258") }, prizes = List(6) { card("a-prize-$it", "sv01-258") },
                active = PokemonInstance(listOf(card("toedscruel", toedscruelDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck-$it", "sv01-258") }, prizes = List(6) { card("b-prize-$it", "sv01-258") },
                active = PokemonInstance(listOf(card("active-pokemon", activeDef.id)), enteredPlayTurn = 0), bench = listOf(benchPokemon))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attackResult = rules.apply(state, GameAction.Attack(pull.id))
        assertEquals(Legality.Legal, attackResult.legality)
        assertEquals(ChoiceKind.SWITCH_OPPONENT, attackResult.state.resolution!!.choices.single().kind)
        val switched = rules.apply(attackResult.state, GameAction.ChooseCards(listOf(benchPokemon.id)))
        assertEquals(benchPokemon.id, switched.state.players[1].active!!.id)
        assertEquals("active-pokemon", switched.state.players[1].bench.single().id)
    }

    @Test fun `Audino Beckon recovers only a Supporter from discard`() {
        val audino = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-124"), emptyMap())!!
        val beckon = audino.attacks.first().copy(cost = emptyList())
        val audinoDef = audino.copy(attacks = listOf(beckon))
        val targetDef = CardDefinition("beckon-target", "Beckon Target", "Test", "T", category = Category.POKEMON, hp = 150)
        val supporterDef = CardDefinition("test-supporter", "Test Supporter", "Test", "S", category = Category.TRAINER, trainerType = TrainerType.SUPPORTER)
        val itemDef = CardDefinition("test-item", "Test Item", "Test", "I", category = Category.TRAINER, trainerType = TrainerType.ITEM)
        val supporter = CardInstance("supporter", supporterDef.id)
        val item = CardInstance("item", itemDef.id)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            audinoDef.id -> audinoDef; targetDef.id -> targetDef; supporterDef.id -> supporterDef; itemDef.id -> itemDef; else -> TrainerCatalog.get(id)
        } })
        fun card(id: String) = CardInstance(id, "sv01-258")
        val state = GameState("beckon", 65, RngState(65), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it") }, prizes = List(6) { card("a-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("audino", audinoDef.id)), enteredPlayTurn = 0), discard = listOf(supporter, item)),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("target", targetDef.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attackResult = rules.apply(state, GameAction.Attack(beckon.id))
        assertEquals(listOf(supporter.id), attackResult.state.resolution!!.choices.single().candidates)
        val recovered = rules.apply(attackResult.state, GameAction.ChooseCards(listOf(supporter.id)))
        assertTrue(recovered.state.players[0].hand.any { it.id == supporter.id })
        assertTrue(recovered.state.players[0].discard.any { it.id == item.id })
        assertEquals(0, recovered.state.players[1].active!!.damage)
    }

    @Test fun `Avalugg Iceberg Breaker discards six top cards and scales damage by Basic Water Energy`() {
        val avalugg = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me04-024"), emptyMap())!!
        val attack = avalugg.attacks.first().copy(cost = emptyList())
        val avaluggDef = avalugg.copy(attacks = listOf(attack))
        val targetDef = CardDefinition("iceberg-target", "Iceberg Target", "Test", "T", category = Category.POKEMON, hp = 300)
        val waterDef = CardDefinition("iceberg-water", "Water Energy", "Test", "W", category = Category.ENERGY, type = EnergyType.WATER, basicEnergy = true)
        val fillerDef = CardDefinition("iceberg-filler", "Filler", "Test", "F", category = Category.TRAINER, trainerType = TrainerType.ITEM)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            avaluggDef.id -> avaluggDef; targetDef.id -> targetDef; waterDef.id -> waterDef; fillerDef.id -> fillerDef; else -> TrainerCatalog.get(id)
        } })
        fun card(id: String) = CardInstance(id, fillerDef.id)
        val deck = listOf(CardInstance("water-1", waterDef.id), card("filler-1"), CardInstance("water-2", waterDef.id),
            card("filler-2"), card("filler-3"), card("filler-4"), CardInstance("water-7", waterDef.id))
        val state = GameState("iceberg", 75, RngState(75), listOf(
            PlayerState(deck = deck, prizes = List(6) { card("a-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("avalugg", avaluggDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("target", targetDef.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(120, result.state.players[1].active!!.damage)
        assertEquals(deck.take(6).map { it.id }.toSet(), result.state.players[0].discard.map { it.id }.toSet())
        assertEquals(listOf(card("water-7").id), result.state.players[0].deck.map { it.id })
    }

    @Test fun `Axew Gather Strength reveals and searches up to two Basic Energy cards`() {
        val axew = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10.5b-068"), emptyMap())!!
        val attack = axew.attacks.single().copy(cost = emptyList())
        val axewDef = axew.copy(attacks = listOf(attack))
        val waterDef = CardDefinition("axew-water", "Water Energy", "Test", "W", category = Category.ENERGY, type = EnergyType.WATER, basicEnergy = true)
        val fireDef = CardDefinition("axew-fire", "Fire Energy", "Test", "F", category = Category.ENERGY, type = EnergyType.FIRE, basicEnergy = true)
        val specialDef = CardDefinition("axew-special", "Special Energy", "Test", "S", category = Category.ENERGY, type = EnergyType.COLORLESS)
        val fillerDef = CardDefinition("axew-filler", "Filler", "Test", "X", category = Category.TRAINER, trainerType = TrainerType.ITEM)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            axewDef.id -> axewDef; waterDef.id -> waterDef; fireDef.id -> fireDef; specialDef.id -> specialDef; fillerDef.id -> fillerDef; else -> TrainerCatalog.get(id)
        } })
        val water = CardInstance("basic-water", waterDef.id)
        val fire = CardInstance("basic-fire", fireDef.id)
        val special = CardInstance("special-energy", specialDef.id)
        val filler = CardInstance("filler", fillerDef.id)
        fun card(id: String) = CardInstance(id, fillerDef.id)
        val state = GameState("axew-search", 81, RngState(81), listOf(
            PlayerState(deck = listOf(water, filler, fire, special), prizes = List(6) { card("a-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("axew", axewDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") },
                active = PokemonInstance(listOf(card("target")), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attacked = rules.apply(state, GameAction.Attack(attack.id))
        val choice = attacked.state.resolution!!.choices.single()
        assertEquals(setOf(water.id, fire.id), choice.candidates.toSet())
        assertEquals(2, choice.max)
        val searched = rules.apply(attacked.state, GameAction.ChooseCards(listOf(water.id, fire.id)))
        assertEquals(setOf(water.id, fire.id), searched.state.players[0].hand.map { it.id }.toSet())
        assertEquals(listOf(special.id, filler.id).toSet(), searched.state.players[0].deck.map { it.id }.toSet())
        assertTrue(searched.events.any { it.type == "DeckShuffled" })
    }

    @Test fun `Azelf Neurokinesis counts damage counters on every opposing Pokemon`() {
        val azelf = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-080"), emptyMap())!!
        val attack = azelf.attacks.single().copy(cost = emptyList())
        val azelfDef = azelf.copy(attacks = listOf(attack))
        val targetDef = CardDefinition("neuro-target", "Neuro Target", "Test", "T", category = Category.POKEMON, hp = 200)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            azelfDef.id -> azelfDef; targetDef.id -> targetDef; else -> TrainerCatalog.get(id)
        } })
        fun card(id: String) = CardInstance(id, "sv01-258")
        val state = GameState("neurokinesis", 91, RngState(91), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it") }, prizes = List(6) { card("a-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("azelf", azelfDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck-$it") }, prizes = List(6) { card("b-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("target-active", targetDef.id)), damage = 20, enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(CardInstance("target-bench", targetDef.id)), damage = 30, enteredPlayTurn = 0)))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(60, rules.preview(state, attack.id)!!.damage)
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(80, result.state.players[1].active!!.damage)
    }

    @Test fun `Azumarill ex Bubble Gathering can move multiple energies from other Pokemon in one turn`() {
        val azumarill = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-084"), emptyMap())!!
        val energyDef = CardDefinition("bubble-energy", "Fire Energy", "Test", "F", category = Category.ENERGY, type = EnergyType.FIRE, basicEnergy = true)
        val donorDef = CardDefinition("bubble-donor", "Donor", "Test", "D", category = Category.POKEMON, hp = 100)
        val targetDef = CardDefinition("bubble-target", "Target", "Test", "T", category = Category.POKEMON, hp = 100)
        val azumarillDef = azumarill.copy()
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            azumarillDef.id -> azumarillDef; energyDef.id -> energyDef; donorDef.id -> donorDef; targetDef.id -> targetDef; else -> TrainerCatalog.get(id)
        } })
        fun filler(id: String) = CardInstance(id, "sv01-258")
        val energy1 = CardInstance("donor-energy-1", energyDef.id)
        val energy2 = CardInstance("donor-energy-2", energyDef.id)
        val state = GameState("bubble-gathering", 93, RngState(93), listOf(
            PlayerState(deck = List(10) { filler("a-deck-$it") }, prizes = List(6) { filler("a-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("azumarill", azumarillDef.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(CardInstance("donor", donorDef.id)), energies = listOf(energy1, energy2), enteredPlayTurn = 0))),
            PlayerState(deck = List(10) { filler("b-deck-$it") }, prizes = List(6) { filler("b-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("target", targetDef.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = azumarill.abilities.single()
        var result = rules.apply(state, GameAction.UseAbility("azumarill", ability.id))
        assertEquals(Legality.Legal, result.legality)
        result = rules.apply(result.state, GameAction.ChooseCards(listOf(energy1.id)))
        assertTrue(result.state.players[0].active!!.energies.any { it.id == energy1.id })
        result = rules.apply(result.state, GameAction.UseAbility("azumarill", ability.id))
        assertEquals(Legality.Legal, result.legality)
        result = rules.apply(result.state, GameAction.ChooseCards(listOf(energy2.id)))
        assertEquals(setOf(energy1.id, energy2.id), result.state.players[0].active!!.energies.map { it.id }.toSet())
        assertTrue(result.state.players[0].flags.abilitiesUsed.none { it.contains(ability.name) })
        assertEquals(Legality.Illegal("Não há Energia em outro Pokémon para mover."), rules.validate(result.state, GameAction.UseAbility("azumarill", ability.id)))
    }

    @Test fun `Azumarill Glistening Bubbles grants Double-Edge for one Psychic only with Tera in play`() {
        val azumarill = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-074"), emptyMap())!!
        val tera = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-128"), emptyMap())!!
        val psychic = CardDefinition("tera-psychic", "Psychic Energy", "Test", "P", category = Category.ENERGY, type = EnergyType.PSYCHIC, basicEnergy = true)
        fun filler(id: String) = CardInstance(id, "sv01-258")
        val energy = CardInstance("one-psychic", psychic.id)
        val active = PokemonInstance(listOf(CardInstance("azumarill", azumarill.id)), energies = listOf(energy), enteredPlayTurn = 0)
        val enemyDef = CardDefinition("tera-cost-enemy", "Enemy", "Test", "E", category = Category.POKEMON, hp = 300)
        val withTera = GameState("glistening-tera", 97, RngState(97), listOf(
            PlayerState(deck = List(10) { filler("a-deck-$it") }, prizes = List(6) { filler("a-prize-$it") }, active = active,
                bench = listOf(PokemonInstance(listOf(CardInstance("tera-pokemon", tera.id)), enteredPlayTurn = 0))),
            PlayerState(deck = List(10) { filler("b-deck-$it") }, prizes = List(6) { filler("b-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("enemy", enemyDef.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val extended = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            azumarill.id -> azumarill; tera.id -> tera; psychic.id -> psychic; enemyDef.id -> enemyDef; else -> TrainerCatalog.get(id)
        } })
        val attack = azumarill.attacks.single()
        assertEquals(Legality.Legal, extended.validate(withTera, GameAction.Attack(attack.id)))
        val withoutTera = withTera.copy(players = withTera.players.toMutableList().also { it[0] = it[0].copy(bench = emptyList()) })
        assertTrue(extended.validate(withoutTera, GameAction.Attack(attack.id)) is Legality.Illegal)
    }

    @Test fun `Banette Cursed Words lets the defending player choose three cards to shuffle`() {
        val banette = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-091"), emptyMap())!!
        val attack = banette.attacks.first().copy(cost = emptyList())
        val banetteDef = banette.copy(attacks = listOf(attack))
        val targetDef = CardDefinition("cursed-target", "Target", "Test", "T", category = Category.POKEMON, hp = 300)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            banetteDef.id -> banetteDef; targetDef.id -> targetDef; else -> TrainerCatalog.get(id)
        } })
        fun filler(id: String) = CardInstance(id, "sv01-258")
        val selected = List(3) { CardInstance("opponent-hand-$it", "sv01-258") }
        val untouched = List(2) { CardInstance("untouched-hand-$it", "sv01-258") }
        val state = GameState("cursed-words", 99, RngState(99), listOf(
            PlayerState(deck = List(10) { filler("a-deck-$it") }, prizes = List(6) { filler("a-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("banette", banetteDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { filler("b-deck-$it") }, hand = selected + untouched, prizes = List(6) { filler("b-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("target", targetDef.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attacked = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(1, rules.actingPlayer(attacked.state))
        val choice = attacked.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.OPPONENT_SHUFFLES_OWN_HAND, choice.kind)
        assertEquals((selected + untouched).map { it.id }.toSet(), choice.candidates.toSet())
        val result = rules.apply(attacked.state, GameAction.ChooseCards(selected.map { it.id }))
        assertEquals(Legality.Legal, result.legality)
        val opponent = result.state.players[1]
        assertEquals(2, opponent.hand.count { it.id in untouched.map(CardInstance::id) })
        assertEquals(0, opponent.hand.count { it.id in selected.map(CardInstance::id) })
        assertEquals(3, (opponent.deck + opponent.hand).count { it.id in selected.map(CardInstance::id) })
        assertEquals(1, result.state.currentPlayer)
        assertTrue(result.events.any { it.type == "OpponentHandShuffled" })
    }

    @Test fun `Barbaracle Stone Arms attaches a Fighting Basic Energy to a chosen Fighting Pokemon`() {
        val barbaracle = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me03-043"), emptyMap())!!
        val energyDef = CardDefinition("stone-arms-energy", "Fighting Energy", "Test", "F", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
        val targetDef = CardDefinition("stone-arms-target", "Fighting Target", "Test", "T", category = Category.POKEMON, hp = 120, type = EnergyType.FIGHTING)
        val enemyDef = CardDefinition("stone-arms-enemy", "Enemy", "Test", "E", category = Category.POKEMON, hp = 100)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            barbaracle.id -> barbaracle; energyDef.id -> energyDef; targetDef.id -> targetDef; enemyDef.id -> enemyDef; else -> TrainerCatalog.get(id)
        } })
        fun filler(id: String) = CardInstance(id, "sv01-258")
        val energy = CardInstance("fighting-energy", energyDef.id)
        val target = PokemonInstance(listOf(CardInstance("fighting-target", targetDef.id)), enteredPlayTurn = 0)
        val state = GameState("stone-arms", 103, RngState(103), listOf(
            PlayerState(deck = List(10) { filler("a-deck-$it") }, hand = listOf(energy), prizes = List(6) { filler("a-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("barbaracle", barbaracle.id)), enteredPlayTurn = 0), bench = listOf(target)),
            PlayerState(deck = List(10) { filler("b-deck-$it") }, prizes = List(6) { filler("b-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("enemy", enemyDef.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = barbaracle.abilities.single()
        val used = rules.apply(state, GameAction.UseAbility("barbaracle", ability.id))
        assertEquals(Legality.Legal, used.legality)
        val chooseEnergy = rules.apply(used.state, GameAction.ChooseCards(listOf(energy.id)))
        assertEquals(ChoiceKind.ABILITY_ATTACH_ENERGY_TARGET, chooseEnergy.state.resolution!!.choices.single().kind)
        val attached = rules.apply(chooseEnergy.state, GameAction.ChooseCards(listOf(target.id)))
        assertTrue(attached.state.players[0].hand.none { it.id == energy.id })
        assertNotNull(attached.state.players[0].bench.singleOrNull(), attached.state.players[0].toString())
        assertTrue(attached.state.players[0].bench.single().energies.any { it.id == energy.id })
        assertEquals(Legality.Illegal("Esta habilidade já foi usada neste turno."), rules.validate(attached.state, GameAction.UseAbility("barbaracle", ability.id)))
    }

    @Test fun `Blissey ex Happy Switch moves only a Basic Energy between different own Pokemon once`() {
        val blissey = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-134"), emptyMap())!!
        val basic = CardDefinition("happy-basic", "Fire Energy", "Test", "B", category = Category.ENERGY, type = EnergyType.FIRE, basicEnergy = true)
        val special = CardDefinition("happy-special", "Special Energy", "Test", "S", category = Category.ENERGY)
        val targetDef = CardDefinition("happy-target", "Target", "Test", "T", category = Category.POKEMON, hp = 100)
        val enemyDef = CardDefinition("happy-enemy", "Enemy", "Test", "E", category = Category.POKEMON, hp = 100)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            blissey.id -> blissey; basic.id -> basic; special.id -> special; targetDef.id -> targetDef; enemyDef.id -> enemyDef
            else -> TrainerCatalog.get(id)
        } })
        fun filler(id: String) = CardInstance(id, "sv01-258")
        val basicCard = CardInstance("happy-energy", basic.id)
        val specialCard = CardInstance("happy-special-energy", special.id)
        val source = PokemonInstance(listOf(CardInstance("happy-blissey", blissey.id)), energies = listOf(basicCard, specialCard), enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(CardInstance("happy-target-card", targetDef.id)), enteredPlayTurn = 0)
        val state = GameState("happy-switch", 211, RngState(211), listOf(
            PlayerState(deck = List(10) { filler("happy-deck-$it") }, prizes = List(6) { filler("happy-prize-$it") }, active = source, bench = listOf(target)),
            PlayerState(deck = List(10) { filler("happy-enemy-deck-$it") }, prizes = List(6) { filler("happy-enemy-prize-$it") }, active = PokemonInstance(listOf(CardInstance("happy-enemy-card", enemyDef.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = blissey.abilities.single()
        val begun = rules.apply(state, GameAction.UseAbility("happy-blissey", ability.id))
        assertEquals(Legality.Legal, begun.legality)
        val energyChoice = begun.state.resolution!!.choices.single()
        assertEquals(listOf(basicCard.id), energyChoice.candidates)
        val chooseBasic = rules.apply(begun.state, GameAction.ChooseCards(listOf(basicCard.id)))
        assertEquals(listOf(target.id), chooseBasic.state.resolution!!.choices.single().candidates)
        val moved = rules.apply(chooseBasic.state, GameAction.ChooseCards(listOf(target.id)))
        assertEquals(Legality.Legal, moved.legality)
        assertTrue(moved.state.players[0].active!!.energies.any { it.id == specialCard.id })
        assertFalse(moved.state.players[0].active!!.energies.any { it.id == basicCard.id })
        assertTrue(moved.state.players[0].bench.single().energies.any { it.id == basicCard.id })
        assertEquals(Legality.Illegal("Esta habilidade já foi usada neste turno."), rules.validate(moved.state, GameAction.UseAbility("happy-blissey", ability.id)))
    }

    @Test fun `Bloodmoon Ursaluna Battle-Hardened attaches up to two Fighting Basic Energy when benched`() {
        val ursaluna = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06.5-025"), emptyMap())!!
        val ursalunaForTest = ursaluna.copy(attacks = ursaluna.attacks.map { it.copy(cost = emptyList()) })
        val fire = CardDefinition("urs-fire", "Fighting Energy", "Test", "F", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
        val water = CardDefinition("urs-water", "Water Energy", "Test", "W", category = Category.ENERGY, type = EnergyType.WATER, basicEnergy = true)
        val special = CardDefinition("urs-special", "Special Energy", "Test", "S", category = Category.ENERGY, type = EnergyType.FIGHTING)
        val filler = CardInstance("urs-filler", "sv01-258")
        val energies = listOf(CardInstance("urs-f1", fire.id), CardInstance("urs-f2", fire.id), CardInstance("urs-w", water.id), CardInstance("urs-s", special.id))
        val enemy = PokemonInstance(listOf(CardInstance("urs-enemy", "sv01-258")), enteredPlayTurn = 0)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            ursaluna.id -> ursalunaForTest; fire.id -> fire; water.id -> water; special.id -> special; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("ursa-attach", 311, RngState(311), listOf(
            PlayerState(deck = List(8) { filler.copy(id = "ursa-deck-$it") }, hand = listOf(CardInstance("ursa-card", ursaluna.id)) + energies,
                prizes = List(6) { filler.copy(id = "ursa-prize-$it") }, active = PokemonInstance(listOf(filler.copy(id = "ursa-active")), enteredPlayTurn = 0)),
            PlayerState(deck = List(8) { filler.copy(id = "ursa-opp-deck-$it") }, prizes = List(6) { filler.copy(id = "ursa-opp-prize-$it") }, active = enemy)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val played = rules.apply(state, GameAction.PlayBasic("ursa-card"))
        assertEquals(Legality.Legal, played.legality)
        val choice = played.state.resolution!!.choices.single()
        assertEquals(setOf("urs-f1", "urs-f2"), choice.candidates.toSet())
        assertEquals(2, choice.max)
        val attached = rules.apply(played.state, GameAction.ChooseCards(listOf("urs-f1", "urs-f2")))
        assertEquals(Legality.Legal, attached.legality)
        assertEquals(setOf("urs-f1", "urs-f2"), attached.state.players[0].bench.single().energies.map { it.id }.toSet())
        val attack = ursalunaForTest.attacks.single { it.name == "Mad Bite" }
        val damagedEnemy = enemy.copy(damage = 30)
        val damageState = state.copy(players = state.players.mapIndexed { index, player -> if (index == 1) player.copy(active = damagedEnemy) else player.copy(active = PokemonInstance(listOf(CardInstance("ursa-active", ursaluna.id)), enteredPlayTurn = 0)) })
        assertEquals(190, rules.preview(damageState, attack.id)?.damage)
    }

    @Test fun `Drakloak Recon Directive keeps one of the top two and bottoms the other once per turn`() {
        val drakloak = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-129"), emptyMap())!!
        val cards = listOf("top-a", "top-b", "deck-c").map { CardInstance(it, "sv01-258") }
        val active = PokemonInstance(listOf(CardInstance("drakloak", drakloak.id)), enteredPlayTurn = 0)
        val filler = CardInstance("top-enemy", "sv01-258")
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = if (id == drakloak.id) drakloak else TrainerCatalog.get(id) })
        val state = GameState("drakloak-top-two", 312, RngState(312), listOf(
            PlayerState(deck = cards, prizes = List(6) { filler.copy(id = "drak-prize-$it") }, active = active),
            PlayerState(deck = List(8) { filler.copy(id = "drak-opp-deck-$it") }, prizes = List(6) { filler.copy(id = "drak-opp-prize-$it") }, active = filler.let { PokemonInstance(listOf(it), enteredPlayTurn = 0) })
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = drakloak.abilities.single()
        val looked = rules.apply(state, GameAction.UseAbility(active.id, ability.id))
        assertEquals(Legality.Legal, looked.legality)
        assertEquals(setOf("top-a", "top-b"), looked.state.resolution!!.choices.single().visibleCards.toSet())
        val selected = rules.apply(looked.state, GameAction.ChooseCards(listOf("top-b")))
        assertEquals(listOf("deck-c", "top-a"), selected.state.players[0].deck.map { it.id })
        assertEquals("top-b", selected.state.players[0].hand.single().id)
        assertTrue(ability.name in selected.state.players[0].flags.abilitiesUsed)
    }

    @Test fun `Eelektrik Dynamotor attaches only Basic Lightning Energy to a Benched Pokemon`() {
        val eel = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-060"), emptyMap())!!
        val lightning = CardDefinition("dyn-l", "Lightning Energy", "Test", "L", category = Category.ENERGY, type = EnergyType.LIGHTNING, basicEnergy = true)
        val water = CardDefinition("dyn-w", "Water Energy", "Test", "W", category = Category.ENERGY, type = EnergyType.WATER, basicEnergy = true)
        val fillerDefinition = CardDefinition("dyn-pokemon", "Test Pokémon", "Test", "P", category = Category.POKEMON, hp = 100)
        val filler = CardInstance("dyn-filler", fillerDefinition.id)
        val energy = CardInstance("dyn-energy", lightning.id)
        val wrongEnergy = CardInstance("dyn-wrong", water.id)
        val eelPokemon = PokemonInstance(listOf(CardInstance("dyn-eel", eel.id)), enteredPlayTurn = 0)
        val bench = PokemonInstance(listOf(CardInstance("dyn-bench", fillerDefinition.id)), enteredPlayTurn = 0)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            eel.id -> eel; lightning.id -> lightning; water.id -> water; fillerDefinition.id -> fillerDefinition; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("dynamotor", 991, RngState(991), listOf(
            PlayerState(deck = listOf(filler.copy(id = "dyn-own-deck")), discard = listOf(energy, wrongEnergy), active = eelPokemon, bench = listOf(bench)),
            PlayerState(deck = listOf(filler.copy(id = "dyn-opp-deck")), active = PokemonInstance(listOf(filler.copy(id = "dyn-opp")), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = eel.abilities.single()
        val selectEnergy = rules.apply(state, GameAction.UseAbility(eelPokemon.id, ability.id))
        assertEquals(listOf(energy.id), selectEnergy.state.resolution!!.choices.single().candidates)
        val selectTarget = rules.apply(selectEnergy.state, GameAction.ChooseCards(listOf(energy.id)))
        assertEquals(listOf(bench.id), selectTarget.state.resolution!!.choices.single().candidates)
        val attached = rules.apply(selectTarget.state, GameAction.ChooseCards(listOf(bench.id)))
        assertEquals(Legality.Legal, attached.legality)
        assertNotNull(attached.state.players[0].bench.singleOrNull(), attached.state.players[0].toString())
        assertTrue(attached.state.players[0].bench.single().energies.any { it.id == energy.id })
        assertTrue(wrongEnergy in attached.state.players[0].discard)
    }

    @Test fun `Ionos Bellibolt may attach Lightning Energy repeatedly only to Ionos Pokemon`() {
        val bellibolt = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-053"), emptyMap())!!
        val ionoPokemon = CardDefinition("iono-partner", "Iono's Wattrel", "Test", "P", category = Category.POKEMON, hp = 100)
        val otherPokemon = CardDefinition("other-partner", "Wattrel", "Test", "Q", category = Category.POKEMON, hp = 100)
        val lightning = CardDefinition("iono-lightning", "Lightning Energy", "Test", "L", category = Category.ENERGY, type = EnergyType.LIGHTNING, basicEnergy = true)
        val ionoBench = PokemonInstance(listOf(card("iono-wattrel", ionoPokemon.id)), enteredPlayTurn = 0)
        val otherBench = PokemonInstance(listOf(card("ordinary-wattrel", otherPokemon.id)), enteredPlayTurn = 0)
        val energies = listOf(card("iono-energy-a", lightning.id), card("iono-energy-b", lightning.id))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            bellibolt.id -> bellibolt; ionoPokemon.id -> ionoPokemon; otherPokemon.id -> otherPokemon; lightning.id -> lightning; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val active = PokemonInstance(listOf(card("iono-bellibolt", bellibolt.id)), enteredPlayTurn = 0)
        var state = GameState("iono-energy", 337, RngState(337), listOf(
            PlayerState(deck = listOf(card("iono-deck", lightning.id)), hand = energies, prizes = List(6) { card("iono-prize$it") }, active = active,
                bench = listOf(ionoBench, otherBench)),
            PlayerState(deck = listOf(card("iono-opp-deck", lightning.id)), prizes = List(6) { card("iono-opp-prize$it") }, active = otherBench.copy(cards = listOf(card("iono-opp-active", otherPokemon.id))))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = bellibolt.abilities.single()
        repeat(2) { index ->
            val energy = energies[index]
            val selectEnergy = rules.apply(state, GameAction.UseAbility(active.id, ability.id))
            assertEquals(Legality.Legal, selectEnergy.legality)
            val selectTarget = rules.apply(selectEnergy.state, GameAction.ChooseCards(listOf(energy.id)))
            assertEquals(listOf(active.id, ionoBench.id), selectTarget.state.resolution!!.choices.single().candidates)
            state = rules.apply(selectTarget.state, GameAction.ChooseCards(listOf(ionoBench.id))).state
            if (index == 0) assertEquals(Legality.Legal, rules.validate(state, GameAction.UseAbility(active.id, ability.id)))
        }
        assertEquals(2, state.players[0].bench.first { it.id == ionoBench.id }.energies.size)
        assertTrue(state.players[0].bench.first { it.id == otherBench.id }.energies.isEmpty())
    }

    @Test fun `Piplup Call for Support selects only a Supporter and adds it to hand`() {
        val raw = CatalogRuleCompiler.rows.getValue("me02-027")
        val compiledAttack = CatalogRuleCompiler.compileAttack("piplup-support", 0, raw["attacks"]!!.jsonArray.first().jsonObject)!!.copy(cost = emptyList())
        val piplup = CardDefinition("piplup-support", "Piplup", "Test", "1", category = Category.POKEMON,
            hp = 100, attacks = listOf(compiledAttack))
        val supporter = CardDefinition("supporter-choice", "Professor's Research", "Test", "2", category = Category.TRAINER, trainerType = TrainerType.SUPPORTER)
        val item = CardDefinition("item-choice", "Potion", "Test", "3", category = Category.TRAINER, trainerType = TrainerType.ITEM)
        val dummy = CardDefinition("support-target", "Target", "Test", "4", category = Category.POKEMON, hp = 200)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            piplup.id -> piplup; supporter.id -> supporter; item.id -> item; dummy.id -> dummy; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val attacker = PokemonInstance(listOf(card("piplup-active", piplup.id)), enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(card("support-target-active", dummy.id)), enteredPlayTurn = 0)
        val supporterCard = card("supporter-card", supporter.id)
        val itemCard = card("item-card", item.id)
        val state = GameState("supporter-search", 58, RngState(58), listOf(
            PlayerState(deck = listOf(supporterCard, itemCard), prizes = List(6) { card("support-prize-$it") }, active = attacker),
            PlayerState(deck = listOf(card("other-deck", dummy.id)), prizes = List(6) { card("other-prize-$it") }, active = target)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val started = rules.apply(state, GameAction.Attack(compiledAttack.id))
        assertEquals(Legality.Legal, started.legality)
        assertEquals(listOf(supporterCard.id), started.state.resolution!!.choices.single().candidates)
        val resolved = rules.apply(started.state, GameAction.ChooseCards(listOf(supporterCard.id)))
        assertEquals(Legality.Legal, resolved.legality)
        assertTrue(supporterCard in resolved.state.players[0].hand)
        assertTrue(itemCard in resolved.state.players[0].deck)
    }

    @Test fun `Mega Gardevoir Overflowing Wishes attaches Psychic Energy to every Benched Pokemon`() {
        val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-060"), emptyMap())!!
        val attack = compiled.attacks.single { it.name == "Overflowing Wishes" }.copy(cost = emptyList())
        val gardevoir = compiled.copy(attacks = listOf(attack))
        val dummy = CardDefinition("wish-target", "Target", "Test", "1", category = Category.POKEMON, hp = 200)
        val psychic = CardDefinition("wish-psychic", "Psychic Energy", "Test", "P", category = Category.ENERGY, type = EnergyType.PSYCHIC, basicEnergy = true)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            gardevoir.id -> gardevoir; dummy.id -> dummy; psychic.id -> psychic; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val active = PokemonInstance(listOf(card("wish-gardevoir", gardevoir.id)), enteredPlayTurn = 0)
        val benchA = PokemonInstance(listOf(card("wish-a", dummy.id)), enteredPlayTurn = 0)
        val benchB = PokemonInstance(listOf(card("wish-b", dummy.id)), enteredPlayTurn = 0)
        val energyA = card("wish-energy-a", psychic.id)
        val energyB = card("wish-energy-b", psychic.id)
        val opponent = PokemonInstance(listOf(card("wish-opponent", dummy.id)), enteredPlayTurn = 0)
        val state = GameState("overflowing-wishes", 62, RngState(62), listOf(
            PlayerState(deck = listOf(energyA, energyB), prizes = List(6) { card("wish-prize-$it") }, active = active, bench = listOf(benchA, benchB)),
            PlayerState(deck = listOf(card("wish-opp-deck", dummy.id)), prizes = List(6) { card("wish-opp-prize-$it") }, active = opponent)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val first = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, first.legality)
        var resolving = rules.apply(first.state, GameAction.ChooseCards(listOf(energyA.id)))
        assertEquals(listOf(benchA.id, benchB.id), resolving.state.resolution!!.choices.first().candidates)
        resolving = rules.apply(resolving.state, GameAction.ChooseCards(listOf(benchA.id)))
        resolving = rules.apply(resolving.state, GameAction.ChooseCards(listOf(energyB.id)))
        assertEquals(listOf(benchB.id), resolving.state.resolution!!.choices.first().candidates)
        resolving = rules.apply(resolving.state, GameAction.ChooseCards(listOf(benchB.id)))
        assertEquals(Legality.Legal, resolving.legality)
        assertTrue(resolving.state.players[0].bench.all { pokemon -> pokemon.energies.size == 1 })
        assertEquals(0, resolving.state.players[0].deck.size)
    }

    @Test fun `Iron Crown Cobalt Command boosts other Future Pokémon attacks`() {
        val crown = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv05-081"), emptyMap())!!
        val attacker = CardDefinition("future-attacker", "Iron Hands ex", "Test", "1", category = Category.POKEMON,
            hp = 200, attacks = listOf(AttackDefinition("future-hit", "Hit", emptyList(), listOf(Effect.Damage(100)))))
        val targetDef = CardDefinition("future-target", "Target", "Test", "2", category = Category.POKEMON, hp = 200)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            crown.id -> crown; attacker.id -> attacker; targetDef.id -> targetDef; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val active = PokemonInstance(listOf(card("future-iron-hands", attacker.id)), enteredPlayTurn = 0)
        val crownBench = PokemonInstance(listOf(card("future-crown", crown.id)), enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(card("future-target-active", targetDef.id)), enteredPlayTurn = 0)
        val state = GameState("cobalt-command", 72, RngState(72), listOf(
            PlayerState(deck = emptyList(), prizes = List(6) { card("future-prize-$it") }, active = active, bench = listOf(crownBench)),
            PlayerState(deck = emptyList(), prizes = List(6) { card("future-opp-prize-$it") }, active = target)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(120, rules.preview(state, "future-hit")!!.damage)
    }

    @Test fun `Mega Clefable Shooting Moons adds damage for each discarded hand Energy`() {
        val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me03-031"), emptyMap())!!
        val attack = compiled.attacks.single().copy(cost = emptyList())
        val clefable = compiled.copy(attacks = listOf(attack))
        val targetDef = CardDefinition("clefable-target", "Target", "Test", "1", category = Category.POKEMON, hp = 1000)
        val energyDef = CardDefinition("clefable-energy", "Energy", "Test", "2", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            clefable.id -> clefable; targetDef.id -> targetDef; energyDef.id -> energyDef; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val attacker = PokemonInstance(listOf(card("clefable-active", clefable.id)), enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(card("clefable-target-active", targetDef.id)), enteredPlayTurn = 0)
        val energyA = card("clefable-energy-a", energyDef.id)
        val energyB = card("clefable-energy-b", energyDef.id)
        val state = GameState("clefable-moons", 73, RngState(73), listOf(
            PlayerState(deck = emptyList(), hand = listOf(energyA, energyB), prizes = List(6) { card("clefable-prize-$it") }, active = attacker),
            PlayerState(deck = emptyList(), prizes = List(6) { card("clefable-opp-prize-$it") }, active = target)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val started = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, started.legality)
        assertEquals(setOf(energyA.id, energyB.id), started.state.resolution!!.choices.single().candidates.toSet())
        val resolved = rules.apply(started.state, GameAction.ChooseCards(listOf(energyA.id, energyB.id)))
        assertEquals(Legality.Legal, resolved.legality)
        assertEquals(200, resolved.state.players[1].active!!.damage, resolved.state.toString())
        assertEquals(setOf(energyA, energyB), resolved.state.players[0].discard.toSet())
    }

    @Test fun `Numel active retaliation Burns attacker even when Numel is Knocked Out`() {
        val numel = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-027"), emptyMap())!!
        val attackerDef = CardDefinition("retaliation-attacker", "Attacker", "Test", "1", category = Category.POKEMON, hp = 200,
            attacks = listOf(AttackDefinition("retaliation-hit", "Hit", emptyList(), listOf(Effect.Damage(100)))))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            numel.id -> numel; attackerDef.id -> attackerDef; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val attacker = PokemonInstance(listOf(card("retaliation-attacker", attackerDef.id)), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(card("retaliation-numel", numel.id)), enteredPlayTurn = 0)
        val state = GameState("numel-retaliation", 74, RngState(74), listOf(
            PlayerState(deck = emptyList(), prizes = List(6) { card("retaliation-prize-$it") }, active = attacker),
            PlayerState(deck = emptyList(), prizes = List(6) { card("retaliation-opp-prize-$it") }, active = defender)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack("retaliation-hit"))
        assertEquals(Legality.Legal, result.legality)
        assertTrue(result.state.players[0].active!!.conditions.contains(SpecialCondition.BURNED))
    }

    @Test fun `Charmander has free retreat while it has no Energy`() {
        val raw = CatalogRuleCompiler.rows.getValue("me02-011")
        val ability = CatalogRuleCompiler.compileAbility("charmander-free-retreat", 0, raw["abilities"]!!.jsonArray.single().jsonObject)!!
        val charmander = CardDefinition("charmander-free-retreat", "Charmander", "Test", "1", category = Category.POKEMON,
            hp = 100, retreatCost = 2, abilities = listOf(ability))
        val energyDef = CardDefinition("retreat-energy", "Energy", "Test", "2", category = Category.ENERGY, type = EnergyType.FIRE, basicEnergy = true)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { charmander.id -> charmander; energyDef.id -> energyDef; else -> TrainerCatalog.get(id) } }
        val rules = RulesEngine(catalog)
        val free = PokemonInstance(listOf(card("charmander-no-energy", charmander.id)), enteredPlayTurn = 0)
        val attached = free.copy(energies = listOf(card("charmander-energy", energyDef.id)))
        assertEquals(0, rules.retreatCost(free))
        assertEquals(2, rules.retreatCost(attached))
    }

    @Test fun `Raging Bolt Bellowing Thunder discards Basic Energy from any of its Pokemon`() {
        val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv05-123"), emptyMap())!!
        val attack = compiled.attacks.single { it.name == "Bellowing Thunder" }.copy(cost = emptyList())
        val ragingBolt = compiled.copy(attacks = listOf(attack))
        val energy = CardDefinition("bolt-basic-energy", "Basic Energy", "Test", "E", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
        val targetDef = CardDefinition("bolt-target", "Target", "Test", "T", category = Category.POKEMON, hp = 400)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            ragingBolt.id -> ragingBolt; energy.id -> energy; targetDef.id -> targetDef; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val active = PokemonInstance(listOf(card("bolt-active", ragingBolt.id)), energies = listOf(card("bolt-e1", energy.id), card("bolt-e2", energy.id)), enteredPlayTurn = 0)
        val bench = PokemonInstance(listOf(card("bolt-bench", targetDef.id)), energies = listOf(card("bolt-e3", energy.id)), enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(card("bolt-opponent", targetDef.id)), enteredPlayTurn = 0)
        val state = GameState("bellowing-thunder", 75, RngState(75), listOf(
            PlayerState(deck = emptyList(), prizes = List(6) { card("bolt-prize-$it") }, active = active, bench = listOf(bench)),
            PlayerState(deck = emptyList(), prizes = List(6) { card("bolt-opp-prize-$it") }, active = target)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val started = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, started.legality)
        val selectedEnergyIds = listOf("bolt-e1", "bolt-e2", "bolt-e3")
        assertEquals(selectedEnergyIds.toSet(), started.state.resolution!!.choices.single().candidates.toSet())
        val resolved = rules.apply(started.state, GameAction.ChooseCards(selectedEnergyIds))
        assertEquals(Legality.Legal, resolved.legality)
        assertEquals(210, resolved.state.players[1].active!!.damage)
        assertEquals(3, resolved.state.players[0].discard.size)
        assertTrue(resolved.state.players[0].pokemon().all { it.energies.isEmpty() })
    }

    @Test fun `Torment lets the player choose and lock only one opposing attack`() {
        val row = CatalogRuleCompiler.rows.getValue("me02.5-126")
        val torment = CatalogRuleCompiler.compileAttack("rocket-murkrow", 1, row["attacks"]!!.jsonArray[1].jsonObject)!!.copy(cost = emptyList())
        val attackerDef = CardDefinition("rocket-murkrow", "Team Rocket's Murkrow", "Test", "1", category = Category.POKEMON,
            hp = 100, attacks = listOf(torment))
        val blocked = AttackDefinition("target-blocked", "Blocked", emptyList(), listOf(Effect.Damage(40)))
        val allowed = AttackDefinition("target-allowed", "Allowed", emptyList(), listOf(Effect.Damage(10)))
        val opponentDef = CardDefinition("lock-target", "Target", "Test", "2", category = Category.POKEMON, hp = 200, attacks = listOf(blocked, allowed))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            attackerDef.id -> attackerDef; opponentDef.id -> opponentDef; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val attacker = PokemonInstance(listOf(card("torment-murkrow", attackerDef.id)), enteredPlayTurn = 0)
        val opponent = PokemonInstance(listOf(card("torment-target", opponentDef.id)), enteredPlayTurn = 0)
        val state = GameState("torment-lock", 76, RngState(76), listOf(
            PlayerState(deck = emptyList(), prizes = List(6) { card("torment-prize-$it") }, active = attacker),
            PlayerState(deck = emptyList(), prizes = List(6) { card("torment-opp-prize-$it") }, active = opponent)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val started = rules.apply(state, GameAction.Attack(torment.id))
        assertEquals(Legality.Legal, started.legality)
        assertEquals(setOf(blocked.id, allowed.id), started.state.resolution!!.choices.single().candidates.toSet())
        val selected = rules.apply(started.state, GameAction.ChooseCards(listOf(blocked.id)))
        assertEquals(Legality.Legal, selected.legality)
        val nextTurn = selected.state.copy(currentPlayer = 1, turn = 4, phase = Phase.MAIN, resolution = null)
        assertIs<Legality.Illegal>(rules.validate(nextTurn, GameAction.Attack(blocked.id)))
        assertEquals(Legality.Legal, rules.validate(nextTurn, GameAction.Attack(allowed.id)))
    }

    @Test fun `Giga Drain heals the attacker for the actual damage dealt`() {
        val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-013"), emptyMap())!!
        val attack = compiled.attacks.single { it.name == "Giga Drain" }.copy(cost = emptyList())
        val trevenant = compiled.copy(attacks = listOf(attack))
        val targetDef = CardDefinition("drain-target", "Target", "Test", "1", category = Category.POKEMON, hp = 200)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            trevenant.id -> trevenant; targetDef.id -> targetDef; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val attacker = PokemonInstance(listOf(card("drain-trevenant", trevenant.id)), damage = 80, enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(card("drain-target-active", targetDef.id)), enteredPlayTurn = 0)
        val state = GameState("giga-drain", 77, RngState(77), listOf(
            PlayerState(deck = emptyList(), prizes = List(6) { card("drain-prize-$it") }, active = attacker),
            PlayerState(deck = emptyList(), prizes = List(6) { card("drain-opp-prize-$it") }, active = target)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(30, result.state.players[0].active!!.damage)
        assertEquals(50, result.state.players[1].active!!.damage)
    }

    @Test fun `Crabominable discounts one Colorless attack cost per Kofu in discard`() {
        val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-042"), emptyMap())!!
        val kofu = CardDefinition("discount-kofu", "Kofu", "Test", "K", category = Category.TRAINER, trainerType = TrainerType.SUPPORTER)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { compiled.id -> compiled; kofu.id -> kofu; else -> TrainerCatalog.get(id) } }
        val rules = RulesEngine(catalog)
        val pokemon = PokemonInstance(listOf(card("discount-crabominable", compiled.id)), enteredPlayTurn = 0)
        val attack = compiled.attacks.single { it.name == "Haymaker" }
        val state = GameState("kofu-discount", 78, RngState(78), listOf(
            PlayerState(deck = emptyList(), discard = listOf(card("kofu-a", kofu.id), card("kofu-b", kofu.id)), prizes = List(6) { card("kofu-prize-$it") }, active = pokemon),
            PlayerState(deck = emptyList(), prizes = List(6) { card("kofu-opp-prize-$it") }, active = field("kofu-target"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(listOf(EnergyType.WATER, EnergyType.COLORLESS, EnergyType.COLORLESS), rules.attackCost(state, pokemon, attack))
    }

    @Test fun `Flip the Script draws three after an opposing-turn Knock Out and remains once per turn`() {
        val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-142"), emptyMap())!!
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = if (id == compiled.id) compiled else TrainerCatalog.get(id) })
        val pokemon = PokemonInstance(listOf(card("flip-fezandipiti", compiled.id)), enteredPlayTurn = 0)
        val deck = listOf(card("flip-draw-a", "sv01-258"), card("flip-draw-b", "sv01-258"), card("flip-draw-c", "sv01-258"), card("flip-draw-d", "sv01-258"))
        val state = GameState("flip-script", 79, RngState(79), listOf(
            PlayerState(deck = deck, lastKnockedOutTurn = 2, prizes = List(6) { card("flip-prize-$it") }, active = pokemon),
            PlayerState(deck = emptyList(), prizes = List(6) { card("flip-opp-prize-$it") }, active = field("flip-target"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = compiled.abilities.single()
        val result = rules.apply(state, GameAction.UseAbility(pokemon.id, ability.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(3, result.state.players[0].hand.size)
        assertEquals(1, result.state.players[0].deck.size)
        assertEquals(Legality.Illegal("Esta habilidade já foi usada neste turno."), rules.validate(result.state, GameAction.UseAbility(pokemon.id, ability.id)))
    }

    @Test fun `Duskull Come and Get You recovers only Duskull up to open Bench spaces`() {
        val duskull = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06.5-018"), emptyMap())!!
        val card = CardInstance("duskull-discard", duskull.id)
        val dummy = CardDefinition("duskull-dummy", "Test Pokémon", "Test", "P", category = Category.POKEMON, hp = 100)
        val other = CardInstance("duskull-other", dummy.id)
        val active = PokemonInstance(listOf(CardInstance("duskull-active", duskull.id)), enteredPlayTurn = 0)
        val attack = duskull.attacks.single { it.name == "Come and Get You" }.copy(cost = emptyList())
        val playable = duskull.copy(attacks = duskull.attacks.map { if (it.id == attack.id) attack else it })
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) { duskull.id -> playable; dummy.id -> dummy; else -> TrainerCatalog.get(id) } })
        val state = GameState("duskull-recover", 992, RngState(992), listOf(
            PlayerState(deck = listOf(other.copy(id = "duskull-deck")), discard = listOf(card, other), active = active),
            PlayerState(deck = listOf(other.copy(id = "duskull-opp-deck")), active = PokemonInstance(listOf(other.copy(id = "duskull-opp")), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val choice = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(listOf(card.id), choice.state.resolution!!.choices.single().candidates)
        val recovered = rules.apply(choice.state, GameAction.ChooseCards(listOf(card.id)))
        assertEquals(Legality.Legal, recovered.legality)
        assertEquals(card.id, recovered.state.players[0].bench.single().top.id)
        assertTrue(other in recovered.state.players[0].discard)
    }

    @Test fun `Braviary Drag Off switches its selected Benched target before applying damage`() {
        val braviary = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-153"), emptyMap())!!
        val attack = braviary.attacks.single { it.name == "Drag Off" }.copy(cost = emptyList())
        val card = braviary.copy(attacks = braviary.attacks.map { if (it.id == attack.id) attack else it })
        val active = PokemonInstance(listOf(CardInstance("drag-active", braviary.id)), enteredPlayTurn = 0)
        val dummy = CardDefinition("drag-dummy", "Test Pokémon", "Test", "P", category = Category.POKEMON, hp = 100)
        val oldDefender = PokemonInstance(listOf(CardInstance("drag-old", dummy.id)), enteredPlayTurn = 0)
        val pulledDefender = PokemonInstance(listOf(CardInstance("drag-pulled", dummy.id)), enteredPlayTurn = 0)
        val deckCard = CardInstance("drag-deck", dummy.id)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) { braviary.id -> card; dummy.id -> dummy; else -> TrainerCatalog.get(id) } })
        val state = GameState("drag-off", 993, RngState(993), listOf(
            PlayerState(deck = listOf(deckCard.copy(id = "drag-own-deck")), active = active), PlayerState(deck = listOf(deckCard.copy(id = "drag-opp-deck")), active = oldDefender, bench = listOf(pulledDefender))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attackResult = rules.apply(state, GameAction.Attack(attack.id, pulledDefender.id))
        assertEquals(Legality.Legal, attackResult.legality)
        val defender = attackResult.state.players[1]
        assertEquals(pulledDefender.id, defender.active!!.id)
        assertEquals(40, defender.active!!.damage)
        assertEquals(oldDefender.id, defender.bench.single().id)
    }

    @Test fun `Durant ex Sudden Shearing optionally mills the opponent deck when benched`() {
        val durant = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-004"), emptyMap())!!
        val durantCard = CardInstance("durant-in-hand", durant.id)
        val dummy = CardDefinition("durant-dummy", "Test Pokémon", "Test", "P", category = Category.POKEMON, hp = 100)
        val top = CardInstance("durant-opponent-top", dummy.id)
        val backup = CardInstance("durant-opponent-backup", dummy.id)
        val active = PokemonInstance(listOf(CardInstance("durant-own-active", dummy.id)), enteredPlayTurn = 0)
        val enemy = PokemonInstance(listOf(CardInstance("durant-enemy-active", dummy.id)), enteredPlayTurn = 0)
        val filler = CardInstance("durant-filler", dummy.id)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) { durant.id -> durant; dummy.id -> dummy; else -> TrainerCatalog.get(id) } })
        val state = GameState("sudden-shearing", 994, RngState(994), listOf(
            PlayerState(deck = listOf(filler.copy(id = "durant-own-deck")), hand = listOf(durantCard), active = active),
            PlayerState(deck = listOf(top, backup), active = enemy)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val played = rules.apply(state, GameAction.PlayBasic(durantCard.id))
        val choice = played.state.resolution!!.choices.single()
        assertEquals(listOf(top.id), choice.candidates)
        val milled = rules.apply(played.state, GameAction.ChooseCards(listOf(top.id)))
        assertEquals(Legality.Legal, milled.legality)
        assertEquals(listOf(top.id), milled.state.players[1].discard.map { it.id })
        assertEquals(listOf(backup.id), milled.state.players[1].deck.map { it.id })
    }

    @Test fun `Carbink applies Prize condition and dual-type Weakness while Brambleghast gains HP per Prize`() {
        val carbink = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-117"), emptyMap())!!
        val target = CardDefinition("dual-target", "Test Target", "Test", "T", category = Category.POKEMON, hp = 300, weakness = EnergyType.PSYCHIC)
        val attacker = PokemonInstance(listOf(CardInstance("carbink-active", carbink.id)), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("dual-defender", target.id)), enteredPlayTurn = 0)
        val filler = CardInstance("dual-filler", target.id)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            carbink.id -> carbink; target.id -> target; else -> TrainerCatalog.get(id)
        } })
        val state = GameState("carbink-dual-type", 995, RngState(995), listOf(
            PlayerState(deck = listOf(filler), active = attacker),
            PlayerState(deck = listOf(filler.copy(id = "dual-opponent-deck")), prizes = listOf(filler.copy(id = "dual-prize-1"), filler.copy(id = "dual-prize-2")), active = defender)
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attack = carbink.attacks.single { it.name == "Counter Jewel" }
        assertEquals(340, rules.preview(state, attack.id)!!.damage)

        val resilient = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv05-021"), emptyMap())!!
        val resilientPokemon = PokemonInstance(listOf(CardInstance("resilient-active", resilient.id)), enteredPlayTurn = 0)
        val resilientState = state.copy(players = listOf(
            PlayerState(deck = listOf(filler), active = resilientPokemon),
            PlayerState(deck = listOf(filler.copy(id = "resilient-opponent-deck")), prizes = List(4) { filler.copy(id = "resilient-prize-$it") }, active = defender)
        ))
        assertEquals(resilient.hp + 100, rules.effects.effectiveHp(resilientPokemon, resilientState))
    }

    @Test fun `Evolution-only attack bonuses and protections apply only against Evolution Pokémon`() {
        val carracosta = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-038"), emptyMap())!!
        val bronzong = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me05-064"), emptyMap())!!
        val evolution = CardDefinition("evo-test", "Test Evolution", "Test", "E", category = Category.POKEMON, stage = Stage.STAGE_ONE, hp = 200)
        val basic = CardDefinition("basic-test", "Test Basic", "Test", "B", category = Category.POKEMON, hp = 200)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            carracosta.id -> carracosta; bronzong.id -> bronzong; evolution.id -> evolution; basic.id -> basic; else -> TrainerCatalog.get(id)
        } }
        val engine = EffectEngine(catalog)
        val attacker = PokemonInstance(listOf(CardInstance("carracosta", carracosta.id)), enteredPlayTurn = 0)
        val targetEvolution = PokemonInstance(listOf(CardInstance("evo-target", evolution.id)), enteredPlayTurn = 0)
        val targetBasic = PokemonInstance(listOf(CardInstance("basic-target", basic.id)), enteredPlayTurn = 0)
        assertEquals(130, engine.damage(attacker, targetEvolution, listOf(Effect.Damage(100)), attackerPokemon = listOf(attacker)))
        assertEquals(100, engine.damage(attacker, targetBasic, listOf(Effect.Damage(100)), attackerPokemon = listOf(attacker)))
        val protected = PokemonInstance(listOf(CardInstance("bronzong", bronzong.id)), enteredPlayTurn = 0,
            incomingDamageReduction = 100, damageReductionTurn = 4, incomingDamageReductionEvolutionOnly = true)
        assertEquals(0, engine.damage(PokemonInstance(listOf(CardInstance("evo", evolution.id)), enteredPlayTurn = 0), protected, listOf(Effect.Damage(100)), turn = 4))
        assertEquals(100, engine.damage(PokemonInstance(listOf(CardInstance("basic", basic.id)), enteredPlayTurn = 0), protected, listOf(Effect.Damage(100)), turn = 4))
    }
    @Test fun `Roasting Burn does damage only while the opponent Active is Burned`() {
        val camerupt = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-028"), emptyMap())!!
        val target = CardDefinition("burn-target", "Test Target", "Test", "B", category = Category.POKEMON, hp = 200)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { camerupt.id -> camerupt; target.id -> target; else -> TrainerCatalog.get(id) } }
        val engine = EffectEngine(catalog)
        val attacker = PokemonInstance(listOf(CardInstance("camerupt", camerupt.id)), enteredPlayTurn = 0)
        val attack = camerupt.attacks.first()
        val clean = PokemonInstance(listOf(CardInstance("target-clean", target.id)), enteredPlayTurn = 0)
        val burned = clean.copy(conditions = setOf(SpecialCondition.BURNED))
        assertEquals(0, engine.damage(attacker, clean, attack.effects))
        assertEquals(110, engine.damage(attacker, burned, attack.effects))
    }
    @Test fun `Loom Over damage penalty counts damage counters on Carnivine`() {
        val carnivine = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-007"), emptyMap())!!
        val target = CardDefinition("loom-target", "Test Target", "Test", "T", category = Category.POKEMON, hp = 200)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { carnivine.id -> carnivine; target.id -> target; else -> TrainerCatalog.get(id) } }
        val engine = EffectEngine(catalog)
        val attacker = PokemonInstance(listOf(CardInstance("carnivine", carnivine.id)), damage = 30, enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("loom-defender", target.id)), enteredPlayTurn = 0)
        val attack = carnivine.attacks.single { it.name == "Loom Over" }
        assertEquals(100, engine.damage(attacker, defender, attack.effects))
    }

    @Test fun `Crobat Nighttime Maneuvers searches any card then puts it on top after shuffling`() {
        val crobat = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me04-051"), emptyMap())!!
        val deck = listOf("crobat-a", "crobat-b", "crobat-c").map { CardInstance(it, "sv01-258") }
        val active = PokemonInstance(listOf(CardInstance("crobat-active", crobat.id)), enteredPlayTurn = 0)
        val filler = CardInstance("crobat-filler", "sv01-258")
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = if (id == crobat.id) crobat else TrainerCatalog.get(id) })
        val state = GameState("crobat-search-top", 313, RngState(313), listOf(
            PlayerState(deck = deck, prizes = List(6) { filler.copy(id = "crobat-prize-$it") }, active = active),
            PlayerState(deck = List(8) { filler.copy(id = "crobat-opp-deck-$it") }, prizes = List(6) { filler.copy(id = "crobat-opp-prize-$it") }, active = PokemonInstance(listOf(filler), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = crobat.abilities.single()
        val search = rules.apply(state, GameAction.UseAbility(active.id, ability.id))
        assertEquals(Legality.Legal, search.legality)
        assertEquals(deck.map { it.id }.toSet(), search.state.resolution!!.choices.single().visibleCards.toSet())
        val selected = rules.apply(search.state, GameAction.ChooseCards(listOf("crobat-c")))
        assertEquals(Legality.Legal, selected.legality)
        assertEquals("crobat-c", selected.state.players[0].deck.first().id)
        assertEquals(deck.map { it.id }.toSet(), selected.state.players[0].deck.map { it.id }.toSet())
        assertTrue(ability.name in selected.state.players[0].flags.abilitiesUsed)
    }

    @Test fun `Dragapult Phantom Dive distributes six damage counters one at a time`() {
        val dragapult = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-130"), emptyMap())!!
        val dragapultForTest = dragapult.copy(attacks = dragapult.attacks.map { it.copy(cost = emptyList()) })
        val targetDef = CardDefinition("phantom-target", "Target", "Test", "T", category = Category.POKEMON, hp = 500)
        val dragapultActive = PokemonInstance(listOf(CardInstance("phantom-dragapult", dragapult.id)), enteredPlayTurn = 0)
        fun target(id: String) = PokemonInstance(listOf(CardInstance(id, targetDef.id)), enteredPlayTurn = 0)
        fun filler(id: String) = CardInstance(id, "sv01-258")
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { dragapult.id -> dragapultForTest; targetDef.id -> targetDef; else -> TrainerCatalog.get(id) } }
        val rules = RulesEngine(catalog)
        val state = GameState("phantom-dive", 314, RngState(314), listOf(
            PlayerState(deck = List(10) { filler("phantom-a-deck-$it") }, prizes = List(6) { filler("phantom-a-prize-$it") }, active = dragapultActive),
            PlayerState(deck = List(10) { filler("phantom-b-deck-$it") }, prizes = List(6) { filler("phantom-b-prize-$it") }, active = target("phantom-active"), bench = listOf(target("phantom-b1"), target("phantom-b2")))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attack = dragapultForTest.attacks.single { it.name == "Phantom Dive" }
        var result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(200, result.state.players[1].active!!.damage)
        repeat(6) { index ->
            val targetId = if (index < 3) "phantom-b1" else "phantom-b2"
            assertEquals(6-index, result.state.resolution!!.choices.first().remaining)
            result = rules.apply(result.state, GameAction.ChooseCards(listOf(targetId)))
            assertEquals(Legality.Legal, result.legality)
        }
        assertEquals(30, result.state.players[1].bench.first { it.id == "phantom-b1" }.damage)
        assertEquals(30, result.state.players[1].bench.first { it.id == "phantom-b2" }.damage)
    }
    @Test fun `Cinderace ex Flare Strike restricts only Flare Strike`() {
        val cinderace = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-028"), emptyMap())!!
        val cinderaceForTest = cinderace.copy(attacks = cinderace.attacks.map { it.copy(cost = emptyList()) })
        val targetDef = CardDefinition("flare-target", "Target", "Test", "T", category = Category.POKEMON, hp = 500)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { cinderace.id -> cinderaceForTest; targetDef.id -> targetDef; else -> TrainerCatalog.get(id) } }
        val rules = RulesEngine(catalog)
        fun filler(id: String) = CardInstance(id, "sv01-258")
        val state = GameState("flare-lock", 315, RngState(315), listOf(
            PlayerState(deck = List(8) { filler("flare-a-$it") }, prizes = List(6) { filler("flare-p-$it") }, active = PokemonInstance(listOf(CardInstance("flare-cinderace", cinderace.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(8) { filler("flare-b-$it") }, prizes = List(6) { filler("flare-q-$it") }, active = PokemonInstance(listOf(CardInstance("flare-target-card", targetDef.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attack = cinderaceForTest.attacks.single { it.name == "Flare Strike" }
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(listOf(AttackRestriction(attack.id, 5)), result.state.players[0].active!!.restrictions)
        assertTrue(rules.attackCost(result.state, result.state.players[0].active!!, cinderaceForTest.attacks.single { it.name == "Garnet Volley" }).isEmpty())
    }

    @Test fun `Budew Itchy Pollen blocks Item cards during the opponent's next turn`() {
        val budew = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08.5-004"), emptyMap())!!
        val budewForTest = budew.copy(attacks = budew.attacks.map { it.copy(cost = emptyList()) })
        val targetDef = CardDefinition("itchy-target", "Target", "Test", "T", category = Category.POKEMON, hp = 500)
        val itemDef = TrainerCatalog.get("me01-116")
        val toolDef = CardDefinition("itchy-tool", "Test Tool", "Test", "T", category = Category.TRAINER, trainerType = TrainerType.TOOL, trainerEffect = TrainerEffect.ToolHpModifier(100))
        val supporterDef = CardDefinition("itchy-supporter", "Test Supporter", "Test", "S", category = Category.TRAINER, trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.DrawCards(1))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { budew.id -> budewForTest; targetDef.id -> targetDef; itemDef.id -> itemDef; toolDef.id -> toolDef; supporterDef.id -> supporterDef; else -> TrainerCatalog.get(id) } }
        val rules = RulesEngine(catalog)
        fun filler(id: String) = CardInstance(id, "sv01-258")
        val state = GameState("itchy-pollen", 316, RngState(316), listOf(
            PlayerState(deck = List(8) { filler("itchy-a-$it") }, prizes = List(6) { filler("itchy-p-$it") }, active = PokemonInstance(listOf(CardInstance("itchy-budew", budew.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(8) { filler("itchy-b-$it") }, hand = listOf(CardInstance("itchy-item-card", itemDef.id), CardInstance("itchy-tool-card", toolDef.id), CardInstance("itchy-supporter-card", supporterDef.id)), prizes = List(6) { filler("itchy-q-$it") }, active = PokemonInstance(listOf(CardInstance("itchy-target-card", targetDef.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack(budewForTest.attacks.single().id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(4, result.state.turn)
        assertEquals(1, result.state.currentPlayer)
        assertEquals(result.state.turn, result.state.players[1].itemPlayLockedUntilTurn)
        assertEquals(Legality.Illegal("Você não pode jogar cartas de Item neste turno."), rules.validate(result.state, GameAction.PlayTrainer("itchy-item-card")))
        assertEquals(Legality.Illegal("Você não pode jogar cartas de Item neste turno."), rules.validate(result.state, GameAction.PlayTrainer("itchy-tool-card")))
        assertEquals(Legality.Legal, rules.validate(result.state, GameAction.PlayTrainer("itchy-supporter-card")))
    }

    @Test fun `Ciphermaniac shuffles remaining deck and puts chosen cards on top in selected order`() {
        val cipher = TrainerCatalog.get("sv05-145")
        assertEquals(TrainerEffect.Ciphermaniac, cipher.trainerEffect)
        fun card(id: String) = CardInstance(id, "sv01-258")
        val deck = (1..5).map { card("cipher-$it") }
        val basic = CardInstance("cipher-basic", "me01-076")
        val state = GameState("cipher-test", 417, RngState(417), listOf(
            PlayerState(deck = deck, hand = listOf(CardInstance("cipher-supporter", cipher.id)), prizes = List(6) { card("cipher-p-$it") },
                active = PokemonInstance(listOf(basic), enteredPlayTurn = 0)),
            PlayerState(deck = List(8) { card("cipher-op-$it") }, prizes = List(6) { card("cipher-q-$it") },
                active = PokemonInstance(listOf(card("cipher-op-basic")), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val rules = RulesEngine(TrainerCatalog)
        val played = rules.apply(state, GameAction.PlayTrainer("cipher-supporter"))
        assertEquals(Legality.Legal, played.legality)
        assertEquals(ChoiceKind.TRAINER_CIPHER_SEARCH, played.state.resolution!!.choices.single().kind)
        val selected = listOf(deck[3].id, deck[1].id)
        val resolved = rules.apply(played.state, GameAction.ChooseCards(selected))
        assertEquals(Legality.Legal, resolved.legality)
        assertEquals(selected, resolved.state.players[0].deck.take(2).map { it.id })
        assertEquals(deck.size, resolved.state.players[0].deck.size)
    }

    @Test fun `Naveen allows optional discard before drawing up to five`() {
        val naveen = TrainerCatalog.get("me03-079")
        assertEquals(TrainerEffect.Naveen, naveen.trainerEffect)
        fun card(id: String) = CardInstance(id, "sv01-258")
        val hand = (1..6).map { card("naveen-hand-$it") }
        val deck = (1..4).map { card("naveen-deck-$it") }
        val state = GameState("naveen-test", 418, RngState(418), listOf(
            PlayerState(deck = deck, hand = hand + CardInstance("naveen-supporter", naveen.id), prizes = List(6) { card("naveen-p-$it") },
                active = PokemonInstance(listOf(CardInstance("naveen-basic", "me01-076")), enteredPlayTurn = 0)),
            PlayerState(deck = List(8) { card("naveen-op-$it") }, prizes = List(6) { card("naveen-q-$it") },
                active = PokemonInstance(listOf(card("naveen-op-basic")), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val rules = RulesEngine(TrainerCatalog)
        val played = rules.apply(state, GameAction.PlayTrainer("naveen-supporter"))
        assertEquals(Legality.Legal, played.legality)
        assertEquals(ChoiceKind.TRAINER_NAVEEN_DISCARD, played.state.resolution!!.choices.single().kind)
        val discarded = rules.apply(played.state, GameAction.ChooseCards(listOf(hand[0].id, hand[1].id)))
        assertEquals(Legality.Legal, discarded.legality)
        assertEquals(5, discarded.state.players[0].hand.size)
        assertEquals(3, discarded.state.players[0].deck.size)
    }

    @Test fun `Glass Trumpet attaches one Basic Energy to each selected benched Colorless Pokemon`() {
        val trumpet = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-135"), emptyMap())!!
        val tera = CardDefinition("glass-tera", "Tera Test", "Test", "T", category = Category.POKEMON, hp = 200,
            stage = Stage.BASIC, type = EnergyType.FIGHTING, isTera = true)
        val colorless = CardDefinition("glass-colorless", "Colorless Test", "Test", "C", category = Category.POKEMON, hp = 100,
            stage = Stage.BASIC, type = EnergyType.COLORLESS)
        val energy = CardDefinition("glass-energy", "Basic Fighting Energy", "Test", "E", category = Category.ENERGY,
            type = EnergyType.FIGHTING, basicEnergy = true)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            trumpet.id -> trumpet; tera.id -> tera; colorless.id -> colorless; energy.id -> energy; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun card(id: String, definition: String) = CardInstance(id, definition)
        val state = GameState("glass-trumpet", 419, RngState(419), listOf(
            PlayerState(deck = List(5) { card("glass-a-$it", "sv01-258") },
                hand = listOf(card("glass-trumpet-card", trumpet.id)), discard = listOf(card("glass-energy-1", energy.id), card("glass-energy-2", energy.id)),
                prizes = List(6) { card("glass-p-$it", "sv01-258") }, active = PokemonInstance(listOf(card("glass-tera-card", tera.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(card("glass-bench-1", colorless.id)), enteredPlayTurn = 0), PokemonInstance(listOf(card("glass-bench-2", colorless.id)), enteredPlayTurn = 0))),
            PlayerState(deck = List(5) { card("glass-b-$it", "sv01-258") }, prizes = List(6) { card("glass-q-$it", "sv01-258") },
                active = PokemonInstance(listOf(card("glass-op-active", tera.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val played = rules.apply(state, GameAction.PlayTrainer("glass-trumpet-card"))
        assertEquals(Legality.Legal, played.legality)
        val targets = played.state.players[0].bench.map { it.id }
        var resolved = rules.apply(played.state, GameAction.ChooseCards(targets))
        assertEquals(Legality.Legal, resolved.legality)
        repeat(2) { index ->
            val choice = resolved.state.resolution!!.choices.first()
            assertEquals(ChoiceKind.TRAINER_GLASS_ENERGY, choice.kind)
            resolved = rules.apply(resolved.state, GameAction.ChooseCards(listOf(choice.candidates.first())))
            assertEquals(Legality.Legal, resolved.legality)
        }
        assertTrue(resolved.state.players[0].bench.all { it.energies.size == 1 })
        assertTrue(resolved.state.players[0].discard.none { it.id.startsWith("glass-energy-") })
    }

    @Test fun `Basculin Bared Fangs deals damage only when opposing Active starts undamaged`() {
        val basculin = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10.5w-024"), emptyMap())!!
        val attack = basculin.attacks.last().copy(cost = emptyList())
        val basculinDef = basculin.copy(attacks = listOf(attack))
        val targetDef = CardDefinition("bared-target", "Target", "Test", "T", category = Category.POKEMON, hp = 150)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            basculinDef.id -> basculinDef; targetDef.id -> targetDef; else -> TrainerCatalog.get(id)
        } })
        fun filler(id: String) = CardInstance(id, "sv01-258")
        fun state(targetDamage: Int) = GameState("bared-fangs-$targetDamage", 107, RngState(107), listOf(
            PlayerState(deck = List(10) { filler("a-deck-$it") }, prizes = List(6) { filler("a-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("basculin", basculinDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { filler("b-deck-$it") }, prizes = List(6) { filler("b-prize-$it") },
                active = PokemonInstance(listOf(CardInstance("target", targetDef.id)), damage = targetDamage, enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val clean = state(0)
        val damaged = state(10)
        assertEquals(50, rules.preview(clean, attack.id)!!.damage)
        assertEquals(0, rules.preview(damaged, attack.id)!!.damage)
        val hit = rules.apply(clean, GameAction.Attack(attack.id))
        assertEquals(50, hit.state.players[1].active!!.damage)
        val miss = rules.apply(damaged, GameAction.Attack(attack.id))
        assertEquals(10, miss.state.players[1].active!!.damage)
        assertTrue(miss.events.any { it.type == "AttackEffectFailed" })
    }

    @Test fun `Ampharos Synchro Pulse bonus uses actual hand sizes in attack preview`() {
        val ampharos = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me04-029"), emptyMap())!!
        val attack = ampharos.attacks.first().copy(cost = emptyList())
        val ampharosDef = ampharos.copy(attacks = listOf(attack))
        val defender = CardDefinition("test-defender", "Test Defender", "Test", "D", category = Category.POKEMON, hp = 300)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            ampharos.id -> ampharosDef; defender.id -> defender; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun state(ownHand: Int, opposingHand: Int) = GameState("synchro-$ownHand-$opposingHand", 47, RngState(47), listOf(
            PlayerState(deck = List(10) { card("deck-$it", "TEST-004") }, hand = List(ownHand) { card("own-$it", "TEST-004") },
                prizes = List(6) { card("prize$it") }, active = PokemonInstance(listOf(card("ampharos", ampharos.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck-$it", "TEST-004") }, hand = List(opposingHand) { card("op-hand-$it", "TEST-004") },
                prizes = List(6) { card("op-prize$it") }, active = PokemonInstance(listOf(card("defender", defender.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        assertEquals(220, rules.preview(state(2, 2), attack.id)!!.damage)
        assertEquals(140, rules.preview(state(2, 1), attack.id)!!.damage)
    }

    @Test fun `Annihilape Ghostly Blow places counters on a selected Bench Pokemon and awards its Prize`() {
        val attackerDef = CardDefinition("test-attacker", "Test Attacker", "Test", "A", category = Category.POKEMON, hp = 300,
            attacks = listOf(AttackDefinition("ghostly-blow", "Ghostly Blow", emptyList(), listOf(Effect.Damage(100), Effect.BenchDamageCounters(5)))) )
        val targetDef = CardDefinition("test-target", "Test Target", "Test", "B", category = Category.POKEMON, stage = Stage.BASIC, hp = 60)
        val victimDef = CardDefinition("test-victim", "Test Victim", "Test", "C", category = Category.POKEMON, hp = 300)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            attackerDef.id -> attackerDef; targetDef.id -> targetDef; victimDef.id -> victimDef; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("ghostly-blow", 53, RngState(53), listOf(
            PlayerState(deck = List(10) { card("deck$it", "TEST-004") }, prizes = List(6) { card("prize$it") },
                active = PokemonInstance(listOf(card("attacker", attackerDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it") },
                active = PokemonInstance(listOf(card("victim", victimDef.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(card("target", targetDef.id)), damage = 10, enteredPlayTurn = 0)))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val started = rules.apply(state, GameAction.Attack("ghostly-blow"))
        assertEquals(ChoiceKind.ATTACK_DAMAGE_COUNTER_TARGET, started.state.resolution!!.choices.single().kind)
        val finished = rules.apply(started.state, GameAction.ChooseCards(listOf("target")))
        assertTrue(finished.state.players[1].bench.isEmpty())
        assertEquals(1, finished.state.prizesOwed[0])
        assertEquals(-1, finished.state.players[1].lastKnockedOutByAttackDamageTurn)
    }

    @Test fun `Annihilape Durable Body survives a lethal attack on heads and is Knocked Out on tails`() {
        val annihilape = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me05-041"), emptyMap())!!
        val attackerDef = CardDefinition("test-attacker", "Test Attacker", "Test", "A", category = Category.POKEMON, hp = 300,
            attacks = listOf(AttackDefinition("huge-hit", "Huge Hit", emptyList(), listOf(Effect.Damage(500)))))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            attackerDef.id -> attackerDef; annihilape.id -> annihilape; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun state(seed: Long) = GameState("durable-body-$seed", seed, RngState(seed), listOf(
            PlayerState(deck = List(10) { card("a-deck-$it", "TEST-004") }, prizes = List(6) { card("a-prize$it") },
                active = PokemonInstance(listOf(card("attacker", attackerDef.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck-$it", "TEST-004") }, prizes = List(6) { card("b-prize$it") },
                active = PokemonInstance(listOf(card("annihilape", annihilape.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val headsSeed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) == 0 }
        val survived = rules.apply(state(headsSeed), GameAction.Attack("huge-hit"))
        assertEquals(Legality.Legal, survived.legality)
        assertEquals(annihilape.hp - 10, survived.state.players[1].active!!.damage)
        assertEquals(0, survived.state.prizesOwed[0])
        assertTrue(survived.events.any { it.type == "CoinFlipped" && it.message.contains("Durable Body") })

        val tailsSeed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) != 0 }
        val knockedOut = rules.apply(state(tailsSeed), GameAction.Attack("huge-hit"))
        assertNull(knockedOut.state.players[1].active)
        assertEquals(1, knockedOut.state.prizesOwed[0])
    }

    @Test fun `Ambipom Dual Tail damages Active with Weakness and Bench without it then awards Prize`() {
        val ambipom = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02-079"), emptyMap())!!
        val ambipomDef = ambipom.copy(attacks = ambipom.attacks.mapIndexed { index, attack -> if (index == 1) attack.copy(cost = emptyList()) else attack })
        val activeDef = CardDefinition("test-active", "Test Active", "Test", "A", category = Category.POKEMON,
            type = EnergyType.FIRE, weakness = ambipom.type, hp = 300)
        val benchDef = CardDefinition("test-bench", "Test Bench", "Test", "B", category = Category.POKEMON,
            stage = Stage.BASIC, type = EnergyType.FIRE, weakness = ambipom.type, hp = 50)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            ambipom.id -> ambipomDef; activeDef.id -> activeDef; benchDef.id -> benchDef; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val energy1 = card("energy1", "TEST-004"); val energy2 = card("energy2", "TEST-004")
        val state = GameState("dual-tail", 59, RngState(59), listOf(
            PlayerState(deck = List(10) { card("deck$it", "TEST-004") }, prizes = List(6) { card("prize$it") },
                active = PokemonInstance(listOf(card("ambipom", ambipom.id)), energies = listOf(energy1, energy2), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it") },
                active = PokemonInstance(listOf(card("active", activeDef.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(card("bench", benchDef.id)), enteredPlayTurn = 0)))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val started = rules.apply(state, GameAction.Attack(ambipomDef.attacks.last().id))
        assertEquals(ChoiceKind.ATTACK_MULTI_DAMAGE_TARGETS, started.state.resolution!!.choices.first().kind)
        val targets = listOf("active", "bench")
        val damageResolved = rules.apply(started.state, GameAction.ChooseCards(targets))
        val discardChoice = damageResolved.state.resolution!!.choices.first()
        assertEquals(ChoiceKind.DISCARD_ATTACK_ENERGY, discardChoice.kind)
        val finished = rules.apply(damageResolved.state, GameAction.ChooseCards(discardChoice.candidates))
        assertEquals(120, finished.state.players[1].active!!.damage)
        assertTrue(finished.state.players[1].bench.isEmpty())
        assertEquals(1, finished.state.prizesOwed[0])
        assertTrue(finished.state.players[0].discard.any { it.id == energy1.id })
        assertTrue(finished.state.players[0].discard.any { it.id == energy2.id })
    }

    @Test fun `Annihilape Destined Fight Knocks Out both Actives and awards Prizes simultaneously`() {
        val annihilape = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-100"), emptyMap())!!
        val attack = annihilape.attacks.last().copy(cost = emptyList())
        val annihilapeDef = annihilape.copy(attacks = listOf(attack))
        val other = CardDefinition("test-other", "Test Other", "Test", "O", category = Category.POKEMON, hp = 300)
        val reserve = CardDefinition("test-reserve", "Test Reserve", "Test", "R", category = Category.POKEMON, stage = Stage.BASIC, hp = 100)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            annihilape.id -> annihilapeDef; other.id -> other; reserve.id -> reserve; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("destined-fight", 61, RngState(61), listOf(
            PlayerState(deck = List(10) { card("a-deck$it", "TEST-004") }, prizes = List(6) { card("a-prize$it") },
                active = PokemonInstance(listOf(card("annihilape", annihilape.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(card("a-reserve", reserve.id)), enteredPlayTurn = 0))),
            PlayerState(deck = List(10) { card("b-deck$it", "TEST-004") }, prizes = List(6) { card("b-prize$it") },
                active = PokemonInstance(listOf(card("other", other.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(card("b-reserve", reserve.id)), enteredPlayTurn = 0)))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(listOf(1, 1), result.state.prizesOwed)
        assertNull(result.state.players[0].active)
        assertNull(result.state.players[1].active)
        assertEquals(Phase.SELECT_PRIZES, result.state.phase)
        assertEquals(-1, result.state.players[0].lastKnockedOutByAttackDamageTurn)
        assertEquals(-1, result.state.players[1].lastKnockedOutByAttackDamageTurn)
    }

    @Test fun `Arboliva Oil Salvo can repeat a target and ignores Weakness`() {
        val arboliva = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-023"), emptyMap())!!
        val arbolivaDef = arboliva.copy(attacks = arboliva.attacks.map { it.copy(cost = emptyList()) })
        val target = CardDefinition("target", "Target", "Test", "T", category = Category.POKEMON, type = EnergyType.FIRE,
            weakness = EnergyType.GRASS, hp = 300)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            arboliva.id -> arbolivaDef; target.id -> target; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val state = GameState("oil-salvo", 67, RngState(67), listOf(
            PlayerState(deck = List(10) { card("a-deck$it", "TEST-004") }, prizes = List(6) { card("a-prize$it") },
                active = PokemonInstance(listOf(card("arboliva", arboliva.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck$it", "TEST-004") }, prizes = List(6) { card("b-prize$it") },
                active = PokemonInstance(listOf(card("active", target.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(card("bench", target.id)), enteredPlayTurn = 0)))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        var result = rules.apply(state, GameAction.Attack(arbolivaDef.attacks.first().id))
        repeat(6) { index ->
            assertEquals(ChoiceKind.ATTACK_MULTI_DAMAGE_SINGLE_TARGET, result.state.resolution!!.choices.first().kind)
            result = rules.apply(result.state, GameAction.ChooseCards(listOf("active")))
            if (index < 5) assertEquals(5-index, result.state.resolution!!.choices.first().remaining)
        }
        assertEquals(120, result.state.players[1].active!!.damage)
        assertEquals(0, result.state.players[1].bench.single().damage)
        assertEquals(Phase.MAIN, result.state.phase)
    }

    @Test fun `Arboliva Aroma Shot removes all Special Conditions from itself`() {
        val arboliva = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-023"), emptyMap())!!
        val attack = arboliva.attacks[1].copy(cost = emptyList())
        val arbolivaDef = arboliva.copy(attacks = listOf(attack))
        val defender = CardDefinition("defender", "Defender", "Test", "D", category = Category.POKEMON, hp = 300)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            arboliva.id -> arbolivaDef; defender.id -> defender; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val headsSeed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) == 0 }
        val state = GameState("aroma-shot", headsSeed, RngState(headsSeed), listOf(
            PlayerState(deck = List(10) { card("a-deck$it", "TEST-004") }, prizes = List(6) { card("a-prize$it") },
                active = PokemonInstance(listOf(card("arboliva", arboliva.id)), conditions = setOf(SpecialCondition.POISONED, SpecialCondition.BURNED, SpecialCondition.CONFUSED), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck$it", "TEST-004") }, prizes = List(6) { card("b-prize$it") },
                active = PokemonInstance(listOf(card("defender", defender.id)), enteredPlayTurn = 0))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertTrue(result.state.players[0].active!!.conditions.isEmpty())
        assertTrue(result.events.any { it.type == "SpecialConditionsRecovered" })
    }

    @Test fun `Ambipom Wicked Tail removes a random opposing hand card for each heads`() {
        val ambipom = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-138"), emptyMap())!!
        val base = CardDefinition("test-aipom", "Aipom", "Test", "A", category = Category.POKEMON, stage = Stage.BASIC, hp = 70)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            ambipom.id -> ambipom; base.id -> base; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun heads(seed: Long): Int {
            val rng = SeededRng(RngState(seed))
            return List(2) { rng.nextInt(2) == 0 }.count { it }
        }
        for (expectedHeads in 0..2) {
            val seed = (1L..1000L).first { heads(it) == expectedHeads }
            val evolved = card("ambipom", ambipom.id)
            val opponentHand = List(3) { card("op-hand-$expectedHeads-$it", "TEST-001") }
            val opponentDeck = List(8) { card("op-deck-$expectedHeads-$it", "TEST-004") }
            val state = GameState("wicked-tail-$expectedHeads", seed, RngState(seed), listOf(
                PlayerState(deck = List(10) { card("deck-$expectedHeads-$it", "TEST-004") }, hand = listOf(evolved), prizes = List(6) { card("prize-$expectedHeads-$it") },
                    active = PokemonInstance(listOf(card("aipom-$expectedHeads", base.id)), enteredPlayTurn = 0)),
                PlayerState(deck = opponentDeck, hand = opponentHand, prizes = List(6) { card("op-prize-$expectedHeads-$it") },
                    active = field("op-active-$expectedHeads"))
            ), 0, 0, turn = 3, phase = Phase.MAIN)
            val result = rules.apply(state, GameAction.Evolve(evolved.id, "aipom-$expectedHeads"))
            assertEquals(Legality.Legal, result.legality)
            val opposing = result.state.players[1]
            assertEquals(3-expectedHeads, opposing.hand.size)
            assertEquals(8+expectedHeads, opposing.deck.size)
            assertTrue(opponentHand.filter { it !in opposing.hand }.all { it in opposing.deck })
            val shuffled = result.events.find { it.type == "OpponentHandCardsShuffled" }
            assertEquals(expectedHeads.takeIf { it > 0 }, shuffled?.amount)
        }
    }

    @Test fun `Annihilape Lose Cool boosts only at two damage counters and only against the Active`() {
        val annihilape = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-092"), emptyMap())!!
        val target = CardDefinition("target", "Target", "Test", "T", category = Category.POKEMON, hp = 400)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            annihilape.id -> annihilape; target.id -> target; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun state(sourceDamage: Int) = GameState("lose-cool-$sourceDamage", 73, RngState(73), listOf(
            PlayerState(deck = List(10) { card("a-deck$it", "TEST-004") }, prizes = List(6) { card("a-prize$it") },
                active = PokemonInstance(listOf(card("annihilape", annihilape.id)), damage = sourceDamage, enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("b-deck$it", "TEST-004") }, prizes = List(6) { card("b-prize$it") },
                active = PokemonInstance(listOf(card("active", target.id)), enteredPlayTurn = 0),
                bench = listOf(PokemonInstance(listOf(card("bench", target.id)), enteredPlayTurn = 0)))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val attack = annihilape.attacks.first()
        assertEquals(160, rules.preview(state(10), attack.id)!!.damage)
        assertEquals(280, rules.preview(state(20), attack.id)!!.damage)
        assertEquals(160, rules.preview(state(20), attack.id, "bench")!!.damage)
    }

    @Test fun `Alolan Exeggutor Swinging Sphene coin chooses Basic active or Basic bench knockout`() {
        val exeggutor = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-133"), emptyMap())!!
        val attack = exeggutor.attacks[1].copy(cost = emptyList())
        val exeggutorDef = exeggutor.copy(attacks = listOf(attack))
        val attackerTank = CardDefinition("test-attacker", "Test Attacker", "Test", "T", category = Category.POKEMON, hp = 300)
        val basic = CardDefinition("test-basic", "Basic Target", "Test", "B", category = Category.POKEMON, stage = Stage.BASIC, hp = 100)
        val evolution = CardDefinition("test-evolution", "Evolved Target", "Test", "E", category = Category.POKEMON, stage = Stage.STAGE_ONE, hp = 180)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            exeggutor.id -> exeggutorDef; attackerTank.id -> attackerTank; basic.id -> basic; evolution.id -> evolution; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        fun game(seed: Long, activeDefinition: String) = GameState("sphene-$seed", seed, RngState(seed), listOf(
            PlayerState(deck = List(10) { card("deck-$seed-$it", "TEST-004") }, prizes = List(6) { card("prize-$seed-$it", "TEST-004") }, active = PokemonInstance(listOf(card("exeggutor-$seed", exeggutor.id)), enteredPlayTurn = 0)),
            PlayerState(deck = List(10) { card("op-deck-$seed-$it", "TEST-004") }, prizes = List(6) { card("op-prize-$seed-$it", "TEST-004") },
                active = PokemonInstance(listOf(card("op-active-$seed", activeDefinition)), enteredPlayTurn = 0), bench = listOf(
                    PokemonInstance(listOf(card("bench-basic-$seed", basic.id)), enteredPlayTurn = 0),
                    PokemonInstance(listOf(card("bench-evolution-$seed", evolution.id)), enteredPlayTurn = 0)))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val headsSeed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) == 0 }
        val heads = rules.apply(game(headsSeed, basic.id), GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, heads.legality)
        assertNull(heads.state.players[1].active)
        assertEquals(1, heads.state.prizesOwed[0])
        assertEquals(-1, heads.state.players[1].lastKnockedOutByAttackDamageTurn)
        assertTrue(heads.events.any { it.type == "AttackEffectKnockout" })

        val tailsSeed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) != 0 }
        val tails = rules.apply(game(tailsSeed, evolution.id), GameAction.Attack(attack.id))
        val choice = tails.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.ATTACK_KNOCKOUT_BASIC_BENCH, choice.kind)
        assertEquals(listOf("bench-basic-$tailsSeed"), choice.candidates)
        val resolved = rules.apply(tails.state, GameAction.ChooseCards(choice.candidates))
        assertTrue(resolved.state.players[1].bench.none { it.id == choice.candidates.single() })
        assertEquals(1, resolved.state.prizesOwed[0])
    }

    @Test fun `Retaliate counts a Pokemon knocked out by opponent attack damage last turn only`() {
        val marowak = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-057"), emptyMap())!!
        val retaliate = marowak.attacks.first().copy(cost = emptyList())
        val marowakDef = marowak.copy(attacks = listOf(retaliate))
        val victim = CardDefinition("retaliate-victim", "Victim", "Test", "V", category = Category.POKEMON, hp = 50)
        val benchHit = AttackDefinition("bench-hit", "Bench Hit", emptyList(), listOf(Effect.Damage(10), Effect.BenchDamage(100)))
        val attacker = CardDefinition("bench-attacker", "Bench Attacker", "Test", "A", category = Category.POKEMON, hp = 300, attacks = listOf(benchHit))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            marowak.id -> marowakDef; victim.id -> victim; attacker.id -> attacker; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val marowakPokemon = PokemonInstance(listOf(card("marowak", marowak.id)), enteredPlayTurn = 0)
        val victimPokemon = PokemonInstance(listOf(card("victim", victim.id)), enteredPlayTurn = 0)
        val p0 = PlayerState(deck = List(10) { card("p0-deck$it", "TEST-004") }, prizes = List(6) { card("p0-prize$it", "TEST-004") },
            active = marowakPokemon, bench = listOf(victimPokemon))
        val p1 = PlayerState(deck = List(10) { card("p1-deck$it", "TEST-004") }, prizes = List(6) { card("p1-prize$it", "TEST-004") },
            active = PokemonInstance(listOf(card("attacker", attacker.id)), enteredPlayTurn = 0))
        val state = GameState("retaliate", 29, RngState(29), listOf(p0, p1), 0, 1, turn = 3, phase = Phase.MAIN)
        val attacked = rules.apply(state, GameAction.Attack(benchHit.id))
        assertEquals(ChoiceKind.ATTACK_DAMAGE_TARGET, attacked.state.resolution!!.choices.first().kind)
        val ko = rules.apply(attacked.state, GameAction.ChooseCards(listOf(victimPokemon.id)))
        assertNull(ko.state.players[0].bench.singleOrNull { it.id == victimPokemon.id })
        assertEquals(3, ko.state.players[0].lastKnockedOutByAttackDamageTurn)
        val prize = rules.apply(ko.state, GameAction.TakePrize(ko.state.players[1].prizes.first().id))
        assertEquals(4, prize.state.turn)
        assertEquals(0, prize.state.currentPlayer)
        assertEquals(120, rules.preview(prize.state, retaliate.id)!!.damage)
    }

    @Test fun `Alomomola Gentle Fin returns only an eligible Basic Pokemon from discard`() {
        val alomomola = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10.5b-024"), emptyMap())!!
        val eligible = CardDefinition("gentle-basic", "Small Basic", "Test", "B1", category = Category.POKEMON, stage = Stage.BASIC, hp = 70)
        val highHp = eligible.copy(id = "gentle-high-hp", name = "High HP Basic", hp = 80)
        val evolution = eligible.copy(id = "gentle-evolution", name = "Evolution", stage = Stage.STAGE_ONE, hp = 60)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            alomomola.id -> alomomola; eligible.id -> eligible; highHp.id -> highHp; evolution.id -> evolution; else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val selected = card("recover-small", eligible.id)
        val tooBig = card("recover-high", highHp.id)
        val notBasic = card("recover-evolution", evolution.id)
        val source = PokemonInstance(listOf(card("alomomola", alomomola.id)), enteredPlayTurn = 0)
        val p0 = PlayerState(deck = List(10) { card("deck$it", "TEST-004") }, discard = listOf(selected, tooBig, notBasic),
            prizes = List(6) { card("prize$it", "TEST-004") }, active = source)
        val p1 = PlayerState(deck = List(10) { card("op-deck$it", "TEST-004") }, prizes = List(6) { card("op-prize$it", "TEST-004") }, active = field("opponent"))
        val state = GameState("gentle-fin", 31, RngState(31), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = alomomola.abilities.single()
        val search = rules.apply(state, GameAction.UseAbility(source.id, ability.id))
        assertEquals(Legality.Legal, search.legality)
        assertEquals(listOf(selected.id), search.state.resolution!!.choices.single().candidates)
        val recovered = rules.apply(search.state, GameAction.ChooseCards(listOf(selected.id)))
        assertTrue(recovered.state.players[0].bench.any { it.top.id == selected.id })
        assertEquals(listOf(tooBig.id, notBasic.id), recovered.state.players[0].discard.map { it.id })
        assertIs<Legality.Illegal>(rules.validate(recovered.state, GameAction.UseAbility(source.id, ability.id)))
    }

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
    @Test fun `exact hand size attack has no damage or other effects when condition fails`() {
        val attack = AttackDefinition("hand-a0", "Trio-Cheehoo", listOf(EnergyType.COLORLESS),
            listOf(Effect.Damage(120), Effect.RequiresExactHandSize(3)))
        val definition = TestCatalog.get("TEST-001").copy(id = "hand-check", attacks = listOf(attack))
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "hand-check") definition else TestCatalog.get(id) }
        val rules = RulesEngine(catalog)
        val state = main().let { s -> s.copy(players = listOf(s.players[0].copy(active = s.players[0].active!!.copy(
            cards = listOf(card("attacker", "hand-check")))), s.players[1])) }
        assertEquals(0, rules.preview(state, "hand-a0")!!.damage)
        val failed = rules.apply(state, GameAction.Attack("hand-a0"))
        assertEquals(Legality.Legal, failed.legality)
        assertEquals(0, failed.state.players[1].active!!.damage)
        assertTrue(failed.events.any { it.type == "AttackEffectFailed" })
        val satisfied = state.copy(players = listOf(state.players[0].copy(hand = state.players[0].hand + card("third-card")), state.players[1]))
        assertEquals(120, rules.preview(satisfied, "hand-a0")!!.damage)
        val applied = rules.apply(satisfied, GameAction.Attack("hand-a0"))
        assertEquals(Legality.Legal, applied.legality)
        assertTrue(applied.events.any { it.type == "AttackDeclared" && it.amount == 120 })
    }
    @Test fun `random opponent hand card is revealed and shuffled into deck without entering discard`() {
        val attack = AttackDefinition("shuffle-hand-a0", "Astonish", listOf(EnergyType.COLORLESS),
            listOf(Effect.Damage(20), Effect.ShuffleRandomOpponentHand(1)))
        val definition = TestCatalog.get("TEST-001").copy(id = "shuffle-hand-check", attacks = listOf(attack))
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "shuffle-hand-check") definition else TestCatalog.get(id) }
        val rules = RulesEngine(catalog)
        val base = main()
        val opponentHand = listOf(card("op-card-1", "TEST-002"), card("op-card-2", "TEST-003"))
        val state = base.copy(players = listOf(
            base.players[0].copy(active = base.players[0].active!!.copy(cards = listOf(card("attacker", "shuffle-hand-check")))),
            base.players[1].copy(hand = opponentHand)))
        val result = rules.apply(state, GameAction.Attack("shuffle-hand-a0"))
        assertEquals(Legality.Legal, result.legality)
        val updatedOpponent = result.state.players[1]
        assertEquals(opponentHand.size, updatedOpponent.hand.size) // shuffled card removed; turn start draw added one
        assertEquals(state.players[1].deck.size, updatedOpponent.deck.size)
        assertTrue(updatedOpponent.discard.isEmpty())
        val revealed = result.events.single { it.type == "OpponentCardsShuffledIntoDeck" }
        val shuffledCard = opponentHand.single { catalog.get(it.definitionId).canonicalName in revealed.message }
        assertTrue(updatedOpponent.hand.any { it.id == opponentHand.single { it != shuffledCard }.id })
        assertEquals(1, (updatedOpponent.hand + updatedOpponent.deck).count { it.id == shuffledCard.id })
        assertEquals(result.state, rules.apply(state, GameAction.Attack("shuffle-hand-a0")).state)
    }
    @Test fun `tool count damage includes all of the player's Pokemon`() {
        val attack = AttackDefinition("tool-count-a0", "Gadget Show", listOf(EnergyType.COLORLESS), listOf(Effect.OwnToolCountDamage(30)))
        val definition = TestCatalog.get("TEST-001").copy(id = "tool-count-check", attacks = listOf(attack))
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "tool-count-check") definition else TestCatalog.get(id) }
        val rules = RulesEngine(catalog)
        val base = main()
        val attacker = base.players[0].active!!.copy(cards = listOf(card("attacker", "tool-count-check")), tool = card("tool-a", "TEST-006"))
        val bench = base.players[0].bench.map { it.copy(tool = card("tool-b", "TEST-006")) }
        val state = base.copy(players = listOf(base.players[0].copy(active = attacker, bench = bench), base.players[1]))
        assertEquals(60, rules.preview(state, "tool-count-a0")!!.damage)
        val applied = rules.apply(state, GameAction.Attack("tool-count-a0"))
        assertEquals(Legality.Legal, applied.legality)
        assertTrue(applied.events.any { it.type == "AttackDeclared" && it.amount == 60 })
    }
    @Test fun `Roto Call searches any number of matching Pokemon up to free bench spaces then shuffles`() {
        val rotom = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-092"), emptyMap())!!
        val attack = rotom.attacks.first { it.name == "Roto Call" }
        assertEquals(listOf(Effect.SearchRotomToBench), attack.effects)
        val rotomBasic = TestCatalog.get("TEST-001").copy(id = "rotom-basic", name = "Rotom", category = Category.POKEMON, stage = Stage.BASIC)
        val rotomV = rotomBasic.copy(id = "rotom-v", name = "Rotom V")
        val unrelated = rotomBasic.copy(id = "unrelated", name = "Pikachu")
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) {
                rotom.id -> rotom
                rotomBasic.id -> rotomBasic
                rotomV.id -> rotomV
                unrelated.id -> unrelated
                else -> TestCatalog.get(id)
            }
        }
        val rules = RulesEngine(catalog)
        val base = main()
        val rotomCards = listOf(card("rotom-card-a", rotomBasic.id), card("rotom-card-b", rotomV.id))
        val unrelatedCard = card("not-rotom", unrelated.id)
        val state = base.copy(players = listOf(base.players[0].copy(
            active = base.players[0].active!!.copy(cards = listOf(card("rotom-attacker", rotom.id))),
            deck = rotomCards + unrelatedCard + base.players[0].deck), base.players[1]))
        val declared = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, declared.legality)
        val choice = declared.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.ATTACK_ROTOM_SEARCH, choice.kind)
        assertEquals(setOf("rotom-card-a", "rotom-card-b"), choice.candidates.toSet())
        assertEquals(2, choice.max)
        val resolved = rules.apply(declared.state, GameAction.ChooseCards(listOf("rotom-card-a")))
        assertEquals(Legality.Legal, resolved.legality)
        assertTrue(resolved.state.players[0].bench.any { it.id == "rotom-card-a" })
        assertTrue(resolved.state.players[0].deck.any { it.id == "rotom-card-b" })
        assertTrue(resolved.state.players[0].deck.any { it.id == "not-rotom" })
        assertTrue(resolved.events.any { it.type == "RotomSearched" && it.amount == 1 })
        assertTrue(resolved.events.any { it.type == "DeckShuffled" })

        val fullBenchState = state.copy(players = listOf(state.players[0].copy(bench = List(5) { field("full-bench-$it") }), state.players[1]))
        val fullBenchResult = rules.apply(fullBenchState, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, fullBenchResult.legality)
        assertNull(fullBenchResult.state.resolution)
        assertEquals(5, fullBenchResult.state.players[0].bench.size)
        assertTrue(fullBenchResult.events.any { it.type == "DeckShuffled" })
    }
    @Test fun `Teal Dance attaches basic Grass Energy and draws only when energy is attached`() {
        val ogerpon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-025"), emptyMap())!!
        val ability = ogerpon.abilities.single()
        val specialGrassDefinition = TestCatalog.get("TEST-004").copy(id = "special-grass-energy", name = "Special Grass Energy", type = EnergyType.GRASS, basicEnergy = false)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            ogerpon.id -> ogerpon
            specialGrassDefinition.id -> specialGrassDefinition
            else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val base = main()
        val basicGrass = card("basic-grass", "TEST-005")
        val secondBasicGrass = card("basic-grass-2", "TEST-005")
        val specialGrass = card("special-grass", specialGrassDefinition.id)
        val ogerponActive = base.players[0].active!!.copy(cards = listOf(card("ogerpon-active", ogerpon.id)))
        val ogerponBench = base.players[0].bench.single().copy(cards = listOf(card("ogerpon-bench", ogerpon.id)))
        val state = base.copy(players = listOf(base.players[0].copy(active = ogerponActive, bench = listOf(ogerponBench),
            hand = listOf(basicGrass, secondBasicGrass, specialGrass), deck = base.players[0].deck), base.players[1]))
        assertEquals(Legality.Legal, rules.validate(state, GameAction.UseAbility("ogerpon-active", ability.id)))
        val used = rules.apply(state, GameAction.UseAbility("ogerpon-active", ability.id))
        assertEquals(Legality.Legal, used.legality)
        assertEquals(setOf("basic-grass", "basic-grass-2"), used.state.resolution!!.choices.single().candidates.toSet())
        assertFalse(used.state.players[0].active!!.energies.any { it.id == "basic-grass" })
        val attached = rules.apply(used.state, GameAction.ChooseCards(listOf("basic-grass")))
        assertEquals(Legality.Legal, attached.legality)
        assertEquals(setOf("a-e0", "basic-grass"), attached.state.players[0].active!!.energies.map { it.id }.toSet())
        assertTrue(attached.state.players[0].hand.any { it.id == "special-grass" })
        assertEquals(state.players[0].hand.size, attached.state.players[0].hand.size) // one Energy out, one draw in
        assertTrue(attached.events.any { it.type == "CardsDrawn" })
        assertIs<Legality.Illegal>(rules.validate(attached.state, GameAction.UseAbility("ogerpon-active", ability.id)))
        // A second Ogerpon has its own once-per-turn ability instance.
        assertEquals(Legality.Legal, rules.validate(attached.state, GameAction.UseAbility("ogerpon-bench", ability.id)))
        assertIs<Legality.Illegal>(rules.validate(state.copy(players = listOf(state.players[0].copy(hand = listOf(specialGrass)), state.players[1])),
            GameAction.UseAbility("ogerpon-active", ability.id)))
    }
    @Test fun `Myriad Leaf Shower counts Energy attached to both Active Pokemon`() {
        val source = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-025"), emptyMap())!!
        val attack = source.attacks.first()
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == source.id) source else TrainerCatalog.get(id) }
        val rules = RulesEngine(catalog)
        val base = main()
        val attacker = base.players[0].active!!.copy(cards = listOf(card("ogerpon", source.id)),
            energies = listOf(card("a1", "TEST-005"), card("a2", "TEST-005"), card("a3", "TEST-005")))
        val defender = base.players[1].active!!.copy(energies = listOf(card("d1", "TEST-004"), card("d2", "TEST-004"), card("d3", "TEST-004")))
        val state = base.copy(players = listOf(base.players[0].copy(active = attacker), base.players[1].copy(active = defender)))
        assertEquals(420, rules.preview(state, attack.id)!!.damage)
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertTrue(result.events.any { it.type == "AttackDeclared" && it.amount == 420 })
    }
    @Test fun `Terapagos attack first turn restriction depends on who starts`() {
        val terapagos = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-179"), emptyMap())!!
        val attack = terapagos.attacks.first()
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == terapagos.id) terapagos else TrainerCatalog.get(id) }
        val rules = RulesEngine(catalog)
        val base = main()
        val attacker = base.players[1].active!!.copy(cards = listOf(card("tera", terapagos.id)),
            energies = List(attack.cost.size) { card("tera-e$it", "TEST-004") })
        val turnTwo = base.copy(players = listOf(base.players[0], base.players[1].copy(active = attacker)),
            startingPlayer = 0, currentPlayer = 1, turn = 2)
        assertIs<Legality.Illegal>(rules.validate(turnTwo, GameAction.Attack(attack.id)))
        val laterTurn = turnTwo.copy(turn = 4)
        assertEquals(Legality.Legal, rules.validate(laterTurn, GameAction.Attack(attack.id)))
        assertEquals(30, rules.preview(laterTurn, attack.id)!!.damage)
    }
    @Test fun `Crown Opal prevents only damage from basic noncolorless attackers for one turn`() {
        val terapagos = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-179"), emptyMap())!!
        val attack = terapagos.attacks.first { it.name == "Crown Opal" }
        val sturdy = TestCatalog.get("TEST-001").copy(id = "sturdy-target", hp = 500, weakness = null)
        val basicGrass = TestCatalog.get("TEST-003").copy(id = "basic-grass-attacker")
        val evolvedGrass = TestCatalog.get("TEST-002").copy(id = "evolved-grass-attacker", type = EnergyType.GRASS)
        val basicColorless = TestCatalog.get("TEST-001").copy(id = "basic-colorless-attacker", type = EnergyType.COLORLESS)
        val grassEnergy = TestCatalog.get("TEST-005").copy(id = "tera-grass-energy")
        val waterEnergy = TestCatalog.get("TEST-004").copy(id = "tera-water-energy", name = "Basic Water Energy", type = EnergyType.WATER)
        val lightningEnergy = TestCatalog.get("TEST-004").copy(id = "tera-lightning-energy", name = "Basic Lightning Energy", type = EnergyType.LIGHTNING)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            terapagos.id -> terapagos; sturdy.id -> sturdy; basicGrass.id -> basicGrass
            evolvedGrass.id -> evolvedGrass; basicColorless.id -> basicColorless
            grassEnergy.id -> grassEnergy; waterEnergy.id -> waterEnergy; lightningEnergy.id -> lightningEnergy
            else -> TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val base = main()
        val terapagosField = PokemonInstance(listOf(card("tera", terapagos.id)),
            energies = listOf(card("tera-grass", grassEnergy.id), card("tera-water", waterEnergy.id), card("tera-lightning", lightningEnergy.id)), enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(card("sturdy", sturdy.id)), enteredPlayTurn = 0)
        val state = base.copy(players = listOf(base.players[0].copy(active = terapagosField), base.players[1].copy(active = target)))
        val used = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, used.legality)
        val protected = used.state.players[0].active!!
        assertEquals(used.state.turn, protected.preventBasicNonColorlessDamageTurn)
        fun damageFrom(id: String, turn: Int = used.state.turn) = rules.effects.damage(PokemonInstance(listOf(card("test-$id", id)), enteredPlayTurn = 0),
            protected, listOf(Effect.Damage(70)), turn = turn)
        assertEquals(0, damageFrom(basicGrass.id))
        assertEquals(70, damageFrom(evolvedGrass.id))
        assertEquals(70, damageFrom(basicColorless.id))
        assertEquals(70, damageFrom(basicGrass.id, used.state.turn + 1))
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
    @Test fun `Blaziken ex Seething Spirit attaches one Basic Energy from discard to any own Pokemon once per turn`() {
        val blaziken = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-024"), emptyMap())!!
        val basic = CardDefinition("test-basic-energy", "Basic Fire Energy", "Test", "R", category = Category.ENERGY, type = EnergyType.FIRE, basicEnergy = true)
        val special = CardDefinition("test-special-energy", "Special Energy", "Test", "S", category = Category.ENERGY, type = EnergyType.COLORLESS, basicEnergy = false)
        val target = CardDefinition("test-energy-target", "Target", "Test", "T", category = Category.POKEMON, hp = 100)
        val rules = RulesEngine(object : CardCatalog { override fun get(id: String) = when (id) {
            blaziken.id -> blaziken; basic.id -> basic; special.id -> special; target.id -> target; else -> TrainerCatalog.get(id)
        } })
        val basicCard = card("discard-basic", basic.id); val specialCard = card("discard-special", special.id)
        val ally = PokemonInstance(listOf(card("ally", target.id)), enteredPlayTurn = 0)
        val state = GameState("seething-spirit", 52, RngState(52), listOf(
            PlayerState(deck = List(10) { card("d0-$it") }, discard = listOf(basicCard, specialCard), prizes = List(6) { card("p0-$it") },
                active = PokemonInstance(listOf(card("blaziken", blaziken.id)), enteredPlayTurn = 0), bench = listOf(ally)),
            PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") }, active = field("op"))
        ), 0, 0, turn = 3, phase = Phase.MAIN)
        val ability = blaziken.abilities.single()
        val used = rules.apply(state, GameAction.UseAbility("blaziken", ability.id))
        assertEquals(Legality.Legal, used.legality)
        val chooseEnergy = used.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.ABILITY_DISCARD_ENERGY_FROM_DISCARD, chooseEnergy.kind)
        assertEquals(listOf(basicCard.id), chooseEnergy.candidates)
        val chooseTarget = rules.apply(used.state, GameAction.ChooseCards(listOf(basicCard.id)))
        assertEquals(ChoiceKind.ABILITY_DISCARD_ENERGY_TARGET, chooseTarget.state.resolution!!.choices.single().kind)
        val attached = rules.apply(chooseTarget.state, GameAction.ChooseCards(listOf(ally.id)))
        assertEquals(Legality.Legal, attached.legality)
        assertTrue(attached.state.players[0].discard.any { it.id == specialCard.id })
        assertFalse(attached.state.players[0].discard.any { it.id == basicCard.id })
        assertTrue(attached.state.players[0].bench.single().energies.any { it.id == basicCard.id })
        assertEquals(Legality.Illegal("Esta habilidade já foi usada neste turno."), rules.validate(attached.state, GameAction.UseAbility("blaziken", ability.id)))
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
        // v0.5 adds deck identity/list metadata; v0.6 adds retreat-block state; v0.7 adds status and damage-reduction state.
        // v0.8 adds protection from all Basic Pokémon attacks; v0.9 adds timed weakness suppression.
        // v1.1 enables additional card rules that can change deterministic attack outcomes.
        // v1.2 serializes Festival Lead bonus attack availability; v1.3 weakness overrides; v1.4 copied-attack state.
        assertEquals("5bafc9c091b3eaf27f4a9030863c028d527cb455beb4248cf3331cb90b656a97", digest)
    }

    @Test fun `Gothitelle Distorted Future shuffles opponent hand then draws three once per turn`() {
        val gothitelle = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10.5w-043"), emptyMap())!!
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == gothitelle.id) gothitelle else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        }
        val rules = RulesEngine(catalog)
        val ability = gothitelle.abilities.single()
        assertEquals(3, ability.opponentShuffleHandAndDraw)
        val start = main(4).let { state -> state.copy(players = state.players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("gothitelle", gothitelle.id))
            else player.copy(hand = List(5) { card("opp-hand-$it", "TEST-004") }, deck = List(8) { card("opp-deck-$it", "TEST-004") })
        }) }
        val used = rules.apply(start, GameAction.UseAbility("gothitelle", ability.id))
        assertEquals(Legality.Legal, used.legality)
        assertEquals(3, used.state.players[1].hand.size)
        assertEquals(10, used.state.players[1].deck.size)
        assertTrue(used.events.any { it.type == "OpponentHandShuffled" })
        assertEquals(Legality.Illegal("Esta habilidade já foi usada neste turno."),
            rules.validate(used.state, GameAction.UseAbility("gothitelle", ability.id)))
    }

    @Test fun `Alluring Light makes both players draw a card`() {
        val row = CatalogRuleCompiler.rows.values.first { card ->
            card["abilities"]?.jsonArray?.any { it.jsonObject["effect"]?.jsonPrimitive?.contentOrNull ==
                "Once during your turn, you may have each player draw a card." } == true
        }
        val definition = CatalogRuleCompiler.compile(row, emptyMap())!!
        val rules = RulesEngine(object : CardCatalog {
            override fun get(id: String) = if (id == definition.id) definition else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        })
        val ability = definition.abilities.single()
        val start = main(4).let { state -> state.copy(players = state.players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("alluring", definition.id)) else player
        }) }
        val result = rules.apply(start, GameAction.UseAbility("alluring", ability.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(start.players[0].hand.size + 1, result.state.players[0].hand.size)
        assertEquals(start.players[1].hand.size + 1, result.state.players[1].hand.size)
        assertEquals(start.players[0].deck.size - 1, result.state.players[0].deck.size)
        assertEquals(start.players[1].deck.size - 1, result.state.players[1].deck.size)
    }

    @Test fun `Coiling Crush discards one Active Energy for each heads`() {
        val parsed = CatalogRuleCompiler.compileAttack("coiling-crush", 0, buildJsonObject {
            put("name", "Coiling Crush")
            put("effect", "Flip 2 coins. For each heads, discard an Energy from your opponent's Active Pokémon.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("coiling-crush-attacker", "Coiling Crush Attacker", "Test", "1",
            category = Category.POKEMON, hp = 120, attacks = listOf(parsed))
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == attacker.id) attacker else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        }
        val rules = RulesEngine(catalog)
        val seed = (1L..10_000L).first { seed ->
            val rng = SeededRng(RngState(seed))
            rng.nextInt(2) == 0 && rng.nextInt(2) == 0
        }
        val start = main(4).copy(seed = seed, rng = RngState(seed)).let { state -> state.copy(players = state.players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("attacker", attacker.id, 1))
            else player.copy(active = player.active!!.copy(energies = List(3) { card("target-energy-$it") }))
        }) }
        val result = rules.apply(start, GameAction.Attack(parsed.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(1, result.state.players[1].active!!.energies.size)
        assertEquals(2, result.state.players[1].discard.count { it.id.startsWith("target-energy-") })
        assertTrue(result.events.any { it.type == "DefendingEnergyDiscarded" })
    }

    @Test fun `Lucky Attachment requires one Energy and allows choosing any friendly Pokemon`() {
        val parsed = CatalogRuleCompiler.compileAttack("lucky-attachment", 0, buildJsonObject {
            put("name", "Lucky Attachment")
            put("effect", "Attach a Basic Energy card from your hand to 1 of your Pokémon.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("lucky-attachment-attacker", "Lucky Attachment Attacker", "Test", "1",
            category = Category.POKEMON, hp = 120, attacks = listOf(parsed))
        val rules = RulesEngine(object : CardCatalog {
            override fun get(id: String) = if (id == attacker.id) attacker else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        })
        var state = main(4).copy(players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("attacker", attacker.id, 1), bench = listOf(field("bench"))) else player
        })
        val attack = rules.apply(state, GameAction.Attack(parsed.id))
        assertEquals(Legality.Legal, attack.legality)
        state = attack.state
        val energyChoice = state.resolution!!.choices.first()
        assertEquals(ChoiceKind.ATTACK_HAND_ENERGY, energyChoice.kind)
        assertEquals(1, energyChoice.min)
        state = rules.apply(state, GameAction.ChooseCards(listOf("energy"))).also { assertEquals(Legality.Legal, it.legality) }.state
        val targetChoice = state.resolution!!.choices.first()
        assertEquals(ChoiceKind.ATTACK_HAND_ENERGY_TARGET, targetChoice.kind)
        state = rules.apply(state, GameAction.ChooseCards(listOf("bench"))).also { assertEquals(Legality.Legal, it.legality) }.state
        assertEquals(1, state.players[0].bench.single().energies.size)
        assertTrue(state.resolution?.choices.isNullOrEmpty())
    }

    @Test fun `Homeward Chime shuffles a chosen benched Pokemon and its attached Energy into the deck`() {
        val parsed = CatalogRuleCompiler.compileAttack("homeward-chime", 0, buildJsonObject {
            put("name", "Homeward Chime")
            put("effect", "Shuffle 1 of your Benched Pokémon and all attached cards into your deck.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("homeward-chime-attacker", "Homeward Chime Attacker", "Test", "1",
            category = Category.POKEMON, hp = 120, attacks = listOf(parsed))
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == attacker.id) attacker else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        }
        val rules = RulesEngine(catalog)
        val target = field("to-shuffle").copy(energies = listOf(card("shuffled-energy", "TEST-004")))
        val start = main(4).copy(players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("attacker", attacker.id, 1), bench = listOf(target)) else player
        })
        val attack = rules.apply(start, GameAction.Attack(parsed.id))
        assertEquals(Legality.Legal, attack.legality)
        assertEquals(ChoiceKind.ATTACK_SHUFFLE_OWN_BENCH, attack.state.resolution!!.choices.single().kind)
        val result = rules.apply(attack.state, GameAction.ChooseCards(listOf(target.id)))
        assertEquals(Legality.Legal, result.legality)
        assertTrue(result.state.players[0].bench.isEmpty())
        assertTrue(result.state.players[0].deck.any { it.id == target.cards.single().id })
        assertTrue(result.state.players[0].deck.any { it.id == "shuffled-energy" })
        assertEquals(22, result.state.players[0].deck.size)
    }

    @Test fun `Parallel Placement searches only named Pokemon and benches up to requested amount`() {
        val parsed = CatalogRuleCompiler.compileAttack("parallel-placement", 0, buildJsonObject {
            put("name", "Parallel Placement")
            put("effect", "Search your deck for up to 3 Charjabug and put them onto your Bench. Then, shuffle your deck.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("parallel-placement-attacker", "Parallel Placement Attacker", "Test", "1",
            category = Category.POKEMON, hp = 120, attacks = listOf(parsed))
        val charjabug = CardDefinition("charjabug-test", "Charjabug", "Test", "2", category = Category.POKEMON, hp = 90)
        val other = CardDefinition("other-test", "Not Charjabug", "Test", "3", category = Category.POKEMON, hp = 90)
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) {
                attacker.id -> attacker
                charjabug.id -> charjabug
                other.id -> other
                else -> TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
            }
        }
        val rules = RulesEngine(catalog)
        val searchDeck = listOf(card("charja-1", charjabug.id), card("other", other.id), card("charja-2", charjabug.id), card("charja-3", charjabug.id))
        val start = main(4).copy(players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(deck = searchDeck, active = field("attacker", attacker.id, 1)) else player
        })
        val attack = rules.apply(start, GameAction.Attack(parsed.id))
        assertEquals(Legality.Legal, attack.legality)
        val choice = attack.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.ATTACK_NAMED_POKEMON_TO_BENCH, choice.kind)
        assertEquals(listOf("charja-1", "charja-2", "charja-3"), choice.candidates)
        val result = rules.apply(attack.state, GameAction.ChooseCards(listOf("charja-1", "charja-2")))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(3, result.state.players[0].bench.size)
        assertEquals(2, result.state.players[0].bench.count { it.top.definitionId == charjabug.id })
        assertEquals(2, result.state.players[0].deck.size)
        assertTrue(result.state.players[0].deck.any { it.id == "other" })
    }

    @Test fun `Turbo Flare attaches several Basic Energy cards only to Benched Pokemon and allows concentration`() {
        val parsed = CatalogRuleCompiler.compileAttack("turbo-flare", 0, buildJsonObject {
            put("name", "Turbo Flare")
            put("effect", "Search your deck for up to 3 Basic Energy cards and attach them to your Benched Pokémon in any way you like. Then, shuffle your deck.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("turbo-flare-attacker", "Turbo Flare Attacker", "Test", "1",
            category = Category.POKEMON, hp = 120, attacks = listOf(parsed))
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == attacker.id) attacker else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        }
        val rules = RulesEngine(catalog)
        val energies = List(3) { card("turbo-energy-$it", "TEST-004") }
        val start = main(4).copy(players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(deck = energies, active = field("attacker", attacker.id, 1), bench = listOf(field("bench"))) else player
        })
        var state = rules.apply(start, GameAction.Attack(parsed.id)).also { assertEquals(Legality.Legal, it.legality) }.state
        repeat(3) { index ->
            val pickEnergy = rules.apply(state, GameAction.ChooseCards(listOf("turbo-energy-$index")))
            assertEquals(Legality.Legal, pickEnergy.legality)
            val chooseTarget = rules.apply(pickEnergy.state, GameAction.ChooseCards(listOf("bench")))
            assertEquals(Legality.Legal, chooseTarget.legality)
            state = chooseTarget.state
        }
        assertEquals(3, state.players[0].bench.single().energies.size)
        assertEquals(1, state.players[0].active!!.energies.size)
        assertTrue(state.resolution?.choices.isNullOrEmpty())
    }

    @Test fun `Flower Shower draws for each player independently up to their remaining deck`() {
        val parsed = CatalogRuleCompiler.compileAttack("flower-shower", 0, buildJsonObject {
            put("name", "Flower Shower")
            put("effect", "Each player draws 3 cards.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("flower-shower-attacker", "Flower Shower Attacker", "Test", "1",
            category = Category.POKEMON, hp = 120, attacks = listOf(parsed))
        val rules = RulesEngine(object : CardCatalog {
            override fun get(id: String) = if (id == attacker.id) attacker else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        })
        val start = main(4).copy(players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("attacker", attacker.id, 1), deck = listOf(card("self-draw")))
            else player.copy(deck = listOf(card("opponent-draw-1"), card("opponent-draw-2")))
        })
        val result = rules.apply(start, GameAction.Attack(parsed.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(start.players[0].hand.size + 1, result.state.players[0].hand.size)
        assertEquals(start.players[1].hand.size + 2, result.state.players[1].hand.size)
        assertTrue(result.state.players.all { it.deck.isEmpty() })
    }

    @Test fun `Devolution Ray returns the evolved Active card to the opponent's hand`() {
        val parsed = CatalogRuleCompiler.compileAttack("devolution-ray", 0, buildJsonObject {
            put("name", "Devolution Ray")
            put("effect", "If your opponent's Active Pokémon is an evolved Pokémon, devolve it by putting the highest Stage Evolution card on it into your opponent's hand.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("devolution-ray-attacker", "Devolution Ray Attacker", "Test", "1",
            category = Category.POKEMON, hp = 120, attacks = listOf(parsed))
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == attacker.id) attacker else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        }
        val rules = RulesEngine(catalog)
        val evolved = field("opponent-active").copy(cards = listOf(card("opponent-base", "TEST-001"), card("opponent-evolution", "TEST-002")))
        val start = main(4).copy(players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("attacker", attacker.id, 1)) else player.copy(active = evolved)
        })
        val attack = rules.apply(start, GameAction.Attack(parsed.id))
        assertEquals(Legality.Legal, attack.legality)
        assertEquals(ChoiceKind.ATTACK_DEVOLVE_OPPONENT_ACTIVE, attack.state.resolution!!.choices.single().kind)
        val result = rules.apply(attack.state, GameAction.ChooseCards(listOf(evolved.id)))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(listOf("opponent-base"), result.state.players[1].active!!.cards.map { it.id })
        assertTrue(result.state.players[1].hand.any { it.id == "opponent-evolution" })
    }

    @Test fun `attack discards a Stadium to its owner discard pile`() {
        val attack = CatalogRuleCompiler.compileAttack("discard-stadium", 0, buildJsonObject {
            put("name", "Stadium Break")
            put("effect", "Discard a Stadium in play.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("stadium-breaker", "Stadium Breaker", "Test", "1", category = Category.POKEMON,
            hp = 120, attacks = listOf(attack))
        val stadium = CardDefinition("test-stadium", "Test Stadium", "Test", "2", category = Category.TRAINER,
            trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.HpModifier(Stage.BASIC, 10))
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) {
                attacker.id -> attacker
                stadium.id -> stadium
                else -> TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
            }
        }
        val rules = RulesEngine(catalog)
        val activeStadium = card("active-stadium", stadium.id)
        val start = main(4).copy(stadium = activeStadium, stadiumOwner = 1, players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("attacker", attacker.id, 1)) else player
        })
        val result = rules.apply(start, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertNull(result.state.stadium)
        assertTrue(result.state.players[1].discard.any { it.id == activeStadium.id })
        assertTrue(result.events.any { it.type == "StadiumDiscarded" })
    }

    @Test fun `opponent prizes taken multiplies attack damage correctly`() {
        val attack = CatalogRuleCompiler.compileAttack("opponent-prizes", 0, buildJsonObject {
            put("name", "Prize Counter")
            put("effect", "This attack does 60 damage for each Prize card your opponent has taken.")
            put("damage", "60×")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("prize-counter", "Prize Counter", "Test", "1", category = Category.POKEMON,
            hp = 120, type = EnergyType.COLORLESS, attacks = listOf(attack))
        val defender = CardDefinition("prize-target", "Prize Target", "Test", "2", category = Category.POKEMON,
            hp = 200, type = EnergyType.COLORLESS)
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) {
                attacker.id -> attacker
                defender.id -> defender
                else -> TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
            }
        }
        val rules = RulesEngine(catalog)
        val start = main(4).copy(players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("attacker", attacker.id, 1))
            else player.copy(active = field("defender", defender.id), prizes = player.prizes.drop(2))
        })
        val result = rules.apply(start, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(120, result.state.players[1].active!!.damage)
    }

    @Test fun `search up to three Pokemon reveals and moves only chosen Pokemon to hand`() {
        val attack = CatalogRuleCompiler.compileAttack("search-three-pokemon", 0, buildJsonObject {
            put("name", "Threefold Search")
            put("effect", "Search your deck for up to 3 Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("threefold-attacker", "Threefold Attacker", "Test", "1", category = Category.POKEMON,
            hp = 120, attacks = listOf(attack))
        val pokemon = CardDefinition("searchable-pokemon", "Searchable", "Test", "2", category = Category.POKEMON, hp = 60)
        val other = CardDefinition("searchable-other", "Not a Pokémon", "Test", "3", category = Category.TRAINER, trainerType = TrainerType.ITEM)
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) {
                attacker.id -> attacker
                pokemon.id -> pokemon
                other.id -> other
                else -> TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
            }
        }
        val rules = RulesEngine(catalog)
        val deck = listOf(card("search-1", pokemon.id), card("item", other.id), card("search-2", pokemon.id), card("search-3", pokemon.id))
        val start = main(4).copy(players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(deck = deck, active = field("attacker", attacker.id, 1)) else player
        })
        val attacked = rules.apply(start, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, attacked.legality)
        val choice = attacked.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.ATTACK_POKEMON_SEARCH, choice.kind)
        assertEquals(3, choice.max)
        val searched = rules.apply(attacked.state, GameAction.ChooseCards(listOf("search-1", "search-3")))
        assertEquals(Legality.Legal, searched.legality)
        assertTrue(searched.state.players[0].hand.any { it.id == "search-1" })
        assertTrue(searched.state.players[0].hand.any { it.id == "search-3" })
        assertFalse(searched.state.players[0].hand.any { it.id == "item" })
        assertEquals(2, searched.state.players[0].deck.size)
    }

    @Test fun `attack returns its Active Pokemon and every attached card to hand`() {
        val attack = CatalogRuleCompiler.compileAttack("return-self", 0, buildJsonObject {
            put("name", "Homeward Attack")
            put("effect", "Put this Pokémon and all attached cards into your hand.")
            put("damage", "20")
            put("cost", buildJsonArray { add("Colorless") })
        })!!
        val attacker = CardDefinition("return-self-attacker", "Returning Attacker", "Test", "1", category = Category.POKEMON,
            hp = 120, attacks = listOf(attack))
        val rules = RulesEngine(object : CardCatalog {
            override fun get(id: String) = if (id == attacker.id) attacker else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        })
        val active = field("returning", attacker.id, 1).copy(tool = card("returning-tool", "TEST-004"))
        val start = main(4).copy(players = main(4).players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = active) else player
        })
        val result = rules.apply(start, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertTrue(result.state.players[0].hand.any { it.id == "returning" })
        assertTrue(result.state.players[0].hand.any { it.id == "returning-e0" })
        assertTrue(result.state.players[0].hand.any { it.id == "returning-tool" })
        assertNull(result.state.players[0].pokemon().firstOrNull { it.id == "returning" })
    }

    @Test fun `Iron Leaves entry ability switches and can move energy from former Active`() {
        val leaves = CardDefinition("iron-leaves-test", "Iron Leaves ex", "Test", "1", category = Category.POKEMON,
            hp = 220, abilities = listOf(AbilityDefinition("iron-leaves-entry", "Rapid Vernier", "", onBenchSwitchAndMoveEnergy = true)))
        val catalog = object : CardCatalog {
            override fun get(id: String) = if (id == leaves.id) leaves else TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        }
        val rules = RulesEngine(catalog)
        val original = main()
        val start = original.copy(players = original.players.mapIndexed { index, player ->
            if (index == 0) player.copy(hand = player.hand + card("leaves", leaves.id)) else player
        })
        val played = rules.apply(start, GameAction.PlayBasic("leaves"))
        assertEquals(Legality.Legal, played.legality)
        assertEquals(ChoiceKind.ON_BENCH_SWITCH_AND_MOVE_ENERGY, played.state.resolution!!.choices.single().kind)

        val switched = rules.apply(played.state, GameAction.ChooseCards(listOf("leaves")))
        assertEquals(Legality.Legal, switched.legality)
        assertEquals("leaves", switched.state.players[0].active!!.id)
        val energyChoice = switched.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.ON_BENCH_MOVE_ENERGY, energyChoice.kind)
        assertEquals(listOf("a-e0"), energyChoice.candidates)

        val moved = rules.apply(switched.state, GameAction.ChooseCards(listOf("a-e0")))
        assertEquals(Legality.Legal, moved.legality)
        assertEquals(listOf("a-e0"), moved.state.players[0].active!!.energies.map { it.id })
        assertTrue(moved.state.players[0].bench.first { it.id == "a" }.energies.isEmpty())
        assertEquals(Phase.MAIN, moved.state.phase)
    }

    @Test fun `Mega Lucario attack attaches compatible discard energy to chosen Benched Pokemon and can stop early`() {
        val attack = CatalogRuleCompiler.compileAttack("mega-lucario-test", 0, buildJsonObject {
            put("name", "Aura Jab")
            put("effect", "Attach up to 3 Basic {F} Energy cards from your discard pile to your Benched Pokémon in any way you like.")
            put("damage", "0")
            put("cost", buildJsonArray {})
        })!!
        val attacker = CardDefinition("mega-lucario-attacker", "Mega Lucario ex", "Test", "1", category = Category.POKEMON, hp = 320, attacks = listOf(attack))
        val bench = CardDefinition("lucario-bench", "Riolu", "Test", "2", category = Category.POKEMON, hp = 70, type = EnergyType.FIGHTING)
        val fighting = CardDefinition("lucario-fighting", "Fighting Energy", "Test", "3", category = Category.ENERGY, type = EnergyType.FIGHTING, basicEnergy = true)
        val water = CardDefinition("lucario-water", "Water Energy", "Test", "4", category = Category.ENERGY, type = EnergyType.WATER, basicEnergy = true)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            attacker.id -> attacker; bench.id -> bench; fighting.id -> fighting; water.id -> water
            else -> TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val original = main()
        val start = original.copy(players = original.players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("lucario", attacker.id),
                bench = listOf(field("riolu-1", bench.id), field("riolu-2", bench.id)),
                discard = listOf(card("fighting-1", fighting.id), card("fighting-2", fighting.id), card("water-1", water.id))) else player
        })
        val started = rules.apply(start, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, started.legality)
        val energyChoice = started.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.ABILITY_DISCARD_ENERGY_FROM_DISCARD, energyChoice.kind)
        assertEquals(setOf("fighting-1", "fighting-2"), energyChoice.candidates.toSet())
        val selected = rules.apply(started.state, GameAction.ChooseCards(listOf("fighting-1")))
        assertEquals(Legality.Legal, selected.legality)
        assertEquals(ChoiceKind.ABILITY_DISCARD_ENERGY_TARGET, selected.state.resolution!!.choices.first().kind)
        val attached = rules.apply(selected.state, GameAction.ChooseCards(listOf("riolu-2")))
        assertEquals(Legality.Legal, attached.legality)
        assertEquals(listOf("fighting-1"), attached.state.players[0].bench.first { it.id == "riolu-2" }.energies.map { it.id })
        assertTrue(attached.state.players[0].discard.any { it.id == "fighting-2" })
        assertTrue(attached.state.players[0].discard.any { it.id == "water-1" })
        assertEquals(ChoiceKind.ABILITY_DISCARD_ENERGY_FROM_DISCARD, attached.state.resolution!!.choices.first().kind)
        val stopped = rules.apply(attached.state, GameAction.ChooseCards(emptyList()))
        assertEquals(Legality.Legal, stopped.legality)
        assertEquals(Phase.MAIN, stopped.state.phase)
    }

    @Test fun `Lillie Clefairy weakness change affects every N Pokemon and later Psychic damage`() {
        val psychicHit = AttackDefinition("psychic-hit", "Mind Ray", emptyList(), listOf(Effect.Damage(30)))
        val attacker = CardDefinition("lillie-attack-user", "Psychic Attacker", "Test", "1", category = Category.POKEMON,
            hp = 190, type = EnergyType.PSYCHIC, attacks = listOf(psychicHit))
        val lillie = CardDefinition("lillie-ability-owner", "Lillie's Clefairy ex", "Test", "4", category = Category.POKEMON,
            hp = 190, abilities = listOf(AbilityDefinition("fairy-zone", "Fairy Zone", "", changeOpponentNsPokemonWeaknessToPsychic = true)))
        val nPokemon = CardDefinition("lillie-n-pokemon", "N's Zoroark ex", "Test", "2", category = Category.POKEMON,
            hp = 280, type = EnergyType.DARKNESS, weakness = EnergyType.FIRE)
        val regular = CardDefinition("lillie-regular-pokemon", "Zoroark ex", "Test", "3", category = Category.POKEMON,
            hp = 280, type = EnergyType.DARKNESS, weakness = EnergyType.FIRE)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            attacker.id -> attacker; lillie.id -> lillie; nPokemon.id -> nPokemon; regular.id -> regular
            else -> TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val original = main()
        val start = original.copy(players = original.players.mapIndexed { index, player -> when (index) {
            0 -> player.copy(active = field("psychic-attacker", attacker.id), bench = listOf(field("lillie", lillie.id)))
            else -> player.copy(active = field("n-active", nPokemon.id), bench = listOf(field("n-bench", nPokemon.id), field("regular-bench", regular.id)))
        } })
        assertEquals(60, rules.preview(start, psychicHit.id)!!.damage)
        val nBenchActive = start.copy(players = start.players.mapIndexed { index, player ->
            if (index == 1) player.copy(active = player.bench.first { it.id == "n-bench" }, bench = player.bench.filterNot { it.id == "n-bench" }) else player
        })
        assertEquals(60, rules.preview(nBenchActive, psychicHit.id)!!.damage)
        val regularBenchActive = start.copy(players = start.players.mapIndexed { index, player ->
            if (index == 1) player.copy(active = player.bench.first { it.id == "regular-bench" }, bench = player.bench.filterNot { it.id == "regular-bench" }) else player
        })
        assertEquals(30, rules.preview(regularBenchActive, psychicHit.id)!!.damage)
    }

    @Test fun `Night Joker asks for an N bench attack and resolves the selected attack as Zoroark`() {
        val nightJoker = CatalogRuleCompiler.compileAttack("night-joker-test", 0, buildJsonObject {
            put("name", "Night Joker")
            put("effect", "Choose 1 of your Benched N's Pokémon's attacks and use it as this attack.")
            put("damage", "0")
            put("cost", buildJsonArray { add("Darkness"); add("Darkness") })
        })!!
        val zoroark = CardDefinition("night-joker-zoroark", "N's Zoroark ex", "Test", "1", category = Category.POKEMON,
            hp = 280, type = EnergyType.DARKNESS, attacks = listOf(nightJoker))
        val copiedAttack = AttackDefinition("copied-40", "Shadow Slash", emptyList(), listOf(Effect.Damage(40)))
        val otherAttack = AttackDefinition("copied-90", "Dark Impact", emptyList(), listOf(Effect.Damage(90)))
        val nPokemon = CardDefinition("night-joker-bench", "N's Zekrom", "Test", "2", category = Category.POKEMON,
            hp = 130, type = EnergyType.LIGHTNING, attacks = listOf(copiedAttack, otherAttack))
        val defender = CardDefinition("night-joker-defender", "Defender", "Test", "3", category = Category.POKEMON, hp = 220)
        val darkness = CardDefinition("night-joker-darkness", "Darkness Energy", "Test", "4", category = Category.ENERGY,
            type = EnergyType.DARKNESS, basicEnergy = true)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            zoroark.id -> zoroark; nPokemon.id -> nPokemon; defender.id -> defender; darkness.id -> darkness
            else -> TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val original = main()
        val start = original.copy(players = original.players.mapIndexed { index, player -> when (index) {
            0 -> player.copy(active = PokemonInstance(listOf(card("night-joker", zoroark.id)),
                energies = listOf(card("dark-a", darkness.id), card("dark-b", darkness.id)), enteredPlayTurn = 0),
                bench = listOf(field("n-zekrom", nPokemon.id)))
            else -> player.copy(active = field("night-defender", defender.id), bench = emptyList())
        } })
        val opened = rules.apply(start, GameAction.Attack(nightJoker.id))
        assertEquals(Legality.Legal, opened.legality)
        val choice = opened.state.resolution!!.choices.single()
        assertEquals(ChoiceKind.ATTACK_COPY_BENCHED_N, choice.kind)
        assertEquals(2, choice.candidates.size)
        assertTrue(choice.optionLabels.values.any { it.contains("Shadow Slash") })
        val selected = rules.apply(opened.state, GameAction.ChooseCards(listOf("n-zekrom::copy::${copiedAttack.id}")))
        assertEquals(Legality.Legal, selected.legality)
        assertEquals(40, selected.state.players[1].active!!.damage)
        assertTrue(selected.events.any { it.type == "AttackDeclared" && it.message.startsWith("Night Joker") })
        assertNull(selected.state.pendingCopiedAttack)
    }

    @Test fun `Shadowy Concealment reduces one Prize for an ex attack knockout and does not stack`() {
        val gengarAbility = AbilityDefinition("shadowy-test", "Shadowy Concealment", "", reducePrizeForDarknessKnockoutByEx = true)
        val gengar = CardDefinition("shadowy-gengar", "Mega Gengar ex", "Test", "1", category = Category.POKEMON,
            hp = 350, type = EnergyType.DARKNESS, abilities = listOf(gengarAbility), prizes = 3)
        val darkPokemon = CardDefinition("shadowy-victim", "Dark ex", "Test", "2", category = Category.POKEMON,
            hp = 100, type = EnergyType.DARKNESS, prizes = 3)
        val attack = AttackDefinition("shadowy-hit", "Ex Impact", emptyList(), listOf(Effect.Damage(100)))
        val attacker = CardDefinition("shadowy-attacker", "Attacker ex", "Test", "3", category = Category.POKEMON,
            hp = 300, attacks = listOf(attack))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            gengar.id -> gengar; darkPokemon.id -> darkPokemon; attacker.id -> attacker
            else -> TestCatalog.definitions[id] ?: TrainerCatalog.get(id)
        } }
        val rules = RulesEngine(catalog)
        val original = main()
        val state = original.copy(currentPlayer = 1, turn = 3, players = original.players.mapIndexed { index, player ->
            if (index == 0) player.copy(active = field("dark-victim", darkPokemon.id),
                bench = listOf(field("gengar-a", gengar.id), field("gengar-b", gengar.id)))
            else player.copy(active = field("attacker-ex", attacker.id), bench = emptyList())
        })
        val result = rules.apply(state, GameAction.Attack(attack.id))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(2, result.state.prizesOwed[1])
        assertEquals(2, result.state.pendingPrizes)
        assertTrue(result.events.any { it.message.contains("2 Prêmio(s) após Shadowy Concealment") })
    }
}
