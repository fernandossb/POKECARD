package com.tcgtrainer.engine

import kotlinx.serialization.json.*
import kotlin.test.*

class CatalogRuleCompilerTest {
    @Test fun `Mamoswine Rumbling March counts only own Stage 2 Bench Pokemon`() {
        listOf("sv09-079", "sv09-174").forEach { id ->
            val definition = TrainerCatalog.get(id)
            val attack = definition.attacks.single()
            assertTrue(CardLibrary.byId.getValue(id).implemented, id)
            assertTrue(attack.effects.contains(Effect.OwnBenchStageTwoCountDamage(40)), id)
            val attacker = PokemonInstance(listOf(CardInstance("attacker", id)), enteredPlayTurn = 0)
            val stageTwoBench = PokemonInstance(listOf(CardInstance("stage-two", id)), enteredPlayTurn = 0)
            val basicBench = PokemonInstance(listOf(CardInstance("basic", "me01-076")), enteredPlayTurn = 0)
            val defender = PokemonInstance(listOf(CardInstance("defender", "me01-076")), enteredPlayTurn = 0)
            val engine = EffectEngine(TrainerCatalog)
            assertEquals(180, engine.damage(attacker, defender, attack.effects,
                ignoreWeaknessResistance = true, attackerPokemon = listOf(attacker, basicBench)), id)
            assertEquals(220, engine.damage(attacker, defender, attack.effects,
                ignoreWeaknessResistance = true, attackerPokemon = listOf(attacker, stageTwoBench, basicBench)), id)
        }
    }
    @Test fun `Erika team and generic Pokemon search abilities retain their deck filters`() {
        listOf("me02.5-007", "me02.5-218").forEach { id ->
            val ability = CatalogRuleCompiler.compileAbility(id, 0,
                CatalogRuleCompiler.rows.getValue(id)["abilities"]!!.jsonArray.single().jsonObject)!!
            assertEquals("Erika's", ability.searchPokemonNamePrefixToHand, id)
            assertEquals(1, ability.searchPokemonMaxToHand, id)
        }
        listOf("sv09-079", "sv09-174").forEach { id ->
            val ability = CatalogRuleCompiler.compileAbility(id, 0,
                CatalogRuleCompiler.rows.getValue(id)["abilities"]!!.jsonArray.single().jsonObject)!!
            assertNull(ability.searchPokemonNamePrefixToHand, id)
            assertEquals(1, ability.searchPokemonMaxToHand, id)
        }
    }
    @Test fun `current Standard prints execute bench entry energy counterattack and discard paralysis`() {
        listOf("sv10-003", "sv10-206", "sv10-228").forEach { id ->
            val ability = TrainerCatalog.get(id).abilities.single()
            assertTrue(ability.triggersWhenMovedFromBenchToActive, id)
            assertEquals(EnergyType.GRASS, ability.attachBasicEnergyFromDeckToSelfType, id)
            assertEquals(3, ability.attachBasicEnergyFromDeckToSelfMax, id)
            assertTrue(CardLibrary.byId.getValue(id).implemented, id)
        }
        listOf("me02.5-135", "me02.5-270", "me02.5-285").forEach { id ->
            assertEquals(5, TrainerCatalog.get(id).abilities.single().retaliateDamageCounters, id)
            assertTrue(CardLibrary.byId.getValue(id).implemented, id)
        }
        listOf("me02.5-045", "me02.5-228", "sv06.5-014").forEach { id ->
            val attack = TrainerCatalog.get(id).attacks.first { it.text == "Discard all Energy from this Pokémon. Your opponent's Active Pokémon is now Paralyzed." }
            assertTrue(attack.effects.contains(Effect.DiscardAttackEnergy(0, all = true)), id)
            assertTrue(attack.effects.contains(Effect.StatusCondition(SpecialCondition.PARALYZED)), id)
            assertTrue(CardLibrary.byId.getValue(id).implemented, id)
        }
    }
    @Test fun `all Mega Greninja prints compile the Active Spot Water Energy damage-counter ability`() {
        val ids = listOf("me04-022", "me04-100", "me04-116", "me04-122", "mep-081")
        ids.forEach { id ->
            val row = CatalogRuleCompiler.rows.getValue(id)
            val abilityRaw = row["abilities"]!!.jsonArray.single().jsonObject
            val ability = CatalogRuleCompiler.compileAbility(id, 0, abilityRaw)!!
            assertEquals(EnergyType.WATER, ability.discardEnergyType, id)
            assertEquals(6, ability.damageCounters, id)
            assertEquals(1, ability.damageTargets, id)
            assertTrue(ability.requiresActive, id)
        }
    }
    @Test fun `Subjugating Chains switches only eligible Darkness and poisons the selected Active once per turn`() {
        val ids = listOf("sv06.5-039", "sv06.5-085", "sv06.5-093", "sv06.5-095", "sv08.5-163")
        val compiled = ids.map { id -> CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!! }
        assertTrue(compiled.all { card -> card.abilities.any { it.switchOwnDarknessExceptPecharunt && it.poisonNewActiveOnSwitch } })
        val source = compiled.first(); val otherPecharunt = compiled.last()
        val darkId = CardLibrary.entries.first { it.category == Category.POKEMON && it.canonicalName != "Pecharunt ex" &&
            CatalogRuleCompiler.rows[it.id]?.get("types")?.jsonArray?.any { type -> type.jsonPrimitive.content == "Darkness" } == true }.id
        val engine = RulesEngine(object : CardCatalog { override fun get(id: String) = compiled.find { it.id == id } ?: TrainerCatalog.get(id) })
        fun c(id: String, def: String = "sv01-258") = CardInstance(id, def)
        val active = PokemonInstance(listOf(c("pecharunt", source.id)), enteredPlayTurn = 0)
        val eligible = PokemonInstance(listOf(c("dark", darkId)), enteredPlayTurn = 0)
        val excluded = PokemonInstance(listOf(c("pecharunt-ex", otherPecharunt.id)), enteredPlayTurn = 0)
        val player = PlayerState(deck = List(20) { c("d-$it") }, prizes = List(6) { c("p-$it") }, active = active,
            bench = listOf(eligible, excluded))
        val opponent = PlayerState(deck = List(20) { c("od-$it") }, prizes = List(6) { c("op-$it") },
            active = PokemonInstance(listOf(c("opp", "me01-073")), enteredPlayTurn = 0))
        val state = GameState("subjugating-chains", 81, RngState(81), listOf(player, opponent), 0, 0, 3, Phase.MAIN)
        val ability = source.abilities.first { it.switchOwnDarknessExceptPecharunt }
        val started = engine.apply(state, GameAction.UseAbility(active.id, ability.id))
        assertEquals(Legality.Legal, started.legality)
        assertEquals(listOf(eligible.id), started.state.resolution!!.choices.single().candidates)
        val switched = engine.apply(started.state, GameAction.ChooseCards(listOf(eligible.id)))
        assertEquals(Legality.Legal, switched.legality)
        assertEquals(eligible.id, switched.state.players[0].active!!.id)
        assertTrue(SpecialCondition.POISONED in switched.state.players[0].active!!.conditions)
        assertTrue(switched.state.players[0].bench.single { it.id == active.id }.conditions.isEmpty())
        assertEquals(Legality.Illegal("Esta habilidade já foi usada neste turno."),
            engine.validate(switched.state, GameAction.UseAbility(excluded.id, otherPecharunt.abilities.first { it.switchOwnDarknessExceptPecharunt }.id)))
    }
    @Test fun `Oh No You Don't reduces Munkidori ex prizes only while Pecharunt ex is in play`() {
        val ids = listOf("sv06.5-037", "sv06.5-083", "sv06.5-091")
        val cards = ids.map { CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(it), emptyMap())!! }
        assertTrue(cards.all { card -> card.abilities.any { it.requirePecharuntExForPrizeReduction } })
        val munkidori = cards.first()
        val pecharunt = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06.5-039"), emptyMap())!!
        val opponentEx = CardDefinition("opponent-ex", "Opponent ex", "TEST", "1", category = Category.POKEMON,
            hp = 200, prizes = 2)
        val engine = RulesEngine(object : CardCatalog { override fun get(id: String) =
            cards.find { it.id == id } ?: if (id == pecharunt.id) pecharunt else if (id == opponentEx.id) opponentEx else TrainerCatalog.get(id) })
        fun card(id: String, definition: String = "sv01-258") = CardInstance(id, definition)
        fun settle(withPecharunt: Boolean): GameState {
            val knockedOut = PokemonInstance(listOf(card("munk", munkidori.id)), munkidori.hp,
                enteredPlayTurn = 0)
            val own = PlayerState(deck = List(10) { card("deck-a$it") }, prizes = List(6) { card("prize-a$it") },
                active = knockedOut, bench = if (withPecharunt) listOf(PokemonInstance(listOf(card("pech", pecharunt.id)), enteredPlayTurn = 0)) else emptyList())
            val rival = PlayerState(deck = List(10) { card("deck-b$it") }, prizes = List(6) { card("prize-b$it") },
                active = PokemonInstance(listOf(card("rival", opponentEx.id)), enteredPlayTurn = 0))
            return engine.settle(GameState("pecharunt-prize-$withPecharunt", 82, RngState(82), listOf(own, rival),
                1, 1, 4, Phase.MAIN, pendingAttackDamageKnockoutIds = setOf("munk")), ResolutionEnd.ATTACK).state
        }
        assertEquals(2, settle(false).prizesOwed[1])
        assertEquals(1, settle(true).prizesOwed[1])
    }
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
    @Test fun `Festival Lead is executable and recognizes Festival Grounds attacks`() {
        val row = CatalogRuleCompiler.rows.values.first { card ->
            card["abilities"]?.jsonArray?.any { ability -> ability.jsonObject["effect"]?.jsonPrimitive?.contentOrNull
                ?.startsWith("If Festival Grounds is in play,") == true } == true
        }
        val card = CatalogRuleCompiler.compile(row, emptyMap())!!
        assertTrue(card.abilities.any { it.festivalGroundsMayAttackAgain })
    }
    @Test fun `attack damage bonus checks equal hand sizes`() {
        val compiled = CatalogRuleCompiler.compileAttack("hand-size", 0,
            attack("If you have the same number of cards in your hand as your opponent, this attack does 90 more damage.", "30+"))!!
        assertTrue(Effect.SameHandSizeDamageBonus(90) in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `attack damage bonus counts damaged Benched Pokemon only`() {
        val compiled = CatalogRuleCompiler.compileAttack("bench-damage", 0,
            attack("This attack does 50 more damage for each of your Benched Pokémon that has any damage counters on it.", "50+"))!!
        assertTrue(Effect.OwnDamagedBenchedPokemonCountDamage(50) in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `Dynamic Blaze scales against an evolved Active and discards all attacker Energy`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv06-040"), emptyMap())!!
        val attack = card.attacks.first { it.name == "Dynamic Blaze" }
        assertTrue(Effect.OpponentEvolutionActiveDamageBonus(140) in attack.effects)
        assertTrue(attack.effects.any { it == Effect.DiscardAttackEnergy(0, all = true) })
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
    }
    @Test fun `Slight Shift compiles opponent Energy transfer between their Pokemon`() {
        val row = CatalogRuleCompiler.rows.getValue("sv10.5b-040")
        val index = row["attacks"]!!.jsonArray.indexOfFirst { it.jsonObject["name"]?.jsonPrimitive?.content == "Slight Shift" }
        val attack = CatalogRuleCompiler.compileAttack(row["id"]!!.jsonPrimitive.content, index, row["attacks"]!!.jsonArray[index].jsonObject)!!
        assertTrue(Effect.MoveEnergyBetweenOpponentPokemon in attack.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
    }
    @Test fun `Mirage Barrage discards Energy before selecting two damage targets`() {
        val row = CatalogRuleCompiler.rows.getValue("sv06-106")
        val index = row["attacks"]!!.jsonArray.indexOfFirst { it.jsonObject["name"]?.jsonPrimitive?.content == "Mirage Barrage" }
        val attack = CatalogRuleCompiler.compileAttack(row["id"]!!.jsonPrimitive.content, index, row["attacks"]!!.jsonArray[index].jsonObject)!!
        assertTrue(attack.effects.any { it == Effect.DiscardAttackEnergy(2) })
        assertTrue(attack.effects.any { it is Effect.MultiTargetDamage && it.amount == 120 && it.targets == 2 && it.discardBeforeDamage })
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
    }
    @Test fun `Extended Damagriiigus moves all own Bench counters onto an opponent Pokemon`() {
        val row = CatalogRuleCompiler.rows.getValue("sv10.5w-040")
        val index = row["attacks"]!!.jsonArray.indexOfFirst { it.jsonObject["name"]?.jsonPrimitive?.content == "Extended Damagriiigus" }
        val attack = CatalogRuleCompiler.compileAttack(row["id"]!!.jsonPrimitive.content, index, row["attacks"]!!.jsonArray[index].jsonObject)!!
        assertTrue(Effect.MoveOwnBenchDamageCountersToOpponent in attack.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
    }
    @Test fun `Snow Camouflage recognizes Item and Supporter effect protection`() {
        val row = CatalogRuleCompiler.rows.values.first { card ->
            card["abilities"]?.jsonArray?.any { ability -> ability.jsonObject["effect"]?.jsonPrimitive?.contentOrNull ==
                "Whenever your opponent plays an Item or Supporter card from their hand, prevent all effects of that card done to this Pokémon." } == true
        }
        val card = CatalogRuleCompiler.compile(row, emptyMap())!!
        assertTrue(card.abilities.any { it.preventOpponentItemSupporterEffects })
    }
    @Test fun `Whirling Envy uses damage counter threshold and ignores Weakness`() {
        val attack = CatalogRuleCompiler.compileAttack("test", 0, attack(
            "If this Pokémon has 2 or more damage counters on it, this attack does 90 more damage. This attack's damage isn't affected by Weakness.", "30+"))!!
        assertTrue(Effect.ConditionalOwnDamageCounterBonus(2, 90) in attack.effects)
        assertTrue(Effect.IgnoreWeakness in attack.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
    }
    @Test fun `Coiling Crush compiles a shared coin pool for defender Energy discards`() {
        val compiled = CatalogRuleCompiler.compileAttack("coiling-crush", 0,
            attack("Flip 2 coins. For each heads, discard an Energy from your opponent's Active Pokémon.", "20"))!!
        assertTrue(Effect.CoinDamage(2, 0) in compiled.effects)
        assertTrue(Effect.DiscardDefendingEnergy(1, onHeads = true, perHeads = true) in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `Lucky Attachment compiles as a mandatory single Basic Energy attachment`() {
        val compiled = CatalogRuleCompiler.compileAttack("lucky-attachment", 0,
            attack("Attach a Basic Energy card from your hand to 1 of your Pokémon.", "20"))!!
        assertTrue(Effect.AttachOneBasicEnergyFromHand in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `Homeward Chime compiles the attached bench Pokemon shuffle effect`() {
        val compiled = CatalogRuleCompiler.compileAttack("homeward-chime", 0,
            attack("Shuffle 1 of your Benched Pokémon and all attached cards into your deck.", "20"))!!
        assertTrue(Effect.ShuffleOwnBenchedPokemonIntoDeck in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `Parallel Placement compiles a named Pokemon search to the Bench`() {
        val compiled = CatalogRuleCompiler.compileAttack("parallel-placement", 0,
            attack("Search your deck for up to 3 Charjabug and put them onto your Bench. Then, shuffle your deck.", "20"))!!
        assertTrue(Effect.NamedPokemonToBench("Charjabug", 3) in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `Turbo Flare compiles to bench-only Basic Energy acceleration`() {
        val compiled = CatalogRuleCompiler.compileAttack("turbo-flare", 0,
            attack("Search your deck for up to 3 Basic Energy cards and attach them to your Benched Pokémon in any way you like. Then, shuffle your deck.", "20"))!!
        assertTrue(Effect.AttachBasicEnergyFromDeckAny(3, benchedOnly = true) in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `Flower Shower compiles the shared draw effect`() {
        val compiled = CatalogRuleCompiler.compileAttack("flower-shower", 0,
            attack("Each player draws 3 cards.", "20"))!!
        assertTrue(Effect.EachPlayerDraw(3) in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `Devolution Ray compiles the active evolution removal effect`() {
        val compiled = CatalogRuleCompiler.compileAttack("devolution-ray", 0,
            attack("If your opponent's Active Pokémon is an evolved Pokémon, devolve it by putting the highest Stage Evolution card on it into your opponent's hand.", "20"))!!
        assertTrue(Effect.DevolveOpponentActive in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `an attack can discard a Stadium in play`() {
        val compiled = CatalogRuleCompiler.compileAttack("discard-stadium", 0, attack("Discard a Stadium in play.", "20"))!!
        assertTrue(Effect.DiscardStadium in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `damage formula counts Prizes taken by the opponent`() {
        val compiled = CatalogRuleCompiler.compileAttack("opponent-prizes", 0,
            attack("This attack does 60 damage for each Prize card your opponent has taken.", "60×"))!!
        assertTrue(Effect.OpponentPrizeTakenDamage(60) in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `search up to three Pokemon into hand compiles with its printed maximum`() {
        val compiled = CatalogRuleCompiler.compileAttack("search-three-pokemon", 0,
            attack("Search your deck for up to 3 Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.", "20"))!!
        assertTrue(Effect.SearchPokemonToHand(3) in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `return this Pokemon and all attached cards to hand compiles`() {
        val compiled = CatalogRuleCompiler.compileAttack("return-self", 0,
            attack("Put this Pokémon and all attached cards into your hand.", "20"))!!
        assertTrue(Effect.ReturnSelfAndAttachmentsToHand in compiled.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(compiled))
    }
    @Test fun `on evolution healing and energy discard ability compiles`() {
        val row = CatalogRuleCompiler.rows.values.first { card ->
            card["abilities"]?.jsonArray?.any { ability -> ability.jsonObject["effect"]?.jsonPrimitive?.contentOrNull
                ?.contains("you may heal all damage from each of your Evolution Pokémon") == true } == true
        }
        val abilityIndex = row["abilities"]!!.jsonArray.indexOfFirst { ability ->
            ability.jsonObject["effect"]?.jsonPrimitive?.contentOrNull
                ?.contains("you may heal all damage from each of your Evolution Pokémon") == true
        }
        val compiled = CatalogRuleCompiler.compileAbility(row["id"]!!.jsonPrimitive.content, abilityIndex,
            row["abilities"]!!.jsonArray[abilityIndex].jsonObject)
        assertNotNull(compiled, "Ability text did not compile: ${row["abilities"]!!.jsonArray[abilityIndex].jsonObject["effect"]}")
        assertTrue(compiled.onEvolveHealAllEvolutionAndDiscardEnergy)
    }
    @Test fun `Chien-Pao returns one attached Energy to hand after attacking`() {
        val attack = CatalogRuleCompiler.compileAttack("chien-pao", 0,
            attack("Put an Energy attached to this Pokémon into your hand.", "120"))!!
        assertTrue(Effect.ReturnAttachedEnergyToHand in attack.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
        for (id in listOf("sv08-056", "svp-152")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.abilities.single().onBenchDiscardStadium)
            assertTrue(Effect.ReturnAttachedEnergyToHand in card.attacks.single { it.name == "Icicle Loop" }.effects)
        }
    }
    @Test fun `Duraludon Raging Hammer scales from its own damage counters`() {
        for (id in listOf("sv07-106", "sv08.5-069")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single { it.name == "Raging Hammer" }
            assertTrue(Effect.OwnDamageCounterDamage(10) in attack.effects)
        }
    }
    @Test fun `Cinccino Do the Wave scales from the player's bench`() {
        for (id in listOf("sv10.5b-076", "sv10.5b-153")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single { it.name == "Do the Wave" }
            assertTrue(Effect.OwnBenchCountDamage(20) in attack.effects)
        }
    }
    @Test fun `Exeggutor Stomping Wood counts attached Grass Energy`() {
        for (id in listOf("me01-005", "me01-135")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single { it.name == "Stomping Wood" }
            assertTrue(Effect.OwnEnergyTypeCountDamage(EnergyType.GRASS, 30) in attack.effects)
        }
    }
    @Test fun `Ceruledge Cursed Edge removes opponent Special Energy`() {
        for (id in listOf("sv08-035", "sv08-197")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single { it.name == "Cursed Edge" }
            assertTrue(Effect.DiscardAllOpponentSpecialEnergy in attack.effects)
            assertTrue(EffectEngine(TrainerCatalog).supported(attack))
        }
    }
    @Test fun `opponent Energy discard clauses preserve type and Special restrictions`() {
        val fire = CatalogRuleCompiler.compileAttack("ducklett", 0, attack(
            "Discard a {R} Energy from your opponent's Active Pokémon."))!!
        assertTrue(Effect.DiscardDefendingEnergy(1, type = EnergyType.FIRE) in fire.effects)
        val special = CatalogRuleCompiler.compileAttack("cobalion", 0, attack(
            "Discard a Special Energy from your opponent's Active Pokémon."))!!
        assertTrue(Effect.DiscardDefendingEnergy(1, specialOnly = true) in special.effects)
    }
    @Test fun `team healing attack heals every Pokémon`() {
        val attack = CatalogRuleCompiler.compileAttack("ho-oh", 0,
            attack("Heal 50 damage from each of your Pokémon.", ""))!!
        assertTrue(Effect.HealEachOwnPokemon(50) in attack.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
    }
    @Test fun `Fan Rotom implements its first-turn search and Stadium-gated attack`() {
        for (id in listOf("me02.5-171", "me02.5-250", "sv07-118", "sv08.5-085")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val fanCall = card.abilities.single()
            assertEquals(3, fanCall.searchColorlessPokemonMaxHpToHand)
            assertTrue(fanCall.requiresFirstTurn)
            assertTrue(Effect.RequiresStadium in card.attacks.single().effects)
        }
    }
    @Test fun `Durant Bite Together checks for Durant on the Bench`() {
        for (id in listOf("sv10.5w-070", "sv10.5w-150")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single { it.name == "Bite Together" }
            assertTrue(Effect.BenchPokemonPresenceDamageBonus("Durant", 20) in attack.effects)
        }
    }
    @Test fun `Excadrill Piercing Drill only targets damaged Benched Pokemon`() {
        for (id in listOf("sv10.5b-046", "sv10.5b-160", "sv10.5b-168")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val effect = card.attacks.single { it.name == "Piercing Drill" }.effects.filterIsInstance<Effect.BenchDamage>().single()
            assertEquals(60, effect.amount)
            assertTrue(effect.requiresDamageCounters)
        }
    }
    @Test fun `Ethan Ho-Oh Golden Flame attaches up to two Fire Energy to a Benched Ethan Pokemon`() {
        for (id in listOf("me02.5-026", "sv10-039", "sv10-209", "sv10-230", "sv10-239")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val ability = card.abilities.single()
            assertEquals(EnergyType.FIRE, ability.attachHandEnergyType)
            assertEquals(2, ability.attachHandEnergyMax)
            assertEquals("Ethan's", ability.attachHandEnergyToPokemonNamePrefix)
            assertTrue(ability.attachHandEnergyBenchOnly)
            assertTrue(card.attacks.any { Effect.HealEachOwnPokemon(50) in it.effects })
        }
    }
    @Test fun `Dhelmise counts Pokémon with Hide 'n' Sneak in discard`() {
        for (id in listOf("me05-039", "me05-091", "mep-084")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single()
            assertTrue(Effect.OwnDiscardAbilityCountBonus("Hide 'n' Sneak", 4, 140) in attack.effects)
            val attacker = PokemonInstance(listOf(CardInstance("attacker", id)), enteredPlayTurn = 0)
            val defender = PokemonInstance(listOf(CardInstance("defender", id)), enteredPlayTurn = 0)
            val damage = EffectEngine(TrainerCatalog).damage(attacker, defender, attack.effects,
                ignoreWeaknessResistance = true, ownDiscardPokemonAbilityNames = List(4) { "Hide 'n' Sneak" })
            assertEquals(170, damage)
        }
    }
    @Test fun `Cynthia's Spiritomb counts damage counters on Benched Cynthia's Pokémon`() {
        for (id in listOf("me02.5-133", "me02.5-244", "sv10-129")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single()
            assertTrue(Effect.OwnNamedBenchDamageCounterDamage("Cynthia's", 10) in attack.effects)
            assertTrue(Effect.IgnoreWeakness in attack.effects)
            val pokemon = PokemonInstance(listOf(CardInstance("p", id)), enteredPlayTurn = 0)
            val damage = EffectEngine(TrainerCatalog).damage(pokemon, pokemon, attack.effects,
                ownBenchPokemonDetails = listOf("Cynthia's Gible" to 40))
            assertEquals(40, damage)
        }
    }
    @Test fun `Fezandipiti and Cinccino compile coin based attack damage prevention`() {
        for (id in listOf("sv06-096", "sv06.5-073", "sv08.5-045")) {
            val ability = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!.abilities.single()
            assertTrue(ability.coinFlipPreventAttackDamage)
            assertEquals(EnergyType.DARKNESS, ability.coinFlipPreventAttackDamageEnergyType)
        }
        for (id in listOf("me04-073", "me04-105", "me04-119")) {
            val ability = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!.abilities.single()
            assertTrue(ability.coinFlipPreventAttackDamage)
            assertNull(ability.coinFlipPreventAttackDamageEnergyType)
        }
    }

    @Test fun `Castform Sunny Assist moves all attached Energy to a Benched Pokemon`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-020"), emptyMap())!!
        assertTrue(Effect.MoveAllEnergyToBench in card.attacks.single { it.name == "Sunny Assist" }.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(card.attacks.single { it.name == "Sunny Assist" }))
    }

    @Test fun `all Froslass prints compile the shared checkup damage ability`() {
        listOf("sv06-053", "sv06-174", "svp-117").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertEquals(1, card.abilities.single().checkupDamageCountersPerPokemonWithAbilityExceptFroslass)
        }
    }

    @Test fun `all bench damage self side clause compiles and is executable`() {
        val row = CatalogRuleCompiler.rows.getValue("sv08-028")
        val card = CatalogRuleCompiler.compile(row, emptyMap())!!
        assertTrue(card.attacks.any { attack -> attack.effects.any { it is Effect.OwnBenchDamage } })
    }

    @Test fun `Metal bench presence bonus compiles for all matching attacks`() {
        listOf("sv05-019", "sv08-109").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.any { it.effects.any { effect -> effect is Effect.BenchTypePresenceDamageBonus && effect.type == EnergyType.METAL } })
        }
    }

    @Test fun `both Drampa prints compile damaged bench bonus`() {
        listOf("sv05-138", "sv05-184").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.any { it.effects.any { effect -> effect is Effect.AllOwnBenchedPokemonDamagedBonus } })
        }
    }

    @Test fun `all Galvantula ex Fulgurite prints combine damage discard and Item lock`() {
        listOf("sv07-051", "sv07-159", "sv07-168").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single { it.name == "Fulgurite" }
            val conditional = card.attacks.single { it.name == "Charged Web" }
            assertTrue(conditional.effects.any { it is Effect.OpponentExDamage })
            assertTrue(attack.effects.any { it is Effect.DiscardAttackEnergy && it.all })
            assertTrue(Effect.LockOpponentItemsNextTurn in attack.effects)
        }
    }

    @Test fun `all Fraxure and Goodra Dragon Pulse prints discard own deck top`() {
        listOf("sv06.5-045", "sv06.5-077", "me04-068", "mep-077").forEach { id ->
            val row = CatalogRuleCompiler.rows.getValue(id)
            val attacks = row["attacks"]!!.jsonArray.map { it.jsonObject }
            val index = attacks.indexOfFirst { it["name"]!!.jsonPrimitive.content == "Dragon Pulse" }
            val attack = CatalogRuleCompiler.compileAttack(id, index, attacks[index])
            assertNotNull(attack, id)
            assertTrue(Effect.DiscardOwnDeckTop in attack.effects, id)
        }
    }

    @Test fun `Farigiraf ex Armor Tail compiles against Basic Pokemon ex`() {
        listOf("sv05-108", "sv05-194").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.abilities.single().preventDamageFromBasicPokemonEx)
        }
    }

    @Test fun `both Chandelure prints compile Basic Fire discard threshold damage`() {
        listOf("sv10.5w-018", "sv10.5w-103").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.single { it.name == "Incendiary Pillar" }.effects.any {
                it is Effect.DiscardEnergyThresholdDamage && it.type == EnergyType.FIRE && it.minimum == 10 && it.bonus == 100
            })
        }
    }

    @Test fun `all Glaceon Permeating Chill prints schedule delayed damage counters`() {
        listOf("sv06-054", "sv08.5-025", "svp-171").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.single { it.name == "Permeating Chill" }.effects.any {
                it == Effect.DelayedDefendingDamageCounters(9)
            })
        }
    }

    @Test fun `Delibird and Exeggcute first turn attacks search any card`() {
        listOf("me01-105", "me01-152", "sv08-001", "sv08-192").forEach { id ->
            val row = CatalogRuleCompiler.rows.getValue(id)
            val attacks = row["attacks"]!!.jsonArray.map { it.jsonObject }
            val index = attacks.indexOfFirst { it["effect"]?.jsonPrimitive?.content?.startsWith("If you go first,") == true }
            val attack = CatalogRuleCompiler.compileAttack(id, index, attacks[index])!!
            assertTrue(Effect.MayAttackFirstTurnIfStarting in attack.effects)
            if (id.startsWith("me01")) assertTrue(Effect.SearchAnyCardToHand in attack.effects)
            else assertTrue(Effect.SearchEvolutionForSelfToEvolve in attack.effects)
        }
    }

    @Test fun `all Garganacl prints compile Fighting attack damage aura`() {
        listOf("me01-084", "me01-147").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.abilities.single().teamAttackDamageBonusType == EnergyType.FIGHTING)
            assertEquals(30, card.abilities.single().teamAttackDamageBonus)
        }
    }

    @Test fun `all simple hand reveal attack prints compile`() {
        listOf("sv05-126", "sv06-066", "sv06.5-016", "sv08-084", "sv10.5b-071", "sv10.5b-148", "svp-185")
            .forEach { id ->
                val row = CatalogRuleCompiler.rows.getValue(id)
                row["attacks"]!!.jsonArray.map { it.jsonObject }.forEachIndexed { index, raw ->
                    if (raw["effect"]?.jsonPrimitive?.content == "Your opponent reveals their hand.") {
                        val attack = CatalogRuleCompiler.compileAttack(id, index, raw)
                        assertNotNull(attack, id)
                        assertTrue(Effect.RevealOpponentHand in attack.effects, id)
                    }
                }
            }
    }

    @Test fun `both Conkeldurr prints gain HP for attached Fighting Energy`() {
        val energy = CardDefinition("test-fighting-energy", "Fighting Energy", "test", "1", category = Category.ENERGY,
            type = EnergyType.FIGHTING, basicEnergy = true)
        listOf("sv10.5b-049", "sv10.5b-127").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val catalog = object : CardCatalog {
                override fun get(id: String) = when (id) { card.id -> card; energy.id -> energy; else -> TrainerCatalog.get(id) }
            }
            val pokemon = PokemonInstance(listOf(CardInstance("conk", card.id)), energies = List(2) { CardInstance("f$it", energy.id) }, enteredPlayTurn = 0)
            assertEquals(card.hp + 80, EffectEngine(catalog).effectiveHp(pokemon))
        }
    }

    @Test fun `both Enamorus prints compile same type in play damage bonus`() {
        listOf("sv06-093", "sv06-180").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.single { it.name == "Love Resonance" }.effects.contains(Effect.SameTypePokemonInPlayDamageBonus(120)))
        }
    }

    @Test fun `both Cramorant prints compile Spit Shot target damage after energy discard`() {
        listOf("sv06-058", "sv06-176").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single { it.name == "Spit Shot" }
            assertTrue(attack.effects.any { it is Effect.DiscardAttackEnergy && it.all })
            assertTrue(attack.effects.contains(Effect.TargetedDamage(120)))
        }
    }

    @Test fun `Cynthia and Genesect abilities search their named evolution Pokemon`() {
        listOf("me02.5-110", "sv10-103", "sv10.5b-067", "sv10.5b-161", "sv10.5b-169").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val ability = card.abilities.single()
            assertEquals(if (id.startsWith("sv10.5b")) 2 else 1, ability.searchPokemonMaxToHand)
            if (id.startsWith("sv10.5b")) assertEquals(EnergyType.METAL, ability.searchPokemonEvolutionTypeToHand)
            else assertEquals("Cynthia's", ability.searchPokemonNamePrefixToHand)
        }
    }

    @Test fun `both Dudunsparce ex prints scale damage by opponent ex count`() {
        listOf("sv09-121", "sv09-178").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.single { it.name == "Tenacious Tail" }.effects.contains(Effect.OpponentExCountDamage(60)))
        }
    }

    @Test fun `both Cyclizar ex prints may draw five after Zircon Road`() {
        listOf("sv08-159", "sv08-228").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(Effect.Draw(5) in card.attacks.single { it.name == "Zircon Road" }.effects)
        }
    }
    @Test fun `Blaziken Inferno Kick Flurry compiles base damage energy discard and bench shot`() {
        for (id in listOf("sv10-042", "sv10-192")) {
            val blaziken = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = blaziken.attacks.single { it.name == "Inferno Kick Flurry" }
            assertTrue(Effect.Damage(120) in attack.effects)
            assertTrue(Effect.DiscardEnergyThenBenchDamage(2, 120) in attack.effects)
        }
    }
    @Test fun `Blissey ex Happy Switch and Return compile fully`() {
        for (id in listOf("sv06-134", "sv06-201")) {
            val blissey = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(blissey.abilities.single().moveBasicEnergyBetweenOwnPokemon)
            assertTrue(Effect.DrawToHand(6) in blissey.attacks.single { it.name == "Return" }.effects)
        }
    }
    @Test fun `Bloodmoon Ursaluna rules compile for every print in the batch`() {
        for (id in listOf("sv06.5-025", "sv08.5-054")) {
            val ursaluna = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(ursaluna.attacks.single { it.name == "Mad Bite" }.effects.contains(Effect.OpponentActiveDamageCounterDamage(30)))
        }
        for (id in listOf("sv06-202", "sv06-216", "sv06-222", "sv08.5-168", "svp-177")) {
            val ursaluna = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(ursaluna.abilities.single().attackDiscountPerOpponentPrize)
            assertTrue(Effect.RestrictNextTurn() in ursaluna.attacks.single { it.name == "Blood Moon" }.effects)
        }
    }
    @Test fun `Cornerstone Ogerpon and Dragapult spread compile across repeated prints`() {
        for (id in listOf("sv06-112", "sv06-199", "sv06-215", "sv08.5-058", "sv08.5-160")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.abilities.single().preventDamageFromPokemonWithAbilities)
            assertTrue(Effect.IgnoreWeaknessResistance in card.attacks.single().effects)
        }
        for (id in listOf("me02.5-160", "sv06-130", "sv06-200", "sv08.5-073", "sv08.5-165")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.flatMap { it.effects }.contains(Effect.BenchDamageCounters(6, distribute = true)))
        }
    }
    @Test fun `Drakloak Recon Directive compiles on every print`() {
        for (id in listOf("me02.5-159", "me02.5-248", "sv06-129", "sv08.5-072")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertEquals(2, card.abilities.single().lookAtTopCardsChooseOne)
        }
    }
    @Test fun `Crobat Nighttime Maneuvers compiles as an Active search to deck top`() {
        for (id in listOf("me04-051", "me04-093", "mep-076")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.abilities.single().searchAnyCardToTop)
            assertTrue(card.abilities.single().requiresActive)
        }
    }
    @Test fun `Eelektrik Dynamotor compiles for each legal print with Lightning bench restriction`() {
        for (id in listOf("me02.5-060", "sv10.5b-031", "sv10.5b-114")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val ability = card.abilities.single()
            assertTrue(ability.attachBasicEnergyFromDiscard)
            assertEquals(EnergyType.LIGHTNING, ability.attachBasicEnergyFromDiscardType)
            assertTrue(ability.attachBasicEnergyFromDiscardBenchOnly)
        }
    }

    @Test fun `Ionos Bellibolt energy acceleration compiles across every print`() {
        val prints = listOf("me02.5-070", "me02.5-279", "sv09-053", "sv09-172", "sv09-183", "sv09-188", "svp-194")
        prints.forEach { id ->
            val ability = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!.abilities.single()
            assertEquals(EnergyType.LIGHTNING, ability.attachHandEnergyType)
            assertEquals("Iono's", ability.attachHandEnergyToPokemonNamePrefix)
            assertTrue(ability.unlimitedUse)
        }
    }

    @Test fun `Shuffle hand and draw attack compiles for all affected prints`() {
        val prints = listOf("me02.5-102", "me02.5-240", "me05-055", "me05-102", "me05-117", "sv05-030")
        prints.forEach { id ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            val attack = raw["attacks"]!!.jsonArray.map { it.jsonObject }.first { it["name"]?.jsonPrimitive?.content == "Wheely Draw" || it["name"]?.jsonPrimitive?.content == "Spin and Draw" || it["name"]?.jsonPrimitive?.content == "Flippity Flap" }
            val compiled = CatalogRuleCompiler.compileAttack(id, 0, attack)
            assertNotNull(compiled, id)
            assertTrue(compiled.effects.contains(Effect.ShuffleHandDraw(6)), id)
        }
    }

    @Test fun `Mega Venusaur movable Grass Energy ability compiles for every print`() {
        listOf("me01-003", "me01-155", "me01-177", "mep-013").forEach { id ->
            val ability = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!.abilities.single()
            assertTrue(ability.unlimitedUse)
            assertTrue(ability.moveBasicEnergyBetweenOwnPokemon)
            assertEquals(EnergyType.GRASS, ability.moveBasicEnergyType)
        }
    }

    @Test fun `Iron Crown Twin Shotels ignores weakness resistance and target effects`() {
        listOf("sv05-081", "sv05-191", "sv05-206", "sv05-216", "sv08.5-158", "svp-146").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single()
            assertTrue(attack.effects.contains(Effect.MultiTargetDamage(50, 2, ignoreWeaknessResistance = true)), id)
            assertTrue(attack.effects.contains(Effect.IgnoreOpponentActiveEffects), id)
            assertEquals(20, card.abilities.single().futurePokemonDamageBonus, id)
        }
    }

    @Test fun `Supporter search attack compiles across all matching prints`() {
        listOf("me02-027", "me02-098", "me02.5-126", "sv06.5-027", "sv10-127", "sv10-200").forEach { id ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            val attack = CatalogRuleCompiler.compileAttack(id, 0, raw["attacks"]!!.jsonArray.first().jsonObject)!!
            assertTrue(Effect.SearchSupporterToHand in attack.effects, id)
        }
    }

    @Test fun `Mega Gardevoir Overflowing Wishes compiles across all prints`() {
        listOf("me01-060", "me01-159", "me01-178", "me01-187", "me02.5-089", "mep-032").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.single { it.name == "Overflowing Wishes" }.effects.contains(Effect.AttachBasicEnergyToEachBenchedPokemon(EnergyType.PSYCHIC)), id)
        }
    }

    @Test fun `common effect prevention abilities compile across Mega Clefable and Empoleon prints`() {
        listOf("me02-070", "me02-114", "me03-031", "me03-103", "me03-119", "mep-072").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.abilities.single().preventOpponentEffects, id)
        }
    }

    @Test fun `active damage retaliation conditions compile across matching card prints`() {
        listOf("me02.5-027", "me02.5-223", "me04-052", "sv05-008", "sv05-009", "sv06-123", "sv10.5b-056", "sv10.5b-134").forEach { id ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            val ability = CatalogRuleCompiler.compileAbility(id, 0, raw["abilities"]!!.jsonArray.single().jsonObject)
            assertNotNull(ability, id)
            assertNotNull(ability.retaliateCondition, id)
        }
    }

    @Test fun `no-energy retreat and switch abilities compile across their prints`() {
        listOf("me02-011", "me02.5-024", "me02.5-222", "sv10-036").forEach { id ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            assertTrue(CatalogRuleCompiler.compileAbility(id, 0, raw["abilities"]!!.jsonArray.single().jsonObject)!!.freeRetreatWithNoEnergy, id)
        }
        listOf("me02.5-152", "me02.5-271", "me02.5-290", "me02.5-295").forEach { id ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            assertTrue(CatalogRuleCompiler.compileAbility(id, 0, raw["abilities"]!!.jsonArray.single().jsonObject)!!.switchOwnActive, id)
        }
    }

    @Test fun `discard own Energy for variable attack damage compiles across all matching prints`() {
        val prints = mapOf(
            "me02.5-024" to "Lava Burst", "me02.5-222" to "Lava Burst", "sv10-036" to "Lava Burst",
            "sv05-123" to "Bellowing Thunder", "sv05-196" to "Bellowing Thunder", "sv05-208" to "Bellowing Thunder",
            "sv05-218" to "Bellowing Thunder", "sv08.5-166" to "Bellowing Thunder", "svp-145" to "Bellowing Thunder",
            "sv06-022" to "Spill the Tea")
        prints.forEach { (id, name) ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            val index = raw["attacks"]!!.jsonArray.indexOfFirst { it.jsonObject["name"]?.jsonPrimitive?.content == name }
            assertTrue(index >= 0, id)
            val attack = CatalogRuleCompiler.compileAttack(id, index, raw["attacks"]!!.jsonArray[index].jsonObject)
            assertNotNull(attack, id)
            assertTrue(attack.effects.any { it is Effect.DiscardOwnEnergyDamageBonus }, id)
        }
    }

    @Test fun `Torment and Memory Lock compile for all attack-lock prints`() {
        listOf("me02.5-126", "me03-059", "sv09-099", "sv10-127", "sv10-200").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.any { Effect.ChooseOpponentAttackToRestrict in it.effects }, id)
        }
    }

    @Test fun `Item search attacks compile across all matching prints`() {
        listOf("me05-051", "sv05-047", "sv10-072", "sv10.5w-072", "sv10.5w-152").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.any { Effect.SearchItemToHand in it.effects }, id)
        }
    }

    @Test fun `damage counter scaling and damage healing attacks compile across all prints`() {
        listOf("me01-061", "me01-144", "sv06-052", "sv08-139", "sv08-210").forEach { id ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            val attackJson = raw["attacks"]!!.jsonArray.first { it.jsonObject["effect"]?.jsonPrimitive?.content == "This attack does 20 damage for each damage counter on your opponent's Active Pokémon." }.jsonObject
            val attack = CatalogRuleCompiler.compileAttack(id, 0, attackJson)
            assertNotNull(attack, id)
            assertTrue(attack.effects.any { it == Effect.OpponentActiveDamageCounterDamage(20) }, id)
        }
        val heals = mapOf("sv06-013" to "Giga Drain", "sv06.5-009" to "Suction", "sv08-025" to "Leech Life",
            "sv09-004" to "Leech Life", "sv10.5w-010" to "Giga Drain", "sv10.5w-095" to "Giga Drain")
        heals.forEach { (id, attackName) ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            val attackJson = raw["attacks"]!!.jsonArray.first { it.jsonObject["name"]?.jsonPrimitive?.content == attackName }.jsonObject
            val attack = CatalogRuleCompiler.compileAttack(id, 0, attackJson)
            assertNotNull(attack, id)
            assertTrue(Effect.HealSelfByDamageDealt in attack.effects, id)
        }
    }

    @Test fun `Kofu discount and Flip the Script abilities compile across all prints`() {
        listOf("sv07-042", "sv07-045", "sv07-149", "svp-134").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.abilities.single().attackDiscountPerKofuDiscard, id)
        }
        listOf("me02.5-142", "me02.5-288", "sv06.5-084", "sv06.5-092").forEach { id ->
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertEquals(3, card.abilities.single().draw, id)
            assertTrue(card.abilities.single().requiresPreviousKnockout, id)
        }
    }
    @Test fun `Duskull Come and Get You recovers up to three discarded Duskull`() {
        for (id in listOf("sv06.5-018", "sv06.5-068", "sv08.5-035")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(Effect.RecoverNamedPokemonToBench("Duskull", 3) in card.attacks.single { it.name == "Come and Get You" }.effects)
        }
    }
    @Test fun `Durant ex Sudden Shearing compiles across every legal print`() {
        for (id in listOf("sv08-004", "sv08-215", "sv08-236")) {
            val ability = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!.abilities.single()
            assertTrue(ability.onBenchDiscardOpponentDeckTop)
        }
    }
    @Test fun `Bombirdier Drop Shot discards all attached Energy and targets any opposing Pokemon`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-101"), emptyMap())!!
        val attack = card.attacks.single { it.name == "Drop Shot" }
        assertTrue(Effect.DiscardAttackEnergy(0, all = true) in attack.effects)
        assertTrue(Effect.TargetedDamage(120) in attack.effects)
    }
    @Test fun `Braviary Drag Off selects a Benched Pokemon before dealing damage`() {
        for (id in listOf("sv08-153", "sv08-214")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = card.attacks.single { it.name == "Drag Off" }
            assertTrue(Effect.PullSelectedBenchBeforeDamage in attack.effects)
            assertTrue(Effect.TargetedDamage(40, benchedOnly = true) in attack.effects)
        }
    }
    @Test fun `discard hand and draw attack clause compiles as an executable effect`() {
        val compiled = CatalogRuleCompiler.compileAttack("discard-draw", 0, attack("Discard your hand and draw 6 cards.", ""))!!
        assertTrue(Effect.DiscardHandDraw(6) in compiled.effects)
    }
    @Test fun `Bramblin Sneaky Placement compiles a single damage counter effect`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02-046"), emptyMap())!!
        assertTrue(Effect.PlaceDamageCounter in card.attacks.single { it.name == "Sneaky Placement" }.effects)
    }
    @Test fun `Bronzong Tool Drop counts Tools attached across both fields`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02-072"), emptyMap())!!
        assertTrue(Effect.AllPokemonToolCountDamage(40) in card.attacks.single { it.name == "Tool Drop" }.effects)
    }
    @Test fun `Bronzong Protective Bell compiles its whole-team reduction`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08.5-067"), emptyMap())!!
        assertEquals(10, card.abilities.single().teamDamageReduction)
    }
    @Test fun `Brambleghast abilities compile evolution confusion and Prize-scaled HP`() {
        val evolved = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02-047"), emptyMap())!!
        assertTrue(evolved.abilities.single().onEvolveConfuseOpponentActive)
        val resilient = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv05-021"), emptyMap())!!
        assertEquals(50, resilient.abilities.single().hpBonusPerOpponentPrizeTaken)
    }
    @Test fun `Carbink Double Type grants Psychic as an attack type`() {
        for (id in listOf("me02.5-117", "me02.5-242")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertEquals(listOf(EnergyType.PSYCHIC), card.abilities.single().additionalPokemonTypes)
        }
    }
    @Test fun `Bronzong Evolution Jammer and Metal Block compile their next-turn restrictions`() {
        val jammer = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv05-069"), emptyMap())!!
        assertTrue(Effect.LockOpponentEvolutionNextTurn in jammer.attacks.single { it.name == "Evolution Jammer" }.effects)
        val metalBlock = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me05-064"), emptyMap())!!
        assertTrue(Effect.ReduceEvolutionDamageNextTurn(100) in metalBlock.attacks.single { it.name == "Metal Block" }.effects)
    }
    @Test fun `Carracosta Primal Knowledge compiles Active Evolution damage bonus`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-038"), emptyMap())!!
        assertEquals(30, card.abilities.single().attackDamageBonusAgainstEvolutionActive)
    }
    @Test fun `Camerupt and Centiskorch gate attacks on the Burned condition`() {
        for (id in listOf("me02.5-028", "sv05-037")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(card.attacks.any { Effect.RequiresOpponentBurned in it.effects })
        }
    }
    @Test fun `Carnivine Loom Over reduces damage for each of its damage counters`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-007"), emptyMap())!!
        assertTrue(Effect.OwnDamageCounterPenalty(10) in card.attacks.single { it.name == "Loom Over" }.effects)
    }
    @Test fun `Celebi Traverse Time searches Grass Pokemon and Stadiums in one selection`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-012"), emptyMap())!!
        assertTrue(Effect.SearchPokemonTypeAndStadiumToHand(3, EnergyType.GRASS) in card.attacks.single { it.name == "Traverse Time" }.effects)
    }
    @Test fun `Bouffalant Ready to Ram compiles delayed retaliation after being damaged`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-151"), emptyMap())!!
        assertTrue(Effect.RetaliateDamageCountersNextTurn(6) in card.attacks.single { it.name == "Ready to Ram" }.effects)
    }
    @Test fun `Iron Boulder retaliation wording compiles for every Standard print`() {
        val ids = listOf("sv05-099", "sv05-192", "sv05-207", "sv05-217", "svp-147")
        ids.forEach { id ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            val compiled = CatalogRuleCompiler.compile(raw, emptyMap())
            assertNotNull(compiled, id)
            assertTrue(compiled.attacks.flatMap { it.effects }.contains(Effect.RetaliateDamageCountersNextTurn(8)), id)
        }
    }
    @Test fun `Mega Lucario recovers Fighting Energy to its Bench on every Standard print`() {
        val ids = listOf("me01-160", "me01-179", "me01-188", "me02.5-113", "mep-012", "mep-033")
        ids.forEach { id ->
            val raw = CatalogRuleCompiler.rows.getValue(id)
            val compiled = CatalogRuleCompiler.compile(raw, emptyMap())
            assertNotNull(compiled, id)
            assertTrue(compiled.attacks.flatMap { it.effects }.contains(Effect.AttachBasicEnergyFromDiscardAny(3, EnergyType.FIGHTING, benchedOnly = true)), id)
        }
    }
    @Test fun `Lillie Clefairy changes weakness for all Standard prints`() {
        val ids = listOf("me02.5-076", "me02.5-280", "sv09-056", "sv09-173", "sv09-184", "svp-195")
        ids.forEach { id ->
            val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())
            assertNotNull(compiled, id)
            assertTrue(compiled.abilities.any { it.changeOpponentNsPokemonWeaknessToPsychic }, id)
        }
    }
    @Test fun `Night Joker copy attack compiles for all Standard prints`() {
        val ids = listOf("me02.5-137", "me02.5-286", "sv09-098", "sv09-175", "sv09-185", "sv09-189")
        ids.forEach { id ->
            val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())
            assertNotNull(compiled, id)
            assertTrue(Effect.CopyBenchedNsAttack in compiled.attacks.single { it.name == "Night Joker" }.effects, id)
        }
    }
    @Test fun `Shadowy Concealment compiles on every Standard Mega Gengar print`() {
        val ids = listOf("me02-056", "me02.5-125", "me02.5-269", "me02.5-284", "mep-073")
        ids.forEach { id ->
            val compiled = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())
            assertNotNull(compiled, id)
            assertTrue(compiled.abilities.any { it.reducePrizeForDarknessKnockoutByEx }, id)
        }
    }
    @Test fun `Boltund Electrifying Dash selects only Lightning Energy for Benched targets`() {
        val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv05-059"), emptyMap())!!
        assertTrue(Effect.AttachBasicEnergyFromDeckAny(2, EnergyType.LIGHTNING, benchedOnly = true) in card.attacks.single { it.name == "Electrifying Dash" }.effects)
    }
    @Test fun `batch damage formulas compile as executable effects`() {
        fun compiled(text: String, damage: String) = CatalogRuleCompiler.compileAttack("batch", 0, attack(text, damage))!!
        assertTrue(Effect.OwnDiscardEnergyDamage(20) in compiled("This attack does 20 more damage for each Energy card in your discard pile.", "30+").effects)
        assertTrue(Effect.OwnInPlayEnergyDamage(30) in compiled("This attack does 30 damage for each Energy attached to all Pokémon.", "30×").effects)
        assertTrue(Effect.OwnSpecialEnergyDamage(70) in compiled("This attack does 70 damage for each Special Energy card attached to this Pokémon.", "70×").effects)
        assertTrue(Effect.OpponentPrizeTakenDamage(30) in compiled("This attack does 30 more damage for each Prize card your opponent has taken.", "120+").effects)
        assertTrue(Effect.OpponentPrizeCountDamage(2, 100) in compiled("If your opponent has 2 or fewer Prize cards remaining, this attack does 100 more damage.", "70+").effects)
        assertTrue(Effect.OpponentNoRetreatDamage(80) in compiled("If your opponent's Active Pokémon has no Retreat Cost, this attack does 80 more damage.", "80+").effects)
        assertTrue(Effect.OpponentResistanceDamage(EnergyType.FIGHTING, 50) in compiled("If your opponent's Active Pokémon has {F} Resistance, this attack does 50 more damage.", "30+").effects)
        assertTrue(Effect.OwnToolAttachedDamage(40) in compiled("If this Pokémon has a Pokémon Tool attached, this attack does 40 more damage.", "80+").effects)
    }
    @Test fun `batch damage formulas evaluate against live game state`() {
        val attackerDef = CardDefinition("batch-source", "Source", "Test", "S", category = Category.POKEMON, hp = 200, type = EnergyType.FIGHTING)
        val defenderDef = CardDefinition("batch-target", "Target", "Test", "T", category = Category.POKEMON, hp = 300, resistance = EnergyType.FIGHTING)
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            attackerDef.id -> attackerDef; defenderDef.id -> defenderDef; else -> TrainerCatalog.get(id)
        } }
        val attacker = PokemonInstance(listOf(CardInstance("batch-a", attackerDef.id)), tool = CardInstance("batch-tool", "sv01-258"), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("batch-d", defenderDef.id)), enteredPlayTurn = 0)
        val effects = listOf(Effect.Damage(10), Effect.OwnDiscardEnergyDamage(20), Effect.OwnInPlayEnergyDamage(5),
            Effect.OwnSpecialEnergyDamage(30), Effect.OpponentPrizeTakenDamage(10), Effect.OpponentPrizeCountDamage(2, 40),
            Effect.OpponentNoRetreatDamage(50), Effect.OpponentResistanceDamage(EnergyType.FIGHTING, 15), Effect.OwnToolAttachedDamage(10))
        assertEquals(250, EffectEngine(catalog).damage(attacker, defender, effects, ignoreWeaknessResistance = true,
            ownEnergyCounts = mapOf(EnergyType.FIRE to 2, EnergyType.PSYCHIC to 1), ownDiscardEnergyCount = 2,
            ownSpecialEnergyCount = 1, opponentPrizeCardsRemaining = 2, opponentActiveRetreatCost = 0))
    }
    @Test fun `frontier defensive Abilities compile with their specific conditions`() {
        for (id in listOf("sv10.5b-023", "sv10.5b-107"))
            assertTrue(CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!.abilities.single().preventDamageFromSpecialEnergyAttacker)
        for (id in listOf("sv10-012", "sv10-186"))
            assertTrue(CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!.abilities.single().preventDamageFromOpponentEx)
        for (id in listOf("me02-022", "me02-097"))
            assertEquals(listOf(EnergyType.FIRE, EnergyType.WATER), CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!.abilities.single().damageReductionTypes)
        for (id in listOf("sv07-119", "svp-136"))
            assertEquals(60, CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!.abilities.single().protectBasicColorlessWithSecondBouffalant)
    }
    @Test fun `Cinderace named attack lock and targeted damage compile for all prints`() {
        for (id in listOf("me02.5-038", "sv07-028", "sv07-157", "svp-163")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(Effect.RestrictNamedAttackNextTurn("Flare Strike") in card.attacks.single { it.name == "Flare Strike" }.effects)
            assertTrue(Effect.TargetedDamage(180) in card.attacks.single { it.name == "Garnet Volley" }.effects)
        }
        for (id in listOf("me02.5-016", "me02.5-221", "sv08.5-004")) {
            val card = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(Effect.LockOpponentItemsNextTurn in card.attacks.single().effects)
        }
    }
    @Test fun `Blaziken ex Seething Spirit attaches a Basic Energy from discard and blocks its next attack`() {
        val blaziken = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-024"), emptyMap())!!
        assertTrue(blaziken.abilities.single().attachBasicEnergyFromDiscard)
        assertEquals("Seething Spirit", blaziken.abilities.single().name)
        assertEquals(listOf(Effect.Damage(200), Effect.RestrictNextTurn()), blaziken.attacks.single().effects)
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
    @Test fun `Articuno Frigid Fluttering compiles a search for up to two Basic Water Energy`() {
        val articuno = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-032"), emptyMap())!!
        assertEquals(listOf(Effect.EnergyFromDeck(2, EnergyType.WATER)), articuno.attacks.first().effects)
        assertEquals(listOf(Effect.Damage(110)), articuno.attacks.last().effects)
    }
    @Test fun `Arven Greedent Greedy Order compiles named Trainer recovery on evolution`() {
        val greedent = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-159"), emptyMap())!!
        val ability = greedent.abilities.single()
        assertEquals("Greedy Order", ability.name)
        assertEquals("Arven's Sandwich", ability.onEvolveRecoverNamedTrainerFromDiscard)
        assertEquals(2, ability.onEvolveRecoverNamedTrainerMax)
    }
    @Test fun `Arven Mabosstiff Vigorous Tackle adds damage only with zero damage counters`() {
        val mabosstiff = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-139"), emptyMap())!!
        assertEquals(listOf(Effect.Damage(30), Effect.OwnUndamagedDamageBonus(120)), mabosstiff.attacks.first().effects)
        assertEquals(listOf(Effect.Damage(210), Effect.RestrictNamedAttackNextTurn("Boss Headbutt")), mabosstiff.attacks.last().effects)
    }
    @Test fun `Arven Skwovet Gnaw Through compiles removal of opposing Active Pokémon Tools before damage`() {
        val skwovet = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-158"), emptyMap())!!
        assertEquals(listOf(Effect.Damage(10), Effect.DiscardDefendingTools), skwovet.attacks.single().effects)
    }
    @Test fun `Arven Toedscruel attacks compile gust and self damage effects`() {
        val toedscruel = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv10-110"), emptyMap())!!
        assertEquals(listOf(Effect.PullOpponentBench), toedscruel.attacks.first().effects)
        assertEquals(listOf(Effect.Damage(120), Effect.Recoil(30)), toedscruel.attacks.last().effects)
    }
    @Test fun `Audino Beckon compiles Supporter recovery from discard`() {
        val audino = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv09-124"), emptyMap())!!
        assertEquals(listOf(Effect.RecoverSupporterFromDiscard), audino.attacks.first().effects)
        assertEquals(listOf(Effect.Damage(70)), audino.attacks.last().effects)
    }
    @Test fun `Aurorus Tundra Wall reduces damage once for each own Water Energy Pokemon`() {
        val aurorus = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me03-024"), emptyMap())!!
        assertEquals(50, aurorus.abilities.single().tundraWallWaterDamageReduction)
        assertTrue(aurorus.attacks.single().effects.contains(Effect.PreventDefendingAttackNextTurn))
        val waterEnergy = CardInstance("water", "sv01-258")
        val catalog = object : CardCatalog { override fun get(id: String) = when (id) {
            aurorus.id -> aurorus
            "water-energy" -> CardDefinition("water-energy", "Water Energy", "Test", "W", category = Category.ENERGY, type = EnergyType.WATER, basicEnergy = true)
            else -> TrainerCatalog.get(id)
        } }
        val attackerDef = CardDefinition("wall-attacker", "Wall Attacker", "Test", "A", category = Category.POKEMON, hp = 200, type = EnergyType.FIRE)
        val targetDef = CardDefinition("wall-target", "Wall Target", "Test", "T", category = Category.POKEMON, hp = 200)
        val extended = object : CardCatalog { override fun get(id: String) = when (id) {
            aurorus.id -> aurorus
            attackerDef.id -> attackerDef
            targetDef.id -> targetDef
            "water-energy" -> catalog.get("water-energy")
            else -> TrainerCatalog.get(id)
        } }
        val attacker = PokemonInstance(listOf(CardInstance("attacker", attackerDef.id)), enteredPlayTurn = 0)
        val defender = PokemonInstance(listOf(CardInstance("target", targetDef.id)), energies = listOf(CardInstance(waterEnergy.id, "water-energy")), enteredPlayTurn = 0)
        val wall = PokemonInstance(listOf(CardInstance("aurorus", aurorus.id)), enteredPlayTurn = 0)
        val engine = EffectEngine(extended)
        assertEquals(50, engine.damage(attacker, defender, listOf(Effect.Damage(100)), friendlyPokemon = listOf(defender, wall)))
        assertEquals(100, engine.damage(attacker, defender, listOf(Effect.Damage(100)), friendlyPokemon = listOf(defender)))
    }
    @Test fun `Avalugg Iceberg Breaker compiles top deck Basic Water Energy damage`() {
        val avalugg = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me04-024"), emptyMap())
        assertNotNull(avalugg)
        val attack = avalugg.attacks.first()
        assertEquals(listOf(Effect.DiscardTopDeckEnergyDamage(6, EnergyType.WATER, 60)), attack.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
    }
    @Test fun `Axew Gather Strength compiles Basic Energy search for every print`() {
        for (id in listOf("sv10.5b-068", "sv10.5b-145")) {
            val axew = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertEquals(listOf(Effect.SearchBasicEnergyToHand(2)), axew.attacks.single().effects)
        }
    }
    @Test fun `Azelf Neurokinesis compiles bonus for damage counters across opponent field`() {
        val azelf = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-080"), emptyMap())!!
        assertEquals(listOf(Effect.Damage(10), Effect.OpponentFieldDamageCounterDamage(10)), azelf.attacks.single().effects)
    }
    @Test fun `Azumarill ex compiles unlimited energy transfer and Psychic Energy damage scaling`() {
        val azumarill = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-084"), emptyMap())!!
        assertTrue(azumarill.abilities.single().moveEnergyFromOtherPokemonToSelf)
        assertTrue(azumarill.abilities.single().unlimitedUse)
        assertEquals(listOf(Effect.Damage(60), Effect.OwnEnergyTypeCountDamage(EnergyType.PSYCHIC, 40)), azumarill.attacks.single().effects)
    }
    @Test fun `Azumarill Glistening Bubbles compiles a Tera conditioned Psychic attack cost`() {
        val azumarill = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv08-074"), emptyMap())!!
        val ability = azumarill.abilities.single()
        assertEquals("Double-Edge", ability.alternativeAttackName)
        assertEquals(listOf(EnergyType.PSYCHIC), ability.alternativeAttackCost)
        assertTrue(ability.requiresTeraInPlay)
        assertEquals(listOf(Effect.Damage(230), Effect.Recoil(50)), azumarill.attacks.single().effects)
    }
    @Test fun `Banette Cursed Words compiles opponent hand choice and shuffle`() {
        val banette = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-091"), emptyMap())!!
        assertEquals(listOf(Effect.OpponentChoosesHandShuffle(3)), banette.attacks.first().effects)
        assertEquals(listOf(Effect.Damage(70)), banette.attacks.last().effects)
    }
    @Test fun `Barbaracle Stone Arms compiles a once per turn Fighting Energy attachment`() {
        for (id in listOf("me03-043", "mep-065")) {
            val barbaracle = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val ability = barbaracle.abilities.single()
            assertEquals(EnergyType.FIGHTING, ability.attachHandEnergyType)
            assertEquals(EnergyType.FIGHTING, ability.attachHandEnergyToPokemonType)
            assertEquals("Stone Arms", ability.name)
            assertEquals(listOf(Effect.Damage(80)), barbaracle.attacks.single().effects)
        }
    }
    @Test fun `Basculin Bared Fangs requires an undamaged opposing Active before damage`() {
        for (id in listOf("sv10.5w-024", "sv10.5w-108")) {
            val basculin = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertEquals(listOf(Effect.Damage(50), Effect.RequiresUndamagedOpponentActive), basculin.attacks.last().effects)
        }
    }
    @Test fun `Bastiodon Ancient Bulwark compiles Bench damage immunity threshold`() {
        val bastiodon = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me05-062"), emptyMap())!!
        assertEquals(2, bastiodon.abilities.single().benchDamageImmunityForAttackerMaxEnergy)
        assertEquals(listOf(Effect.Damage(160)), bastiodon.attacks.single().effects)
    }
    @Test fun `Beautifly Energy Straw scales with revealed opposing hand Energy`() {
        for (id in listOf("me02.5-013", "me02.5-219")) {
            val beautifly = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val attack = beautifly.attacks.single { it.name == "Energy Straw" }
            assertTrue(Effect.OpponentHandEnergyCountDamage(80) in attack.effects)
            assertTrue(EffectEngine(TrainerCatalog).supported(attack))
            assertTrue(beautifly.attacks.single { it.name == "Stun Spore" }.effects.any { it is Effect.StatusCondition && it.onHeads })
        }
    }
    @Test fun `Banette Puppet Pull compiles an optional search for any card`() {
        val row = CatalogRuleCompiler.rows.getValue("me05-034")
        val attack = CatalogRuleCompiler.compileAttack("me05-034", 0, row["attacks"]!!.jsonArray.first().jsonObject)!!
        assertTrue(Effect.SearchAnyCardToHand in attack.effects)
        assertTrue(EffectEngine(TrainerCatalog).supported(attack))
        assertTrue(CatalogRuleCompiler.compile(row, emptyMap())!!.abilities.single().preventOpponentEffects)
    }
    @Test fun `Beedrill ex Rumbling Bees counts Beedrill species only`() {
        for (id in listOf("me04-003", "me04-098")) {
            val beedrill = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertEquals(listOf(Effect.OwnSpeciesCountDamage("Beedrill", 110)), beedrill.attacks.single().effects)
            assertTrue(EffectEngine(TrainerCatalog).supported(beedrill.attacks.single()))
        }
    }
    @Test fun `Bewear compiles all-heads threshold and any-type Energy search prints`() {
        val bewear = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me01-112"), emptyMap())!!
        val lariat = bewear.attacks.single { it.name == "Hyper Lariat" }
        assertTrue(Effect.CoinDamage(2, 100, bonusOnly = true, minimumHeads = 2) in lariat.effects)
        for (id in listOf("sv06.5-053", "sv06.5-079")) {
            val charger = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertTrue(Effect.EnergyFromDeck(1, null) in charger.attacks.first().effects)
            assertTrue(EffectEngine(TrainerCatalog).supported(charger.attacks.first()))
        }
    }
    @Test fun `Bisharp Finishing Blow compiles an already-damaged Active bonus`() {
        for (id in listOf("sv10.5b-065", "sv10.5b-143")) {
            val bisharp = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            assertEquals(listOf(Effect.Damage(60), Effect.OpponentActiveDamagedDamage(60)), bisharp.attacks.last().effects)
        }
    }
    @Test fun `Black Kyurem ex Ice Age compiles conditional Paralysis against Dragon Pokemon`() {
        for (id in listOf("sv08-048", "sv08-218")) {
            val blackKyurem = CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue(id), emptyMap())!!
            val iceAge = blackKyurem.attacks.first()
            assertTrue(Effect.OpponentActiveTypeCondition(EnergyType.DRAGON) in iceAge.effects)
            assertTrue(Effect.StatusCondition(SpecialCondition.PARALYZED) in iceAge.effects)
        }
    }
    @Test fun `Tera card trait identifiers match current rotation catalog prints`() {
        assertEquals(90, CardTraits.teraPokemonIds.size)
        CardTraits.teraPokemonIds.forEach { id ->
            val row = CatalogRuleCompiler.rows.getValue(id)
            assertTrue(row["regulationMark"]!!.jsonPrimitive.content in setOf("H", "I", "J"), id)
        }
        assertTrue(CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("sv07-128"), emptyMap())!!.isTera)
        assertFalse(CatalogRuleCompiler.compile(CatalogRuleCompiler.rows.getValue("me02.5-084"), emptyMap())!!.isTera)
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
        val inPlay = CatalogRuleCompiler.compileAttack("attacker", 1,
            attack("If you have at least 2 {G} Energy in play, this attack does 120 more damage.", "20+"))!!
        val psychic = CatalogRuleCompiler.compileAttack("attacker", 2,
            attack("This attack does 20 more damage for each {P} Energy attached to this Pokémon.", "20+"))!!
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
        assertTrue(inPlay.effects.contains(Effect.ConditionalInPlayEnergyDamage(EnergyType.GRASS, 2, 120)))
        assertTrue(psychic.effects.contains(Effect.OwnEnergyTypeCountDamage(EnergyType.PSYCHIC, 20)))
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
        java.io.File("build/rules-uncovered-causes.txt").writeText("First 200 uncovered cards, with independently compiled components:\n" + unsupported.take(200).joinToString("\n\n") { card ->
            val raw = CatalogRuleCompiler.rows[card.id] ?: TrainerCatalog.sourceData(card.id)
            val attacks = raw["attacks"]?.jsonArray.orEmpty().mapIndexed { index, item ->
                val attack = item.jsonObject
                val name = attack["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = CatalogRuleCompiler.compileAttack(card.id, index, attack)
                "Attack $name: ${if (result == null) "UNSUPPORTED" else "supported"} — ${attack["effect"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
            }
            val abilities = raw["abilities"]?.jsonArray.orEmpty().mapIndexed { index, item ->
                val ability = item.jsonObject
                val name = ability["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = CatalogRuleCompiler.compileAbility(card.id, index, ability)
                "Ability $name: ${if (result == null) "UNSUPPORTED" else "supported"} — ${ability["effect"]?.jsonPrimitive?.contentOrNull.orEmpty()}"
            }
            val cardResult = CatalogRuleCompiler.compile(raw, emptyMap())
            "${card.id} | ${card.name} | ${card.category} | full card: ${if (cardResult == null) "UNSUPPORTED" else "supported"}\n${(attacks + abilities).joinToString("\n")}"
        })
        val patternCards = linkedMapOf<String, MutableSet<String>>()
        unsupported.forEach { card ->
            val raw = CatalogRuleCompiler.rows[card.id] ?: TrainerCatalog.sourceData(card.id)
            raw["attacks"]?.jsonArray.orEmpty().forEachIndexed { index, item ->
                val attack = item.jsonObject
                if (CatalogRuleCompiler.compileAttack(card.id, index, attack) == null) {
                    val text = attack["effect"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "(sem efeito impresso)" }
                    patternCards.getOrPut("Ataque — $text") { linkedSetOf() } += "${card.id} ${card.name}"
                }
            }
            raw["abilities"]?.jsonArray.orEmpty().forEachIndexed { index, item ->
                val ability = item.jsonObject
                if (CatalogRuleCompiler.compileAbility(card.id, index, ability) == null) {
                    val text = ability["effect"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "(sem efeito impresso)" }
                    patternCards.getOrPut("Habilidade — $text") { linkedSetOf() } += "${card.id} ${card.name}"
                }
            }
        }
        java.io.File("build/rules-uncovered-patterns.txt").writeText(
            "Unsupported effect text grouped by distinct Standard prints, highest coverage first:\n" +
                patternCards.entries.sortedByDescending { it.value.size }.take(200).joinToString("\n\n") { (pattern, prints) ->
                    "${prints.size} prints\n$pattern\n${prints.joinToString() }"
                })
    }
}
