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
        assertEquals("155d0cf1e27f0632a25f3370e9b6996196195524754f26a15d163987467c6a7e", digest)
    }
}
