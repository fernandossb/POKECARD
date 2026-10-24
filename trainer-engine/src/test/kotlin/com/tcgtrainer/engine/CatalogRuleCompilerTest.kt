package com.tcgtrainer.engine

import kotlinx.serialization.json.*
import kotlin.test.*

class CatalogRuleCompilerTest {
    @Test fun `evolution accepts another print of the same species but preserves owner and stage`() {
        val evolution = TrainerCatalog.get("me01-077")
        val base = TrainerCatalog.get("me01-076").copy(id = "another-riolu-print")
        assertTrue(evolution.canEvolveFrom(base))
        assertFalse(evolution.canEvolveFrom(base.copy(canonicalName = "Team Rocket's Riolu")))
        assertFalse(evolution.canEvolveFrom(base.copy(stage = Stage.STAGE_ONE)))
        assertFalse(evolution.canEvolveFrom(TrainerCatalog.get("me01-072")))
    }
    private fun attack(effect: String, damage: String = "20") = buildJsonObject {
        put("name", "Teste"); put("effect", effect); put("damage", damage)
        put("cost", buildJsonArray { add("Colorless") })
    }
    @Test fun `unknown clauses and damage formulas reject the whole attack`() {
        assertNull(CatalogRuleCompiler.compileAttack("test", 0, attack("Draw 2 cards. Your opponent is Poisoned.")))
        assertNull(CatalogRuleCompiler.compileAttack("test", 0, attack("", "20+")))
        assertNull(CatalogRuleCompiler.compileAttack("test", 0, attack("Flip 2 coins. This attack does 20 damage for each heads.", "30×")))
        assertNotNull(CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-006"), emptyMap()))
    }
    @Test fun `Archaludon Metal Bridge compiles to a passive Metal energy retreat rule`() {
        for (id in listOf("sv07-107", "sv07-155", "sv08.5-070")) {
            val archaludon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertEquals(EnergyType.METAL, archaludon.abilities.single().freeRetreatWithAttachedEnergyType)
        }
    }
    @Test fun `Archaludon ex Assemble Alloy and Metal Defender compile with their full effects`() {
        val archaludon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-130"), emptyMap())!!
        assertEquals(2, archaludon.abilities.single().onEvolveAttachMetalFromDiscardMax)
        assertEquals(listOf(Effect.Damage(220), Effect.NoWeaknessNextTurn), archaludon.attacks.single().effects)
    }
    @Test fun `Archeops Ancient Wing compiles as a once-per-turn Active devolution ability`() {
        val archeops = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10.5w-051"), emptyMap())!!
        val ability = archeops.abilities.single()
        assertEquals("Ancient Wing", ability.name)
        assertTrue(ability.requiresActive)
        assertEquals(1, ability.devolveOpponentPokemonMax)
    }
    @Test fun `Ariados Big Net and String Bind compile passive retreat tax and cost-based damage`() {
        val ariados = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-005"), emptyMap())!!
        assertEquals(1, ariados.abilities.single().opponentActiveEvolutionRetreatCostBonus)
        assertEquals(listOf(Effect.Damage(10), Effect.OpponentActiveRetreatCostDamage(30)), ariados.attacks.single().effects)
    }
    @Test fun `Armarouge Flame Legion and Crimson Blaster compile energy-counting and bench shot rules`() {
        val armarouge = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-034"), emptyMap())!!
        assertEquals(listOf(Effect.Damage(40), Effect.OwnBenchWithEnergyDamage(40, EnergyType.FIRE)),
            CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me05-012"), emptyMap())!!.attacks.single().effects)
        val blaster = armarouge.attacks.single { it.name == "Crimson Blaster" }
        assertTrue(blaster.effects.contains(Effect.DiscardAttackEnergy(0, EnergyType.FIRE, all = true)))
        assertTrue(blaster.effects.contains(Effect.TargetedDamage(180, benchedOnly = true)))
    }
    @Test fun `Aromatisse Scent Collection compiles a once-per-turn Basic Psychic Energy search`() {
        val aromatisse = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me03-036"), emptyMap())!!
        val ability = aromatisse.abilities.single()
        assertEquals("Scent Collection", ability.name)
        assertEquals(EnergyType.PSYCHIC, ability.searchBasicEnergyTypeToHand)
        assertEquals(2, ability.searchBasicEnergyMaxToHand)
        assertEquals(listOf(Effect.Damage(50), Effect.Heal(30)), aromatisse.attacks.single().effects)
    }
    @Test fun `healing executes and never creates negative damage`() {
        val move = CatalogRuleCompiler.compileAttack("test", 0, attack("Heal 30 damage from this Pokémon."))!!
        val def = CardDefinition("test", "Test", "T", "1", category = Category.POKEMON, hp = 200, attacks = listOf(move))
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "test") def else TrainerCatalog.get(id) }
        val engine = RulesEngine(catalog)
        fun card(id: String) = CardInstance(id, "sv01-258")
        fun player(id: String, damage: Int) = PlayerState(deck = List(10) { card("$id-d$it") },
            prizes = List(6) { card("$id-p$it") }, active = PokemonInstance(listOf(CardInstance(id, "test")),
                damage = damage, energies = listOf(card("$id-e")), enteredPlayTurn = 0))
        for (damage in listOf(0, 10, 60)) {
            val state = GameState("test", 42, RngState(42), listOf(player("a", damage), player("b", 0)), 0, 0, 3, Phase.MAIN)
            val result = engine.apply(state, GameAction.Attack(move.id))
            assertEquals(Legality.Legal, result.legality)
            assertEquals((damage - 30).coerceAtLeast(0), result.state.players[0].active!!.damage)
            assertEquals(20, result.state.players[1].active!!.damage)
            assertEquals(1, result.state.currentPlayer)
        }
    }
    @Test fun `known effects compile exactly and preserve card instructions`() {
        assertEquals(listOf(Effect.Damage(20), Effect.Draw(2)), CatalogRuleCompiler.compileAttack("t", 0, attack("Draw 2 cards."))!!.effects)
        assertEquals(listOf(Effect.Damage(20), Effect.Recoil(10)), CatalogRuleCompiler.compileAttack("t", 0, attack("This Pokémon also does 10 damage to itself."))!!.effects)
        assertEquals(listOf(Effect.CoinDamage(2, 30)), CatalogRuleCompiler.compileAttack("t", 0, attack("Flip 2 coins. This attack does 30 damage for each heads.", "30×"))!!.effects)
        assertEquals(listOf(Effect.Damage(120), Effect.RequiresExactHandSize(3)),
            CatalogRuleCompiler.compileAttack("t", 0, attack("If you don't have exactly 3 cards in your hand, this attack does nothing.", "120"))!!.effects)
        assertEquals(listOf(Effect.Damage(20), Effect.ShuffleRandomOpponentHand(1)),
            CatalogRuleCompiler.compileAttack("t", 0, attack("Choose a random card from your opponent's hand, and your opponent reveals that card and shuffles it into their deck.", "20"))!!.effects)
        assertEquals(listOf(Effect.Damage(20), Effect.ShuffleRandomOpponentHand(1)),
            CatalogRuleCompiler.compileAttack("t", 0, attack("Choose a random card from your opponent's hand. Your opponent reveals that card and shuffles it into their deck.", "20"))!!.effects)
        assertEquals(listOf(Effect.OwnToolCountDamage(30)),
            CatalogRuleCompiler.compileAttack("t", 0, attack("This attack does 30 damage for each Pokémon Tool attached to all of your Pokémon.", "30×"))!!.effects)
        assertEquals(listOf(Effect.SearchRotomToBench),
            CatalogRuleCompiler.compileAttack("t", 0, attack("You may search your deck for any number of Pokémon that have \"Rotom\" in their name and put them onto your Bench. Then, shuffle your deck.", ""))!!.effects)
    }
    @Test fun `Teal Dance and Myriad Leaf Shower compile to explicit executable effects`() {
        val ogerpon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-025"), emptyMap())!!
        val dance = ogerpon.abilities.single()
        assertEquals("Teal Dance", dance.name)
        assertEquals(EnergyType.GRASS, dance.attachHandEnergyType)
        assertEquals(1, dance.drawIfAttached)
        assertTrue(dance.usagePerPokemon)
        assertEquals(listOf(Effect.Damage(30), Effect.BothActiveEnergyCountDamage(30)), ogerpon.attacks.first().effects)
    }
    @Test fun `Terapagos attacks compile their first-turn restriction, bench scaling and typed protection`() {
        val terapagos = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-179"), emptyMap())!!
        assertEquals(listOf(Effect.RestrictIfSecondPlayerFirstTurn, Effect.OwnBenchCountDamage(30)), terapagos.attacks[0].effects)
        assertEquals(listOf(Effect.Damage(180), Effect.PreventBasicNonColorlessDamageNextTurn), terapagos.attacks[1].effects)
    }
    @Test fun `Pikachu ex Resolute Heart compiles as full HP attack knockout prevention`() {
        val pikachu = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-057"), emptyMap())!!
        assertEquals(10, pikachu.abilities.single().surviveAttackKnockoutAtHp)
        assertEquals("Topaz Bolt", pikachu.attacks.first().name)
    }
    @Test fun `Aggron Angry Slam counts own damaged Pokemon and Guard Claw reduces damage next turn`() {
        val aggron = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-122"), emptyMap())!!
        assertEquals(listOf(Effect.OwnDamagedPokemonCountDamage(50)), aggron.attacks.first().effects)
        assertEquals(listOf(Effect.Damage(120), Effect.ReduceDamageNextTurn(50)), aggron.attacks[1].effects)
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == aggron.id) aggron else TrainerCatalog.get(id) }
        val attacker = PokemonInstance(listOf(CardInstance("aggron", aggron.id)), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("target", "sv01-258")), enteredPlayTurn = 0)
        assertEquals(150, EffectEngine(catalog).damage(attacker, defender, aggron.attacks.first().effects, ownDamagedPokemonCount = 3))
    }
    @Test fun `Absol Darkfall counts matching Energy across the whole field`() {
        val absol = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06.5-030"), emptyMap())!!
        val attack = absol.attacks.first()
        assertEquals(listOf(Effect.Damage(20), Effect.ConditionalInPlayEnergyDamage(EnergyType.DARKNESS, 3, 50)), attack.effects)
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == absol.id) absol else TrainerCatalog.get(id) }
        val attacker = PokemonInstance(listOf(CardInstance("absol", absol.id)), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("target", "sv01-258")), enteredPlayTurn = 0)
        val engine = EffectEngine(catalog)
        assertEquals(20, engine.damage(attacker, defender, attack.effects, ownEnergyCounts = mapOf(EnergyType.DARKNESS to 2)))
        assertEquals(70, engine.damage(attacker, defender, attack.effects, ownEnergyCounts = mapOf(EnergyType.DARKNESS to 3)))
    }
    @Test fun `Abra Teleporter compiles as active once per turn self shuffle`() {
        val abra = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-080"), emptyMap())!!
        assertEquals(10, abra.attacks.single().effects.filterIsInstance<Effect.Damage>().single().amount)
        assertTrue(abra.abilities.single().requiresActive)
        assertTrue(abra.abilities.single().shuffleSelfIntoDeck)
    }
    @Test fun `Alakazam Psychic Draw and Powerful Hand compile together`() {
        val alakazam = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("mep-003"), emptyMap())!!
        assertEquals(3, alakazam.abilities.single().onEvolveDraw)
        assertEquals(listOf(Effect.OwnHandCounterDamage(2)), alakazam.attacks.single().effects)
    }
    @Test fun `Alakazam Strange Hacking compiles confusion with opponent damage counter movement`() {
        val alakazam = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-082"), emptyMap())!!
        assertEquals(listOf(Effect.StatusCondition(SpecialCondition.CONFUSED), Effect.MoveOpponentDamageCounters), alakazam.attacks.first().effects)
    }
    @Test fun `Alcremie Colorful Confection compiles energy-type filtered search`() {
        val alcremie = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-065"), emptyMap())!!
        assertEquals(listOf(Effect.SearchPokemonMatchingAttachedBasicEnergy(5)), alcremie.attacks.first().effects)
        assertEquals(listOf(Effect.Damage(60)), alcremie.attacks[1].effects)
    }
    @Test fun `Alcremie ex Confectionary Gift compiles as once-per-turn healing`() {
        val alcremie = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-075"), emptyMap())!!
        assertEquals(30, alcremie.abilities.single().healDamage)
        assertEquals(listOf(Effect.Damage(160)), alcremie.attacks.single().effects)
    }
    @Test fun `Alolan Exeggutor ex attacks compile hand energy attachment and Basic knockout coin`() {
        val exeggutor = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-133"), emptyMap())!!
        assertEquals(listOf(Effect.Damage(150), Effect.AttachAnyBasicEnergyFromHand), exeggutor.attacks.first().effects)
        assertEquals(listOf(Effect.CoinKnockOutBasic), exeggutor.attacks[1].effects)
        assertTrue(exeggutor.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Alolan Marowak Retaliate compiles as opponent last turn attack knockout bonus`() {
        val marowak = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-057"), emptyMap())!!
        assertEquals(listOf(Effect.Damage(30), Effect.OwnKnockoutLastTurnDamage(90)), marowak.attacks.first().effects)
        val damage = EffectEngine(TrainerCatalog)
        val attacker = PokemonInstance(listOf(CardInstance("marowak", marowak.id)), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("target", "sv01-258")), enteredPlayTurn = 0)
        assertEquals(30, damage.damage(attacker, defender, marowak.attacks.first().effects))
        assertEquals(120, damage.damage(attacker, defender, marowak.attacks.first().effects, ownKnockedOutByAttackLastTurn = true))
    }
    @Test fun `Alomomola Gentle Fin compiles its Active and HP-limited discard recovery`() {
        val alomomola = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10.5b-024"), emptyMap())!!
        val ability = alomomola.abilities.single()
        assertTrue(ability.requiresActive)
        assertEquals(70, ability.recoverBasicPokemonToBenchMaxHp)
        assertEquals(listOf(Effect.Damage(70)), alomomola.attacks.single().effects)
    }
    @Test fun `Altaria Humming Charge and Cotton Wings compile as deck energy search and coin protection`() {
        val altaria = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-134"), emptyMap())!!
        assertEquals(listOf(Effect.AttachBasicEnergyFromDeckAny(2)), altaria.attacks[0].effects)
        assertEquals(listOf(Effect.Damage(100), Effect.PreventAttackDamageNextTurnOnHeads), altaria.attacks[1].effects)
        assertTrue(altaria.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Applin Find a Friend compiles as optional Pokemon search`() {
        val applin = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-126"), emptyMap())!!
        assertEquals(listOf(Effect.SearchPokemonToHand()), applin.attacks.first().effects)
        assertTrue(applin.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Applin Nutrients compiles as healing a selected own Pokemon`() {
        val applin = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-138"), emptyMap())!!
        assertEquals(listOf(Effect.HealOwnPokemon(30)), applin.attacks.first().effects)
        assertTrue(applin.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Archaludon Coated Attack blocks damage from every Basic attacker next turn`() {
        val archaludon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02-075"), emptyMap())!!
        assertEquals(listOf(Effect.Damage(120), Effect.PreventBasicPokemonDamageNextTurn), archaludon.attacks.first().effects)
        assertTrue(archaludon.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Ampharos Synchro Pulse compiles equal hand count damage bonus`() {
        val ampharos = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me04-029"), emptyMap())!!
        assertEquals(80, ampharos.abilities.single().damageBonusIfHandsEqual)
        assertTrue(ampharos.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Arcanine Punishing Fang applies bonus only to Active Darkness target type`() {
        val arcanine = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-028"), emptyMap())!!
        val attack = arcanine.attacks.last()
        assertEquals(listOf(Effect.Damage(100), Effect.OpponentTypeDamage(EnergyType.DARKNESS, 100)), attack.effects)
        val attacker = PokemonInstance(listOf(CardInstance("arcanine", arcanine.id)), enteredPlayTurn = 0)
        val dark = PokemonInstance(listOf(CardInstance("dark", "test-dark")), enteredPlayTurn = 0)
        val water = PokemonInstance(listOf(CardInstance("water", "test-water")), enteredPlayTurn = 0)
        val darkDef = CardDefinition("test-dark", "Dark", "T", "D", category = Category.POKEMON, type = EnergyType.DARKNESS)
        val waterDef = CardDefinition("test-water", "Water", "T", "W", category = Category.POKEMON, type = EnergyType.WATER)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            arcanine.id -> arcanine; darkDef.id -> darkDef; waterDef.id -> waterDef; else -> TrainerCatalog.get(id)
        } }
        val effects = EffectEngine(catalog)
        assertEquals(200, effects.damage(attacker, dark, attack.effects))
        assertEquals(100, effects.damage(attacker, water, attack.effects))
        assertEquals(100, effects.damage(attacker, dark, attack.effects, targetIsOpponentActive = false))
    }
    @Test fun `Annihilape Ghostly Blow compiles damage counters to a selected opposing Benched Pokemon`() {
        val attack = CatalogRuleCompiler.compileAttack("annihilape", 0,
            attack("Place 5 damage counters on 1 of your opponent's Benched Pokémon.", "100"))!!
        assertEquals(listOf(Effect.Damage(100), Effect.BenchDamageCounters(5)), attack.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me05-041"), emptyMap())!!
        assertEquals(10, card.abilities.single().surviveAttackKnockoutOnHeadsAtHp)
        assertTrue(card.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Ambipom Dual Tail compiles energy discard and two-target damage`() {
        val ambipom = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02-079"), emptyMap())!!
        assertEquals(listOf(Effect.DiscardAttackEnergy(2), Effect.MultiTargetDamage(60, 2)), ambipom.attacks.last().effects)
        assertTrue(ambipom.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Annihilape Destined Fight compiles simultaneous Active knockouts`() {
        val annihilape = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-100"), emptyMap())!!
        assertEquals(listOf(Effect.Damage(130), Effect.StatusCondition(SpecialCondition.CONFUSED, onSelf = true)), annihilape.attacks.first().effects)
        assertEquals(listOf(Effect.KnockOutBothActive), annihilape.attacks.last().effects)
        assertTrue(annihilape.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Arboliva ex attacks compile repeatable damage choices and self-condition recovery`() {
        val arboliva = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-023"), emptyMap())!!
        assertEquals(listOf(Effect.MultiTargetDamage(20, 6, allowRepeat = true, ignoreWeaknessResistance = true)), arboliva.attacks.first().effects)
        assertEquals(listOf(Effect.Damage(160), Effect.RecoverSelfFromSpecialConditions), arboliva.attacks[1].effects)
        assertTrue(arboliva.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Ambipom Wicked Tail compiles two coin-triggered random hand shuffles on evolve`() {
        val ambipom = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-138"), emptyMap())!!
        assertEquals(2, ambipom.abilities.single().onEvolveShuffleRandomOpponentHandPerHeads)
        assertTrue(ambipom.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `Annihilape Lose Cool compiles damage-counter threshold and Active-only bonus`() {
        val annihilape = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-092"), emptyMap())!!
        val ability = annihilape.abilities.single()
        assertEquals(20, ability.damageBonusAgainstActiveWhenDamageAtLeast)
        assertEquals(120, ability.damageBonusAmount)
        assertTrue(annihilape.attacks.all { EffectEngine(TrainerCatalog).supported(it) })
    }
    @Test fun `status and conditional defending energy discard compile without dropping base damage`() {
        assertEquals(listOf(Effect.Damage(70), Effect.StatusCondition(SpecialCondition.POISONED)),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Your opponent's Active Pokémon is now Poisoned.", "70"))!!.effects)
        assertEquals(listOf(Effect.Damage(50), Effect.DiscardDefendingEnergy(1, true)),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Flip a coin. If heads, discard an Energy from your opponent's Active Pokémon.", "50"))!!.effects)
        assertEquals(listOf(Effect.StatusCondition(SpecialCondition.PARALYZED, onHeads = true)),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Flip a coin. If heads, your opponent's Active Pokémon is now Paralyzed.", ""))!!.effects)
        assertEquals(listOf(Effect.Damage(20), Effect.CoinMiss),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Flip a coin. If tails, this attack does nothing.", "20"))!!.effects)
        assertEquals(listOf(Effect.Damage(20), Effect.BenchDamage(30)),
            CatalogRuleCompiler.compileAttack("test", 0, attack("This attack also does 30 damage to 1 of your opponent's Benched Pokémon. (Don't apply Weakness and Resistance for Benched Pokémon.)", "20"))!!.effects)
        assertEquals(listOf(Effect.Damage(70), Effect.StatusCondition(SpecialCondition.CONFUSED),
            Effect.StatusCondition(SpecialCondition.POISONED), Effect.SwitchOwn()),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Your opponent's Active Pokémon is now Confused and Poisoned. Switch this Pokémon with 1 of your Benched Pokémon.", "70"))!!.effects)
        assertEquals(listOf(Effect.Damage(50), Effect.StatusCondition(SpecialCondition.POISONED), Effect.PreventRetreatNextTurn),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Your opponent's Active Pokémon is now Poisoned. During your opponent's next turn, that Pokémon can't retreat.", "50"))!!.effects)
        assertEquals(listOf(Effect.Damage(80), Effect.StatusCondition(SpecialCondition.CONFUSED), Effect.PreventRetreatNextTurn),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Your opponent's Active Pokémon is now Confused. During your opponent's next turn, that Pokémon can't retreat.", "80"))!!.effects)
        assertEquals(listOf(Effect.Damage(60), Effect.StatusCondition(SpecialCondition.POISONED), Effect.PreventRetreatNextTurn),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Your opponent's Active Pokémon is now Poisoned. During your opponent's next turn, the Defending Pokémon can't retreat.", "60"))!!.effects)
        val arbok = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv05-101"), emptyMap())!!
        assertEquals(listOf(Effect.StatusCondition(SpecialCondition.BURNED), Effect.StatusCondition(SpecialCondition.CONFUSED),
            Effect.StatusCondition(SpecialCondition.POISONED)), arbok.attacks.first().effects)
    }
    @Test fun `defending Pokemon attack lock compiles independently from self attack restriction`() {
        assertEquals(listOf(Effect.Damage(50), Effect.PreventDefendingAttackNextTurn),
            CatalogRuleCompiler.compileAttack("test", 0, attack("During your opponent's next turn, the Defending Pokémon can't attack.", "50"))!!.effects)
        assertEquals(listOf(Effect.Damage(50), Effect.RestrictNextTurn()),
            CatalogRuleCompiler.compileAttack("test", 0, attack("During your next turn, this Pokémon can't attack.", "50"))!!.effects)
    }
    @Test fun `damage bonus against ex is conditional and gets weakness applied normally`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("If your opponent's Active Pokémon is a Pokémon ex, this attack does 100 more damage.", "50+"))!!
        assertEquals(listOf(Effect.Damage(50), Effect.OpponentExDamage(100)), move.effects)
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, type = EnergyType.FIRE)
        val defenderDef = CardDefinition("defender", "Defender ex", "T", "2", category = Category.POKEMON, weakness = EnergyType.FIRE)
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "attacker") attackerDef else defenderDef }
        val attacker = PokemonInstance(listOf(CardInstance("a", "attacker")), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("d", "defender")), enteredPlayTurn = 0)
        val engine = EffectEngine(catalog)
        assertEquals(300, engine.damage(attacker, defender, move.effects, opponentActiveIsEx = true))
        assertEquals(100, engine.damage(attacker, defender, move.effects, opponentActiveIsEx = false))
    }
    @Test fun `damage bonuses per Energy count the correct active Pokemon before weakness`() {
        val own = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("This attack does 50 more damage for each Energy attached to this Pokémon.", "30+"))!!
        val opponent = CatalogRuleCompiler.compileAttack("attacker", 1,
            attack("This attack does 40 more damage for each Energy attached to your opponent's Active Pokémon.", "20+"))!!
        assertEquals(listOf(Effect.Damage(30), Effect.OwnEnergyCountDamage(50)), own.effects)
        assertEquals(listOf(Effect.Damage(20), Effect.OpponentEnergyCountDamage(40)), opponent.effects)
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, type = EnergyType.FIRE)
        val defenderDef = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, weakness = EnergyType.FIRE)
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "attacker") attackerDef else defenderDef }
        val attacker = PokemonInstance(listOf(CardInstance("a", "attacker")), energies = List(2) { CardInstance("e$it", "energy") }, enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("d", "defender")), energies = List(3) { CardInstance("de$it", "energy") }, enteredPlayTurn = 0)
        val engine = EffectEngine(catalog)
        assertEquals(260, engine.damage(attacker, defender, own.effects))
        assertEquals(280, engine.damage(attacker, defender, opponent.effects, opponentActiveEnergyCount = 3))
    }
    @Test fun `Walking Wake attack condition bonus and ability bypass Active effects`() {
        val wake = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv05-050"), emptyMap())!!
        assertTrue(wake.abilities.single().ignoreOpponentActiveEffects)
        assertEquals(listOf(Effect.Damage(120), Effect.OpponentConditionDamage(120)), wake.attacks.first().effects)
        val guarded = CardDefinition("guarded", "Guarded", "T", "2", category = Category.POKEMON,
            type = EnergyType.GRASS, abilities = listOf(AbilityDefinition("guard", "Guard", "", damageReduction = 30)))
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == wake.id) wake else guarded }
        val attacker = PokemonInstance(listOf(CardInstance("wake", wake.id)), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("guarded", guarded.id)), incomingDamageReduction = 20,
            damageReductionTurn = 3, enteredPlayTurn = 0)
        assertEquals(240, EffectEngine(catalog).damage(attacker, defender, wake.attacks.first().effects,
            turn = 3, opponentHasSpecialCondition = true))
        assertEquals(120, EffectEngine(catalog).damage(attacker, defender, wake.attacks.first().effects, turn = 3))
    }
    @Test fun `heads prevents damage and attack effects to protected Pokemon for opponent next turn`() {
        val shield = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("Flip a coin. If heads, during your opponent's next turn, prevent all damage from and effects of attacks done to this Pokémon.", "60"))!!
        assertEquals(listOf(Effect.Damage(60), Effect.PreventAttackDamageNextTurnOnHeads), shield.effects)
        val hit = AttackDefinition("hit", "Hit", listOf(EnergyType.COLORLESS),
            listOf(Effect.Damage(50), Effect.StatusCondition(SpecialCondition.POISONED)))
        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 200, attacks = listOf(shield))
        val defender = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, hp = 200, attacks = listOf(hit))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attacker; "defender" -> defender; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "defender")), energies = listOf(card("be")), enteredPlayTurn = 0))
        val seed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) == 0 }
        val state = GameState("attack-immunity", seed, RngState(seed), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val engine = RulesEngine(catalog)
        val first = engine.apply(state, GameAction.Attack(shield.id))
        assertEquals(60, first.state.players[1].active!!.damage)
        assertEquals(4, first.state.players[0].active!!.attackImmunityTurn)
        val second = engine.apply(first.state, GameAction.Attack("hit"))
        assertEquals(0, second.state.players[0].active!!.damage)
        assertTrue(second.state.players[0].active!!.conditions.isEmpty())
    }
    @Test fun `simple once per turn draw abilities are executable and unknown abilities reject the card`() {
        val row = CatalogRuleCompiler.rows.getValue("me01-006")
        val drawAbility = buildJsonObject { put("name", "Field Search"); put("effect", "Once during your turn, you may use this Ability. Draw 2 cards.") }
        val upgraded = buildJsonObject { row.forEach { (key, value) -> put(key, value) }; put("abilities", buildJsonArray { add(drawAbility) }) }
        val compiled = CatalogRuleCompiler.compile(upgraded, emptyMap())!!
        assertEquals(2, compiled.abilities.single().draw)
        val limited = buildJsonObject { put("name", "Field Search"); put("effect", "Once during your turn, you may use this Ability. Draw 2 cards. You can't use more than 1 Field Search Ability each turn.") }
        val limitedRow = buildJsonObject { row.forEach { (key, value) -> put(key, value) }; put("abilities", buildJsonArray { add(limited) }) }
        assertEquals(2, CatalogRuleCompiler.compile(limitedRow, emptyMap())!!.abilities.single().draw)
        val unsupported = buildJsonObject { put("name", "Unmapped"); put("effect", "Your Pokémon have no Weakness.") }
        val rejected = buildJsonObject { row.forEach { (key, value) -> put(key, value) }; put("abilities", buildJsonArray { add(unsupported) }) }
        assertNull(CatalogRuleCompiler.compile(rejected, emptyMap()))
        val evolveDraw = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-056"), emptyMap())
        assertEquals(3, evolveDraw?.abilities?.singleOrNull()?.onEvolveDraw)
    }
    @Test fun `Team Rocket attack requirement counts active and benched Rocket Pokemon`() {
        val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-079"), emptyMap())!!
        assertEquals(4, compiled.abilities.single().requiresTeamRocketPokemonCount)
        val attacker = compiled.copy(id = "rocket-mewtwo", attacks = listOf(
            AttackDefinition("rocket-attack", "Rocket Attack", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(20)))))
        val rocket = CardDefinition("rocket-grunt", "Team Rocket's Grunt", "T", "2", category = Category.POKEMON, hp = 100)
        val other = CardDefinition("other", "Other", "T", "3", category = Category.POKEMON, hp = 100)
        val enemy = CardDefinition("enemy", "Enemy", "T", "4", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "rocket-mewtwo" -> attacker; "rocket-grunt" -> rocket; "other" -> other; "enemy" -> enemy; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val active = PokemonInstance(listOf(card("mewtwo", "rocket-mewtwo")), energies = listOf(card("energy")), enteredPlayTurn = 0)
        val rocketBench = (1..3).map { PokemonInstance(listOf(card("rocket-$it", "rocket-grunt")), enteredPlayTurn = 0) }
        val p0 = PlayerState(deck = emptyList(), prizes = emptyList(), active = active, bench = rocketBench.take(2))
        val p1 = PlayerState(deck = emptyList(), prizes = emptyList(), active = PokemonInstance(listOf(card("enemy-active", "enemy")), enteredPlayTurn = 0))
        val engine = RulesEngine(catalog)
        val state = GameState("rocket-requirement", 1, RngState(1), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        assertTrue(engine.validate(state, GameAction.Attack("rocket-attack")) is Legality.Illegal)
        val enough = state.copy(players = listOf(p0.copy(bench = rocketBench), p1))
        assertEquals(Legality.Legal, engine.validate(enough, GameAction.Attack("rocket-attack")))
        val mixed = state.copy(players = listOf(p0.copy(bench = rocketBench.take(1) + PokemonInstance(listOf(card("other-pokemon", "other")), enteredPlayTurn = 0)), p1))
        assertTrue(engine.validate(mixed, GameAction.Attack("rocket-attack")) is Legality.Illegal)
    }
    @Test fun `Cursed Blast places damage counters then knocks out its source Pokemon`() {
        val dusclops = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06.5-019"), emptyMap())!!
        assertEquals(5, dusclops.abilities.single().damageCounters)
        assertTrue(dusclops.abilities.single().selfKnockout)
        val active = CardDefinition("active", "Friendly", "T", "1", category = Category.POKEMON, hp = 100)
        val target = CardDefinition("target", "Target", "T", "2", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { dusclops.id -> dusclops; "active" -> active; "target" -> target; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val own = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("friendly", "active")), enteredPlayTurn = 0),
            bench = listOf(PokemonInstance(listOf(card("dusclops", dusclops.id)), enteredPlayTurn = 0)))
        val opponent = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("target-active", "target")), enteredPlayTurn = 0))
        val state = GameState("cursed-blast", 1, RngState(1), listOf(own, opponent), 0, 0, turn = 3, phase = Phase.MAIN)
        val engine = RulesEngine(catalog)
        val used = engine.apply(state, GameAction.UseAbility("dusclops", dusclops.abilities.single().id))
        assertEquals(ChoiceKind.ABILITY_DAMAGE_TARGET, used.state.resolution!!.choices.first().kind)
        val resolved = engine.apply(used.state, GameAction.ChooseCards(listOf("target-active")))
        assertEquals(50, resolved.state.players[1].active!!.damage)
        assertFalse(resolved.state.players[0].bench.any { it.id == "dusclops" })
        assertTrue(resolved.state.players[0].discard.any { it.id == "dusclops" })
    }
    @Test fun `passive damage reduction and ignoring opponent effects calculate independently of weakness`() {
        val row = CatalogRuleCompiler.rows.getValue("me01-006")
        val ability = buildJsonObject { put("name", "Hide"); put("effect", "This Pokémon takes 30 less damage from attacks (after applying Weakness and Resistance).") }
        val protected = buildJsonObject { row.forEach { (key, value) -> put(key, value) }; put("abilities", buildJsonArray { add(ability) }) }
        val compiled = CatalogRuleCompiler.compile(protected, emptyMap())!!
        assertEquals(30, compiled.abilities.single().damageReduction)
        val furCoat = buildJsonObject { put("name", "Fur Coat"); put("effect", "This Pokémon takes 20 less damage from attacks (after applying Weakness and Resistance).") }
        val furCoatRow = buildJsonObject { row.forEach { (key, value) -> put(key, value) }; put("abilities", buildJsonArray { add(furCoat) }) }
        assertEquals(20, CatalogRuleCompiler.compile(furCoatRow, emptyMap())!!.abilities.single().damageReduction)
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, type = EnergyType.FIRE)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attackerDef; "protected" -> compiled; else -> TrainerCatalog.get(id) } }
        val attacker = PokemonInstance(listOf(CardInstance("a", "attacker")), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("d", "protected")), enteredPlayTurn = 0)
        val damage = listOf(Effect.Damage(50))
        val engine = EffectEngine(catalog)
        assertEquals(70, engine.damage(attacker, defender, damage))
        assertEquals(100, engine.damage(attacker, defender, damage, ignoreOpponentActiveEffects = true))
        val fireWeak = compiled.copy(weakness = EnergyType.FIRE)
        val withWeakness = object : CardCatalog { override fun get(id: String) = if (id == "protected") fireWeak else attackerDef }
        assertEquals(100, EffectEngine(withWeakness).damage(attacker, defender, damage, ignoreOpponentActiveEffects = true))
        val resistant = fireWeak.copy(id = "resistant", weakness = null, resistance = EnergyType.FIRE, abilities = emptyList())
        val withResistance = object : CardCatalog { override fun get(id: String) = if (id == "protected") resistant else attackerDef }
        val fiftyDamage = listOf(Effect.Damage(50))
        assertEquals(20, EffectEngine(withResistance).damage(attacker, defender, fiftyDamage))
        assertEquals(50, EffectEngine(withResistance).damage(attacker, defender, fiftyDamage, ignoreResistance = true))
    }
    @Test fun `draw to hand and damage per own damage counter compile and calculate`() {
        assertEquals(listOf(Effect.DrawToHand(6)), CatalogRuleCompiler.compileAttack("test", 0,
            attack("You may draw cards until you have 6 cards in your hand.", ""))!!.effects)
        val move = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("This attack does 20 damage for each damage counter on this Pokémon.", "20×"))!!
        assertEquals(listOf(Effect.OwnDamageCounterDamage(20)), move.effects)
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON)
        val defenderDef = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON)
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "attacker") attackerDef else defenderDef }
        val attacker = PokemonInstance(listOf(CardInstance("a", "attacker")), damage = 30, enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("d", "defender")), enteredPlayTurn = 0)
        assertEquals(60, EffectEngine(catalog).damage(attacker, defender, move.effects))
        val handMove = CatalogRuleCompiler.compileAttack("hand", 0,
            attack("Place 2 damage counters on your opponent's Active Pokémon for each card in your hand.", ""))!!
        assertEquals(listOf(Effect.OwnHandCounterDamage(2)), handMove.effects)
        val protectedDefender = defenderDef.copy(weakness = EnergyType.FIGHTING,
            abilities = listOf(AbilityDefinition("guard", "Guard", "", damageReduction = 30)))
        val protectedCatalog = object : CardCatalog { override fun get(id: String) = if (id == "attacker") attackerDef else protectedDefender }
        assertEquals(80, EffectEngine(protectedCatalog).damage(attacker, defender,
            handMove.effects, ownHandCount = 4))
    }
    @Test fun `coin until tails and per Energy attacks compile and resolve all flips`() {
        val untilTails = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("Flip a coin until you get tails. This attack does 30 damage for each heads.", "30×"))!!
        assertEquals(listOf(Effect.CoinDamage(0, 30, untilTails = true)), untilTails.effects)
        val perEnergy = CatalogRuleCompiler.compileAttack("attacker", 1,
            attack("Flip a coin for each Energy attached to this Pokémon. This attack does 20 damage for each heads.", "20×"))!!
        assertEquals(listOf(Effect.CoinDamage(0, 20, perEnergy = true)), perEnergy.effects)
        val bothActive = CatalogRuleCompiler.compileAttack("attacker", 3,
            attack("Flip a coin for each Energy attached to both Active Pokémon. This attack does 60 damage for each heads.", "60×"))!!
        assertEquals(listOf(Effect.CoinDamage(0, 60, perEnergyBothActive = true)), bothActive.effects)
        val threshold = CatalogRuleCompiler.compileAttack("attacker", 4,
            attack("Flip 4 coins. This attack does 60 damage for each heads. If at least 2 of them are heads, your opponent's Active Pokémon is now Paralyzed.", "60×"))!!
        assertEquals(listOf(Effect.CoinDamage(4, 60), Effect.StatusCondition(SpecialCondition.PARALYZED, onHeads = true, minimumHeads = 2)), threshold.effects)
        val bonus = CatalogRuleCompiler.compileAttack("attacker", 2,
            attack("Flip a coin until you get tails. This attack does 50 more damage for each heads.", "30+"))!!
        assertEquals(listOf(Effect.Damage(30), Effect.CoinDamage(0, 50, untilTails = true, bonusOnly = true)), bonus.effects)

        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 200, attacks = listOf(untilTails))
        val defender = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, hp = 200)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attacker; "defender" -> defender; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "defender")), enteredPlayTurn = 0))
        fun headsUntilTail(seed: Long): Int {
            val rng = SeededRng(RngState(seed)); var heads = 0
            while (rng.nextInt(2) == 0) heads++
            return heads
        }
        val seed = (1L..10000L).first { headsUntilTail(it) == 2 }
        val state = GameState("coin-until-tails", seed, RngState(seed), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = RulesEngine(catalog).apply(state, GameAction.Attack(untilTails.id))
        assertEquals(60, result.state.players[1].active!!.damage)
        assertTrue(result.events.any { it.type == "CoinFlipped" && it.message.count { c -> c == ',' } == 2 })
    }
    @Test fun `energy movement and opponent discard effects compile exactly`() {
        assertEquals(listOf(Effect.MoveEnergyToBench(1)), CatalogRuleCompiler.compileAttack("test", 0,
            attack("Move an Energy from this Pokémon to 1 of your Benched Pokémon.", ""))!!.effects)
        assertEquals(listOf(Effect.DiscardRandomOpponentHand(1)), CatalogRuleCompiler.compileAttack("test", 0,
            attack("Discard a random card from your opponent's hand.", ""))!!.effects)
        assertEquals(listOf(Effect.DiscardOpponentDeck(2)), CatalogRuleCompiler.compileAttack("test", 0,
            attack("Discard the top 2 cards of your opponent's deck.", ""))!!.effects)
        assertEquals(listOf(Effect.DiscardDefendingEnergy(1)), CatalogRuleCompiler.compileAttack("test", 0,
            attack("Discard an Energy from your opponent's Active Pokémon.", ""))!!.effects)
        assertEquals(listOf(Effect.Damage(200), Effect.DiscardAttackEnergy(1, EnergyType.FIRE)),
            CatalogRuleCompiler.compileAttack("test", 0,
                attack("Discard a {R} Energy from this Pokémon.", "200"))!!.effects)
        assertEquals(listOf(Effect.DiscardAttackEnergy(0, EnergyType.FIRE, all = true)),
            CatalogRuleCompiler.compileAttack("test", 0,
                attack("Discard all {R} Energy from this Pokémon.", ""))!!.effects)
        assertEquals(listOf(Effect.Damage(20), Effect.IgnoreResistance), CatalogRuleCompiler.compileAttack("test", 0,
            attack("This attack's damage isn't affected by Resistance.", "20"))!!.effects)
    }
    @Test fun `next turn damage reduction is active only during the opponent's next turn`() {
        val reduce = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("During your opponent's next turn, this Pokémon takes 30 less damage from attacks (after applying Weakness and Resistance).", ""))!!
        assertEquals(listOf(Effect.ReduceDamageNextTurn(30)), reduce.effects)
        val hit = AttackDefinition("hit", "Hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(50)))
        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 100, attacks = listOf(reduce))
        val defender = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, hp = 100, attacks = listOf(hit))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attacker; "defender" -> defender; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "defender")), energies = listOf(card("be")), enteredPlayTurn = 0))
        val engine = RulesEngine(catalog)
        val state = GameState("damage-reduction-turn", 2, RngState(2), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val firstAttack = engine.apply(state, GameAction.Attack(reduce.id))
        assertEquals(4, firstAttack.state.turn)
        assertEquals(4, firstAttack.state.players[0].active!!.damageReductionTurn)
        val secondAttack = engine.apply(firstAttack.state, GameAction.Attack("hit"))
        assertEquals(20, secondAttack.state.players[0].active!!.damage)
        assertEquals(5, secondAttack.state.turn)
    }
    @Test fun `attack reduces Defending Pokemon outgoing attack damage next turn before weakness`() {
        val effect = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("During your opponent's next turn, attacks used by the Defending Pokémon do 30 less damage (before applying Weakness and Resistance).", "10"))!!
        assertEquals(listOf(Effect.Damage(10), Effect.ReduceDefendingAttackDamageNextTurn(30)), effect.effects)
        val hit = AttackDefinition("hit", "Hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(50)))
        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 200, weakness = EnergyType.COLORLESS, attacks = listOf(effect))
        val defender = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, hp = 200, type = EnergyType.COLORLESS, attacks = listOf(hit))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attacker; "defender" -> defender; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "defender")), energies = listOf(card("be")), enteredPlayTurn = 0))
        val engine = RulesEngine(catalog)
        val state = GameState("outgoing-reduction", 2, RngState(2), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val first = engine.apply(state, GameAction.Attack(effect.id))
        assertEquals(4, first.state.turn)
        assertEquals(30, first.state.players[1].active!!.outgoingDamageReduction)
        val second = engine.apply(first.state, GameAction.Attack("hit"))
        assertEquals(40, second.state.players[0].active!!.damage)
    }
    @Test fun `active counterattack ability damages attacker even when defender is knocked out`() {
        val row = CatalogRuleCompiler.rows.getValue("me01-006")
        val ability = buildJsonObject { put("name", "Counterattack"); put("effect", "If this Pokémon is in the Active Spot and is damaged by an attack from your opponent's Pokémon (even if this Pokémon is Knocked Out), put 3 damage counters on the Attacking Pokémon.") }
        val withAbility = buildJsonObject { row.forEach { (key, value) -> put(key, value) }; put("abilities", buildJsonArray { add(ability) }) }
        val compiled = CatalogRuleCompiler.compile(withAbility, emptyMap())!!
        assertEquals(3, compiled.abilities.single().retaliateDamageCounters)
        val hit = AttackDefinition("hit", "Hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(100)))
        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 200, attacks = listOf(hit))
        val defender = compiled.copy(id = "defender", hp = 50, attacks = emptyList())
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attacker; "defender" -> defender; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "defender")), energies = listOf(card("be")), enteredPlayTurn = 0))
        val state = GameState("counterattack", 2, RngState(2), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = RulesEngine(catalog).apply(state, GameAction.Attack(hit.id))
        assertNull(result.state.players[1].active)
        assertTrue(result.state.players[1].discard.any { it.id == "b" })
        assertEquals(30, result.state.players[0].active!!.damage)
        assertTrue(result.events.any { it.type == "CounterattackDamage" })
    }
    @Test fun `attack moves an Energy only after both source and Bench target are chosen`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("Move an Energy from this Pokémon to 2 of your Benched Pokémon.", ""))!!
        assertEquals(listOf(Effect.MoveEnergyToBench(2)), move.effects)
        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 100, attacks = listOf(move))
        val defender = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, hp = 100)
        val bench = CardDefinition("bench", "Bench", "T", "3", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attacker; "defender" -> defender; "bench" -> bench; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("energy1"), card("energy2")), enteredPlayTurn = 0),
            bench = listOf(PokemonInstance(listOf(card("b", "bench")), enteredPlayTurn = 0),
                PokemonInstance(listOf(card("c", "bench")), enteredPlayTurn = 0)))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("enemy", "defender")), enteredPlayTurn = 0))
        val engine = RulesEngine(catalog)
        val state = GameState("energy-move", 2, RngState(2), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val attacked = engine.apply(state, GameAction.Attack(move.id))
        val energyChosen = engine.apply(attacked.state, GameAction.ChooseCards(listOf("energy1")))
        assertEquals(ChoiceKind.ATTACK_ENERGY_TARGET, energyChosen.state.resolution!!.choices.first().kind)
        val firstMoved = engine.apply(energyChosen.state, GameAction.ChooseCards(listOf("b")))
        assertEquals(ChoiceKind.ATTACK_ENERGY, firstMoved.state.resolution!!.choices.first().kind)
        val secondEnergy = engine.apply(firstMoved.state, GameAction.ChooseCards(listOf("energy2")))
        assertEquals(listOf("c"), secondEnergy.state.resolution!!.choices.first().candidates)
        val moved = engine.apply(secondEnergy.state, GameAction.ChooseCards(listOf("c")))
        assertEquals(Legality.Legal, moved.legality)
        assertTrue(moved.state.players[0].active!!.energies.isEmpty())
        assertEquals("energy1", moved.state.players[0].bench.first { it.id == "b" }.energies.single().id)
        assertEquals("energy2", moved.state.players[0].bench.first { it.id == "c" }.energies.single().id)
        assertEquals(1, moved.state.currentPlayer)
    }
    @Test fun `bench Energy discard adds damage for selected cards and applies weakness once`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0, attack(
            "You may discard up to 2 Energy from your Benched Pokémon. This attack does 60 more damage for each card you discarded in this way.", "10+"))!!
        assertEquals(listOf(Effect.Damage(10), Effect.DiscardBenchEnergyDamage(2, 60)), move.effects)
        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 100,
            type = EnergyType.FIRE, attacks = listOf(move))
        val defender = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, hp = 200, weakness = EnergyType.FIRE)
        val bench = CardDefinition("bench", "Bench", "T", "3", category = Category.POKEMON, hp = 100)
        val fire = CardDefinition("fire", "Fire Energy", "T", "4", category = Category.ENERGY, type = EnergyType.FIRE)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attacker; "defender" -> defender; "bench" -> bench; "fire" -> fire; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0),
            bench = listOf(PokemonInstance(listOf(card("b", "bench")), energies = listOf(card("e1", "fire"), card("e2", "fire")), enteredPlayTurn = 0)))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("enemy", "defender")), enteredPlayTurn = 0))
        val engine = RulesEngine(catalog)
        val state = GameState("bench-energy-damage", 2, RngState(2), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val preview = engine.preview(state, move.id)!!
        assertTrue(preview.uncertain)
        assertEquals(20, preview.minDamage)
        assertEquals(260, preview.maxDamage)
        val attacked = engine.apply(state, GameAction.Attack(move.id))
        assertEquals(20, attacked.state.players[1].active!!.damage)
        val chosen = engine.apply(attacked.state, GameAction.ChooseCards(listOf("e1")))
        assertEquals(Legality.Legal, chosen.legality)
        assertEquals(140, chosen.state.players[1].active!!.damage)
        assertEquals(1, chosen.state.players[0].bench.single().energies.size)
        assertEquals(1, chosen.state.players[0].discard.size)
    }
    @Test fun `common attacks with bonus damage and energy costs compile to executable effects`() {
        assertEquals(listOf(Effect.Damage(20), Effect.CoinDamage(1, 30, bonusOnly = true)),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Flip a coin. If heads, this attack does 30 more damage.", "20+"))!!.effects)
        assertEquals(listOf(Effect.Damage(120), Effect.DiscardAttackEnergy(2)),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Discard 2 Energy from this Pokémon.", "120"))!!.effects)
        assertEquals(listOf(Effect.Damage(90), Effect.IgnoreWeaknessResistance),
            CatalogRuleCompiler.compileAttack("test", 0, attack("This attack's damage isn't affected by Weakness or Resistance.", "90"))!!.effects)
        assertEquals(listOf(Effect.EnergyFromDeck(1, EnergyType.FIRE)),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Search your deck for a Basic {R} Energy card and attach it to this Pokémon. Then, shuffle your deck.", ""))!!.effects)
        assertEquals(listOf(Effect.Damage(30), Effect.SwitchOpponent),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Switch out your opponent's Active Pokémon to the Bench. (Your opponent chooses the new Active Pokémon.)", "30"))!!.effects)
        assertEquals(listOf(Effect.BasicPokemonToBench(2)),
            CatalogRuleCompiler.compileAttack("test", 0, attack("Search your deck for up to 2 Basic Pokémon and put them onto your Bench. Then, shuffle your deck.", ""))!!.effects)
        assertNull(CatalogRuleCompiler.compileAttack("test", 0, attack("Flip a coin. If heads, this attack does 30 more damage.", "20")))
        assertNull(CatalogRuleCompiler.compileAttack("test", 0, attack("Discard 2 Energy from this Pokémon. Also draw a card.")))
    }
    @Test fun `energy threshold bonus damage is calculated from attached energies`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("If this Pokémon has 2 or more {G} Energy attached, this attack does 120 more damage.", "20+"))!!
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON)
        val grass = CardDefinition("grass", "Grass Energy", "T", "2", category = Category.ENERGY, type = EnergyType.GRASS)
        val defender = CardDefinition("defender", "Defender", "T", "3", category = Category.POKEMON)
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) { "attacker" -> attackerDef; "grass" -> grass; "defender" -> defender; else -> TrainerCatalog.get(id) }
        }
        val attacker = PokemonInstance(listOf(CardInstance("a", "attacker")),
            energies = listOf(CardInstance("e1", "grass"), CardInstance("e2", "grass")), enteredPlayTurn = 0)
        val target = PokemonInstance(listOf(CardInstance("d", "defender")), enteredPlayTurn = 0)
        assertTrue(move.effects.contains(Effect.ConditionalEnergyDamage(EnergyType.GRASS, 2, 120)))
        assertEquals(140, EffectEngine(catalog).damage(attacker, target, move.effects))
    }
    @Test fun `damage bonus counts both players benched Pokemon`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("This attack does 20 more damage for each Benched Pokémon (both yours and your opponent's).", "10+"))!!
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON)
        val defenderDef = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON)
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "attacker") attackerDef else defenderDef }
        val attacker = PokemonInstance(listOf(CardInstance("a", "attacker")), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("d", "defender")), enteredPlayTurn = 0)
        assertEquals(70, EffectEngine(catalog).damage(attacker, defender, move.effects, ownBenchCount = 1, opponentBenchCount = 2))
    }
    @Test fun `multiplicative attack counts all of the player's Pokemon in play`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("This attack does 20 damage for each of your Pokémon in play.", "20×"))!!
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON)
        val defenderDef = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON)
        val catalog = object : CardCatalog { override fun get(id: String) = if (id == "attacker") attackerDef else defenderDef }
        val attacker = PokemonInstance(listOf(CardInstance("a", "attacker")), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("d", "defender")), enteredPlayTurn = 0)
        assertEquals(60, EffectEngine(catalog).damage(attacker, defender, move.effects, ownInPlayCount = 3))
    }
    @Test fun `plain evolutions and ex Pokemon retain evolution and prize rules`() {
        val ivysaur = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-002"), emptyMap())!!
        assertEquals(Stage.STAGE_ONE, ivysaur.stage)
        assertEquals("Bulbasaur", ivysaur.evolvesFromName)
        val bulbasaur = CardDefinition("bulbasaur", "Bulbasaur", "MEG", "001", category = Category.POKEMON)
        assertTrue(ivysaur.canEvolveFrom(bulbasaur))

        val pikachuEx = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-276"), emptyMap())!!
        assertEquals(2, pikachuEx.prizes)
        assertEquals(Category.POKEMON, pikachuEx.category)
    }
    @Test fun `attack retreat lock is enforced for the opponent's next turn`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("During your opponent's next turn, the Defending Pokémon can't retreat.", "20"))!!
        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON,
            hp = 100, attacks = listOf(move))
        val target = CardDefinition("target", "Target", "T", "2", category = Category.POKEMON, hp = 100)
        val benchDef = CardDefinition("bench", "Bench", "T", "3", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) { "attacker" -> attacker; "target" -> target; "bench" -> benchDef; else -> TrainerCatalog.get(id) }
        }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "target")), enteredPlayTurn = 0),
            bench = listOf(PokemonInstance(listOf(card("bb", "bench")), enteredPlayTurn = 0)))
        val engine = RulesEngine(catalog)
        val state = GameState("lock", 1, RngState(1), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val attacked = engine.apply(state, GameAction.Attack(move.id))
        assertEquals(Legality.Legal, attacked.legality)
        assertEquals(4, attacked.state.turn)
        assertEquals(4, attacked.state.players[1].active!!.retreatBlockedTurn)
        assertTrue(engine.validate(attacked.state, GameAction.Retreat("bb", emptyList())) is Legality.Illegal)
    }
    @Test fun `attack that switches the attacker resolves through a bench choice`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("Switch this Pokémon with 1 of your Benched Pokémon.", "10"))!!
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON,
            hp = 100, attacks = listOf(move))
        val benchDef = CardDefinition("bench", "Bench", "T", "2", category = Category.POKEMON, hp = 100)
        val targetDef = CardDefinition("target", "Target", "T", "3", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) { "attacker" -> attackerDef; "bench" -> benchDef; "target" -> targetDef; else -> TrainerCatalog.get(id) }
        }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0),
            bench = listOf(PokemonInstance(listOf(card("bb", "bench")), enteredPlayTurn = 0)))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "target")), enteredPlayTurn = 0))
        val engine = RulesEngine(catalog)
        val state = GameState("switch-attack", 1, RngState(1), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val attacked = engine.apply(state, GameAction.Attack(move.id))
        assertEquals(Phase.RESOLVE_CHOICE, attacked.state.phase)
        val switched = engine.apply(attacked.state, GameAction.ChooseCards(listOf("bb")))
        assertEquals(Legality.Legal, switched.legality)
        assertEquals("bb", switched.state.players[0].active!!.id)
        assertEquals(1, switched.state.currentPlayer)
    }
    @Test fun `spread attack damages the Active and the chosen Benched Pokemon independently`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0, attack(
            "This attack also does 30 damage to 1 of your opponent's Benched Pokémon. (Don't apply Weakness and Resistance for Benched Pokémon.)", "20"))!!
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 100, attacks = listOf(move))
        val defenderDef = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, hp = 100)
        val benchDef = CardDefinition("bench", "Bench", "T", "3", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) { "attacker" -> attackerDef; "defender" -> defenderDef; "bench" -> benchDef; else -> TrainerCatalog.get(id) }
        }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "defender")), enteredPlayTurn = 0),
            bench = listOf(PokemonInstance(listOf(card("bb", "bench")), enteredPlayTurn = 0)))
        val engine = RulesEngine(catalog)
        val state = GameState("spread-attack", 1, RngState(1), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val attacked = engine.apply(state, GameAction.Attack(move.id))
        assertEquals(20, attacked.state.players[1].active!!.damage)
        val spread = engine.apply(attacked.state, GameAction.ChooseCards(listOf("bb")))
        assertEquals(Legality.Legal, spread.legality)
        assertEquals(30, spread.state.players[1].bench.single().damage)
        assertEquals(1, spread.state.currentPlayer)
    }
    @Test fun `poison ticks between turns and paralysis blocks attacks for the affected turn`() {
        val basic = CardDefinition("basic", "Basic", "T", "1", category = Category.POKEMON, hp = 100)
        val victim = basic.copy(id = "victim", attacks = listOf(AttackDefinition("victim-attack", "Hit", emptyList(), listOf(Effect.Damage(10)))))
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "basic" -> basic; "victim" -> victim; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "basic")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "victim")), enteredPlayTurn = 0,
                conditions = setOf(SpecialCondition.POISONED, SpecialCondition.PARALYZED)))
        val engine = RulesEngine(catalog)
        val state = GameState("conditions", 1, RngState(1), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val ended = engine.apply(state, GameAction.EndTurn)
        assertEquals(Legality.Legal, ended.legality)
        assertEquals(10, ended.state.players[1].active!!.damage)
        assertTrue(SpecialCondition.PARALYZED in ended.state.players[1].active!!.conditions)
        assertTrue(engine.validate(ended.state, GameAction.Attack("victim-attack")) is Legality.Illegal)
    }
    @Test fun `confusion tails damages the attacker and cancels the attack`() {
        val move = AttackDefinition("hit", "Hit", listOf(EnergyType.COLORLESS), listOf(Effect.Damage(50)))
        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 100, attacks = listOf(move))
        val defender = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attacker; "defender" -> defender; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0,
                conditions = setOf(SpecialCondition.CONFUSED)))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "defender")), enteredPlayTurn = 0))
        val seed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) == 1 }
        val state = GameState("confusion", seed, RngState(seed), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = RulesEngine(catalog).apply(state, GameAction.Attack("hit"))
        assertEquals(Legality.Legal, result.legality)
        assertEquals(30, result.state.players[0].active!!.damage)
        assertEquals(0, result.state.players[1].active!!.damage)
        assertEquals(1, result.state.currentPlayer)
    }
    @Test fun `tails on a miss coin cancels all attack damage`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0, attack("Flip a coin. If tails, this attack does nothing.", "60"))!!
        val attacker = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON, hp = 100, attacks = listOf(move))
        val defender = CardDefinition("defender", "Defender", "T", "2", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) { "attacker" -> attacker; "defender" -> defender; else -> TrainerCatalog.get(id) } }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = List(10) { card("d0-$it") }, prizes = List(6) { card("p0-$it") },
            active = PokemonInstance(listOf(card("a", "attacker")), energies = listOf(card("ae")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "defender")), enteredPlayTurn = 0))
        val seed = (1L..100L).first { SeededRng(RngState(it)).nextInt(2) == 1 }
        val state = GameState("miss", seed, RngState(seed), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val result = RulesEngine(catalog).apply(state, GameAction.Attack(move.id))
        assertEquals(0, result.state.players[1].active!!.damage)
        assertEquals(1, result.state.currentPlayer)
    }
    @Test fun `attack search puts selected basic Pokemon onto the bench`() {
        val move = CatalogRuleCompiler.compileAttack("attacker", 0,
            attack("Search your deck for up to 2 Basic Pokémon and put them onto your Bench. Then, shuffle your deck.", ""))!!
        val attackerDef = CardDefinition("attacker", "Attacker", "T", "1", category = Category.POKEMON,
            hp = 100, attacks = listOf(move))
        val basicDef = CardDefinition("basic", "Basic", "T", "2", category = Category.POKEMON, hp = 60)
        val targetDef = CardDefinition("target", "Target", "T", "3", category = Category.POKEMON, hp = 100)
        val catalog = object : CardCatalog {
            override fun get(id: String) = when (id) { "attacker" -> attackerDef; "basic" -> basicDef; "target" -> targetDef; else -> TrainerCatalog.get(id) }
        }
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        val p0 = PlayerState(deck = listOf(card("basic-card", "basic")) + List(9) { card("d0-$it") },
            prizes = List(6) { card("p0-$it") }, active = PokemonInstance(listOf(card("a", "attacker")),
                energies = listOf(card("ae")), enteredPlayTurn = 0))
        val p1 = PlayerState(deck = List(10) { card("d1-$it") }, prizes = List(6) { card("p1-$it") },
            active = PokemonInstance(listOf(card("b", "target")), enteredPlayTurn = 0))
        val engine = RulesEngine(catalog)
        val state = GameState("bench-search", 1, RngState(1), listOf(p0, p1), 0, 0, turn = 3, phase = Phase.MAIN)
        val attacked = engine.apply(state, GameAction.Attack(move.id))
        assertEquals(Phase.RESOLVE_CHOICE, attacked.state.phase)
        val searched = engine.apply(attacked.state, GameAction.ChooseCards(listOf("basic-card")))
        assertEquals(Legality.Legal, searched.legality)
        assertEquals("basic-card", searched.state.players[0].bench.single().id)
        assertFalse(searched.state.players[0].deck.any { it.id == "basic-card" })
    }
    @Test fun `special energy is never classified as unlimited basic energy`() {
        assertFalse(CardLibrary.byId.getValue("me02-124").basicEnergy)
        assertFalse(CardLibrary.byId.getValue("me02.5-216").basicEnergy)
        assertTrue(CardLibrary.byId.getValue("sv01-258").basicEnergy)
    }
    @Test fun `coverage reports executable prints independently of browsing metadata`() {
        val supported = CardLibrary.entries.filter { it.implemented }
        assertTrue(supported.size > 100)
        assertTrue(supported.all { TrainerCatalog.get(it.id).implemented })
        java.io.File("build/rules-coverage.txt").writeText("Executable prints: ${supported.size}\nStandard executable: ${supported.count { it.standard }}\nCatalogue: ${CardLibrary.entries.size}\n" + supported.joinToString("\n") { "${it.id} ${it.name}" })
        val unsupported = CardLibrary.entries.filter { it.standard && !it.implemented }
        java.io.File("build/rules-uncovered.txt").writeText("Unimplemented Standard prints: ${unsupported.size}\n" + unsupported.joinToString("\n\n") { card ->
            val raw = CatalogRuleCompiler.rows[card.id] ?: TrainerCatalog.sourceData(card.id)
            val details = buildList {
                raw["abilities"]?.jsonArray?.forEach { add("Ability: ${it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()} — ${it.jsonObject["effect"]?.jsonPrimitive?.contentOrNull.orEmpty()}") }
                raw["attacks"]?.jsonArray?.forEach { add("Attack: ${it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()} (${it.jsonObject["damage"]?.jsonPrimitive?.contentOrNull.orEmpty()}) — ${it.jsonObject["effect"]?.jsonPrimitive?.contentOrNull.orEmpty()}") }
                raw["effect"]?.jsonPrimitive?.contentOrNull?.let { add("Effect: $it") }
            }
            "${card.id} | ${card.name} | ${card.category} | mark ${card.regulation.orEmpty()}\n${details.joinToString("\n")}"
        })
    }
}
