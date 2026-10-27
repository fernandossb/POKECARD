package com.tcgtrainer.engine

import kotlinx.serialization.json.*

/** Closed grammar: an unrecognized clause rejects the entire card, never just that clause. */
internal object CatalogRuleCompiler {
    private fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.a(key: String) = (get(key) as? JsonArray).orEmpty()
    val rows: Map<String, JsonObject> by lazy {
        javaClass.getResourceAsStream("/library/catalog.json")!!.bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject["cards"]!!.jsonArray
                .associate { row -> row.jsonObject.s("id")!! to row.jsonObject }
        }
    }
    private fun type(value: String?) = EnergyType.entries.firstOrNull { it.name == value?.uppercase() }
    private fun symbolType(symbol: String): EnergyType? {
        val value = when (symbol) {
        "G" -> "Grass"; "R" -> "Fire"; "F" -> "Fighting"; "L" -> "Lightning"; "W" -> "Water"
        "P" -> "Psychic"; "M" -> "Metal"; "D" -> "Darkness"; "N" -> "Dragon"; "C" -> "Colorless"; else -> return null
        }
        return type(value)
    }
    private fun signature(raw: JsonObject) = listOf("category", "name", "trainerType", "energyType", "effect").map { raw[it] } +
        JsonPrimitive(raw.s("rarity").orEmpty().contains("ACE SPEC", ignoreCase = true))

    fun basicEnergy(raw: JsonObject): Boolean = raw.s("category") == "Energy" &&
        Regex("(?:Basic )?(?:Grass|Fire|Water|Lightning|Psychic|Fighting|Darkness|Metal) Energy").matches(raw.s("name").orEmpty())

    internal fun compileAbility(id: String, index: Int, raw: JsonObject): AbilityDefinition? {
        val name = raw.s("name") ?: return null
        val text = raw.s("effect")?.trim().orEmpty()
        if (text == "Once during your turn, you may put 5 damage counters on this Pokémon. If you do, during this turn, attacks used by this Pokémon do 120 more damage to your opponent's Active Pokémon (before applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
                selfDamageCountersForAttackBoost = 5, attackDamageBoostAfterSelfDamage = 120)
        if (text == "Once during your turn, you may use this Ability. Switch a card from your hand with the top card of your deck.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, swapHandCardWithTopDeck = true)
        val discardBasicFireDrawToSeven = text == "Once during your turn, you may discard a Basic {R} Energy card from your hand in order to use this Ability. Draw cards until you have 7 cards in your hand." ||
            text == "Once during your turn, you may discard a Basic Fire Energy card from your hand in order to use this Ability. Draw cards until you have 7 cards in your hand."
        if (discardBasicFireDrawToSeven) return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
            discardEnergyType = EnergyType.FIRE, drawTo = 7)
        if (text == "If this Pokémon has any Special Energy attached, it gets +150 HP.")
            return AbilityDefinition("$id-ability$index", name, text, hpBonusWithSpecialEnergy = 150)
        if (text == "Once during your turn, you may use this Ability. Draw a card.")
            return AbilityDefinition("$id-ability$index", name, text, draw = 1, usageKey = name)
        val healAllOwnPokemon = Regex("Once during your turn, you may use this Ability\\. Heal (\\d+) damage from each of your Pokémon\\.").matchEntire(text)
        if (healAllOwnPokemon != null) return AbilityDefinition("$id-ability$index", name, text,
            usageKey = name, healEachOwnPokemon = healAllOwnPokemon.groupValues[1].toInt())
        val simpleAbilityDraw = Regex("Once during your turn, you may use this Ability\\. Draw (\\d+) cards?\\.").matchEntire(text)
        if (simpleAbilityDraw != null) return AbilityDefinition("$id-ability$index", name, text,
            draw = simpleAbilityDraw.groupValues[1].toInt(), usageKey = name)
        if (text == "Once during your turn, you may draw 3 cards. If you drew any cards in this way, shuffle this Pokémon and all attached cards into your deck.")
            return AbilityDefinition("$id-ability$index", name, text, draw = 3, shuffleSelfIntoDeck = true, usageKey = name)
        if (text == "If your opponent has exactly 4 cards in their hand, ignore all {C} Energy in the costs of attacks used by this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, ignoreColorlessAttackCostWhenOpponentHandCountIs = 4)
        val benchToActiveEnergySearch = Regex("Once during your turn, when this Pokémon moves from (?:your )?Bench to the Active Spot, you may (?:use this Ability\\. )?[Ss]earch your deck for up to (\\d+) Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy cards and attach them to this Pokémon\\. Then, shuffle your deck\\.").matchEntire(text)
        if (benchToActiveEnergySearch != null) return AbilityDefinition("$id-ability$index", name, text,
            usageKey = name, usagePerPokemon = true, triggersWhenMovedFromBenchToActive = true,
            attachBasicEnergyFromDeckToSelfType = symbolType(benchToActiveEnergySearch.groupValues[2]) ?: return null,
            attachBasicEnergyFromDeckToSelfMax = benchToActiveEnergySearch.groupValues[1].toInt())
        if (text == "Once during your turn, when this Pokémon moves from your Bench to the Active Spot, you may use this Ability. Move any amount of {M} Energy from your other Pokémon to this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, usagePerPokemon = true,
                triggersWhenMovedFromBenchToActive = true, moveEnergyFromOtherPokemonToSelf = true,
                moveEnergyFromOtherPokemonToSelfType = EnergyType.METAL)
        val activeToBenchAttach = Regex("Once during your turn, when this Pokémon moves from the Active Spot to your Bench, you may use this Ability\\. Attach up to (\\d+) Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy cards from your hand to this Pokémon\\.").matchEntire(text)
        if (activeToBenchAttach != null) return AbilityDefinition("$id-ability$index", name, text,
            usageKey = name, usagePerPokemon = true, triggersWhenMovedFromActiveToBench = true,
            onActiveToBenchAttachBasicEnergyMax = activeToBenchAttach.groupValues[1].toInt(),
            onActiveToBenchAttachBasicEnergyType = symbolType(activeToBenchAttach.groupValues[2]) ?: return null)
        if (text == "Attacks used by your Cynthia's Pokémon do 30 more damage to your opponent's Active Pokémon (before applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text, teamAttackDamageBonusAnyType = 30,
                teamAttackDamageBonusPokemonNamePrefix = "Cynthia's ")
        if (text == "Once during your turn, you may use this Ability. Flip a coin. If heads, discard a random card from your opponent's hand.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, coinDiscardRandomOpponentHand = true)
        if (text == "Once during your turn, you may use this Ability. Flip a coin. If heads, put an Energy attached to your opponent's Active Pokémon into their hand.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, coinReturnOpponentActiveEnergyToHand = true)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may use this Ability. Flip a coin. If heads, discard an Energy from your opponent's Active Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, requiresActive = true, coinDiscardOpponentActiveEnergy = true)
        if (text == "Once during your turn, if you have any {G} Mega Evolution Pokémon ex in play, you may use this Ability. Heal 60 damage from 1 of your Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, healDamage = 60,
                requiresMegaEvolutionGrassExInPlay = true)
        if (text == "Once during your turn, you may search your deck for a Stadium card, reveal it, and put it into your hand. Then, shuffle your deck.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, searchStadiumToHand = true)
        if (text == "Once during your turn, if you have no cards in your hand, you may use this Ability. Search your deck for a Supporter card, reveal it, and put it into your hand. Then, shuffle your deck.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, searchSupporterIfHandEmpty = true)
        if (text == "Attacks used by your Hop's Pokémon do 30 more damage to your opponent's Active Pokémon (before applying Weakness and Resistance). The effect of Extra Helpings doesn't stack.")
            return AbilityDefinition("$id-ability$index", name, text, teamAttackDamageBonusAnyType = 30,
                teamAttackDamageBonusPokemonNamePrefix = "Hop's ")
        if (text == "All of your Pokémon that have any {M} Energy attached take 20 less damage from attacks from your opponent's Pokémon (after applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text, teamDamageReductionWithEnergyType = EnergyType.METAL,
                teamDamageReductionWithEnergyAmount = 20)
        if (text == "As long as this Pokémon is in the Active Spot, attacks used by your opponent's Active Pokémon do 30 less damage (before applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text, requiresActive = true, teamDamageReduction = 30)
        if (text == "Each of your Pokémon that has \"Rotom\" in its name may have up to 2 Pokémon Tool cards attached. If this Ability goes away, discard Pokémon Tools from those Pokémon until only 1 remains on each.")
            return AbilityDefinition("$id-ability$index", name, text, rotomToolCapacityBonus = true)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may use this Ability. Draw 2 cards. You can't use more than 1 Run Errand Ability each turn.")
            return AbilityDefinition("$id-ability$index", name, text, draw = 2, usageKey = "Run Errand", requiresActive = true)
        if (text == "When you play this Pokémon from your hand onto your Bench during your turn, you may heal 30 damage from your Active Pokémon and have it recover from a Special Condition.")
            return AbilityDefinition("$id-ability$index", name, text, onBenchHealActiveAndRecover = 30)
        if (text.contains("you play this Pokémon from your hand onto your Bench") &&
            text.contains("Search your deck for a Supporter card, reveal it, and put it into your hand") &&
            text.contains("more than 1 Ability that has \"Last-Ditch\" in its name each turn"))
            return AbilityDefinition("$id-ability$index", name, text, usageKey = "Last-Ditch",
                onBenchSearchSupporterFromDeck = true)
        if (text == "Once during your turn, you may attach a Basic {R} Energy card, a Basic {F} Energy card, or 1 of each from your hand to your Pokémon in any way you like.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, usagePerPokemon = true,
                attachHandEnergyTypes = listOf(EnergyType.FIRE, EnergyType.FIGHTING), attachHandEnergyTypesMax = 2)
        if (text == "Once during your turn, you may use this Ability. Search your deck for a Basic {D} Energy card and attach it to 1 of your Benched {D} Pokémon. Then, shuffle your deck. If you attached Energy to a Pokémon in this way, place 2 damage counters on that Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
                attachBasicEnergyFromDeckToBenchedType = EnergyType.DARKNESS, damageCountersToEnergyTarget = 2)
        if (text == "As often as you like during your turn, if you have any {R} Mega Evolution Pokémon ex in play, you may use this Ability. Attach a Basic {R} Energy card from your hand to 1 of your Benched {R} Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, attachHandEnergyType = EnergyType.FIRE,
                unlimitedUse = true, attachHandEnergyToPokemonType = EnergyType.FIRE,
                attachHandEnergyBenchOnly = true, requiresMegaEvolutionFireExInPlay = true)
        if (text == "When you play this Pokémon from your hand to evolve 1 of your Pokémon during your turn, you may switch in 1 of your opponent's Benched Pokémon that has 90 HP or less remaining to the Active Spot.")
            return AbilityDefinition("$id-ability$index", name, text, onEvolveSwitch = true,
                onEvolveSwitchOpponentBenchMaxRemainingHp = 90)
        if (text == "When you play this Pokémon from your hand to evolve 1 of your Pokémon during your turn, you may have your opponent reveal their hand and you put any number of Basic Pokémon you find there onto their Bench.")
            return AbilityDefinition("$id-ability$index", name, text, onEvolveOpponentHandBasicToBench = true)
        if (text == "If your opponent has no Pokémon ex or Pokémon V in play, this Pokémon can't attack.")
            return AbilityDefinition("$id-ability$index", name, text, requiresOpponentRuleBoxPokemonToAttack = true)
        if (text == "Pokémon in play (both yours and your opponent's) lose any Ability that requires the Pokémon using it to Knock Out itself.")
            return AbilityDefinition("$id-ability$index", name, text, suppressSelfKnockoutAbilities = true)
        if (text == "Pokémon in play (both yours and your opponent's) lose all Abilities that require those Pokémon to be Knocked Out.")
            return AbilityDefinition("$id-ability$index", name, text, suppressSelfKnockoutAbilities = true)
        if (text == "As long as this Pokémon is in play, it is {G} and {R} type.")
            return AbilityDefinition("$id-ability$index", name, text,
                additionalPokemonTypes = listOf(EnergyType.GRASS, EnergyType.FIRE))
        if (text == "If this Pokémon is Knocked Out by damage from an attack from your opponent's Pokémon ex, your opponent can't take any Prize cards for it.")
            return AbilityDefinition("$id-ability$index", name, text, preventsPrizesWhenKnockedOutByEx = true)
        if (text == "As long as this Pokémon is in the Active Spot, put 5 more damage counters on your opponent's Poisoned Pokémon during Pokémon Checkup.")
            return AbilityDefinition("$id-ability$index", name, text, requiresActive = true, opponentPoisonCheckupBonusCounters = 5)
        if (text == "You must discard a Basic {R} Energy card from your hand in order to use this Ability. Once during your turn, you may make your opponent's Active Pokémon Burned.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
                discardEnergyType = EnergyType.FIRE, burnOpponentActive = true, requiresActive = true)
        if (text == "Once during your turn, when you play this Pokémon from your hand to evolve 1 of your Pokémon, you may use this Ability. You may also use this Ability if this Pokémon is in the Active Spot and is Knocked Out by damage from an attack from your opponent's Pokémon. Discard the top 2 cards of your opponent's deck.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, onEvolveDiscardOpponentDeckCount = 2)
        if (text == "If you go first, this Pokémon can use attacks during your first turn.")
            return AbilityDefinition("$id-ability$index", name, text, mayAttackFirstTurnIfStarting = true)
        if (text == "Attacks used by this Pokémon cost {C} less for each of your opponent's Benched Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, attackDiscountPerOpponentBenchedPokemon = true)
        if (text == "If your opponent has any cards in their discard pile that have \"Colress\" in the name, this Pokémon can use the Trifrost attack for {C}.")
            return AbilityDefinition("$id-ability$index", name, text, alternativeAttackName = "Trifrost",
                alternativeAttackCost = listOf(EnergyType.COLORLESS), alternativeAttackRequiresOpponentDiscardName = "Colress")
        if (text == "As long as this Pokémon is in the Active Spot, your opponent's Active Pokémon has no Abilities, except for Midnight Fluttering.")
            return AbilityDefinition("$id-ability$index", name, text, opponentActiveAbilityException = "Midnight Fluttering")
        if (text == "The Weakness of each of your opponent's {N} Pokémon in play is now {P}. (Apply Weakness as ×2.)")
            return AbilityDefinition("$id-ability$index", name, text, changeOpponentNsPokemonWeaknessToPsychic = true)
        if (text == "If 1 of your {D} Pokémon is Knocked Out by damage from an attack from your opponent's Pokémon ex, that player takes 1 fewer Prize card. The effect of Shadowy Concealment doesn't stack.")
            return AbilityDefinition("$id-ability$index", name, text, reducePrizeForDarknessKnockoutByEx = true)
        if (text == "If this Pokémon is Knocked Out by damage from an attack from your opponent's Pokémon, and if you have any Pecharunt ex in play, your opponent takes 1 fewer Prize card.")
            return AbilityDefinition("$id-ability$index", name, text, reducePrizeForDarknessKnockoutByEx = true,
                requirePecharuntExForPrizeReduction = true)
        if (text == "If your Active {D} Pokémon is damaged by an attack from your opponent's Pokémon (even if your Active {D} Pokémon is Knocked Out), place 1 damage counter on the Attacking Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, retaliateDamageCounterWhenActiveDarknessDamaged = true)
        if (text == "If your opponent's Basic Pokémon is Knocked Out by damage from an attack used by this Pokémon, take 1 more Prize card.")
            return AbilityDefinition("$id-ability$index", name, text, extraPrizeForOpponentBasicKnockoutByAttack = 1)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may make your opponent's Active Pokémon Burned.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, requiresActive = true, burnOpponentActive = true)
        if (text == "When your opponent's Active Pokémon is Knocked Out, flip a coin. If heads, take 1 more Prize card. The effect of Wonder Kiss doesn't stack.")
            return AbilityDefinition("$id-ability$index", name, text, wonderKissPrizeOnActiveKnockout = true)
        if (text == "Once during your turn, you may switch 1 of your Benched {D} Pokémon, except any Pecharunt ex, with your Active Pokémon. If you do, the new Active Pokémon is now Poisoned. You can't use more than 1 Subjugating Chains Ability each turn.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = "Subjugating Chains", switchOwnActive = true,
                switchOwnDarknessExceptPecharunt = true, poisonNewActiveOnSwitch = true)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may discard a Basic {W} Energy card from your hand in order to use this Ability. Place 6 damage counters on 1 of your opponent's Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, requiresActive = true, usageKey = name,
                discardEnergyType = EnergyType.WATER, damageCounters = 6, damageTargets = 1)
        if (text == "Once during your turn, if you have Solrock in play, you may discard a Basic {F} Energy card from your hand in order to use this Ability. Draw 3 cards. You can't use more than 1 Lunar Cycle Ability each turn.")
            return AbilityDefinition("$id-ability$index", name, text, requiresInPlayName = "Solrock", usageKey = "Lunar Cycle",
                discardEnergyType = EnergyType.FIGHTING, draw = 3)
        if (text == "During Pokémon Checkup, put 1 damage counter on each Pokémon that has an Ability (both yours and your opponent's), except any Froslass.")
            return AbilityDefinition("$id-ability$index", name, text, checkupDamageCountersPerPokemonWithAbilityExceptFroslass = 1)
        if (text == "If this Pokémon has no Energy attached, it has no Retreat Cost.")
            return AbilityDefinition("$id-ability$index", name, text, freeRetreatWithNoEnergy = true)
        if (text == "Your Basic Pokémon in play have no Retreat Cost.")
            return AbilityDefinition("$id-ability$index", name, text, basicPokemonHaveFreeRetreat = true)
        if (text == "Once during your turn, you may use this Ability. Switch your Active Pokémon with 1 of your Benched Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, switchOwnActive = true)
        if (text == "Attacks used by this Pokémon cost {C} less for each Kofu card in your discard pile.")
            return AbilityDefinition("$id-ability$index", name, text, attackDiscountPerKofuDiscard = true)
        if (text == "If Festival Grounds is in play, this Pokémon may use an attack it has twice. If the first attack Knocks Out your opponent's Active Pokémon, you may attack again after your opponent chooses a new Active Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, festivalGroundsMayAttackAgain = true)
        if (text == "Whenever your opponent plays an Item or Supporter card from their hand, prevent all effects of that card done to this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, preventOpponentItemSupporterEffects = true)
        if (text == "As long as this Pokémon is in the Active Spot, your opponent can't play any Item cards or Pokémon Tool cards from their hand.")
            return AbilityDefinition("$id-ability$index", name, text, requiresActive = true, preventOpponentItemAndToolPlayFromHand = true)
        if (text == "If this Pokémon has a Pokémon Tool attached, your opponent can't play any ACE SPEC cards from their hand.")
            return AbilityDefinition("$id-ability$index", name, text, preventOpponentAceSpecFromHandWithTool = true)
        if (text == "Once during your turn, when this Pokémon moves from the Active Spot to the Bench, you may search your deck for a Palafin ex and switch it with this Pokémon. Any attached cards, damage counters, Special Conditions, turns in play, and any other effects remain on the new Pokémon. If you switched a Pokémon in this way, put this card into your deck. Then, shuffle your deck.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = "Zero to Hero", usagePerPokemon = true,
                triggersWhenMovedFromActiveToBench = true)
        if (text == "Put this Pokémon into play only with the effect of Palafin's Zero to Hero Ability.")
            return AbilityDefinition("$id-ability$index", name, text, onlyPlayableWithPalafinZeroToHero = true)
        if (text == "When you play this Pokémon from your hand to evolve 1 of your Pokémon during your turn, you may heal all damage from each of your Evolution Pokémon. If you healed any damage in this way, discard all Energy from those Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, onEvolveHealAllEvolutionAndDiscardEnergy = true)
        if (text == "When you play this Pokémon from your hand to evolve 1 of your Pokémon during your turn, you may heal all damage from your Active {G} Pokémon. If you healed any damage in this way, discard all Energy from that Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, onEvolveHealActiveGrassDiscardEnergy = true)
        if (text == "Once during your turn, when you play this Pokémon from your hand to evolve 1 of your Pokémon, you may use this Ability. Heal all damage from 1 of your Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, onEvolveHealAllDamage = true)
        if (text == "Once during your turn, when you play this Pokémon from your hand to evolve 1 of your Pokémon, if you have any Tera Pokémon in play, you may search your deck for up to 2 Trainer cards, reveal them, and put them into your hand. Then, shuffle your deck.")
            return AbilityDefinition("$id-ability$index", name, text, onEvolveSearchTrainerIfTeraMax = 2)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may have your opponent shuffle their hand into their deck and draw 3 cards.")
            return AbilityDefinition("$id-ability$index", name, text, requiresActive = true, usageKey = name, opponentShuffleHandAndDraw = 3)
        if (text == "You must discard a Basic {L} Energy from this Pokémon in order to use this Ability. Once during your turn, you may draw cards until you have 6 cards in your hand.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
                discardAttachedBasicEnergyType = EnergyType.LIGHTNING, drawTo = 6)
        val flipTheScript = Regex("Once during your turn, if any of your Pokémon were Knocked Out during your opponent's last turn, you may draw (\\d+) cards?\\. You can't use more than 1 Flip the Script Ability each turn\\.").matchEntire(text)
        if (flipTheScript != null) return AbilityDefinition("$id-ability$index", name, text, draw = flipTheScript.groupValues[1].toInt(),
            requiresPreviousKnockout = true, usageKey = "Flip the Script")
        if (text == "Once during your first turn, you may search your deck for up to 3 {C} Pokémon with 100 HP or less, reveal them, and put them into your hand. Then, shuffle your deck. You can't use more than 1 Fan Call Ability during your turn.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = "Fan Call", requiresFirstTurn = true,
                searchColorlessPokemonMaxHpToHand = 3)
        if (text == "Prevent all effects of your opponent's Pokémon's attacks and Abilities done to this Pokémon. (Damage is not an effect.)")
            return AbilityDefinition("$id-ability$index", name, text, preventOpponentEffects = true)
        if (text == "Prevent all damage from and effects of attacks from your opponent's Tera Pokémon done to this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, preventDamageAndEffectsFromTeraAttacks = true)
        if (text == "Prevent all damage done to your Benched Pokémon that don't have a Rule Box by attacks from your opponent's Pokémon. (Pokémon ex, Pokémon V, etc. have Rule Boxes.)")
            return AbilityDefinition("$id-ability$index", name, text, preventDamageToOwnBenchedNonRuleBoxPokemon = true)
        if (text == "Prevent all effects of attacks used by your opponent's Pokémon done to this Pokémon. (Damage is not an effect.)" ||
            text == "Prevent all effects of your opponent's Pokémon's Abilities done to this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, preventOpponentEffects = true)
        if (text == "As long as this Pokémon is on your Bench, prevent all damage from and effects of attacks from your opponent's Pokémon done to this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, preventOpponentAttackDamageEffectsWhileOnBench = true)
        if (text == "If any damage is done to this Pokémon by attacks, flip a coin. If heads, prevent that damage.")
            return AbilityDefinition("$id-ability$index", name, text, coinFlipPreventAttackDamage = true)
        val energyShield = Regex("If this Pokémon has any (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy attached and is damaged by an attack, flip a coin\\. If heads, prevent that damage\\.").matchEntire(text)
        if (energyShield != null) return AbilityDefinition("$id-ability$index", name, text, coinFlipPreventAttackDamage = true,
            coinFlipPreventAttackDamageEnergyType = symbolType(energyShield.groupValues[1]) ?: return null)
        val retaliationCondition = Regex("If this Pokémon is in the Active Spot and is damaged by an attack from your opponent's Pokémon \\(even if this Pokémon is Knocked Out\\), the Attacking Pokémon is now (Poisoned|Burned)\\.").matchEntire(text)
        if (retaliationCondition != null) return AbilityDefinition("$id-ability$index", name, text,
            retaliateCondition = SpecialCondition.valueOf(retaliationCondition.groupValues[1].uppercase()))
        if (text == "All of your Pokémon that have any {W} Energy attached take 50 less damage from attacks from your opponent's Pokémon (after applying Weakness and Resistance). The effect of Tundra Wall doesn't stack.")
            return AbilityDefinition("$id-ability$index", name, text, tundraWallWaterDamageReduction = 50)
        if (text == "As long as this Pokémon is on your Bench, prevent all damage done to each of your Pokémon by attacks from your opponent's Pokémon that have 2 or less Energy attached.")
            return AbilityDefinition("$id-ability$index", name, text, benchDamageImmunityForAttackerMaxEnergy = 2)
        if (text == "As long as this Pokémon is in the Active Spot, Pokémon with a Rule Box in play (both yours and your opponent's) have no Abilities, except for Future Pokémon. (Pokémon ex, Pokémon V, etc. have Rule Boxes.)")
            return AbilityDefinition("$id-ability$index", name, text, suppressRuleBoxAbilitiesExceptFuture = true)
        if (text == "As often as you like during your turn, you may use this Ability. Move an Energy from 1 of your other Pokémon to this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, moveEnergyFromOtherPokemonToSelf = true, unlimitedUse = true)
        val teraAttack = Regex("If you have any Tera Pokémon in play, this Pokémon can use the (.+) attack for (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\])\\.").matchEntire(text)
        if (teraAttack != null) return AbilityDefinition("$id-ability$index", name, text,
            alternativeAttackName = teraAttack.groupValues[1],
            alternativeAttackCost = listOf(symbolType(teraAttack.groupValues[2]) ?: return null), requiresTeraInPlay = true)
        if (text == "All of your Pokémon that have {M} Energy attached have no Retreat Cost.")
            return AbilityDefinition("$id-ability$index", name, text, freeRetreatWithAttachedEnergyType = EnergyType.METAL)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may devolve 1 of your opponent's evolved Pokémon by putting the highest Stage Evolution card on it into your opponent's hand.")
            return AbilityDefinition("$id-ability$index", name, text, requiresActive = true, usageKey = name, devolveOpponentPokemonMax = 1)
        if (text == "Your opponent's Active Evolution Pokémon's Retreat Cost is {C} more.")
            return AbilityDefinition("$id-ability$index", name, text, opponentActiveEvolutionRetreatCostBonus = 1)
        if (text == "Your opponent's Active Pokémon's Retreat Cost is {C} more.")
            return AbilityDefinition("$id-ability$index", name, text, opponentActiveRetreatCostBonus = 1)
        if (text == "Once during your turn, you may move a Basic Energy from 1 of your Pokémon to another of your Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, moveBasicEnergyBetweenOwnPokemon = true)
        if (text == "As often as you like during your turn, you may use this Ability. Move a Basic {G} Energy from 1 of your Pokémon to another of your Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, unlimitedUse = true,
                moveBasicEnergyBetweenOwnPokemon = true, moveBasicEnergyType = EnergyType.GRASS)
        if (text == "Once during your turn, you may attach a Basic Energy card from your discard pile to 1 of your Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, attachBasicEnergyFromDiscard = true)
        if (text == "Once during your turn, you may attach a Basic Energy card from your discard pile to this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
                attachBasicEnergyFromDiscard = true, attachBasicEnergyFromDiscardToSelf = true)
        val ethanEnergy = Regex("Once during your turn, you may attach up to (\\d+) Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy cards from your hand to 1 of your Benched (.+) Pokémon\\.").matchEntire(text)
        if (ethanEnergy != null) return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
            attachHandEnergyType = symbolType(ethanEnergy.groupValues[2]) ?: return null,
            attachHandEnergyMax = ethanEnergy.groupValues[1].toInt(), attachHandEnergyBenchOnly = true,
            attachHandEnergyToPokemonNamePrefix = ethanEnergy.groupValues[3])
        if (text == "As often as you like during your turn, you may attach a Basic {L} Energy card from your hand to 1 of your Iono's Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, unlimitedUse = true,
                attachHandEnergyType = EnergyType.LIGHTNING, attachHandEnergyToPokemonNamePrefix = "Iono's")
        val unlimitedEnergy = Regex("As often as you like during your turn, you may attach a Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy card from your hand to 1 of your Pokémon\\.").matchEntire(text)
        if (unlimitedEnergy != null) return AbilityDefinition("$id-ability$index", name, text, usageKey = name, unlimitedUse = true,
            attachHandEnergyType = symbolType(unlimitedEnergy.groupValues[1]) ?: return null,
            attachHandEnergyToPokemonNamePrefix = "")
        if (text == "Once during your turn, you may attach a Basic {G} Energy card from your hand to 1 of your Pokémon. If you attached Energy to a Pokémon in this way, heal 30 damage from that Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
                attachHandEnergyType = EnergyType.GRASS, healIfAttached = 30)
        if (text == "Once during your turn, you may attach a Basic {L} Energy card from your discard pile to 1 of your Benched Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, attachBasicEnergyFromDiscard = true,
                attachBasicEnergyFromDiscardType = EnergyType.LIGHTNING, attachBasicEnergyFromDiscardBenchOnly = true)
        if (text == "Once during your turn, you may attach up to 3 Basic Energy cards from your discard pile to your {L} Pokémon in any way you like. If you use this Ability, this Pokémon is Knocked Out.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
                attachBasicEnergyFromDiscard = true, attachBasicEnergyFromDiscardMax = 3,
                attachBasicEnergyFromDiscardToPokemonType = EnergyType.LIGHTNING, selfKnockout = true,
                selfKnockoutAfterUse = true)
        if (text == "When you play this Pokémon from your hand onto your Bench during your turn, you may discard the top card of your opponent's deck.")
            return AbilityDefinition("$id-ability$index", name, text, onBenchDiscardOpponentDeckTop = true)
        if (text == "When you play this Pokémon from your hand onto your Bench during your turn, you may discard a Stadium in play.")
            return AbilityDefinition("$id-ability$index", name, text, onBenchDiscardStadium = true)
        if (text == "When you play this Pokémon from your hand onto your Bench during your turn, you may switch it with your Active Pokémon. If you do, you may move any amount of Energy from your other Pokémon to this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, onBenchSwitchAndMoveEnergy = true)
        if (text == "Blood Moon used by this Pokémon costs {C} less for each Prize card your opponent has taken.")
            return AbilityDefinition("$id-ability$index", name, text, attackDiscountPerOpponentPrize = true)
        if (text == "Prevent all damage from attacks done to this Pokémon by your opponent's Pokémon that have an Ability.")
            return AbilityDefinition("$id-ability$index", name, text, preventDamageFromPokemonWithAbilities = true)
        if (text == "All of your Pokémon take 10 less damage from attacks from your opponent's Pokémon (after applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text, teamDamageReduction = 10)
        if (text == "As long as this Pokémon is in play, it is {F} and {P} type.")
            return AbilityDefinition("$id-ability$index", name, text, additionalPokemonTypes = listOf(EnergyType.PSYCHIC))
        if (text == "This Pokémon gets +50 HP for each Prize card your opponent has taken.")
            return AbilityDefinition("$id-ability$index", name, text, hpBonusPerOpponentPrizeTaken = 50)
        if (text == "If this Pokémon has any {D} Energy attached, it gets +100 HP, and the attacks it uses do 100 more damage to your opponent's Active Pokémon (before applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text,
                hpAndAttackBonusWithAttachedEnergyType = EnergyType.DARKNESS, hpAndAttackBonusWithAttachedEnergy = 100)
        if (text == "Attacks used by your Pokémon do 30 more damage to your opponent's Active Evolution Pokémon (before applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text, attackDamageBonusAgainstEvolutionActive = 30)
        if (text == "Attacks used by your Future Pokémon, except any Iron Crown ex, do 20 more damage to your opponent's Active Pokémon (before applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text, futurePokemonDamageBonus = 20)
        if (text == "Once during your turn, when you play this Pokémon from your hand to evolve 1 of your Pokémon, you may use this Ability. Make your opponent's Active Pokémon Confused.")
            return AbilityDefinition("$id-ability$index", name, text, onEvolveConfuseOpponentActive = true)
        if (text == "When you play this Pokémon from your hand to evolve 1 of your Pokémon during your turn, you may choose 2 of your opponent's Pokémon and put 2 damage counters on each of them.")
            return AbilityDefinition("$id-ability$index", name, text, damageCounters = 2, damageTargets = 2, onEvolveDamageCounters = true)
        if (text == "Prevent all damage from and effects of attacks done to this Pokémon by your opponent's Pokémon that have any Special Energy attached.")
            return AbilityDefinition("$id-ability$index", name, text, preventDamageFromSpecialEnergyAttacker = true)
        if (text == "Prevent all damage done to this Pokémon by attacks from your opponent's Pokémon ex.")
            return AbilityDefinition("$id-ability$index", name, text, preventDamageFromOpponentEx = true)
        if (text == "Prevent all damage done to this Pokémon by attacks from your opponent's Basic Pokémon ex.")
            return AbilityDefinition("$id-ability$index", name, text, preventDamageFromBasicPokemonEx = true)
        if (text == "Attacks used by your {F} Pokémon do 30 more damage to your opponent's Active Pokémon (before applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text, teamAttackDamageBonusType = EnergyType.FIGHTING, teamAttackDamageBonus = 30)
        if (text == "Attacks used by your Pokémon do 20 more damage to your opponent's Active Pokémon (before applying Weakness and Resistance).")
            return AbilityDefinition("$id-ability$index", name, text, teamAttackDamageBonusAnyType = 20)
        val searchTeamPokemon = Regex("Once during your turn, you may (?:use this Ability\\. )?[Ss]earch your deck for an? (Cynthia's|Erika's) Pokémon, reveal it, and put it into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        if (searchTeamPokemon != null)
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
                searchPokemonNamePrefixToHand = searchTeamPokemon.groupValues[1], searchPokemonMaxToHand = 1)
        if (text == "Once during your turn, you may search your deck for a Pokémon, reveal it, and put it into your hand. Then, shuffle your deck.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, searchPokemonMaxToHand = 1)
        if (text == "Once during your turn, you may search your deck for up to 2 Evolution {M} Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, searchPokemonEvolutionTypeToHand = EnergyType.METAL, searchPokemonMaxToHand = 2)
        val hpPerEnergy = Regex("This Pokémon gets \\+(\\d+) HP for each (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy attached to it\\.").matchEntire(text)
        if (hpPerEnergy != null) return AbilityDefinition("$id-ability$index", name, text,
            hpBonusPerAttachedEnergyType = symbolType(hpPerEnergy.groupValues[2]) ?: return null,
            hpBonusPerAttachedEnergy = hpPerEnergy.groupValues[1].toInt())
        val typeReduction = Regex("This Pokémon takes (\\d+) less damage from attacks from your opponent's \\{([RW])\\} or \\{([RW])\\} Pokémon \\(after applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (typeReduction != null) return AbilityDefinition("$id-ability$index", name, text,
            damageReduction = typeReduction.groupValues[1].toInt(),
            damageReductionTypes = listOf(typeReduction.groupValues[2], typeReduction.groupValues[3]).mapNotNull(::symbolType))
        if (text == "As long as you have at least 1 other Bouffalant in play, all of your Basic {C} Pokémon take 60 less damage from attacks from your opponent's Pokémon (after applying Weakness and Resistance). The effect of Curly Wall doesn't stack.")
            return AbilityDefinition("$id-ability$index", name, text, protectBasicColorlessWithSecondBouffalant = 60)
        if (text == "This Pokémon can evolve into any Pokémon ex that evolves from Eevee if you play it from your hand onto this Pokémon. (This Pokémon can't evolve during your first turn or the turn you play it.)")
            return AbilityDefinition("$id-ability$index", name, text, allowAnyEeveeExEvolution = true)
        if (text == "As long as this Pokémon is in the Active Spot, it can evolve during your first turn or the turn you play it.")
            return AbilityDefinition("$id-ability$index", name, text, mayEvolveFirstTurnIfActive = true)
        val mayEvolveFirstTurnIfNamedPokemon = Regex("If you have (.+) in play, this Pokémon can evolve during your first turn or the turn you play it\\.").matchEntire(text)
        if (mayEvolveFirstTurnIfNamedPokemon != null)
            return AbilityDefinition("$id-ability$index", name, text,
                mayEvolveFirstTurnIfPokemonInPlayName = mayEvolveFirstTurnIfNamedPokemon.groupValues[1])
        if (text == "Once during your turn, you may look at the top 2 cards of your deck and put 1 of them into your hand. Put the other card on the bottom of your deck.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, lookAtTopCardsChooseOne = 2)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may look at the top 6 cards of your deck, reveal a Supporter card you find there, and put it into your hand. Shuffle the other cards back into your deck.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, requiresActive = true,
                lookAtTopCardsSupporterToHand = 6)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may use this Ability. Search your deck for a card. Shuffle your deck, then put that card on top of it.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, requiresActive = true, searchAnyCardToTop = true)
        val benchEnergyAttach = Regex("When you play this Pokémon from your hand onto your Bench during your turn, you may attach up to (\\d+) Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy cards from your hand to this Pokémon\\.").matchEntire(text)
        if (benchEnergyAttach != null) return AbilityDefinition("$id-ability$index", name, text,
            onBenchAttachBasicEnergyType = symbolType(benchEnergyAttach.groupValues[2]) ?: return null,
            onBenchAttachBasicEnergyMax = benchEnergyAttach.groupValues[1].toInt())
        val searchEnergyToHand = Regex("Once during your turn, you may use this Ability\\. Search your deck for up to (\\d+) Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy cards, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        if (searchEnergyToHand != null) return AbilityDefinition("$id-ability$index", name, text,
            usageKey = name, searchBasicEnergyTypeToHand = symbolType(searchEnergyToHand.groupValues[2]) ?: return null,
            searchBasicEnergyMaxToHand = searchEnergyToHand.groupValues[1].toInt())
        val evolveMetal = Regex("When you play this Pokémon from your hand to evolve 1 of your Pokémon during your turn, you may attach up to (\\d+) Basic \\{M\\} Energy cards from your discard pile to your \\{M\\} Pokémon in any way you like\\.").matchEntire(text)
        if (evolveMetal != null) return AbilityDefinition("$id-ability$index", name, text,
            onEvolveAttachMetalFromDiscardMax = evolveMetal.groupValues[1].toInt())
        val evolveRecoverNamedTrainer = Regex("When you play this Pokémon from your hand to evolve 1 of your Pokémon during your turn, you may put up to (\\d+) (.+) cards from your discard pile into your hand\\.").matchEntire(text)
        if (evolveRecoverNamedTrainer != null) return AbilityDefinition("$id-ability$index", name, text,
            onEvolveRecoverNamedTrainerFromDiscard = evolveRecoverNamedTrainer.groupValues[2],
            onEvolveRecoverNamedTrainerMax = evolveRecoverNamedTrainer.groupValues[1].toInt())
        val evolveRandomShuffle = Regex("When you play this Pokémon from your hand to evolve 1 of your Pokémon during your turn, you may flip (\\d+) coins?\\. For each heads, choose a random card from your opponent's hand\\. Your opponent reveals those cards and shuffles them into their deck\\.").matchEntire(text)
        if (evolveRandomShuffle != null) return AbilityDefinition("$id-ability$index", name, text,
            onEvolveShuffleRandomOpponentHandPerHeads = evolveRandomShuffle.groupValues[1].toInt())
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may shuffle it and all attached cards into your deck.")
            return AbilityDefinition("$id-ability$index", name, text, requiresActive = true, usageKey = name, shuffleSelfIntoDeck = true)
        val passiveReduction = Regex("This Pokémon takes (\\d+) less damage from attacks \\(after applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (passiveReduction != null)
            return AbilityDefinition("$id-ability$index", name, text, damageReduction = passiveReduction.groupValues[1].toInt(), usageKey = name)
        if (text == "Once during your turn, if this Pokémon has any {D} Energy attached, you may move up to 3 damage counters from 1 of your Pokémon to 1 of your opponent's Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
                moveDamageCountersMax = 3, moveDamageCountersEnergyType = EnergyType.DARKNESS)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may discard a Basic {W} Energy card from your hand in order to use this Ability. Place 6 damage counters on 1 of your opponent's Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, requiresActive = true,
                discardEnergyType = EnergyType.WATER, damageCounters = 6, damageTargets = 1)
        val equalHandBonus = Regex("If you have the same number of cards in your hand as your opponent, attacks used by this Pokémon do (\\d+) more damage to your opponent's Active Pokémon \\(before applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (equalHandBonus != null) return AbilityDefinition("$id-ability$index", name, text,
            damageBonusIfHandsEqual = equalHandBonus.groupValues[1].toInt())
        val damageWhenDamaged = Regex("If this Pokémon has (\\d+) or more damage counters on it, attacks used by this Pokémon do (\\d+) more damage to your opponent's Active Pokémon \\(before applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (damageWhenDamaged != null) return AbilityDefinition("$id-ability$index", name, text,
            damageBonusAgainstActiveWhenDamageAtLeast = damageWhenDamaged.groupValues[1].toInt() * 10,
            damageBonusAmount = damageWhenDamaged.groupValues[2].toInt())
        val counterattack = Regex("If this Pokémon is in the Active Spot and is damaged by an attack from your opponent's Pokémon \\(even if this Pokémon is Knocked Out\\), (?:put|place) (\\d+) damage counters on the Attacking Pokémon\\.").matchEntire(text)
        if (counterattack != null) return AbilityDefinition("$id-ability$index", name, text,
            requiresActive = true, retaliateDamageCounters = counterattack.groupValues[1].toInt(), usageKey = name)
        val attachEnergyDraw = Regex("Once during your turn, you may attach a Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy card from your hand to this Pokémon\\. If you attached Energy to a Pokémon in this way, draw a card\\.").matchEntire(text)
        val attachEnergyToType = Regex("Once during your turn, you may use this Ability\\. Attach a Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy card from your hand to 1 of your (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Pokémon\\.").matchEntire(text)
        if (attachEnergyToType != null) return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
            attachHandEnergyType = symbolType(attachEnergyToType.groupValues[1]) ?: return null,
            attachHandEnergyToPokemonType = symbolType(attachEnergyToType.groupValues[2]) ?: return null)
        if (attachEnergyDraw != null) return AbilityDefinition("$id-ability$index", name, text,
            usageKey = name, attachHandEnergyType = symbolType(attachEnergyDraw.groupValues[1]) ?: return null,
            drawIfAttached = 1, usagePerPokemon = true)
        val surviveAttackKnockout = Regex("If this Pokémon has full HP and would be Knocked Out by damage from an attack, it is not Knocked Out, and its remaining HP becomes (\\d+)\\.").matchEntire(text)
        if (surviveAttackKnockout != null) return AbilityDefinition("$id-ability$index", name, text,
            surviveAttackKnockoutAtHp = surviveAttackKnockout.groupValues[1].toInt())
        val surviveAttackKnockoutOnHeads = Regex("If this Pokémon would be Knocked Out by damage from an attack, flip a coin. If heads, this Pokémon is not Knocked Out, and its remaining HP becomes (\\d+)\\.").matchEntire(text)
        if (surviveAttackKnockoutOnHeads != null) return AbilityDefinition("$id-ability$index", name, text,
            surviveAttackKnockoutOnHeadsAtHp = surviveAttackKnockoutOnHeads.groupValues[1].toInt())
        val healActive = Regex("Once during your turn, you may heal (\\d+) damage from your Active Pokémon\\.").matchEntire(text)
        if (healActive != null) return AbilityDefinition("$id-ability$index", name, text, usageKey = name,
            healDamage = healActive.groupValues[1].toInt(), healActiveOnly = true)
        val heal = Regex("Once during your turn, you may (?:use this Ability\\. )?heal (\\d+) damage from 1 of your Pokémon\\.", RegexOption.IGNORE_CASE).matchEntire(text)
        if (heal != null) return AbilityDefinition("$id-ability$index", name, text, usageKey = name, healDamage = heal.groupValues[1].toInt())
        val recoverBasicToBench = Regex("Once during your turn, if this Pokémon is in the Active Spot, you may put a Basic Pokémon with (\\d+) HP or less from your discard pile onto your Bench\\.").matchEntire(text)
        if (recoverBasicToBench != null) return AbilityDefinition("$id-ability$index", name, text, requiresActive = true,
            usageKey = name, recoverBasicPokemonToBenchMaxHp = recoverBasicToBench.groupValues[1].toInt())
        val rocketCount = Regex("This Pokémon can't attack unless you have (\\d+) or more Team Rocket's Pokémon in play\\.").matchEntire(text)
        if (rocketCount != null) return AbilityDefinition("$id-ability$index", name, text,
            requiresTeamRocketPokemonCount = rocketCount.groupValues[1].toInt(), usageKey = name)
        if (text == "Damage from attacks used by this Pokémon isn't affected by any effects on your opponent's Active Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, ignoreOpponentActiveEffects = true, usageKey = name)
        if (text == "Once during your turn, you may have each player draw a card.")
            return AbilityDefinition("$id-ability$index", name, text, eachPlayerDraw = 1, usageKey = name)
        if (text == "Once during your turn, you may draw a card.")
            return AbilityDefinition("$id-ability$index", name, text, draw = 1, usageKey = name)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may use this Ability. Make your opponent's Active Pokémon Asleep.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, requiresActive = true,
                opponentActiveCondition = SpecialCondition.ASLEEP)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may make your opponent's Active Pokémon Asleep.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, requiresActive = true,
                opponentActiveCondition = SpecialCondition.ASLEEP)
        if (text == "Once during your turn, if this Pokémon has any {G} Energy attached, you may use this Ability. Heal 30 damage from 1 of your Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, usageKey = name, healDamage = 30,
                requiresAttachedEnergyType = EnergyType.GRASS)
        val selfKnockoutDamage = Regex("Once during your turn, you may put (\\d+) damage counters on (\\d+) of your opponent's Pokémon\\. If you use this Ability, this Pokémon is Knocked Out\\.").matchEntire(text)
        if (selfKnockoutDamage != null) return AbilityDefinition("$id-ability$index", name, text,
            damageCounters = selfKnockoutDamage.groupValues[1].toInt(), damageTargets = selfKnockoutDamage.groupValues[2].toInt(),
            selfKnockout = true, usageKey = name)
        val limiter = "(?: You can't use more than 1 .+ Ability (?:each turn|during your turn)\\.)?"
        val draw = Regex("Once during your turn, (?:if this Pokémon is in the Active Spot, )?you may use this Ability\\. Draw (\\d+) cards?\\.$limiter").matchEntire(text)
        if (draw != null) return AbilityDefinition("$id-ability$index", name, text,
            draw = draw.groupValues[1].toInt(), requiresActive = text.contains("in the Active Spot"), usageKey = name)
        val drawTo = Regex("Once during your turn, you may use this Ability\\. Draw cards until you have (\\d+) cards in your hand\\.$limiter").matchEntire(text)
        if (drawTo != null) return AbilityDefinition("$id-ability$index", name, text, drawTo = drawTo.groupValues[1].toInt(), usageKey = name)
        val discardDraw = Regex("You must discard a card from your hand in order to use this Ability\\. Once during your turn, you may draw (\\d+) cards?\\.$limiter").matchEntire(text)
        if (discardDraw != null) return AbilityDefinition("$id-ability$index", name, text,
            draw = discardDraw.groupValues[1].toInt(), discardHandCount = 1, usageKey = name)
        val evolveDraw = Regex("(?:Once during your turn, )?when you play this Pokémon from your hand to evolve 1 of your Pokémon(?: during your turn)?, you may use this Ability\\. Draw (\\d+) cards?\\.$limiter", RegexOption.IGNORE_CASE).matchEntire(text)
        if (evolveDraw != null) return AbilityDefinition("$id-ability$index", name, text,
            onEvolveDraw = evolveDraw.groupValues[1].toInt(), usageKey = name)
        return null
    }

    fun compile(raw: JsonObject, reviewed: Map<String, CardDefinition>): CardDefinition? {
        val id = raw.s("id") ?: return null
        val image = raw.s("image").orEmpty()
        val source = "TCGdex • https://api.tcgdex.net/v2/en/cards/$id • gramática executável v1"
        // Reuse only identical trainer/energy print semantics, including ACE SPEC rarity.
        if (raw.s("category") in listOf("Trainer", "Energy")) {
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                raw.s("effect") == "Search your deck for a Basic Pokémon, a Stage 1 Pokémon, and a Stage 2 Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = raw.s("effect").orEmpty(),
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.SearchEachEvolutionStage)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                raw.s("effect") == "Move up to 2 Energy from your Benched Pokémon to your Active Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = raw.s("effect").orEmpty(),
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.MoveBenchedEnergyToActive(2))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                raw.s("effect") == "Heal 60 damage from each of your {L} Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = raw.s("effect").orEmpty(),
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.HealEachOwnPokemonOfType(60, EnergyType.LIGHTNING))
            }
            val trainerText = raw.s("effect").orEmpty()
            fun trainerSearch(filters: List<CardFilter>, max: Int = 1, discard: Int = 0, lookAtTop: Int = 0): CardDefinition? {
                val trainerName = raw.s("name") ?: return null
                val number = raw.s("localId") ?: return null
                return CardDefinition(id, trainerName, raw.s("setCode").orEmpty(), number,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.valueOf(raw.s("trainerType")!!.uppercase()),
                    trainerEffect = TrainerEffect.Search(filters, discardCost = discard, maxPerFilter = max, lookAtTop = lookAtTop))
            }
            fun coinSearch(heads: CardFilter, tails: CardFilter? = null): CardDefinition? {
                val trainerName = raw.s("name") ?: return null
                val number = raw.s("localId") ?: return null
                return CardDefinition(id, trainerName, raw.s("setCode").orEmpty(), number,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.CoinSearch(heads, tails))
            }
            if (raw.s("trainerType") == "Supporter" && trainerText ==
                "Look at the top 7 cards of your deck. You may reveal a Pokémon and a Trainer card you find there and put them into your hand. Shuffle the other cards back into your deck.")
                return trainerSearch(listOf(CardFilter.POKEMON, CardFilter.TRAINER), lookAtTop = 7)
            if (raw.s("trainerType") == "Item" && trainerText ==
                "Search your deck for a Pokémon, reveal it, and put it into your hand. Then, shuffle your deck.")
                return trainerSearch(listOf(CardFilter.POKEMON))
            if (raw.s("trainerType") == "Item" && trainerText ==
                "Search your deck for a Tera Pokémon, reveal it, and put it into your hand. Then, shuffle your deck.")
                return trainerSearch(listOf(CardFilter.TERA_POKEMON))
            if (raw.s("trainerType") == "Item" && trainerText ==
                "Search your deck for up to 3 Stage 1 Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.")
                return trainerSearch(listOf(CardFilter.STAGE_ONE_POKEMON), max = 3)
            if (raw.s("trainerType") == "Tool" && trainerText ==
                "Search your deck for up to 5 Pokémon Tool cards, reveal them, and put them into your hand. Then, shuffle your deck.")
                return trainerSearch(listOf(CardFilter.TOOL), max = 5)
            if (raw.s("trainerType") == "Supporter" && trainerText ==
                "Search your deck for up to 7 Basic {R} Energy cards, reveal them, and put them into your hand. Then, shuffle your deck.")
                return trainerSearch(listOf(CardFilter.BASIC_FIRE_ENERGY), max = 7)
            if (raw.s("trainerType") == "Supporter" && trainerText ==
                "Search your deck for up to 3 in any combination of Ethan's Pokémon and Basic {R} Energy cards, reveal them, and put them into your hand. Then, shuffle your deck.")
                return trainerSearch(listOf(CardFilter.ETHAN_POKEMON_OR_BASIC_FIRE_ENERGY), max = 3)
            if (raw.s("trainerType") == "Supporter" && trainerText ==
                "Search your deck for up to 3 Misty's Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.")
                return trainerSearch(listOf(CardFilter.MISTY_POKEMON), max = 3)
            if (raw.s("trainerType") == "Supporter" && trainerText == "Heal 40 damage from each of your Pokémon.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.HealEachOwnPokemon(40))
            if (raw.s("trainerType") == "Supporter" && trainerText == "Heal all damage from 1 of your Pokémon that has 30 HP or less remaining.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.HealPokemon(9999, maxRemainingHp = 30))
            if (raw.s("trainerType") == "Supporter" && trainerText ==
                "Look at the top 8 cards of your deck and put up to 3 of them into your hand. Shuffle the other cards back into your deck.")
                return trainerSearch(listOf(CardFilter.ANY_CARD), max = 3, lookAtTop = 8)
            if (raw.s("trainerType") == "Supporter" && trainerText ==
                "Search your deck for up to 2 Basic Pokémon or 1 Evolution Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.") {
                val trainerName = raw.s("name") ?: return null
                val number = raw.s("localId") ?: return null
                return CardDefinition(id, trainerName, raw.s("setCode").orEmpty(), number,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Search(
                        listOf(CardFilter.BASIC_POKEMON, CardFilter.EVOLUTION_POKEMON), maxPerFilter = 2,
                        maxPerFilterOverrides = mapOf(CardFilter.EVOLUTION_POKEMON to 1)))
            }
            if (raw.s("trainerType") == "Item" && trainerText ==
                "You can use this card only if you discard 2 other cards from your hand. Search your deck for a Pokémon, reveal it, and put it into your hand. Then, shuffle your deck.")
                return trainerSearch(listOf(CardFilter.POKEMON), discard = 2)
            if (raw.s("trainerType") == "Supporter" && trainerText ==
                "Draw 4 cards. At the end of this turn, if you have 5 or more cards in your hand, discard your hand.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.DrawCards(4, discardHandAtEndIfAtLeast = 5))
            if (raw.s("trainerType") == "Supporter" && trainerText ==
                "Shuffle your hand into your deck. Then, draw 4 cards. If your opponent has 3 or fewer Prize cards remaining, draw 8 cards instead.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.DrawCardsIfOpponentPrizesAtMost(4, 4, 3, shuffleHand = true))
            if (raw.s("trainerType") == "Supporter" && trainerText in setOf(
                "If you go first, you may use this card during your first turn.  Discard your hand and draw 5 cards.",
                "If you go first, you may use this card during your first turn.\n\nDiscard your hand and draw 5 cards."))
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.DrawTo(5, discardWholeHand = true, allowFirstTurnIfStarting = true))
            if (raw.s("trainerType") == "Supporter" && trainerText ==
                "You can use this card only when it is the last card in your hand.\n\nSearch your deck for up to 2 cards and put them into your hand. Then, shuffle your deck.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Search(
                        listOf(CardFilter.ANY_CARD), maxPerFilter = 2, onlyIfLastCardInHand = true))
            if (raw.s("trainerType") == "Item" && trainerText ==
                "Flip a coin. If heads, search your deck for a Pokémon, reveal it, and put it into your hand. Then, shuffle your deck.")
                return coinSearch(CardFilter.POKEMON)
            if (raw.s("trainerType") == "Item" && trainerText ==
                "Flip a coin. If heads, search your deck for an Evolution Team Rocket's Pokémon, reveal it, and put it into your hand. If tails, search your deck for a Basic Team Rocket's Pokémon, reveal it, and put it into your hand. Then, shuffle your deck.")
                return coinSearch(CardFilter.EVOLUTION_TEAM_ROCKET_POKEMON, CardFilter.TEAM_ROCKET_BASIC_POKEMON)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Tool" && raw.s("name") == "Handheld Fan" &&
                trainerText == "If the Pokémon this card is attached to is in the Active Spot and is damaged by an attack from your opponent's Pokémon (even if this Pokémon is Knocked Out), move an Energy from the Attacking Pokémon to 1 of your opponent's Benched Pokémon.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.TOOL, trainerEffect = TrainerEffect.HandheldFan)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Stadium" && raw.s("name") == "Battle Cage" &&
                trainerText == "Prevent all damage counters from being placed on Benched Pokémon (both yours and your opponent's) by effects of attacks and Abilities from the opponent's Pokémon. (Damage from attacks is still taken.)")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.BattleCage)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Stadium" && raw.s("name") == "Area Zero Underdepths" &&
                trainerText == "Each player who has any Tera Pokémon in play can have up to 8 Pokémon on their Bench.\n\nIf a player no longer has any Tera Pokémon in play, that player discards Pokémon from their Bench until they have 5. When this card leaves play, both players discard Pokémon from their Bench until they have 5, and the player who played this card discards first.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.AreaZeroUnderdepths)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" && raw.s("name") == "Rare Candy" &&
                trainerText == "Choose 1 of your Basic Pokémon in play. If you have a Stage 2 card in your hand that evolves from that Pokémon, put that card onto the Basic Pokémon to evolve it, skipping the Stage 1. You can't use this card during your first turn or on a Basic Pokémon that was put into play this turn.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.RareCandy)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Tool" && raw.s("name") == "Lillie's Pearl" &&
                trainerText == "If the Lillie's Pokémon this card is attached to is Knocked Out by damage from an attack from your opponent's Pokémon, that player takes 1 fewer Prize card.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.TOOL, prizeReductionOnAttackKnockoutForPokemonPrefix = "Lillie's ")
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                raw.s("name") == "Brock's Scouting" &&
                trainerText == "Search your deck for up to 2 Basic Pokémon or 1 Evolution Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.BrockScouting)
            if (raw.s("category") == "Energy") {
                val energyType = when (raw.s("name")) {
                    "Telepathic Psychic Energy" -> EnergyType.PSYCHIC
                    "Boomerang Energy", "Enriching Energy", "Legacy Energy" -> EnergyType.COLORLESS
                    else -> null
                }
                if (energyType != null) {
                    val telepathic = raw.s("name") == "Telepathic Psychic Energy"
                    val enriching = raw.s("name") == "Enriching Energy"
                    val legacy = raw.s("name") == "Legacy Energy"
                    val boomerang = raw.s("name") == "Boomerang Energy"
                    return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                        category = Category.ENERGY, type = energyType, verified = true, source = source,
                        artAsset = "remote:$image/high.webp", text = trainerText,
                        aceSpec = raw.s("rarity").orEmpty().contains("ACE SPEC", ignoreCase = true),
                        providesEveryEnergyType = legacy,
                        reducesPrizeOncePerGameOnAttackKnockout = legacy,
                        returnsFromDiscardAfterAttachedPokemonAttackDiscard = boomerang,
                        drawOnAttachFromHand = if (enriching) 4 else 0,
                        searchBasicPokemonTypeOnAttachFromHand = if (telepathic) EnergyType.PSYCHIC else null,
                        searchBasicPokemonMaxOnAttachFromHand = if (telepathic) 2 else 0)
                }
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "You can use this card only if you have any Tera Pokémon in play.\n\nChoose up to 2 of your Benched {C} Pokémon and attach a Basic Energy card from your discard pile to each of them.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.GlassTrumpet)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Draw cards until you have 5 cards in your hand. Before drawing cards, you may discard any number of cards from your hand. (If you can't draw any cards in this way, you can't use this card.)")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Naveen)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Search your deck for 2 cards, shuffle your deck, then put those cards on top of it in any order.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Ciphermaniac)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "During this turn, attacks used by your Pokémon do 40 more damage to your opponent's Active Pokémon ex (before applying Weakness and Resistance).")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.OpponentExDamageBoost(40))
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Choose 1:\n\n• Switch your Active Pokémon with 1 of your Benched Pokémon.\n\n• During this turn, attacks used by your Pokémon do 30 more damage to your opponent's Active Pokémon ex and Active Pokémon V (before applying Weakness and Resistance).")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Kieran)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Each player shuffles their hand into their deck. Then, flip a coin. If heads, you draw 5 cards, and your opponent draws 3 cards. If tails, you draw 3 cards, and your opponent draws 5 cards.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.ShuffleBothHandsDraw(5, 3, coinFlip = true))
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Stadium" &&
                trainerText == "Once during each player's turn, that player may put a card from their hand on top of their deck.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.AcademyAtNight)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Stadium" &&
                trainerText == "Once during each player's turn, that player may discard 2 cards from their hand in order to draw a card.")
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.PrismTower)
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Stadium" &&
                trainerText == "Whenever any player puts a Basic non-{D} Pokémon onto their Bench during their turn, place 2 damage counters on that Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText, trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.RiskyRuins)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Choose up to 2 of your {D} Pokémon. For each of those Pokémon, search your deck for a Basic {D} Energy card and attach it to that Pokémon. Then, shuffle your deck. If you attached Energy to your Active Pokémon in this way, it is now Poisoned.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText, trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.JanineSecretArt)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Stadium" &&
                trainerText == "N's Pokémon in play (both yours and your opponent's) have no Retreat Cost.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText, trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.NsCastle)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Stadium" &&
                trainerText == "{C} Pokémon in play (both yours and your opponent's) have no Abilities.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText, trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.ColorlessAbilityLock)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Look at the top 7 cards of your deck. You may reveal a Supporter card you find there and put it into your hand. Shuffle the other cards back into your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.Search(listOf(CardFilter.SUPPORTER), lookAtTop = 7))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Look at the top 6 cards of your deck and put 2 of them into your hand. Discard the other cards.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.LookTopKeepDiscard(6, 2))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Heal 80 damage from your Active Pokémon that has 3 or more Energy attached.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.HealPokemon(80, activeOnly = true, requiredEnergyCount = 3))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Tool" &&
                trainerText == "The Pokémon this card is attached to gets +100 HP.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.TOOL, trainerEffect = TrainerEffect.ToolHpModifier(100))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Tool" &&
                trainerText == "If the Pokémon this card is attached to is in the Active Spot and is damaged by an attack from your opponent's Pokémon (even if this Pokémon is Knocked Out), draw 2 cards.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.TOOL, trainerEffect = TrainerEffect.LuckyHelmet)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Tool" &&
                trainerText == "Attacks used by the Poisoned Pokémon this card is attached to do 40 more damage to your opponent's Active Pokémon (before applying Weakness and Resistance).") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.TOOL, trainerEffect = TrainerEffect.BindingMochi(40))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "You can use this card only if you have more Prize cards remaining than your opponent.\n\nAttach up to 2 Basic Energy cards from your discard pile to 1 of your Stage 2 Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.RosaEncouragement)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Move a Basic Energy from 1 of your Pokémon to another of your Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.MoveBasicEnergyBetweenOwnPokemon)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Search your deck for up to 2 Basic Energy cards of different types, reveal them, and put 1 of them into your hand. Attach the other to 1 of your Pokémon. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Crispin)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "You can use this card only if your opponent has 3 or fewer Prize cards remaining.\n\nYour opponent shuffles their hand and puts it on the bottom of their deck. If they put any cards on the bottom of their deck in this way, they draw 3 cards.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.OpponentBottomHandDraw(3), aceSpec = true)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Flip a coin. If heads, discard an Energy from 1 of your opponent's Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.CoinDiscardOpponentEnergy)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Search your deck for up to 3 Pokémon ex, reveal them, and put them into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.Search(listOf(CardFilter.POKEMON_EX), maxPerFilter = 3))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "You can use this card only if any of your Pokémon were Knocked Out during your opponent's last turn.\n\nEach player shuffles their hand into their deck. Then, you draw 5 cards, and your opponent draws 2 cards.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.ShuffleBothHandsDraw(5, 2, true), aceSpec = true)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Each player shuffles their hand into their deck and draws 4 cards.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.ShuffleBothHandsDraw(4))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Search your deck for an Evolution Pokémon and an Energy card, reveal them, and put them into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.Search(listOf(CardFilter.EVOLUTION_POKEMON, CardFilter.ENERGY)))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "You can use this card only if you discard another card from your hand.\n\nDraw a card for each of your opponent's Benched Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.DrawPerOpponentBenchedPokemon(discardCost = 1))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Draw cards until you have 5 cards in your hand. If all of your Pokémon in play are Team Rocket's Pokémon, draw cards until you have 8 cards in your hand instead.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.DrawTo(size = 5, teamRocketSize = 8))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Put up to 4 in any combination of {F} Pokémon and Basic {F} Energy cards from your discard pile into your hand.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.RecoverPokemonAndBasicEnergy(max = 4, type = EnergyType.FIGHTING))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Put up to 3 in any combination of Pokémon that don't have a Rule Box and Basic Energy cards from your discard pile into your hand. (Pokémon ex, Pokémon V, etc. have Rule Boxes.)") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.RecoverPokemonAndBasicEnergy(max = 3, pokemonWithoutRuleBoxOnly = true))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Put up to 5 in any combination of Pokémon and Basic Energy cards from your discard pile into your hand.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.RecoverPokemonAndBasicEnergy(5))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Search your deck for any number of Basic Energy cards of different types, reveal them, and put them into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM,
                    trainerEffect = TrainerEffect.Search(listOf(CardFilter.BASIC_ENERGY), maxPerFilter = 8, differentEnergyTypes = true))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Heal 150 damage from 1 of your Pokémon.\n\nThis card can't be put into your hand or deck from the discard pile.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.HealPokemon(150),
                    cannotBeRecoveredFromDiscard = true)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Attach a Basic {P} Energy card from your discard pile to 1 of your Benched {P} Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM,
                    trainerEffect = TrainerEffect.AttachBasicEnergyFromDiscardToBenchedType(EnergyType.PSYCHIC))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Shuffle up to 5 Pokémon from your discard pile into your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.RecoverPokemonToDeck(max = 5))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Shuffle up to 5 Basic Energy cards from your discard pile into your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.RecoverEnergy(max = 5, toDeck = true))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Put up to 2 Basic Energy cards from your discard pile into your hand.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.RecoverEnergy(max = 2))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Put up to 2 Supporter cards from your discard pile into your hand.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.RecoverSupporters(2))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Discard all Pokémon Tools and Special Energy from all of your opponent's Pokémon, and discard a Stadium in play.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.MegatonBlower)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Search your deck for a Supporter card that has \"Team Rocket\" in its name, reveal it, and put it into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM,
                    trainerEffect = TrainerEffect.Search(listOf(CardFilter.TEAM_ROCKET_SUPPORTER)))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Search your deck for a Pokémon that doesn't have a Rule Box, reveal it, and put it into your hand. Then, shuffle your deck. (Pokémon ex, Pokémon V, etc. have Rule Boxes.)") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM,
                    trainerEffect = TrainerEffect.Search(listOf(CardFilter.POKEMON_WITHOUT_RULE_BOX)))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "You can use this card only if you discard another card from your hand.\n\nSearch your deck for up to 4 {L} Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.Search(listOf(CardFilter.LIGHTNING_POKEMON), discardCost = 1, maxPerFilter = 4))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Put a Pokémon or a Basic Energy card from your discard pile into your hand.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.Recover)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Shuffle your hand into your deck. Then, draw 6 cards. If you have exactly 6 Prize cards remaining, draw 8 cards instead.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.ShuffleHandDraw(count = 6, sixPrizeCount = 8))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Draw 2 cards. If your opponent has 3 or fewer Prize cards remaining, draw 2 more cards.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.DrawCardsIfOpponentPrizesAtMost(count = 2, bonus = 2, prizeThreshold = 3))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Switch your Active Pokémon with 1 of your Benched Pokémon. If you moved a Pokémon ex to your Bench in this way, heal 80 damage from that Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.SwitchAndHealMovedEx(80))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Discard up to 2 Pokémon that don't have a Rule Box from your hand, and draw 3 cards for each card you discarded in this way. (Pokémon ex, Pokémon V, etc. have Rule Boxes.)") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.DiscardHandPokemonAndDraw(max = 2, drawPerCard = 3))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Heal 150 damage from 1 of your {P} Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.HealPokemon(150, pokemonType = EnergyType.PSYCHIC))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "If you go first, you may use this card during your first turn.\n\nSearch your deck for up to 3 Basic Team Rocket's Pokémon, reveal them, and put them into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.Search(listOf(CardFilter.TEAM_ROCKET_BASIC_POKEMON), maxPerFilter = 3, allowFirstTurnIfStarting = true))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Search your deck for a Trainer card, reveal it, and put it into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Search(listOf(CardFilter.TRAINER)))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Attach a Basic Energy card from your discard pile to 1 of your Benched N's Pokémon.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.AttachBasicEnergyFromDiscardToNBench)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Search your deck for a Mega Evolution Pokémon ex, reveal it, and put it into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.Search(listOf(CardFilter.MEGA_EVOLUTION_EX)))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText == "Heal all damage from 1 of your Mega Evolution Pokémon ex. If you healed any damage in this way, put all Energy attached to that Pokémon into your hand.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.WallyCompassion)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Item" &&
                trainerText == "Search your deck for up to 2 Basic Pokémon with 70 HP or less and put them onto your Bench. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM, trainerEffect = TrainerEffect.BuddyBuddyPoffin)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Stadium" &&
                trainerText == "Once during each player's turn, that player may discard an Energy card from their hand in order to draw cards until they have as many cards in their hand as they have {P} Pokémon in play.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.MysteryGarden)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                trainerText.startsWith("You can use this card only if your opponent has exactly 2 Prize cards remaining.") &&
                trainerText.endsWith("During this turn, if your opponent's Active Pokémon is Knocked Out by damage from an attack used by your Tera Pokémon, take 1 more Prize card.")) {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.Briar)
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Supporter" &&
                raw.s("effect") == "Your opponent reveals their hand, and you discard up to 2 Item cards you find there.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = raw.s("effect").orEmpty(),
                    trainerType = TrainerType.SUPPORTER, trainerEffect = TrainerEffect.DiscardOpponentItemsFromHand(2))
            }
            if (raw.s("category") == "Trainer" && raw.s("trainerType") == "Stadium" &&
                raw.s("effect") == "Each player's {G} Pokémon can evolve into {G} Pokémon during the turn they play those Pokémon, except during their first turn.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(), raw.s("localId") ?: return null,
                    category = Category.TRAINER, verified = true, source = source,
                    artAsset = "remote:$image/high.webp", text = raw.s("effect").orEmpty(),
                    trainerType = TrainerType.STADIUM, trainerEffect = TrainerEffect.ForestOfVitality)
            }
            val simpleSupporterDraw = if (raw.s("trainerType") == "Supporter")
                Regex("Draw (\\d+) cards?\\.").matchEntire(trainerText) else null
            if (simpleSupporterDraw != null) {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(),
                    raw.s("localId") ?: return null, category = Category.TRAINER, verified = true,
                    source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.DrawCards(simpleSupporterDraw.groupValues[1].toInt()))
            }
            if (raw.s("trainerType") == "Item" &&
                trainerText == "Search your deck for a Basic Energy card, reveal it, and put it into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(),
                    raw.s("localId") ?: return null, category = Category.TRAINER, verified = true,
                    source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.ITEM,
                    trainerEffect = TrainerEffect.Search(listOf(CardFilter.BASIC_ENERGY)))
            }
            if (raw.s("trainerType") == "Supporter" &&
                trainerText == "Search your deck for a Stadium card and an Energy card, reveal them, and put them into your hand. Then, shuffle your deck.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(),
                    raw.s("localId") ?: return null, category = Category.TRAINER, verified = true,
                    source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.SUPPORTER,
                    trainerEffect = TrainerEffect.Search(listOf(CardFilter.STADIUM, CardFilter.ENERGY)))
            }
            if (raw.s("trainerType") == "Tool" &&
                trainerText == "If you have more Prize cards remaining than your opponent, attacks used by the Pokémon this card is attached to cost {C} less.") {
                return CardDefinition(id, raw.s("name") ?: return null, raw.s("setCode").orEmpty(),
                    raw.s("localId") ?: return null, category = Category.TRAINER, verified = true,
                    source = source, artAsset = "remote:$image/high.webp", text = trainerText,
                    trainerType = TrainerType.TOOL, trainerEffect = TrainerEffect.CounterGain)
            }
            val same = reviewed.entries.firstOrNull { (key, _) ->
                val original = rows[key] ?: TrainerCatalog.sourceData(key)
                signature(original) == signature(raw)
            }?.value ?: return null
            return same.copy(id = id, number = raw.s("localId")!!, set = raw.s("setCode").orEmpty(),
                artAsset = "remote:$image/high.webp", source = source)
        }
        val stage = when (raw.s("stage")) {
            "Basic" -> Stage.BASIC
            "Stage1" -> Stage.STAGE_ONE
            "Stage2" -> Stage.STAGE_TWO
            else -> return null
        }
        val suffix = raw.s("suffix").orEmpty().let { if (it.equals("EX", ignoreCase = true)) "ex" else it }
        if (raw.s("category") != "Pokemon" || suffix !in listOf("", "ex", "V", "VMAX", "VSTAR") ||
            raw.a("rules").isNotEmpty() || !raw.s("effect").isNullOrBlank() ||
            raw.a("types").size != 1 || raw.a("weaknesses").size > 1 || raw.a("resistances").size > 1) return null
        val element = type(raw.a("types").single().jsonPrimitive.content) ?: return null
        val weakness = raw.a("weaknesses").firstOrNull()?.jsonObject
        val resistance = raw.a("resistances").firstOrNull()?.jsonObject
        if (weakness != null && (weakness.s("value") !in listOf("×2", "x2") || type(weakness.s("type")) == null)) return null
        if (resistance != null && (resistance.s("value") != "-30" || type(resistance.s("type")) == null)) return null
        val attacks = raw.a("attacks").mapIndexed { index, attack ->
            compileAttack(id, index, attack.jsonObject) ?: return null
        }
        if (attacks.isEmpty()) return null
        val abilities = raw.a("abilities").mapIndexed { index, ability -> compileAbility(id, index, ability.jsonObject) ?: return null }
        val hp = raw.s("hp")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val retreat = raw.s("retreat")?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val prizes = when {
            suffix == "VMAX" || raw.s("name")!!.startsWith("Mega ") && suffix == "ex" -> 3
            suffix in listOf("ex", "V", "VSTAR") -> 2
            else -> 1
        }
        val rareCandyBase = if (stage == Stage.STAGE_TWO) rows.values.firstOrNull { intermediate ->
            intermediate.s("category") == "Pokemon" && intermediate.s("stage") == "Stage1" &&
                intermediate.s("name") == raw.s("evolveFrom")
        }?.s("evolveFrom") else null
        return CardDefinition(id, raw.s("name")!!, raw.s("setCode").orEmpty(), raw.s("localId")!!,
            category = Category.POKEMON, stage = stage, evolvesFromName = raw.s("evolveFrom"),
            hp = hp, type = element, weakness = type(weakness?.s("type")),
            resistance = type(resistance?.s("type")), retreatCost = retreat, attacks = attacks,
            prizes = prizes, abilities = abilities, verified = true, source = source,
            artAsset = image.takeIf(String::isNotBlank)?.let { "remote:$it/high.webp" },
            isTera = id in CardTraits.teraPokemonIds, rareCandyBasicEvolvesFromName = rareCandyBase)
    }

    fun compileAttack(id: String, index: Int, raw: JsonObject): AttackDefinition? {
        val cost = raw.a("cost").map { type(it.jsonPrimitive.content) ?: return null }
        val text = raw.s("effect").orEmpty().trim()
        val discardOpponentDeckFixedCoins = Regex("Flip (\\d+) coins?\\. For each heads, discard the top card of your opponent's deck\\.").matchEntire(text)
        if (discardOpponentDeckFixedCoins != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardOpponentDeckForFixedCoinHeads(discardOpponentDeckFixedCoins.groupValues[1].toInt()), text = text)
        }
        if (text == "Flip a coin. If heads, switch 1 of your opponent's Benched Pokémon with their Active Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CoinSwitchOpponent, text = text)
        }
        val discardOpponentTools = Regex("Discard up to (\\d+) Pokémon Tools from your opponent's Pokémon\\.").matchEntire(text)
        if (discardOpponentTools != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardOpponentPokemonTools(discardOpponentTools.groupValues[1].toInt()), text = text)
        }
        if (text == "Move all damage counters from 1 of your Benched Team Rocket's Pokémon to your opponent's Active Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.MoveTeamRocketBenchDamageCountersToOpponentActive, text = text)
        }
        if (text == "Attach a Basic Energy card from your hand to this Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AttachBasicEnergyFromHandToSelf, text = text)
        }
        if (text == "Move a Basic Energy from this Pokémon to 1 of your Benched Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.MoveEnergyToBench(1, basicOnly = true), text = text)
        }
        if (text == "If your Benched Pokémon have any Shadowy {D} Energy attached, this attack does 70 more damage.") {
            val printed = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(printed.toString())) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnBenchHasNamedEnergyDamageBonus("Shadowy", 70), text = text)
        }
        if (text == "Search your deck for up to 2 Basic Energy cards and attach them to your Future Pokémon in any way you like. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AttachBasicEnergyFromDeckAny(2, futurePokemonOnly = true), text = text)
        }
        if (text == "Search your deck for up to 3 Basic Energy cards of different types and attach them to your Tera Pokémon in any way you like. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AttachBasicEnergyFromDeckAny(3, teraPokemonOnly = true, differentEnergyTypes = true), text = text)
        }
        val nextTurnAttackBaseDamage = Regex("During your next turn, this Pokémon's (.+) attack's base damage is (\\d+)\\.").matchEntire(text)
        if (nextTurnAttackBaseDamage != null) {
            val attackName = nextTurnAttackBaseDamage.groupValues[1]
            val targetBase = nextTurnAttackBaseDamage.groupValues[2].toInt()
            val cardRaw = rows[id] ?: TrainerCatalog.sourceData(id)
            val printedBase = cardRaw["attacks"]?.jsonArray.orEmpty().map { it.jsonObject }
                .firstOrNull { it.s("name") == attackName }?.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            if (targetBase < printedBase) return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.NextTurnAttackDamageBonus(attackName, targetBase - printedBase), text = text)
        }
        val coinApplyTwoConditions = Regex("Flip a coin\\. If heads, your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused) and (Poisoned|Burned|Asleep|Paralyzed|Confused)\\.").matchEntire(text)
        if (coinApplyTwoConditions != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            val first = runCatching { SpecialCondition.valueOf(coinApplyTwoConditions.groupValues[1].uppercase()) }.getOrNull() ?: return null
            val second = runCatching { SpecialCondition.valueOf(coinApplyTwoConditions.groupValues[2].uppercase()) }.getOrNull() ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CoinDamage(1, 0) +
                Effect.StatusCondition(first, onHeads = true) + Effect.StatusCondition(second, onHeads = true), text = text)
        }
        if (text == "Search your deck for a Basic Energy card, reveal it, and put it into your hand. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.SearchBasicEnergyToHand(1), text = text)
        }
        if (text == "This Pokémon is now Asleep. Draw 2 cards.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.StatusCondition(SpecialCondition.ASLEEP, onSelf = true) + Effect.Draw(2), text = text)
        }
        val selfDamageOnDoubleTails = Regex("Flip (\\d+) coins?\\. If both of them are tails, this Pokémon also does (\\d+) damage to itself\\.").matchEntire(text)
        if (selfDamageOnDoubleTails != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            val coinCount = selfDamageOnDoubleTails.groupValues[1].toInt()
            return compiled.copy(effects = compiled.effects + Effect.CoinDamage(coinCount, 0) + Effect.SelfDamageIfAllCoinsTails(
                coinCount, selfDamageOnDoubleTails.groupValues[2].toInt()), text = text)
        }
        val coinDamageStatusAnyHeads = Regex("Flip (\\d+) coins?\\. This attack does (\\d+) damage for each heads\\. If either of them (?:is|are) heads, your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused)\\.").matchEntire(text)
        if (coinDamageStatusAnyHeads != null) {
            val baseText = "Flip ${coinDamageStatusAnyHeads.groupValues[1]} coins. This attack does ${coinDamageStatusAnyHeads.groupValues[2]} damage for each heads."
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive(baseText)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            val condition = runCatching { SpecialCondition.valueOf(coinDamageStatusAnyHeads.groupValues[3].uppercase()) }.getOrNull() ?: return null
            return compiled.copy(effects = compiled.effects + Effect.StatusCondition(condition, onHeads = true, minimumHeads = 1), text = text)
        }
        val opponentMassDamageCounters = Regex("Put (\\d+) damage counters on each of your opponent's Pokémon\\.").matchEntire(text)
        val abilityMassDamageCounters = Regex("Put (\\d+) damage counters on each Pokémon that has an Ability \\(both yours and your opponent's\\)\\.").matchEntire(text)
        if (opponentMassDamageCounters != null || abilityMassDamageCounters != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            val rule = opponentMassDamageCounters ?: abilityMassDamageCounters!!
            return compiled.copy(effects = compiled.effects + Effect.MassDamageCounters(
                rule.groupValues[1].toInt(), bothPlayers = abilityMassDamageCounters != null, requireAbility = abilityMassDamageCounters != null), text = text)
        }
        if (text == "Put a Trainer card from your discard pile into your hand.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RecoverTrainerFromDiscard, text = text)
        }
        if (text == "Search your deck for up to 3 Basic Energy cards of different types, reveal them, and put them into your hand. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.SearchBasicEnergyToHand(3, differentTypes = true), text = text)
        }
        val knockOutLowRemainingHp = Regex("Knock Out each of your opponent's Pokémon that has (\\d+) HP or less remaining\\.").matchEntire(text)
        if (knockOutLowRemainingHp != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.KnockOutOpponentPokemonRemainingHpAtMost(knockOutLowRemainingHp.groupValues[1].toInt()), text = text)
        }
        val nextTurnDefendingBonus = Regex("During your next turn, the Defending Pokémon takes (\\d+) more damage from attacks \\(after applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (nextTurnDefendingBonus != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.NextTurnDefendingDamageBonus(nextTurnDefendingBonus.groupValues[1].toInt()), text = text)
        }
        if (text == "Search your deck for up to 2 Basic {G} Energy cards and up to 2 Basic {L} Energy cards and attach them to your Pokémon in any way you like. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AttachBasicEnergyFromDeckAny(2, EnergyType.GRASS) +
                Effect.AttachBasicEnergyFromDeckAny(2, EnergyType.LIGHTNING), text = text)
        }
        if (text == "If your opponent's Active Pokémon has any Special Energy attached, it is Knocked Out.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.KnockOutOpponentActiveIfSpecialEnergy, text = text)
        }
        if (text == "Discard a Stadium in play. If you can't, this attack does nothing.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiresStadium + Effect.DiscardStadium, text = text)
        }
        if (text == "Draw cards until you have 7 cards in your hand.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DrawToHand(7), text = text)
        }
        if (text == "Flip a coin. If heads, your opponent's Active Pokémon is now Confused and Poisoned.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CoinDamage(1, 0) +
                Effect.StatusCondition(SpecialCondition.CONFUSED, onHeads = true) +
                Effect.StatusCondition(SpecialCondition.POISONED, onHeads = true), text = text)
        }
        val searchTypedBasicEnergyAttach = Regex("Search your deck for a Basic \\{([GRFLWPMD])\\} Energy card and attach it to 1 of your Pokémon\\. Then, shuffle your deck\\.").matchEntire(text)
        if (searchTypedBasicEnergyAttach != null) {
            val type = symbolType(searchTypedBasicEnergyAttach.groupValues[1]) ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AttachBasicEnergyFromDeckAny(1, type), text = text)
        }
        val lookTopPokemonToBench = Regex("Look at the top (\\d+) cards of your deck\\. You may put any number of Pokémon you find there onto your Bench\\. Shuffle the other cards back into your deck\\.").matchEntire(text)
        if (lookTopPokemonToBench != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.LookTopPokemonToBench(lookTopPokemonToBench.groupValues[1].toInt()), text = text)
        }
        if (text == "During your opponent's next turn, if this Pokémon is damaged by an attack (even if this Pokémon is Knocked Out), put damage counters on the Attacking Pokémon equal to the damage done to this Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RetaliateEqualDamageTakenNextTurn, text = text)
        }
        val coinPerDarkPokemon = Regex("Flip a coin for each \\{D\\} Pokémon you have in play\\. This attack does (\\d+) damage for each heads\\.").matchEntire(text)
        if (coinPerDarkPokemon != null) {
            val perHeads = coinPerDarkPokemon.groupValues[1].toInt()
            if (raw.s("damage") !in listOf("${perHeads}×", "${perHeads}x")) return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CoinDamage(0, perHeads, perPokemonType = EnergyType.DARKNESS), text = text)
        }
        if (text == "This attack does 30 less damage for each {C} in your opponent's Active Pokémon's Retreat Cost.") {
            val base = raw.s("damage")?.removeSuffix("-")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(base)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OpponentActiveRetreatCostDamage(-30), text = text)
        }
        if (text == "If this Pokémon was damaged by an attack during your opponent's last turn, this attack does that much more damage.") {
            val base = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(base)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DamageEqualToAttackDamageTakenLastTurn, text = text)
        }
        val namedEnergyBonus = Regex("If this Pokémon has any (.+) Energy attached, this attack does (\\d+) more damage\\.").matchEntire(text)
        if (namedEnergyBonus != null) {
            val base = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(base)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnHasNamedEnergyDamageBonus(namedEnergyBonus.groupValues[1], namedEnergyBonus.groupValues[2].toInt()), text = text)
        }
        if (text == "Flip 3 coins. Put a number of cards up to the number of heads from your discard pile into your hand.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CoinDamage(3, 0) + Effect.RecoverUpToCoinHeads, text = text)
        }
        if (text == "Your opponent reveals their hand. Discard a card you find there.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RevealAndChooseOpponentHandDiscard, text = text)
        }
        if (text == "You may put 2 Energy attached to your opponent's Active Pokémon into their hand.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.ReturnOpponentActiveEnergyToHand(2), text = text)
        }
        if (text == "Choose 3 of your opponent's Benched Pokémon. If you do, shuffle all of your opponent's Benched Pokémon that you didn't choose, and all cards attached to those Pokémon, into their deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.ShuffleOpponentBenchedPokemonIntoDeck(3, keepSelected = true), text = text)
        }
        if (text == "Flip a coin until you get tails. For each heads, choose a random card from your opponent's hand. Your opponent reveals those cards and shuffles them into their deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CoinDamage(0, 0, untilTails = true) +
                Effect.ShuffleRandomOpponentHandPerCoinHead, text = text)
        }
        if (text == "Discard 2 Basic {G} Energy cards from your hand. If you can't discard 2 cards in this way, this attack does nothing.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiredHandBasicEnergyDiscard(EnergyType.GRASS, 2, 0), text = text)
        }
        val requiredSingleBasicEnergyDiscard = Regex("Discard a Basic \\{([GRFLWPMD])\\} Energy card from your hand\\. If you can't, this attack does nothing\\.").matchEntire(text)
        if (requiredSingleBasicEnergyDiscard != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiredHandBasicEnergyDiscard(symbolType(requiredSingleBasicEnergyDiscard.groupValues[1]) ?: return null, 1, 0), text = text)
        }
        val requiredHandEnergyDiscard = Regex("Discard (\\d+) Basic \\{([GRFLWPMD])\\} Energy cards from your hand\\. If you can't discard \\d+ cards in this way, this attack does nothing\\.").matchEntire(text)
        if (requiredHandEnergyDiscard != null) {
            val type = symbolType(requiredHandEnergyDiscard.groupValues[2]) ?: return null
            val count = requiredHandEnergyDiscard.groupValues[1].toInt()
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiredHandBasicEnergyDiscard(type, count, 0), text = text)
        }
        val hydrappleKnockout = Regex("Discard (\\d+) Basic \\{([GRFLWPMD])\\} Energy cards from your hand, and Knock Out your opponent's Active Pokémon\\. If you can't discard \\d+ cards in this way, this attack does nothing\\.").matchEntire(text)
        if (hydrappleKnockout != null) {
            val type = symbolType(hydrappleKnockout.groupValues[2]) ?: return null
            val count = hydrappleKnockout.groupValues[1].toInt()
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiredHandBasicEnergyDiscard(type, count, 0, knockOutActive = true) +
                Effect.KnockOutOpponentActiveIfPaidDiscard, text = text)
        }
        if (text == "Search your deck for up to 2 Transformation Tome cards, reveal them, and put them into your hand. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.SearchNamedTrainerToHand("Transformation Tome", 2), text = text)
        }
        if (text == "If you go first, you can use this attack during your first turn. Discard your hand and draw 5 cards.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.MayAttackFirstTurnIfStarting + Effect.DiscardHandDraw(5), text = text)
        }
        if (text == "Search your deck for a Basic {G} Energy card and attach it to 1 of your Pokémon. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AttachBasicEnergyFromDeckAny(1, EnergyType.GRASS), text = text)
        }
        if (text == "This attack does 50 damage to 1 of your opponent's Benched Pokémon. (Don't apply Weakness and Resistance for Benched Pokémon.)") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.TargetedDamage(50, benchedOnly = true), text = text)
        }
        if (text == "Put 2 damage counters on 1 of your opponent's Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.BenchDamageCounters(2, benchedOnly = false), text = text)
        }
        if (text == "If you have any Tera Pokémon on your Bench, this attack does 100 more damage.") {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnBenchTeraDamageBonus(100), text = text)
        }
        if (text == "Search your deck for a Stadium card, reveal it, and put it into your hand. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.SearchStadiumToHand, text = text)
        }
        if (text == "Discard a card from your hand. If you do, draw 2 cards.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardOneHandDraw(2), text = text)
        }
        val discardHandDraw = Regex("(You may )?Discard a card from your hand\\. If you do, draw (\\d+) cards?\\.").matchEntire(text)
        if (discardHandDraw != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardOneHandDraw(
                discardHandDraw.groupValues[2].toInt(), optional = discardHandDraw.groupValues[1].isNotEmpty()), text = text)
        }
        if (text == "If your opponent has 3 or more Benched Pokémon, this attack does 80 more damage.") {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OpponentBenchCountAtLeastDamageBonus(3, 80), text = text)
        }
        if (text == "This attack does 50 less damage for each {C} in your opponent's Active Pokémon's Retreat Cost.") {
            val baseDamage = raw.s("damage")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OpponentActiveRetreatCostDamage(-50), text = text)
        }
        if (text == "Both Active Pokémon are now Asleep. During your next turn, attacks used by this Pokémon do 100 more damage to your opponent's Active Pokémon (before applying Weakness and Resistance).") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.StatusCondition(SpecialCondition.ASLEEP) +
                Effect.StatusCondition(SpecialCondition.ASLEEP, onSelf = true) + Effect.NextTurnAllAttacksDamageBonus(100), text = text)
        }
        if (text == "This attack does 20 damage for each Basic {W} Energy card in your discard pile. Then, shuffle those cards into your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnDiscardBasicEnergyDamageAndShuffle(EnergyType.WATER, 20), text = text)
        }
        if (text == "Your opponent's Active Pokémon is now Confused. Put 8 damage counters instead of 3 on that Pokémon for this Special Condition.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.StatusCondition(SpecialCondition.CONFUSED, confusionDamageOnFail = 80), text = text)
        }
        if (text == "This attack does 30 damage for each {W} Energy attached to this Pokémon. Switch this Pokémon with 1 of your Benched Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnEnergyTypeCountDamage(EnergyType.WATER, 30) + Effect.SwitchOwn(), text = text)
        }
        val ownEnergyLeadDamage = Regex("If this Pokémon has more Energy attached than your opponent's Active Pokémon, this attack does (\\d+) more damage\\.").matchEntire(text)
        if (ownEnergyLeadDamage != null) {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnEnergyGreaterThanOpponentActiveDamageBonus(ownEnergyLeadDamage.groupValues[1].toInt()), text = text)
        }
        val opponentRetreatGate = Regex("If the Retreat Cost of your opponent's Active Pokémon is \\{C\\}\\{C\\} or more, this attack does (\\d+) more damage\\.").matchEntire(text)
        if (opponentRetreatGate != null) {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OpponentRetreatCostAtLeastDamageBonus(2, opponentRetreatGate.groupValues[1].toInt()), text = text)
        }
        val ownPrizeDamage = Regex("If you have exactly (\\d+) Prize cards remaining, this attack does (\\d+) more damage\\.").matchEntire(text)
        if (ownPrizeDamage != null) {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiresOwnRemainingPrizes(setOf(ownPrizeDamage.groupValues[1].toInt())) +
                Effect.Damage(ownPrizeDamage.groupValues[2].toInt()), text = text)
        }
        val specialEnergyBonus = Regex("If this Pokémon has any Special Energy attached, this attack does (\\d+) more damage\\.").matchEntire(text)
        if (specialEnergyBonus != null) {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnHasSpecialEnergyDamageBonus(specialEnergyBonus.groupValues[1].toInt()), text = text)
        }
        if (text == "Flip a coin. If heads, your opponent's Active Pokémon is now Paralyzed, and discard an Energy from that Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CoinDamage(1, 0) +
                Effect.StatusCondition(SpecialCondition.PARALYZED, onHeads = true) + Effect.DiscardDefendingEnergy(1, onHeads = true), text = text)
        }
        val flarenEnergySearch = Regex("""Search your deck for up to (\d+) Basic Energy cards and attach them to 1 of your Pokémon\. Then, shuffle your deck\.""").matchEntire(text)
        if (flarenEnergySearch != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AttachBasicEnergyFromDeckAny(flarenEnergySearch.groupValues[1].toInt()), text = text)
        }
        if (text == "Attach up to 2 Basic Energy cards from your discard pile to 1 of your Benched Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AttachBasicEnergyFromDiscardAny(2, benchedOnly = true), text = text)
        }
        val skarmoryTargeted = Regex("Shuffle all Energy attached to this Pokémon into your deck, and this attack does (\\d+) damage to 1 of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        if (skarmoryTargeted != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardAttackEnergy(0, all = true, shuffleIntoDeck = true) +
                Effect.TargetedDamage(skarmoryTargeted.groupValues[1].toInt(), benchedOnly = false), text = text)
        }
        if (text == "You can use this attack only if you go second, and only during your first turn. Your opponent can't play any Supporter cards from their hand during their next turn.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiresSecondPlayerFirstTurn + Effect.LockOpponentSupportersNextTurn, text = text)
        }
        if (text == "Discard the top card of each player's deck. This attack does 140 more damage for each Energy card discarded in this way.") {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardBothDeckTopEnergyDamage(140), text = text)
        }
        if (text == "This attack does 80 damage for each Prize card you have taken.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnPrizeTakenDamage(80), text = text)
        }
        if (text == "If you don't have Uxie and Azelf on your Bench, this attack does nothing.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiresNamedBenchedPokemon(setOf("Uxie", "Azelf")), text = text)
        }
        if (text == "This attack does 30 damage for each of your Ancient Pokémon in play.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnAncientPokemonCountDamage(30), text = text)
        }
        val ownEvolutionPokemonBonus = Regex("This attack does (\\d+) more damage for each of your Evolution Pokémon in play\\.").matchEntire(text)
        if (ownEvolutionPokemonBonus != null) {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnEvolutionPokemonCountDamage(ownEvolutionPokemonBonus.groupValues[1].toInt()), text = text)
        }
        if (text == "Heal 60 damage from this Pokémon. During your next turn, this Pokémon can't retreat.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.Heal(60) + Effect.PreventRetreatNextTurn, text = text)
        }
        if (text == "Put damage counters on your opponent's Active Pokémon until its remaining HP is 50.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DamageCountersUntilActiveHasRemainingHp(50), text = text)
        }
        val raticateBenchDamageCounters = Regex("This attack does (\\d+) damage for each damage counter on all of your Benched Rattata\\.").matchEntire(text)
        if (raticateBenchDamageCounters != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnNamedBenchDamageCounterDamage("Rattata", raticateBenchDamageCounters.groupValues[1].toInt()), text = text)
        }
        if (text == "Put damage counters on each of your opponent's Benched Pokémon until its remaining HP is 100.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DamageCountersUntilBenchedHasRemainingHp(100), text = text)
        }
        if (text == "If your opponent's Active Pokémon isn't a Pokémon ex, this attack does nothing. This attack's damage isn't affected by Weakness or Resistance.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiresOpponentActiveEx + Effect.IgnoreWeaknessResistance, text = text)
        }
        if (text == "This attack does 70 damage to 1 of your opponent's Pokémon. This attack's damage isn't affected by Weakness or Resistance.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.TargetedDamage(70) + Effect.IgnoreWeaknessResistance, text = text)
        }
        if (text == "This Pokémon is now Asleep. Heal 30 damage from it.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.StatusCondition(SpecialCondition.ASLEEP, onSelf = true) + Effect.Heal(30), text = text)
        }
        if (text == "Your opponent's Active Pokémon is now Burned and Poisoned. Switch this Pokémon with 1 of your Benched Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.StatusCondition(SpecialCondition.BURNED) +
                Effect.StatusCondition(SpecialCondition.POISONED) + Effect.SwitchOwn(), text = text)
        }
        if (text == "If Rosa's Encouragement is in your discard pile, this attack does 150 more damage.") {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnDiscardNamedCardDamage("Rosa's Encouragement", 150), text = text)
        }
        val opponentActiveToolBonus = Regex("If your opponent's Active Pokémon has a Pokémon Tool attached, this attack does (\\d+) more damage\\.").matchEntire(text)
        if (opponentActiveToolBonus != null) {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OpponentActiveHasToolDamageBonus(opponentActiveToolBonus.groupValues[1].toInt()), text = text)
        }
        if (text == "If your opponent's Active Pokémon already has any damage counters on it, this attack's base damage is 30.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.ReplaceDamageIfOpponentActiveDamaged(30), text = text)
        }
        if (text == "Flip a coin. If heads, your opponent's Active Pokémon is now Paralyzed and Poisoned. If tails, your opponent's Active Pokémon is now Confused.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CoinDamage(coins = 1, perHeads = 0) +
                Effect.StatusCondition(SpecialCondition.PARALYZED, onHeads = true) +
                Effect.StatusCondition(SpecialCondition.POISONED, onHeads = true) +
                Effect.StatusConditionOnTails(SpecialCondition.CONFUSED), text = text)
        }
        if (text == "Discard the top card of your opponent's deck. If you played an Ancient Supporter card from your hand during this turn, discard 3 more cards in this way.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardOpponentDeck(1) + Effect.DiscardOpponentDeckBonusIfAncientSupporter(3), text = text)
        }
        val targetedDamagePerOwnEnergy = Regex("This attack does (\\d+) damage to 1 of your opponent's Pokémon for each Energy attached to this Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        if (targetedDamagePerOwnEnergy != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.TargetedDamage(0, perOwnEnergy = targetedDamagePerOwnEnergy.groupValues[1].toInt()), text = text)
        }
        if (text == "Heal 100 damage from each of your Benched Pokémon.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.HealEachOwnPokemon(100, benchOnly = true), text = text)
        }
        val opponentRuleBoxDamage = Regex("This attack does (\\d+) damage to each of your opponent's Pokémon ex and Pokémon V\\. This attack's damage isn't affected by Weakness or Resistance\\.").matchEntire(text)
        if (opponentRuleBoxDamage != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OpponentRuleBoxDamage(opponentRuleBoxDamage.groupValues[1].toInt()) +
                Effect.IgnoreWeaknessResistance, text = text)
        }
        val opponentExDamage = Regex("This attack does (\\d+) damage to each of your opponent's Pokémon ex\\. This attack's damage isn't affected by Weakness or Resistance\\.").matchEntire(text)
        if (opponentExDamage != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AllOpponentRuleBoxDamage(opponentExDamage.groupValues[1].toInt()) + Effect.IgnoreWeaknessResistance, text = text)
        }
        if (text == "You may switch out your opponent's Active Pokémon to the Bench. (Your opponent chooses the new Active Pokémon.)") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OptionalSwitchOpponent, text = text)
        }
        val optionalStadiumBoost = Regex("You may discard a Stadium in play\\. If you do, this attack does (\\d+) more damage\\.").matchEntire(text)
        if (optionalStadiumBoost != null) {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OptionalStadiumDiscardDamageBonus(optionalStadiumBoost.groupValues[1].toInt()), text = text)
        }
        val optionalPrizeRevealBonus = Regex("You may turn 1 of your face-down Prize cards face up\\. If you do, this attack does (\\d+) more damage\\.\\s*\\(That Prize card remains face up for the rest of the game\\.\\)").matchEntire(text)
        if (optionalPrizeRevealBonus != null) {
            val damage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(damage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OptionalRevealPrizeDamageBonus(optionalPrizeRevealBonus.groupValues[1].toInt()), text = text)
        }
        val optionalDiscardAllEnergyBonus = Regex("You may discard all Energy from this Pokémon and have this attack do (\\d+) more damage\\.").matchEntire(text)
        if (optionalDiscardAllEnergyBonus != null) {
            val damage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(damage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardOwnEnergyDamageBonus(99,
                optionalDiscardAllEnergyBonus.groupValues[1].toInt(), attackerOnly = true, discardAll = true, optional = true), text = text)
        }
        val optionalSelfDamageBonus = Regex("You may do (\\d+) more damage\\. If you do, this Pokémon also does (\\d+) damage to itself\\.").matchEntire(text)
        if (optionalSelfDamageBonus != null) {
            val damage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(damage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OptionalSelfDamageBonus(
                optionalSelfDamageBonus.groupValues[1].toInt(), optionalSelfDamageBonus.groupValues[2].toInt()), text = text)
        }
        if (text == "You may discard a Stadium in play.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardStadium, text = text)
        }
        val anyTypedEnergyBonus = Regex("If this Pokémon has any (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy attached, this attack does (\\d+) more damage\\.").matchEntire(text)
        val specialEnergyPresenceBonus = Regex("If this Pokémon has any Special Energy attached, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentActiveAbilityBonus = Regex("If your opponent's Active Pokémon has an Ability, this attack does (\\d+) more damage\\.").matchEntire(text)
        if (anyTypedEnergyBonus != null || specialEnergyPresenceBonus != null || opponentActiveAbilityBonus != null) {
            val damage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(damage)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            val conditional = when {
                anyTypedEnergyBonus != null -> Effect.ConditionalEnergyDamage(
                    symbolType(anyTypedEnergyBonus.groupValues[1]) ?: return null, 1, anyTypedEnergyBonus.groupValues[2].toInt())
                specialEnergyPresenceBonus != null -> Effect.OwnHasSpecialEnergyDamageBonus(specialEnergyPresenceBonus.groupValues[1].toInt())
                else -> Effect.OpponentActiveHasAbilityDamageBonus(opponentActiveAbilityBonus!!.groupValues[1].toInt())
            }
            return compiled.copy(effects = compiled.effects + conditional, text = text)
        }
        val discardAllLightningPerEnergy = Regex("Discard all \\{L\\} Energy from this Pokémon\\. This attack does (\\d+) damage for each card you discarded in this way\\.").matchEntire(text)
        if (discardAllLightningPerEnergy != null) {
            if (raw.s("damage").orEmpty() !in listOf("${discardAllLightningPerEnergy.groupValues[1]}×", "${discardAllLightningPerEnergy.groupValues[1]}x")) return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardOwnEnergyDamageBonus(99,
                discardAllLightningPerEnergy.groupValues[1].toInt(), type = EnergyType.LIGHTNING, attackerOnly = true, discardAll = true), text = text)
        }
        val searchFirePokemonAndEnergy = Regex("Search your deck for up to (\\d+) in any combination of \\{R\\} Pokémon and Basic \\{R\\} Energy cards, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        if (searchFirePokemonAndEnergy != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.SearchFirePokemonAndEnergyToHand(
                searchFirePokemonAndEnergy.groupValues[1].toInt()), text = text)
        }
        val searchPokemonTypeToHand = Regex("Search your deck for up to (\\d+) (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Pokémon, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        if (searchPokemonTypeToHand != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.SearchPokemonTypeToHand(
                symbolType(searchPokemonTypeToHand.groupValues[2]) ?: return null,
                searchPokemonTypeToHand.groupValues[1].toInt()), text = text)
        }
        val drifblimEveryoneExplode = Regex("This attack does (\\d+) damage for each of your Drifloon and Drifblim in play\\. This attack also does (\\d+) damage to each of your Drifloon and Drifblim\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        if (drifblimEveryoneExplode != null) {
            val perPokemon = drifblimEveryoneExplode.groupValues[1].toInt()
            if (raw.s("damage") !in listOf("${perPokemon}×", "${perPokemon}x")) return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnSpeciesCountDamage("Drifloon", perPokemon) +
                Effect.OwnSpeciesCountDamage("Drifblim", perPokemon) + Effect.OwnNamedPokemonDamage(
                    setOf("Drifloon", "Drifblim"), drifblimEveryoneExplode.groupValues[2].toInt()), text = text)
        }
        if (text == "Your opponent reveals their hand. Put up to 2 Basic Pokémon you find there onto your opponent's Bench.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RevealOpponentHandBasicToBench(2), text = text)
        }
        val prizeGate = text == "If your opponent doesn't have exactly 3 or 4 Prize cards remaining, this attack does nothing."
        val equalHandGate = text == "If you don't have the same number of cards in your hand as your opponent, this attack does nothing."
        if (prizeGate || equalHandGate) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            val requirement = if (prizeGate) Effect.RequiresOpponentRemainingPrizes(setOf(3, 4)) else Effect.RequiresEqualHandSize
            return compiled.copy(effects = compiled.effects + requirement, text = text)
        }
        val recoilDoubleStatus = Regex("This Pokémon also does (\\d+) damage to itself\\. Your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused) and (Poisoned|Burned|Asleep|Paralyzed|Confused)\\.").matchEntire(text)
        if (recoilDoubleStatus != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            val first = SpecialCondition.valueOf(recoilDoubleStatus.groupValues[2].uppercase())
            val second = SpecialCondition.valueOf(recoilDoubleStatus.groupValues[3].uppercase())
            return compiled.copy(effects = compiled.effects + Effect.SelfDamage(recoilDoubleStatus.groupValues[1].toInt()) +
                Effect.StatusCondition(first) + Effect.StatusCondition(second), text = text)
        }
        val switchToTypedBench = Regex("Switch this Pokémon with 1 of your Benched (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Pokémon\\.").matchEntire(text)
        if (switchToTypedBench != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.SwitchOwn(
                targetType = symbolType(switchToTypedBench.groupValues[1]) ?: return null), text = text)
        }
        if (text == "This attack does 40 more damage for each Stage 2 Pokémon on your Bench.") {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply {
                put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(baseDamage))
            })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnBenchStageTwoCountDamage(40), text = text)
        }
        val discardAllAndParalyze = text == "Discard all Energy from this Pokémon. Your opponent's Active Pokémon is now Paralyzed."
        if (discardAllAndParalyze) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardAttackEnergy(0, all = true) +
                Effect.StatusCondition(SpecialCondition.PARALYZED), text = text)
        }
        val energyToBenchedType = Regex("Search your deck for an Energy card and attach it to 1 of your Benched (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Pokémon\\. Then, shuffle your deck\\.").matchEntire(text)
        if (energyToBenchedType != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.AttachEnergyFromDeckToPokemonType(
                symbolType(energyToBenchedType.groupValues[1]) ?: return null), text = text)
        }
        if (text == "If Beldum and Metang are on your Bench, this attack does 150 more damage.") {
            val baseDamage = raw.s("damage")?.removeSuffix("+")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply {
                put("effect", JsonPrimitive(""))
                put("damage", JsonPrimitive(baseDamage))
            })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiresNamedBenchedPokemon(setOf("Beldum", "Metang")) + Effect.Damage(150), text = text)
        }
        if (text == "During your next turn, this Pokémon can't use attacks.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RestrictNextTurn(), text = text)
        }
        val infernalSlash = Regex("Discard (\\d+) Basic \\{R\\} Energy cards from your hand\\. If you can't discard \\1 cards in this way, this attack does nothing\\.").matchEntire(text)
        if (text == "Discard all Energy from this Pokémon. This attack does 110 damage to 3 of your opponent's Pokémon. (Don't apply Weakness and Resistance for Benched Pokémon.)") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardAttackEnergy(0, all = true) +
                Effect.MultiTargetDamage(110, 3, ignoreWeaknessResistanceForBench = true), text = text)
        }
        if (raw.s("name") == "Infernal Slash" && infernalSlash != null) {
            val count = infernalSlash.groupValues[1].toInt()
            val damage = raw.s("damage")?.toIntOrNull() ?: return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RequiredHandBasicEnergyDiscard(EnergyType.FIRE, count, damage), text = text)
        }
        val restrictNamedAttack = Regex("During your next turn, this Pokémon can't use (.+)\\.").matchEntire(text)
        if (restrictNamedAttack != null) {
            if (restrictNamedAttack.groupValues[1] != raw.s("name")) return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.RestrictNextTurn("$id-a$index"), text = text)
        }
        if (text == "Flip a coin until you get tails. For each heads, discard the top card of your opponent's deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardOpponentDeckPerCoinHead, text = text)
        }
        val revealHandPokemonDamage = Regex("Reveal any number of (.+) from your hand, and this attack does (\\d+) damage for each card you revealed in this way\\.").matchEntire(text)
        if (revealHandPokemonDamage != null) {
            val perCard = revealHandPokemonDamage.groupValues[2].toInt()
            val damage = raw.s("damage")
            if (damage != "${perCard}×" && damage != "${perCard}x") return null
            val names = revealHandPokemonDamage.groupValues[1]
                .split(Regex(",\\s*and\\s+|,\\s*|\\s+and\\s+"))
                .map(String::trim).filter(String::isNotEmpty)
            if (names.isEmpty()) return null
            val rewritten = JsonObject(raw.toMutableMap().apply {
                put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0))
            })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OwnHandPokemonRevealDamage(perCard, names), text = text)
        }
        val echoedVoiceBonus = Regex("During your next turn, this Pokémon's (.+) attack does (\\d+) more damage \\(before applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (echoedVoiceBonus != null) {
            if (raw.s("name") != echoedVoiceBonus.groupValues[1]) return null
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.NextTurnAttackDamageBonus(
                echoedVoiceBonus.groupValues[1], echoedVoiceBonus.groupValues[2].toInt()), text = text)
        }
        val nextTurnAllAttacksBonus = Regex("During your next turn, attacks used by this Pokémon do (\\d+) more damage to your opponent's Active Pokémon \\(before applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (nextTurnAllAttacksBonus != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.NextTurnAllAttacksDamageBonus(nextTurnAllAttacksBonus.groupValues[1].toInt()), text = text)
        }
        val prizeBonusWithRecoil = Regex("This attack does (\\d+) more damage for each Prize card your opponent has taken\\. This Pokémon also does (\\d+) damage to itself\\.").matchEntire(text)
        if (prizeBonusWithRecoil != null) {
            val prizeBonusText = "This attack does ${prizeBonusWithRecoil.groupValues[1]} more damage for each Prize card your opponent has taken."
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive(prizeBonusText)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.SelfDamage(prizeBonusWithRecoil.groupValues[2].toInt()), text = text)
        }
        if (text == "This attack does 60 damage for each of your opponent's Pokémon ex in play." && raw.s("damage") == "60×") {
            val rewritten = JsonObject(raw.toMutableMap().apply {
                put("effect", JsonPrimitive("")); put("damage", JsonPrimitive(0))
            })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OpponentExCountDamage(60), text = text)
        }
        val opponentPokemonDamage = Regex("This attack does (\\d+) damage to each of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)(?: Your opponent's Active Pokémon is now Asleep\\.)?").matchEntire(text)
        if (opponentPokemonDamage != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            val extras = buildList {
                add(Effect.AllOpponentBenchDamage(opponentPokemonDamage.groupValues[1].toInt()))
                add(Effect.Damage(opponentPokemonDamage.groupValues[1].toInt()))
                if (text.contains("is now Asleep")) add(Effect.StatusCondition(SpecialCondition.ASLEEP))
            }
            return compiled.copy(effects = compiled.effects + extras, text = text)
        }
        if (text == "If you go first, you can use this attack during your first turn. Search your deck for a card and put it into your hand. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("You may search your deck for a card and put it into your hand. Then, shuffle your deck.")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = listOf(Effect.MayAttackFirstTurnIfStarting) + compiled.effects, text = text)
        }
        if (text == "If you go first, you can use this attack during your first turn. Search your deck for a card that evolves from this Pokémon and put it onto this Pokémon to evolve it. Then, shuffle your deck.") {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("Search your deck for a card that evolves from this Pokémon and put it onto this Pokémon to evolve it. Then, shuffle your deck.")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = listOf(Effect.MayAttackFirstTurnIfStarting) + compiled.effects, text = text)
        }
        val itemLockDiscardAll = text == "Discard all Energy from this Pokémon. During your opponent's next turn, they can't play any Item cards from their hand."
        if (itemLockDiscardAll) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardAttackEnergy(0, all = true) + Effect.LockOpponentItemsNextTurn, text = text)
        }
        val evolutionActiveBonusDiscard = Regex("If your opponent's Active Pokémon is an Evolution Pokémon, this attack does (\\d+) more damage, and discard all Energy from this Pokémon\\.").matchEntire(text)
        if (evolutionActiveBonusDiscard != null) {
            val bonusText = "If your opponent's Active Pokémon is an Evolution Pokémon, this attack does ${evolutionActiveBonusDiscard.groupValues[1]} more damage."
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive(bonusText)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.DiscardAttackEnergy(0, all = true), text = text)
        }
        val discardThenMultiTarget = Regex("Discard (\\d+) Energy from this Pokémon\\. This attack does (\\d+) damage to (\\d+) of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        if (discardThenMultiTarget != null) {
            val discardText = "Discard ${discardThenMultiTarget.groupValues[1]} Energy from this Pokémon."
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive(discardText)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.MultiTargetDamage(
                discardThenMultiTarget.groupValues[2].toInt(), discardThenMultiTarget.groupValues[3].toInt(), discardBeforeDamage = true), text = text)
        }
        val opponentPrizeBenchDamage = Regex("This attack also does (\\d+) damage to each of your opponent's Benched Pokémon for each Prize card your opponent has taken\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        if (opponentPrizeBenchDamage != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OpponentPrizeTakenBenchDamage(
                opponentPrizeBenchDamage.groupValues[1].toInt()), text = text)
        }
        val multiBenchDamageNoWeakness = Regex("This attack also does (\\d+) damage to (\\d+) of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        if (multiBenchDamageNoWeakness != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.MultiTargetDamage(
                multiBenchDamageNoWeakness.groupValues[1].toInt(), multiBenchDamageNoWeakness.groupValues[2].toInt(),
                ignoreWeaknessResistance = true, benchedOnly = true), text = text)
        }
        val discardThenSingleTarget = Regex("Discard (\\d+) Energy from this Pokémon, and this attack does (\\d+) damage to 1 of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        if (discardThenSingleTarget != null) {
            val discardText = "Discard ${discardThenSingleTarget.groupValues[1]} Energy from this Pokémon."
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive(discardText)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.MultiTargetDamage(
                discardThenSingleTarget.groupValues[2].toInt(), 1, discardBeforeDamage = true), text = text)
        }
        val optionalShuffleBenchDamage = Regex("You may shuffle (\\d+) Energy attached to this Pokémon into your deck\\. If you do, this attack also does (\\d+) damage to 1 of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        if (optionalShuffleBenchDamage != null) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.OptionalShuffleEnergyThenBenchDamage(
                optionalShuffleBenchDamage.groupValues[1].toInt(), optionalShuffleBenchDamage.groupValues[2].toInt()), text = text)
        }
        val secondPlayerFirstTurnPrefix = "If you go second, you can't use this attack during your first turn. "
        if (text.startsWith(secondPlayerFirstTurnPrefix)) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive(text.removePrefix(secondPlayerFirstTurnPrefix))) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = listOf(Effect.RestrictIfSecondPlayerFirstTurn) + compiled.effects, text = text)
        }
        val aggronCompound = Regex("This attack does (\\d+) damage for each of your Pokémon that has any damage counters on it\\. During your opponent's next turn, this Pokémon takes (\\d+) less damage from attacks \\(after applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (aggronCompound != null) {
            val baseText = "This attack does ${aggronCompound.groupValues[1]} damage for each of your Pokémon that has any damage counters on it."
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive(baseText)) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.ReduceDamageNextTurn(aggronCompound.groupValues[2].toInt()), text = text)
        }
        val damageText = raw.s("damage").orEmpty()
        val effects = mutableListOf<Effect>()
        val coinFixed = Regex("Flip (\\d{1,2}) coins?\\. This attack does (\\d+) damage for each heads\\.(?: If at least (\\d+) of them are heads, your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused)\\.)?").matchEntire(text)
        val coinUntilTails = Regex("Flip a coin until you get tails\\. This attack does (\\d+) damage for each heads\\.").matchEntire(text)
        val coinPerEnergy = Regex("Flip a coin for each Energy attached to this Pokémon\\. This attack does (\\d+) damage for each heads\\.").matchEntire(text)
        val coinPerEnergyBoth = Regex("Flip a coin for each Energy attached to both Active Pokémon\\. This attack does (\\d+) damage for each heads\\.").matchEntire(text)
        val coinBonus = Regex("Flip a coin\\. If heads, this attack does (\\d+) more damage\\.").matchEntire(text)
        val coinBonusMultiple = Regex("Flip (\\d{1,2}) coins?\\. This attack does (\\d+) more damage for each heads\\.").matchEntire(text)
        val coinBonusAllHeads = Regex("Flip (\\d{1,2}) coins?\\. If (?:both|all) of them are heads, this attack does (\\d+) more damage\\.").matchEntire(text)
        val coinBonusUntilTails = Regex("Flip a coin until you get tails\\. This attack does (\\d+) more damage for each heads\\.").matchEntire(text)
        val conditionalEnergy = Regex("If this Pokémon has (?:at least )?(\\d+) or more (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy attached, this attack does (\\d+) more damage\\.").matchEntire(text)
        val attackerPoisonBonus = Regex("If this Pokémon is Poisoned, this attack does (\\d+) more damage\\.").matchEntire(text)
        val attackerBurnOrPoisonBonus = Regex("If this Pokémon is Burned or Poisoned, this attack does (\\d+) more damage\\.").matchEntire(text)
        val conditionalExtraEnergy = Regex("If this Pokémon has at least (\\d+) extra Energy attached \\(in addition to this attack's cost\\), this attack does (\\d+) more damage\\.").matchEntire(text)
        val conditionalInPlayEnergy = Regex("If you have (?:at least )?(\\d+)(?: or more)? (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy in play, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownKnockoutBonus = Regex("If any of your Pokémon were Knocked Out by damage from an attack during your opponent's last turn, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownKnockoutBonusHop = Regex("If any of your Hop's Pokémon were Knocked Out by damage from an attack during your opponent's last turn, this attack does (\\d+) more damage\\.").matchEntire(text)
        val movedFromBenchBonus = Regex("If this Pokémon moved from your Bench to the Active Spot this turn, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownBenchNameBonus = Regex("If (.+) is on your Bench, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownBenchTypeBonus = Regex("If you have any (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Pokémon on your Bench, this attack does (\\d+) more damage\\.").matchEntire(text)
        val sameTypePokemonBonus = text == "If any of your Pokémon in play are the same type as any of your opponent's Pokémon in play, this attack does 120 more damage."
        val sameHandSizeDamageBonus = Regex("If you have the same number of cards in your hand as your opponent, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownHandEmptyDamageBonus = Regex("If you have no cards in your hand, this attack does (\\d+) more damage\\.").matchEntire(text)
        val allOwnBenchedDamagedBonus = Regex("If all of your Benched Pokémon have at least 1 damage counter on them, this attack does (\\d+) more damage\\.").matchEntire(text)
        val anyOwnBenchedDamagedBonus = Regex("If your Benched Pokémon have any damage counters on them, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentActiveSpecialConditionKnockout = text == "If your opponent's Active Pokémon is affected by a Special Condition, it is Knocked Out."
        val discardTeamRocketEnergyToDiscardOpponentActive = text == "Discard a Team Rocket's Energy from this Pokémon. If you do, discard your opponent's Active Pokémon and all attached cards."
        val opponentActiveExactlySixCountersKnockout = text == "If your opponent's Active Pokémon has exactly 6 damage counters on it, that Pokémon is Knocked Out."
        val knockOutLeastHpPokemon = text == "Choose a Pokémon in play (yours or your opponent's) that has the least HP remaining, except for this Pokémon, and it is Knocked Out."
        val protectAttackerOnDamageKnockout = text == "If your opponent's Pokémon is Knocked Out by damage from this attack, during your opponent's next turn, prevent all damage from and effects of attacks done to this Pokémon."
        val revealAndChooseOpponentHandDiscard = text == "Your opponent reveals their hand, and you discard a card you find there."
        val revealAndChooseOpponentHandBottomDeck = text == "Your opponent reveals their hand, and you choose a card you find there and put it on the bottom of their deck."
        val attachBasicGrassAndHealBenched = text == "Attach a Basic {G} Energy card from your hand to 1 of your Benched Pokémon. If you do, heal all damage from that Pokémon."
        val ownUndamagedBonus = Regex("If this Pokémon has no damage counters on it, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownHealedThisTurnBonus = Regex("If this Pokémon was healed during this turn, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownAnyDamageCounterBonus = Regex("If this Pokémon has any damage counters on it, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentExBonus = Regex("If your opponent's Active Pokémon is a Pokémon ex(?: or Pokémon V)?, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentTypeBonus = Regex("If your opponent's Active Pokémon is a (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Pokémon, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentSpecificConditionBonus = Regex("If your opponent's Active Pokémon is (Poisoned|Burned|Asleep|Paralyzed|Confused), this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentEvolutionBonus = Regex("If your opponent's Active Pokémon is an Evolution Pokémon, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentStageTwoBonus = Regex("If your opponent's Active Pokémon is a Stage 2 Pokémon, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownEnergyBonus = Regex("This attack does (\\d+) more damage for each (?:(?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) )?Energy attached to this Pokémon\\.").matchEntire(text)
        val opponentEnergyBonus = Regex("This attack does (\\d+) more damage for each Energy attached to your opponent's Active Pokémon\\.").matchEntire(text)
        val opponentActiveEnergyDamage = Regex("This attack does (\\d+) damage for each Energy attached to your opponent's Active Pokémon\\.").matchEntire(text)
        val sameActiveEnergyBonus = Regex("If this Pokémon and your opponent's Active Pokémon have the same amount of Energy attached, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentFieldEnergyDamage = Regex("This attack does (\\d+) damage for each Energy attached to all of your opponent's Pokémon\\.").matchEntire(text)
        val opponentTeamEnergyTypeDamage = Regex("This attack does (\\d+) damage for each (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy attached to all of your opponent's Pokémon\\.").matchEntire(text)
        val opponentSpecialConditionCountDamage = Regex("This attack does (\\d+) damage for each Special Condition affecting your opponent's Active Pokémon\\.").matchEntire(text)
        val opponentHandEnergyDamage = Regex("Your opponent reveals their hand, and this attack does (\\d+) damage for each Energy card you find there\\.").matchEntire(text)
        val opponentHandTrainerDamage = Regex("Your opponent reveals their hand\\. This attack does (\\d+) damage for each Trainer card you find there\\.").matchEntire(text)
        val opponentRetreatCostBonus = Regex("This attack does (\\d+) more damage for each \\{C\\} in your opponent's Active Pokémon's Retreat Cost\\.").matchEntire(text)
        val ownBenchEnergyBonus = Regex("This attack does (\\d+) more damage for each of your Benched Pokémon that has any (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy attached\\.").matchEntire(text)
        val opponentConditionBonus = Regex("If your opponent's Active Pokémon is affected by a Special Condition, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentActiveDamagedBonus = Regex("If your opponent's Active Pokémon already has any damage counters on it, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentActiveDamageCounters = Regex("This attack does (\\d+) more damage for each damage counter on your opponent's Active Pokémon\\.").matchEntire(text)
        val opponentActiveDamageCountersTimes = Regex("This attack does (\\d+) damage for each damage counter on your opponent's Active Pokémon\\.").matchEntire(text)
        val opponentHandCountDamage = Regex("This attack does (\\d+) damage for each card in your opponent's hand\\.").matchEntire(text)
        val opponentBasicDiscardEnergyDamage = Regex("This attack does (\\d+) damage for each Basic Energy card in your opponent's discard pile\\.").matchEntire(text)
        val opponentItemDiscardDamage = Regex("This attack does (\\d+) damage for each Item card in your opponent's discard pile\\.").matchEntire(text)
        val opponentHandAtMostDamage = Regex("If your opponent has (\\d+) or fewer cards in their hand, this attack does (\\d+) more damage\\.").matchEntire(text)
        val healSelfByDamageDealt = text == "Heal from this Pokémon the same amount of damage you did to your opponent's Active Pokémon."
        val ownDiscardEnergyDamage = Regex("This attack does (\\d+) more damage for each Energy card in your discard pile\\.").matchEntire(text)
        val discardEnergyThresholdDamage = Regex("If you have (\\d+) or more Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy cards? in your discard pile, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownDiscardAbilityBonus = Regex("If you have (\\d+) or more Pokémon that have the (.+) Ability in your discard pile, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownDiscardNamedCardDamage = Regex("This attack does (\\d+) more damage for each (.+) card in your discard pile\\.").matchEntire(text)
        val ownDiscardNamedPrefixFormula = Regex("This attack does (\\d+) damage for each Supporter card that has \"([^\"]+)\" in its name in your discard pile\\.").matchEntire(text)
        val ownInPlayEnergyDamage = Regex("This attack does (\\d+) damage for each Energy attached to all Pokémon\\.").matchEntire(text)
        val ownSpecialEnergyDamage = Regex("This attack does (\\d+) damage for each Special Energy card attached to this Pokémon\\.").matchEntire(text)
        val ownTypedEnergyDamage = Regex("This attack does (\\d+) damage for each (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy attached to this Pokémon\\.").matchEntire(text)
        val ownEnergyDamage = Regex("This attack does (\\d+) damage for each Energy attached to this Pokémon\\.").matchEntire(text)
        val opponentPrizeTakenDamage = Regex("This attack does (\\d+) more damage for each Prize card your opponent has taken\\.").matchEntire(text)
        val opponentPrizeTakenDamageWithDiscard = Regex("This attack does (\\d+) more damage for each Prize card your opponent has taken\\. Discard an Energy from this Pokémon\\.").matchEntire(text)
        val opponentPrizeTakenDamageTimes = Regex("This attack does (\\d+) damage for each Prize card your opponent has taken\\.").matchEntire(text)
        val opponentPrizeCountDamage = Regex("If your opponent has (\\d+) or fewer Prize cards remaining, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownPrizeLeadDamage = Regex("If you have more Prize cards remaining than your opponent, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentNoRetreatDamage = Regex("If your opponent's Active Pokémon has no Retreat Cost, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentResistanceDamage = Regex("If your opponent's Active Pokémon has (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Resistance, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownToolAttachedDamage = Regex("If this Pokémon has a Pokémon Tool attached, this attack does (\\d+) more damage\\.").matchEntire(text)
        val benchedDamage = Regex("This attack does (\\d+) more damage for each Benched Pokémon \\(both yours and your opponent's\\)\\.").matchEntire(text)
        val ownInPlayDamage = Regex("This attack does (\\d+) damage for each of your Pokémon in play\\.").matchEntire(text)
        val opponentBenchCountDamage = Regex("This attack does (\\d+) damage for each of your opponent's Benched Pokémon\\.").matchEntire(text)
        val bothPlayersNamedDamage = Regex("This attack does (\\d+) damage for each Pokémon in play that has \"(.+)\" or \"(.+)\" in its name \\(both yours and your opponent's\\)\\.").matchEntire(text)
        val ownTypePokemonDamage = Regex("This attack does (\\d+) damage for each of your (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Pokémon in play\\.").matchEntire(text)
        val ownTeamRocketCountDamage = Regex("This attack does (\\d+) damage for each of your Team Rocket's Pokémon in play\\.").matchEntire(text)
        val ownNamedPrefixDamage = Regex("This attack does (\\d+) damage for each of your (.+)'s Pokémon in play\\.").matchEntire(text)
        val ownAttackNameDamage = Regex("This attack does (\\d+) damage for each of your Pokémon in play that has the (.+) attack\\.").matchEntire(text)
        val ownSpeciesDamage = Regex("This attack does (\\d+) damage for each of your (.+?) and (.+) in play\\.").matchEntire(text)
        val ownBenchDamage = Regex("This attack does (\\d+) damage for each of your Benched Pokémon\\.").matchEntire(text)
        val ownBasicPokemonDamage = Regex("This attack does (\\d+) damage for each of your Basic Pokémon in play\\.").matchEntire(text)
        val ownStageOnePokemonDamage = Regex("This attack does (\\d+) damage for each of your Stage 1 Pokémon in play\\.").matchEntire(text)
        val ownBenchDamageBonus = Regex("This attack does (\\d+) more damage for each of your Benched Pokémon\\.").matchEntire(text)
        val opponentBenchDamageBonus = Regex("This attack does (\\d+) more damage for each of your opponent's Benched Pokémon\\.").matchEntire(text)
        val ownDamagedPokemon = Regex("This attack does (\\d+) damage for each of your Pokémon that has any damage counters on it\\.").matchEntire(text)
        val ownDamagedBenchedPokemonBonus = Regex("This attack does (\\d+) more damage for each of your Benched Pokémon that has any damage counters on it\\.").matchEntire(text)
        val ownDamageCounters = Regex("This attack does (\\d+) damage for each damage counter on this Pokémon\\.").matchEntire(text)
        val ownDamageCounterBonus = Regex("This attack does (\\d+) more damage for each damage counter on this Pokémon\\.").matchEntire(text)
        val conditionalOwnDamageCounterBonus = Regex("If this Pokémon has (\\d+) or more damage counters on it, this attack does (\\d+) more damage\\. This attack's damage isn't affected by Weakness\\.").matchEntire(text)
        val ownCynthiaBenchCounterDamage = Regex("This attack does (\\d+) damage for each damage counter on all of your Benched Cynthia's Pokémon\\. This attack's damage isn't affected by Weakness\\.").matchEntire(text)
        val ownFightingBenchCounterDamage = Regex("This attack does (\\d+) damage for each damage counter on all of your Benched \\{F\\} Pokémon\\.").matchEntire(text)
        val ownDamageCounterPenalty = Regex("This attack does (\\d+) less damage for each damage counter on this Pokémon\\.").matchEntire(text)
        val opponentFieldDamageCounters = Regex("This attack does (\\d+) more damage for each damage counter on all of your opponent's Pokémon\\.").matchEntire(text)
        val ownPsychicEnergyDamage = Regex("This attack does (\\d+) more damage for each \\{P\\} Energy attached to this Pokémon\\.").matchEntire(text)
        val ownTeamEnergyTypeDamage = Regex("This attack does (\\d+) damage for each (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy attached to all of your Pokémon\\.").matchEntire(text)
        val ownTeamEnergyTypeBonus = Regex("This attack does (\\d+) more damage for each (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy attached to all of your Pokémon\\.").matchEntire(text)
        val ownHandCounters = Regex("Place (\\d+) damage counters on your opponent's Active Pokémon for each card in your hand\\.").matchEntire(text)
        val ownToolDamage = Regex("This attack does (\\d+) damage for each Pokémon Tool attached to all of your Pokémon\\.").matchEntire(text)
        val allToolDamage = Regex("This attack does (\\d+) damage for each Pokémon Tool attached to all Pokémon\\.").matchEntire(text)
        val discardTopEnergyDamage = Regex("Discard the top (\\d+) cards of your deck, and this attack does (\\d+) damage for each Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy card (?:you|that you) discarded in this way\\.").matchEntire(text)
        val bothActiveEnergyDamage = Regex("This attack does (\\d+) more damage for each Energy attached to both Active Pokémon\\.").matchEntire(text)
        val moveOpponentDamageCounters = text == "Your opponent's Active Pokémon is now Confused. You may move any number of damage counters from your opponent's Pokémon to their other Pokémon in any way you like."
        val pullOpponentBench = text == "Switch in 1 of your opponent's Benched Pokémon to the Active Spot."
        val dragOff = Regex("Switch in 1 of your opponent's Benched Pokémon to the Active Spot\\. This attack does (\\d+) damage to the new Active Pokémon\\.").matchEntire(text)
        val recoverSupporter = text == "Put a Supporter card from your discard pile into your hand."
        val benchSearch = Regex("Search your deck for up to (\\d+) Basic Pokémon and put them onto your Bench\\. Then, shuffle your deck\\.|Search your deck for a Basic Pokémon and put it onto your Bench\\. Then, shuffle your deck\\.").matchEntire(text)
        val namedPokemonBenchSearch = Regex("Search your deck for up to (\\d+) (.+?) and put them onto your Bench\\. Then, shuffle your deck\\.").matchEntire(text)
        val recoverNamedPokemon = Regex("Put up to (\\d+) (.+?) from your discard pile onto your Bench\\.").matchEntire(text)
        val recoverPokemonType = Regex("Put up to (\\d+) (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Pokémon from your discard pile onto your Bench\\.").matchEntire(text)
        val recoverPokemonToHand = Regex("Put (?:up to )?(\\d+|a) Pokémon from your discard pile into your hand\\.").matchEntire(text)
        val rotomSearch = Regex("""You may search your deck for any number of Pokémon that have "Rotom" in their name and put them onto your Bench\. Then, shuffle your deck\.""").matchEntire(text)
        val matchingEnergySearch = Regex("Search your deck for up to (\\d+) Pokémon that are the same type as any Basic Energy attached to this Pokémon, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val basicEnergyToHandSearch = Regex("Search your deck for up to (\\d+) Basic Energy cards, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val pokemonSearchToHand = Regex("Search your deck for a Pokémon, reveal it, and put it into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val toolSearchToHand = Regex("Search your deck for a Pokémon Tool card, reveal it, and put it into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val namedTrainerSearchToHand = Regex("You may search your deck for any number of ([A-Za-z'’ -]+) cards, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val pokemonPrefixSearchToHand = Regex("Search your deck for up to (\\d+) ([A-Za-z'’ -]+) Pokémon, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val pokemonSearchUpToToHand = Regex("Search your deck for up to (\\d+) Pokémon, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val typePokemonAndStadiumSearch = Regex("Search your deck for up to (\\d+) in any combination of (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Pokémon and Stadium cards, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val anyCardSearchToHand = text == "You may search your deck for a card and put it into your hand. Then, shuffle your deck."
        val anyCardsSearchToHand = Regex("(?:You may )?[Ss]earch your deck for up to (\\d+) cards and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val supporterSearchToHand = text == "Search your deck for a Supporter card, reveal it, and put it into your hand. Then, shuffle your deck."
        val itemSearchToHand = text == "Search your deck for an Item card, reveal it, and put it into your hand. Then, shuffle your deck."
        val chooseOpponentAttackToRestrict = text == "Choose 1 of your opponent's Active Pokémon's attacks. During your opponent's next turn, that Pokémon can't use that attack."
        val copyBenchedNAttack = text == "Choose 1 of your Benched N's Pokémon's attacks and use it as this attack."
        val copyOpponentActiveTeraAttack = text == "Choose 1 of your opponent's Active Tera Pokémon's attacks and use it as this attack."
        val copyOpponentActiveAttack = text == "Choose 1 of your opponent's Active Pokémon's attacks and use it as this attack."
        val copyAttackFromOpponentDeckTop = text == "Reveal the top 10 cards of your opponent's deck. You may choose an attack from a Pokémon you find there and use it as this attack. Shuffle the revealed cards into your opponent's deck."
        val copyAttackFromOwnDeckTop = text == "Discard the top card of your deck, and if that card is a Pokémon that doesn't have a Rule Box, choose 1 of its attacks and use it as this attack. (Pokémon ex, Pokémon V, etc. have Rule Boxes.)"
        if (copyAttackFromOwnDeckTop) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CopyAttackFromOwnDeckTop, text = text)
        }
        if (copyAttackFromOpponentDeckTop) {
            val rewritten = JsonObject(raw.toMutableMap().apply { put("effect", JsonPrimitive("")) })
            val compiled = compileAttack(id, index, rewritten) ?: return null
            return compiled.copy(effects = compiled.effects + Effect.CopyAttackFromOpponentDeckTop, text = text)
        }
        val attachAnyBasicEnergy = text == "You may attach any number of Basic Energy cards from your hand to your Pokémon in any way you like."
        val attachUpToTwoPsychicFromHand = text == "Attach up to 2 Basic {P} Energy cards from your hand to your Pokémon in any way you like."
        val attachOneBasicEnergy = text == "Attach a Basic Energy card from your hand to 1 of your Pokémon."
        val shuffleOwnBenchIntoDeck = text == "Shuffle 1 of your Benched Pokémon and all attached cards into your deck."
        val sylveonAngeliteShuffle = Regex("Choose (\\d+) of your opponent's Benched Pokémon\\. Shuffle those Pokémon and all attached cards into your opponent's deck\\. If 1 of your Pokémon used Angelite during your last turn, this attack can't be used\\.").matchEntire(text)
        val coinShuffleOpponentBench = text == "Flip a coin. If heads, choose 1 of your opponent's Benched Pokémon. Shuffle that Pokémon and all attached cards into their deck."
        val knockOutAtSixCounters = text == "Knock Out 1 of your opponent's Pokémon that has exactly 6 damage counters on it."
        val devolveOpponentActive = text == "If your opponent's Active Pokémon is an evolved Pokémon, devolve it by putting the highest Stage Evolution card on it into your opponent's hand."
        val devolveOpponentAllIntoDeck = text == "Devolve each of your opponent's evolved Pokémon by shuffling the highest Stage Evolution card on it into your opponent's deck."
        val discardStadium = text == "Discard a Stadium in play."
        val stadiumDamageBonus = Regex("If a Stadium is in play, this attack does (\\d+) more damage\\. Then, discard that Stadium\\.").matchEntire(text)
        val stadiumDamageOnlyBonus = Regex("If you have a Stadium in play, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentTypePresentBonus = Regex("If your opponent has any \\{([GRFLWPMD])\\} Pokémon in play, this attack does (\\d+) more damage\\.").matchEntire(text)
        val returnSelfAndAttachments = text == "Put this Pokémon and all attached cards into your hand."
        val shuffleSelfAndAttachmentsIntoDeck = text == "Shuffle this Pokémon and all attached cards into your deck."
        val optionalReturnSelf = text == "You may put this Pokémon into your hand. (Discard all cards attached to this Pokémon.)"
        val attachBasicEnergyFromDeckAny = Regex("Search your deck for up to (\\d+) Basic Energy cards and attach them to your Pokémon in any way you like\\. Then, shuffle your deck\\.").matchEntire(text)
        val attachEnergyFromTopDeck = text == "Look at the top 20 cards of your deck and attach any number of Energy cards you find there to your Pokémon in any way you like. Shuffle the other cards back into your deck."
        val attachBasicEnergyFromDeckBench = Regex("Search your deck for up to (\\d+) Basic Energy cards and attach them to your Benched Pokémon in any way you like\\. Then, shuffle your deck\\.").matchEntire(text)
        val attachTypedEnergyFromDeckBench = Regex("Search your deck for up to (\\d+) Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy cards and attach them to your Benched Pokémon in any way you like\\. Then, shuffle your deck\\.").matchEntire(text)
        val attachTypedEnergyFromDiscardBench = Regex("Attach up to (\\d+) Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy cards from your discard pile to your Benched Pokémon in any way you like\\.").matchEntire(text)
        val attachBasicEnergyFromDiscardToBenched = text == "Attach a Basic Energy card from your discard pile to 1 of your Benched Pokémon."
        val attachFightingEnergyFromDiscardEachBench = text == "Attach a Basic {F} Energy card from your discard pile to each of your Benched Pokémon."
        val attachLightningEnergyFromDiscardByOpponentEnergyCount = text == "Choose Basic {L} Energy cards from your discard pile up to the amount of Energy attached to all of your opponent's Pokémon and attach them to your {L} Pokémon in any way you like."
        val attachBasicFightingEnergyFromDiscardToSelf = Regex("Attach up to (\\d+) Basic \\{F\\} Energy cards from your discard pile to this Pokémon\\.").matchEntire(text)
        val attachOneBasicFightingEnergyFromDiscardToSelf = text == "Attach a Basic {F} Energy card from your discard pile to this Pokémon."
        val attachEnergyToEachBench = Regex("For each of your Benched Pokémon, search your deck for a Basic (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy card and attach it to that Pokémon\\. Then, shuffle your deck\\.").matchEntire(text)
        val coinKnockOutBasic = text == "Flip a coin. If heads, Knock Out your opponent's Active Basic Pokémon. If tails, Knock Out 1 of your opponent's Benched Basic Pokémon."
        val opponentBasicKnockout = text == "If your opponent's Active Pokémon is a Basic Pokémon, it is Knocked Out."
        val coinStatus = Regex("Flip a coin\\. If heads, your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused)\\.").matchEntire(text)
        val opponentTypeStatus = Regex("If your opponent's Active Pokémon is a \\{([GRFLWPMDN])\\} Pokémon, it is now (Poisoned|Burned|Asleep|Paralyzed|Confused)\\.").matchEntire(text)
        val strongPoison = Regex("Your opponent's Active Pokémon is now Poisoned\\. During Pokémon Checkup, (?:put|place) (\\d+) damage counters on that Pokémon instead of 1\\.").matchEntire(text)
        val status = Regex("Your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused)\\.").matchEntire(text)
        val discardDefendingCoin = Regex("Flip a coin\\. If heads, discard (an|a|1|2) Energy from your opponent's Active Pokémon\\.").matchEntire(text)
        val discardDefendingPerHeads = Regex("Flip (\\d+) coins?\\. For each heads, discard (an|a|1|2) Energy from your opponent's Active Pokémon\\.").matchEntire(text)
        val coin = coinFixed != null || coinUntilTails != null || coinPerEnergy != null || coinPerEnergyBoth != null || discardDefendingPerHeads != null
        val discardDefending = Regex("Discard (an|a|1|2) (?:(\\{[GRFLWPMD]\\}|Special) )?Energy from your opponent's Active Pokémon\\.").matchEntire(text)
        val discardAllOpponentSpecialEnergy = text == "Discard all Special Energy from all of your opponent's Pokémon."
        val discardOpponentToolsAndSpecialEnergy = text == "Discard all Pokémon Tools and Special Energy from all of your opponent's Pokémon."
        val benchDamage = Regex("This attack also does (\\d+) damage to 1 of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val allOpponentBenchDamage = Regex("This attack (?:also )?does (\\d+) damage to each of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val bothBenchesDamage = Regex("This attack also does (\\d+) damage to each Benched Pokémon \\(both yours and your opponent's\\)\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val ownBenchAttackDamage = Regex("This attack also does (\\d+) damage to each of your Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val damagedBenchDamage = Regex("This attack also does (\\d+) damage to 1 of your opponent's Benched Pokémon that has any damage counters on it\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val selectedPokemonDamage = Regex("This attack does (\\d+) damage to 1 of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val discardEnergyBenchDamage = Regex("Discard (\\d+) Energy from this Pokémon(?:\\.|, and) (?:this|This) attack also does (\\d+) damage to 1 of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val discardAllEnergyThenBenchDamage = Regex("Discard all Energy from this Pokémon, and this attack also does (\\d+) damage to 1 of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val discardFireTargetedBench = Regex("Discard all \\{R\\} Energy from this Pokémon, and this attack does (\\d+) damage to 1 of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val discardAllTargeted = Regex("Discard all Energy from this Pokémon, and this attack does (\\d+) damage to 1 of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val discardAllTargetedAfter = Regex("Discard all Energy from this Pokémon\\. This attack does (\\d+) damage to 1 of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val benchDamageCounters = Regex("Place (\\d+) damage counters on 1 of your opponent's Benched Pokémon\\.").matchEntire(text)
        val placeDamageCounter = text == "Place 1 damage counter on 1 of your opponent's Pokémon."
        val damagedOpponentCounters = Regex("Put (\\d+) damage counters on each of your opponent's Pokémon that has any damage counters on it\\.").matchEntire(text)
        val spreadBenchDamageCounters = Regex("Put (\\d+) damage counters on your opponent's Benched Pokémon in any way you like\\.").matchEntire(text)
        val spreadOpponentDamageCounters = Regex("Put (\\d+) damage counters on your opponent's Pokémon in any way you like\\.").matchEntire(text)
        val grassDiscardEnergyTargetCounters = Regex("Put 2 damage counters on 1 of your opponent's Pokémon for each Basic \\{G\\} Energy card in your discard pile\\. Then, shuffle those Energy cards into your deck\\.").matches(text)
        val multiTargetDamage = Regex("Discard (\\d+) Energy from this Pokémon, and this attack does (\\d+) damage to each of (\\d+) of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val splitBombDamage = Regex("This attack does (\\d+) damage to each of (\\d+) of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val coinDamageEachPokemon = Regex("For each of your opponent's Pokémon, flip a coin\\. If heads, this attack does (\\d+) damage to that Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val multiTargetDamageIgnoringEffects = Regex("This attack does (\\d+) damage to (\\d+) of your opponent's Pokémon\\. This attack's damage isn't affected by Weakness or Resistance, or by any effects on those Pokémon\\.").matchEntire(text)
        val selectedMultiTargetDamage = Regex("This attack does (\\d+) damage to (\\d+) of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val knockOutBothActive = text == "Both Active Pokémon are Knocked Out."
        val repeatedMultiTargetDamage = Regex("Choose 1 of your opponent's Pokémon (\\d+) times\\. \\(You can choose the same Pokémon more than once\\.\\) For each time you chose a Pokémon, do (\\d+) damage to it\\. This damage isn't affected by Weakness or Resistance\\.").matchEntire(text)
        val opponentDiscard = Regex("Discard a random card from your opponent's hand\\.").matchEntire(text)
        val opponentHandAndDeckDiscard = text == "Discard a random card from your opponent's hand. Discard the top card of your opponent's deck."
        val opponentChoosesHandDiscard = Regex("Your opponent discards (\\d+|a) cards? from their hand\\.").matchEntire(text)
        val salazzleHandDiscard = text == "Your opponent discards a card from their hand. If this Pokémon evolved from Salandit during this turn, your opponent discards 2 more cards."
        val opponentChoosesHandShuffle = Regex("Your opponent chooses (\\d+) cards from their hand and shuffles those cards into their deck\\.").matchEntire(text)
        val opponentShuffleRandom = Regex("Choose a random card from your opponent's hand(?:, and your opponent reveals that card and shuffles it into their deck|\\. Your opponent reveals that card and shuffles it into their deck)\\.").matchEntire(text)
        val moveEnergy = Regex("Move an Energy from this Pokémon to (\\d+) of your Benched Pokémon\\.").matchEntire(text)
        val moveEnergyToOneBench = Regex("Move (\\d+) Energy from this Pokémon to 1 of your Benched Pokémon\\.").matchEntire(text)
        val moveAllEnergyToBench = text == "Move all Energy from this Pokémon to 1 of your Benched Pokémon."
        val returnEnergyToHand = text == "Put an Energy attached to this Pokémon into your hand."
        val optionalWaterEnergyDamageBonus = Regex("You may put a \\{W\\} Energy attached to this Pokémon into your hand and have this attack do (\\d+) more damage\\.").matchEntire(text)
        val optionalLightningParalysis = Regex("You may discard (\\d+) \\{L\\} Energy from this Pokémon and make your opponent's Active Pokémon Paralyzed\\.").matchEntire(text)
        val confusePoisonThenSwitch = text == "Your opponent's Active Pokémon is now Confused and Poisoned. Switch this Pokémon with 1 of your Benched Pokémon."
        val statusThenRetreat = Regex("Your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused)\\. During your opponent's next turn, (?:that Pokémon|the Defending Pokémon) can't retreat\\.").matchEntire(text)
        val exactHandSize = Regex("If you don't have exactly (\\d+) cards? in your hand, this attack does nothing\\.").matchEntire(text)
        val requiresUndamagedOpponentActive = text == "If your opponent's Active Pokémon has no damage counters on it before this attack does damage, this attack does nothing."
        val requiresOpponentBurned = text == "If your opponent's Active Pokémon isn't Burned, this attack does nothing."
        val requiredOpponentCondition = Regex("If your opponent's Active Pokémon isn't (Poisoned|Burned|Asleep|Paralyzed|Confused), this attack does nothing\\.").matchEntire(text)
        val requiresStadium = text == "If there is no Stadium in play, this attack does nothing."
        val requiresOwnBenchCountAtLeast = Regex("If you have (\\d+) or fewer Benched Pokémon, this attack does nothing\\.").matchEntire(text)
        val opponentDeckDiscard = Regex("Discard the top (?:card|([2-5]) cards) of your opponent's deck\\.").matchEntire(text)
        val reorderOpponentDeckTop = Regex("Look at the top ([1-5]) cards of your opponent's deck and put them back in any order\\.").matchEntire(text)
        val discardOwnDeckTop = text == "Discard the top card of your deck."
        val discardOwnDeckTopCards = Regex("Discard the top (\\d+) cards of your deck\\.").matchEntire(text)
        val optionalDiscardOwnDeckTop = text == "Look at the top card of your deck. You may discard that card."
        val lookTopChooseAndDiscardRest = Regex("Look at the top (\\d+) cards of your deck and put (\\d+) of them into your hand\\. Discard the other cards\\.").matchEntire(text)
        val searchEvolutionAny = text == "Search your deck for a card that evolves from 1 of your Pokémon and put it onto that Pokémon to evolve it. Then, shuffle your deck."
        val searchEvolutionSelf = text == "Search your deck for a card that evolves from this Pokémon and put it onto this Pokémon to evolve it. Then, shuffle your deck."
        val searchEvolutionEachBenchedPokemon = text == "For each of your Benched Pokémon, search your deck for a card that evolves from that Pokémon and put it onto that Pokémon to evolve it. Then, shuffle your deck."
        val revealOpponentHand = text == "Your opponent reveals their hand."
        val delayedDefendingCounters = Regex("At the end of your opponent's next turn, put (\\d+) damage counters on the Defending Pokémon\\.").matchEntire(text)
        val benchEnergyBonus = Regex("You may discard up to (\\d+) (Basic )?Energy from your Benched Pokémon\\. This attack does (\\d+) more damage for each card you discarded in this way\\.").matchEntire(text)
        val handEnergyBonus = Regex("You may discard up to (\\d+) Energy cards from your hand, and this attack does (\\d+) more damage for each card you discarded in this way\\.").matchEntire(text)
        val discardTypedAttackerEnergy = Regex("Discard up to (\\d+) (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy from this Pokémon\\. This attack does (\\d+) damage for each card you discarded in this way\\.").matchEntire(text)
        val discardAnyAttackerEnergy = Regex("Discard up to (\\d+) Energy cards from this Pokémon, and this attack does (\\d+) damage for each card you discarded in this way\\.").matchEntire(text)
        val discardAllAttackerEnergyBonus = Regex("You may discard all Energy from this Pokémon and have this attack do (\\d+) more damage\\.").matchEntire(text)
        val discardAnyBasicEnergyFromTeam = Regex("You may discard any amount of Basic Energy from your Pokémon\\. This attack does (\\d+) damage for each card you discarded in this way\\.").matchEntire(text)
        val discardAnyTypedEnergyFromTeam = Regex("Discard any amount of (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy from among your Pokémon, and this attack does (\\d+) damage for each card you discarded in this way\\.").matchEntire(text)
        val discardTypedEnergyFromTeam = Regex("Discard up to (\\d+) (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) Energy cards from your Pokémon\\. This attack does (\\d+) damage for each card you discarded in this way\\.").matchEntire(text)
        val reduceDamage = Regex("During your opponent's next turn, this Pokémon takes (\\d+) less damage from attacks \\(after applying Weakness and Resistance\\)\\.").matchEntire(text)
        val retaliationCounters = Regex("During your opponent's next turn, if this Pokémon is damaged by an attack \\(even if this Pokémon is Knocked Out\\), put (\\d+) damage counters on the Attacking Pokémon\\.").matchEntire(text)
        val ironBoulderRetaliationCounters = Regex("During your opponent's next turn, if this Pokémon is damaged by an attack \\(even if it is Knocked Out\\), put (\\d+) damage counters on the Attacking Pokémon\\.").matchEntire(text)
        val reduceEvolutionDamage = Regex("During your opponent's next turn, this Pokémon takes (\\d+) less damage from attacks from Evolution Pokémon \\(after applying Weakness and Resistance\\)\\.").matchEntire(text)
        val preventEvolutionDamage = text == "During your opponent's next turn, prevent all damage done to this Pokémon by attacks from Evolution Pokémon."
        val preventAbilityPokemonDamage = text == "During your opponent's next turn, prevent all damage done to this Pokémon by attacks from Pokémon that have an Ability."
        val preventBurnedPokemonDamage = text == "During your opponent's next turn, prevent all damage done to this Pokémon by attacks from Burned Pokémon."
        val preventDamageAtMost = Regex("During your opponent's next turn, prevent all damage done to this Pokémon by attacks if that damage is (\\d+) or less\\.").matchEntire(text)
        val reduceDefenderAttack = Regex("During your opponent's next turn, attacks used by the Defending Pokémon do (\\d+) less damage \\(before applying Weakness and Resistance\\)\\.").matchEntire(text)
        val preventBasicNonColorlessDamage = Regex("During your opponent's next turn, prevent all damage done to this Pokémon by attacks from Basic non-\\{C\\} Pokémon\\.").matchEntire(text)
        val preventBasicDamage = text == "During your opponent's next turn, prevent all damage done to this Pokémon by attacks from Basic Pokémon."
        val discardOwn = Regex("Discard (an|a|1|2|3) (?:(?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) )?Energy from this Pokémon\\.").matchEntire(text)
        val discardAllOwn = Regex("Discard all (?:(?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]) )?Energy from this Pokémon\\.").matchEntire(text)
        val discardAllEnergyTakePrize = text == "Discard all Energy from this Pokémon, and take a Prize card."
        val condition = (status ?: coinStatus)?.groupValues?.get(1)?.uppercase()?.let { runCatching { SpecialCondition.valueOf(it) }.getOrNull() }
        val multiStatusParts = Regex("Your opponent's Active Pokémon is now (.+)\\.").matchEntire(text)?.groupValues?.get(1)
            ?.split(Regex(", and | and |, "))
        val multiStatus = multiStatusParts?.let { rawParts ->
            rawParts.mapNotNull { part -> runCatching { SpecialCondition.valueOf(part.uppercase()) }.getOrNull() }
                .takeIf { parts -> parts.size >= 2 && parts.size == rawParts.size }
        }
        if (ownPsychicEnergyDamage != null) {
            val perEnergy = ownPsychicEnergyDamage.groupValues[1].toInt()
            if (!damageText.endsWith("+")) return null
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.OwnEnergyTypeCountDamage(EnergyType.PSYCHIC, perEnergy)
        } else if (opponentFieldDamageCounters != null) {
            val perCounter = opponentFieldDamageCounters.groupValues[1].toInt()
            if (!damageText.endsWith("+")) return null
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.OpponentFieldDamageCounterDamage(perCounter)
        } else if (opponentPrizeTakenDamageTimes != null) {
            val perPrize = opponentPrizeTakenDamageTimes.groupValues[1].toInt()
            if (damageText != "${perPrize}×" && damageText != "${perPrize}x") return null
            effects += Effect.OpponentPrizeTakenDamage(perPrize)
        } else if (sameActiveEnergyBonus != null) {
            if (!damageText.endsWith("+")) return null
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.SameActiveEnergyCountDamageBonus(sameActiveEnergyBonus.groupValues[1].toInt())
        } else if (opponentActiveEnergyDamage != null) {
            val perEnergy = opponentActiveEnergyDamage.groupValues[1].toInt()
            if (damageText != "${perEnergy}×" && damageText != "${perEnergy}x") return null
            effects += Effect.OpponentActiveEnergyCountDamage(perEnergy)
        } else if (opponentFieldEnergyDamage != null) {
            val perEnergy = opponentFieldEnergyDamage.groupValues[1].toInt()
            if (damageText != "${perEnergy}×" && damageText != "${perEnergy}x") return null
            effects += Effect.OpponentFieldEnergyCountDamage(perEnergy)
        } else if (opponentTeamEnergyTypeDamage != null) {
            val perEnergy = opponentTeamEnergyTypeDamage.groupValues[1].toInt()
            if (damageText != "${perEnergy}×" && damageText != "${perEnergy}x") return null
            effects += Effect.OpponentEnergyTypeDamage(symbolType(opponentTeamEnergyTypeDamage.groupValues[2]) ?: return null, perEnergy)
        } else if (opponentSpecialConditionCountDamage != null) {
            val perCondition = opponentSpecialConditionCountDamage.groupValues[1].toInt()
            if (damageText != "${perCondition}×" && damageText != "${perCondition}x") return null
            effects += Effect.OpponentSpecialConditionCountDamage(perCondition)
        } else if (opponentActiveDamageCountersTimes != null) {
            val perCounter = opponentActiveDamageCountersTimes.groupValues[1].toInt()
            if (damageText != "${perCounter}×" && damageText != "${perCounter}x") return null
            effects += Effect.OpponentActiveDamageCounterDamage(perCounter)
        } else if (opponentActiveDamageCounters != null) {
            val perCounter = opponentActiveDamageCounters.groupValues[1].toInt()
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (!damageText.endsWith("+") || base <= 0) return null
            effects += Effect.Damage(base)
            effects += Effect.OpponentActiveDamageCounterDamage(perCounter)
        } else if (opponentHandAtMostDamage != null) {
            if (!damageText.endsWith("+")) return null
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.OpponentHandAtMostDamageBonus(
                opponentHandAtMostDamage.groupValues[1].toInt(), opponentHandAtMostDamage.groupValues[2].toInt())
        } else if (ownDiscardNamedPrefixFormula != null) {
            val perCard = ownDiscardNamedPrefixFormula.groupValues[1].toInt()
            if (damageText != "${perCard}×" && damageText != "${perCard}x") return null
            effects += Effect.OwnDiscardNamedPrefixDamage(ownDiscardNamedPrefixFormula.groupValues[2], perCard)
        } else if (opponentHandCountDamage != null) {
            val perCard = opponentHandCountDamage.groupValues[1].toInt()
            if (damageText != "${perCard}×" && damageText != "${perCard}x") return null
            effects += Effect.OpponentHandCountDamage(perCard)
        } else if (opponentItemDiscardDamage != null) {
            val perItem = opponentItemDiscardDamage.groupValues[1].toInt()
            if (damageText != "${perItem}×" && damageText != "${perItem}x") return null
            effects += Effect.OpponentItemDiscardDamage(perItem)
        } else if (opponentBasicDiscardEnergyDamage != null) {
            val perEnergy = opponentBasicDiscardEnergyDamage.groupValues[1].toInt()
            if (damageText != "${perEnergy}×" && damageText != "${perEnergy}x") return null
            effects += Effect.OpponentBasicDiscardEnergyDamage(perEnergy)
        } else if (opponentBenchDamageBonus != null) {
            if (!damageText.endsWith("+")) return null
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.OpponentBenchCountDamage(opponentBenchDamageBonus.groupValues[1].toInt())
        } else if (ownBenchDamageBonus != null) {
            if (!damageText.endsWith("+")) return null
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.OwnBenchCountDamage(ownBenchDamageBonus.groupValues[1].toInt())
        } else if (ownCynthiaBenchCounterDamage != null) {
            val perCounter = ownCynthiaBenchCounterDamage.groupValues[1].toInt()
            if (damageText != "${perCounter}×" && damageText != "${perCounter}x") return null
            effects += Effect.OwnNamedBenchDamageCounterDamage("Cynthia's", perCounter)
            effects += Effect.IgnoreWeakness
        } else if (ownFightingBenchCounterDamage != null) {
            val perCounter = ownFightingBenchCounterDamage.groupValues[1].toInt()
            if (damageText != "${perCounter}×" && damageText != "${perCounter}x") return null
            effects += Effect.OwnBenchTypeDamageCounterDamage(EnergyType.FIGHTING, perCounter)
        } else if (conditionalOwnDamageCounterBonus != null) {
            if (!damageText.endsWith("+")) return null
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.ConditionalOwnDamageCounterBonus(conditionalOwnDamageCounterBonus.groupValues[1].toInt(), conditionalOwnDamageCounterBonus.groupValues[2].toInt())
            effects += Effect.IgnoreWeakness
        } else if (ownDamageCounterBonus != null) {
            if (!damageText.endsWith("+")) return null
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.OwnDamageCounterDamage(ownDamageCounterBonus.groupValues[1].toInt())
        } else if (ownInPlayEnergyDamage != null || ownSpecialEnergyDamage != null || ownEnergyDamage != null || ownTypedEnergyDamage != null || ownTeamEnergyTypeDamage != null) {
            val formula = ownInPlayEnergyDamage ?: ownSpecialEnergyDamage ?: ownEnergyDamage ?: ownTypedEnergyDamage ?: ownTeamEnergyTypeDamage!!
            val perEnergy = formula.groupValues[1].toInt()
            if (damageText != "${perEnergy}×" && damageText != "${perEnergy}x") return null
            when {
                ownInPlayEnergyDamage != null -> effects += Effect.OwnInPlayEnergyDamage(perEnergy)
                ownSpecialEnergyDamage != null -> effects += Effect.OwnSpecialEnergyDamage(perEnergy)
                ownTeamEnergyTypeDamage != null -> effects += Effect.OwnEnergyTypeCountDamage(symbolType(ownTeamEnergyTypeDamage.groupValues[2]) ?: return null, perEnergy, allPokemon = true)
                ownTypedEnergyDamage != null -> effects += Effect.OwnEnergyTypeCountDamage(symbolType(ownTypedEnergyDamage.groupValues[2]) ?: return null, perEnergy)
                else -> effects += Effect.OwnEnergyCountDamage(perEnergy)
            }
        } else if (ownTeamEnergyTypeBonus != null) {
            if (!damageText.endsWith("+")) return null
            val base = damageText.removeSuffix("+").toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.OwnEnergyTypeCountDamage(symbolType(ownTeamEnergyTypeBonus.groupValues[2]) ?: return null,
                ownTeamEnergyTypeBonus.groupValues[1].toInt(), allPokemon = true)
        } else if (discardTopEnergyDamage != null) {
            val perEnergy = discardTopEnergyDamage.groupValues[2].toInt()
            if (damageText != "${perEnergy}×" && damageText != "${perEnergy}x") return null
            effects += Effect.DiscardTopDeckEnergyDamage(discardTopEnergyDamage.groupValues[1].toInt(),
                symbolType(discardTopEnergyDamage.groupValues[3]) ?: return null, perEnergy)
        } else if (ownTeamRocketCountDamage != null || ownNamedPrefixDamage != null) {
            val perPokemon = (ownTeamRocketCountDamage ?: ownNamedPrefixDamage!!).groupValues[1].toInt()
            if (damageText != "${perPokemon}×" && damageText != "${perPokemon}x") return null
            if (ownTeamRocketCountDamage != null) effects += Effect.OwnTeamRocketPokemonCountDamage(perPokemon)
            else effects += Effect.OwnNamedPokemonCountDamage("${ownNamedPrefixDamage!!.groupValues[2]}'s ", perPokemon)
        } else if (bothPlayersNamedDamage != null) {
            val amount = bothPlayersNamedDamage.groupValues[1].toInt()
            if (damageText != "${amount}×" && damageText != "${amount}x") return null
            effects += Effect.BothPlayersNamedPokemonCountDamage(setOf(bothPlayersNamedDamage.groupValues[2], bothPlayersNamedDamage.groupValues[3]), amount)
        } else if (ownTypePokemonDamage != null) {
            val amount = ownTypePokemonDamage.groupValues[1].toInt()
            if (damageText != "${amount}×" && damageText != "${amount}x") return null
            effects += Effect.OwnTypePokemonCountDamage(symbolType(ownTypePokemonDamage.groupValues[2]) ?: return null, amount)
        } else if (ownInPlayDamage != null || opponentBenchCountDamage != null || ownAttackNameDamage != null || ownBenchDamage != null || ownBasicPokemonDamage != null || ownStageOnePokemonDamage != null || ownDamagedPokemon != null || ownDamageCounters != null || ownHandCounters != null || ownToolDamage != null || allToolDamage != null) {
            val formula = ownInPlayDamage ?: opponentBenchCountDamage ?: ownAttackNameDamage ?: ownBenchDamage ?: ownBasicPokemonDamage ?: ownStageOnePokemonDamage ?: ownDamagedPokemon ?: ownDamageCounters ?: ownHandCounters ?: allToolDamage ?: ownToolDamage!!
            val amount = formula.groupValues[1].toInt()
            if (ownHandCounters != null) {
                if (damageText.isNotEmpty()) return null
                effects += Effect.OwnHandCounterDamage(amount)
            } else if (allToolDamage != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.AllPokemonToolCountDamage(amount)
            } else if (ownToolDamage != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.OwnToolCountDamage(amount)
            } else if (ownBenchDamage != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.OwnBenchCountDamage(amount)
            } else if (ownStageOnePokemonDamage != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.OwnStageOnePokemonCountDamage(amount)
            } else if (ownBasicPokemonDamage != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.OwnBasicPokemonCountDamage(amount)
            } else if (ownDamagedPokemon != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.OwnDamagedPokemonCountDamage(amount)
            } else if (ownAttackNameDamage != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.OwnAttackNameCountDamage(ownAttackNameDamage.groupValues[2], amount)
            } else {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                if (opponentBenchCountDamage != null) effects += Effect.OpponentBenchCountDamage(amount)
                else if (ownInPlayDamage != null) effects += Effect.OwnInPlayCountDamage(amount)
                else effects += Effect.OwnDamageCounterDamage(amount)
            }
        } else if (ownSpeciesDamage != null) {
            val species = ownSpeciesDamage.groupValues[2]
            if (ownSpeciesDamage.groupValues[3] != "$species ex") return null
            val perPokemon = ownSpeciesDamage.groupValues[1].toInt()
            if (damageText != "${perPokemon}×" && damageText != "${perPokemon}x") return null
            effects += Effect.OwnSpeciesCountDamage(species, perPokemon)
        } else if (ownDamageCounterPenalty != null) {
            if (!damageText.endsWith("-")) return null
            val base = damageText.removeSuffix("-").toIntOrNull() ?: return null
            if (base <= 0) return null
            effects += Effect.Damage(base)
            effects += Effect.OwnDamageCounterPenalty(ownDamageCounterPenalty.groupValues[1].toInt())
        } else if (discardEnergyBenchDamage != null) {
            val base = damageText.toIntOrNull() ?: return null
            if (base > 0) effects += Effect.Damage(base)
            effects += Effect.DiscardEnergyThenBenchDamage(discardEnergyBenchDamage.groupValues[1].toInt(), discardEnergyBenchDamage.groupValues[2].toInt())
        } else if (discardFireTargetedBench != null) {
            if (damageText.isNotEmpty()) return null
            effects += Effect.DiscardAttackEnergy(0, EnergyType.FIRE, all = true)
            effects += Effect.TargetedDamage(discardFireTargetedBench.groupValues[1].toInt(), benchedOnly = true)
        } else if (dragOff != null) {
            if (damageText.isNotEmpty()) return null
            effects += Effect.TargetedDamage(dragOff.groupValues[1].toInt(), benchedOnly = true)
            effects += Effect.PullSelectedBenchBeforeDamage
        } else if (discardAllTargeted != null || discardAllTargetedAfter != null) {
            if (damageText.isNotEmpty()) return null
            effects += Effect.DiscardAttackEnergy(0, all = true)
            effects += Effect.TargetedDamage((discardAllTargeted ?: discardAllTargetedAfter!!).groupValues[1].toInt())
        } else if (coinDamageEachPokemon != null) {
            if (damageText.isNotEmpty()) return null
            effects += Effect.CoinDamageEachOpponentPokemon(coinDamageEachPokemon.groupValues[1].toInt())
        } else if (coin) {
            if (discardDefendingPerHeads != null) {
                val count = discardDefendingPerHeads?.groupValues?.get(2) ?: discardDefendingCoin!!.groupValues[1]
                val coinCount = discardDefendingPerHeads?.groupValues?.get(1)?.toInt() ?: 1
                val baseDamage = damageText.toIntOrNull()
                if (baseDamage == null && damageText.isNotEmpty()) return null
                if ((baseDamage ?: 0) > 0) effects += Effect.Damage(baseDamage!!)
                effects += Effect.CoinDamage(coinCount, 0)
                effects += Effect.DiscardDefendingEnergy(if (count in listOf("an", "a")) 1 else count.toInt(), onHeads = true, perHeads = true)
            } else {
            val amount = coinFixed?.groupValues?.get(2)?.toInt()
                ?: coinUntilTails?.groupValues?.get(1)?.toInt()
                ?: coinPerEnergy?.groupValues?.get(1)?.toInt()
                ?: coinPerEnergyBoth?.groupValues?.get(1)?.toInt() ?: return null
            if (damageText != "${amount}×" && damageText != "${amount}x") return null
            effects += Effect.CoinDamage(coinFixed?.groupValues?.get(1)?.toInt() ?: 0, amount,
                untilTails = coinUntilTails != null, perEnergy = coinPerEnergy != null,
                perEnergyBothActive = coinPerEnergyBoth != null)
            coinFixed?.groupValues?.get(3)?.takeIf(String::isNotEmpty)?.let { count ->
                val condition = runCatching { SpecialCondition.valueOf(coinFixed.groupValues[4].uppercase()) }.getOrNull() ?: return null
                effects += Effect.StatusCondition(condition, onHeads = true, minimumHeads = count.toInt())
            }
            }
        } else {
            val discardOwnEnergyDamage = discardTypedAttackerEnergy != null || discardAnyAttackerEnergy != null || discardAnyBasicEnergyFromTeam != null || discardAnyTypedEnergyFromTeam != null || discardTypedEnergyFromTeam != null
            val damage = if (damageText.isEmpty() || ownTeamRocketCountDamage != null || opponentHandTrainerDamage != null || opponentHandEnergyDamage != null || discardOwnEnergyDamage || opponentActiveEnergyDamage != null || sameActiveEnergyBonus != null || opponentFieldEnergyDamage != null || opponentTeamEnergyTypeDamage != null || opponentSpecialConditionCountDamage != null || opponentActiveDamageCountersTimes != null || opponentHandCountDamage != null || opponentBasicDiscardEnergyDamage != null || opponentItemDiscardDamage != null) 0 else
                (if (coinBonus != null || coinBonusMultiple != null || coinBonusAllHeads != null || coinBonusUntilTails != null || optionalWaterEnergyDamageBonus != null || stadiumDamageBonus != null || stadiumDamageOnlyBonus != null || opponentTypePresentBonus != null || conditionalEnergy != null || attackerPoisonBonus != null || attackerBurnOrPoisonBonus != null || conditionalExtraEnergy != null || conditionalInPlayEnergy != null || ownKnockoutBonus != null || ownKnockoutBonusHop != null || movedFromBenchBonus != null || anyOwnBenchedDamagedBonus != null || opponentBenchDamageBonus != null || ownBenchNameBonus != null || ownBenchTypeBonus != null || sameTypePokemonBonus || sameHandSizeDamageBonus != null || ownHandEmptyDamageBonus != null || allOwnBenchedDamagedBonus != null || ownDamagedBenchedPokemonBonus != null || ownHealedThisTurnBonus != null || ownUndamagedBonus != null || ownAnyDamageCounterBonus != null || opponentExBonus != null || opponentTypeBonus != null || opponentSpecificConditionBonus != null || opponentEvolutionBonus != null || opponentStageTwoBonus != null || ownEnergyBonus != null || opponentEnergyBonus != null || opponentRetreatCostBonus != null || ownBenchEnergyBonus != null || opponentConditionBonus != null || opponentActiveDamagedBonus != null || opponentActiveDamageCounters != null || ownDiscardEnergyDamage != null || discardEnergyThresholdDamage != null || ownDiscardAbilityBonus != null || ownDiscardNamedCardDamage != null || opponentPrizeTakenDamage != null || opponentPrizeTakenDamageWithDiscard != null || opponentPrizeCountDamage != null || ownPrizeLeadDamage != null || opponentNoRetreatDamage != null || opponentResistanceDamage != null || opponentHandAtMostDamage != null || ownToolAttachedDamage != null || bothActiveEnergyDamage != null || benchedDamage != null || benchEnergyBonus != null || handEnergyBonus != null || ownTeamEnergyTypeBonus != null) damageText.removeSuffix("+") else damageText).toIntOrNull() ?: return null
            if (damage < 0) return null
            if (damage > 0) effects += Effect.Damage(damage)
            val draw = Regex("(?:You may )?[Dd]raw (a|1|2|3|4|5) cards?\\.").matchEntire(text)
            val eachPlayerDraw = Regex("Each player draws (\\d+) cards?\\.").matchEntire(text)
            val shuffleHandDraw = Regex("Shuffle your hand into your deck\\. Then, draw (\\d+) cards?\\.").matchEntire(text)
            val drawToHand = Regex("You may draw cards until you have (\\d+) cards in your hand\\.").matchEntire(text)
            val discardHandDraw = Regex("Discard your hand and draw (\\d+) cards?\\.").matchEntire(text)
            val recoil = Regex("This Pokémon also does (\\d+) damage to itself\\.").matchEntire(text)
            val selfOnly = Regex("This Pokémon does (\\d+) damage to itself\\.").matchEntire(text)
            val heal = Regex("Heal (\\d+) damage from this Pokémon\\.").matchEntire(text)
            val healAnyPokemon = Regex("Heal (\\d+) damage from 1 of your Pokémon\\.").matchEntire(text)
            val healBenchedPokemon = Regex("Heal (\\d+) damage from 1 of your Benched(?: (?:\\{|\\[)([GRFLWPMD])(?:\\}|\\]))? Pokémon\\.").matchEntire(text)
            val healAllAnyPokemon = text == "Heal all damage from 1 of your Pokémon."
            val healAllBenchedPokemon = text == "Heal all damage from 1 of your Benched Pokémon."
            val healEachBasicPokemon = Regex("Heal (\\d+) damage from each of your Basic Pokémon\\.").matchEntire(text)
            val healEachPokemon = Regex("Heal (\\d+) damage from each of your Pokémon\\.").matchEntire(text)
            val healEachPokemonBoth = Regex("Heal (\\d+) damage from each Pokémon \\(both yours and your opponent's\\)\\.").matchEntire(text)
            val energySearch = Regex("Search your deck for a Basic \\{([GFRLWPMD])\\} Energy card and attach it to this Pokémon\\. Then, shuffle your deck\\.").matchEntire(text)
        val energySearchAnyType = text == "Search your deck for a Basic Energy card and attach it to this Pokémon. Then, shuffle your deck."
        val energySearchUpTo = Regex("Search your deck for up to (\\d+) Basic \\{([GFRLWPMD])\\} Energy cards and attach them to this Pokémon\\. Then, shuffle your deck\\.").matchEntire(text)
        val energySearchSelfPoison = Regex("Search your deck for up to (\\d+) Basic \\{([GFRLWPMD])\\} Energy cards and attach them to this Pokémon\\. Then, shuffle your deck\\. If you attached Energy to a Pokémon in this way, this Pokémon is now Poisoned\\.").matchEntire(text)
            when {
                text.isEmpty() -> Unit
                bothBenchesDamage != null -> {
                    effects += Effect.AllOpponentBenchDamage(bothBenchesDamage.groupValues[1].toInt())
                    effects += Effect.OwnBenchDamage(bothBenchesDamage.groupValues[1].toInt())
                }
                allOpponentBenchDamage != null -> effects += Effect.AllOpponentBenchDamage(allOpponentBenchDamage.groupValues[1].toInt())
                opponentRuleBoxDamage != null -> {
                    effects += Effect.OpponentRuleBoxDamage(opponentRuleBoxDamage.groupValues[1].toInt())
                    effects += Effect.IgnoreWeaknessResistance
                }
                attachBasicGrassAndHealBenched -> effects += Effect.AttachBasicEnergyFromHandToBenchedThenHealAll(EnergyType.GRASS)
                healEachPokemonBoth != null -> effects += Effect.HealEachPokemon(healEachPokemonBoth.groupValues[1].toInt())
                healEachBasicPokemon != null -> effects += Effect.HealEachOwnPokemon(healEachBasicPokemon.groupValues[1].toInt(), basicOnly = true)
                healEachPokemon != null -> effects += Effect.HealEachOwnPokemon(healEachPokemon.groupValues[1].toInt())
                healAllAnyPokemon -> effects += Effect.HealOwnPokemon(Int.MAX_VALUE)
                healAllBenchedPokemon -> effects += Effect.HealOwnPokemon(Int.MAX_VALUE, benchedOnly = true)
                healAnyPokemon != null -> effects += Effect.HealOwnPokemon(healAnyPokemon.groupValues[1].toInt())
                healBenchedPokemon != null -> effects += Effect.HealOwnPokemon(healBenchedPokemon.groupValues[1].toInt(), benchedOnly = true,
                    pokemonType = healBenchedPokemon.groupValues[2].takeIf { it.isNotBlank() }?.let(::symbolType))
                confusePoisonThenSwitch -> {
                    effects += Effect.StatusCondition(SpecialCondition.CONFUSED)
                    effects += Effect.StatusCondition(SpecialCondition.POISONED)
                    effects += Effect.SwitchOwn()
                }
                moveOpponentDamageCounters -> {
                    effects += Effect.StatusCondition(SpecialCondition.CONFUSED)
                    effects += Effect.MoveOpponentDamageCounters
                }
                pullOpponentBench -> effects += Effect.PullOpponentBench
                recoverSupporter -> effects += Effect.RecoverSupporterFromDiscard
                matchingEnergySearch != null -> effects += Effect.SearchPokemonMatchingAttachedBasicEnergy(matchingEnergySearch.groupValues[1].toInt())
                basicEnergyToHandSearch != null -> effects += Effect.SearchBasicEnergyToHand(basicEnergyToHandSearch.groupValues[1].toInt())
                pokemonSearchToHand != null || pokemonSearchUpToToHand != null -> effects += Effect.SearchPokemonToHand(pokemonSearchUpToToHand?.groupValues?.get(1)?.toInt() ?: 1)
                toolSearchToHand != null -> effects += Effect.SearchToolToHand(1)
                namedTrainerSearchToHand != null -> effects += Effect.SearchNamedTrainerToHand(namedTrainerSearchToHand.groupValues[1].trim(), 60)
                pokemonPrefixSearchToHand != null -> effects += Effect.SearchPokemonNamePrefixToHand(pokemonPrefixSearchToHand.groupValues[2].trim(), pokemonPrefixSearchToHand.groupValues[1].toInt())
                typePokemonAndStadiumSearch != null -> effects += Effect.SearchPokemonTypeAndStadiumToHand(typePokemonAndStadiumSearch.groupValues[1].toInt(), symbolType(typePokemonAndStadiumSearch.groupValues[2]) ?: return null)
                anyCardSearchToHand -> effects += Effect.SearchAnyCardToHand
                anyCardsSearchToHand != null -> effects += Effect.SearchUpToAnyCardsToHand(anyCardsSearchToHand.groupValues[1].toInt())
                supporterSearchToHand -> effects += Effect.SearchSupporterToHand
                itemSearchToHand -> effects += Effect.SearchItemToHand
                chooseOpponentAttackToRestrict -> effects += Effect.ChooseOpponentAttackToRestrict
                copyBenchedNAttack -> effects += Effect.CopyBenchedNsAttack
                copyOpponentActiveAttack -> effects += Effect.CopyOpponentActiveAttack
                copyOpponentActiveTeraAttack -> effects += listOf(Effect.CopyOpponentActiveAttack, Effect.CopyOpponentActiveTeraAttack)
                healSelfByDamageDealt -> effects += Effect.HealSelfByDamageDealt
                attachAnyBasicEnergy -> effects += Effect.AttachAnyBasicEnergyFromHand
                attachUpToTwoPsychicFromHand -> effects += Effect.AttachUpToTwoBasicEnergyFromHand(EnergyType.PSYCHIC)
                attachOneBasicEnergy -> effects += Effect.AttachOneBasicEnergyFromHand
                shuffleOwnBenchIntoDeck -> effects += Effect.ShuffleOwnBenchedPokemonIntoDeck
                sylveonAngeliteShuffle != null -> {
                    effects += Effect.ShuffleOpponentBenchedPokemonIntoDeck(sylveonAngeliteShuffle.groupValues[1].toInt())
                    effects += Effect.RequiresAngeliteUsedLastTurn
                }
                coinShuffleOpponentBench -> effects += Effect.CoinShuffleOpponentBenchedPokemonIntoDeck(1)
                knockOutAtSixCounters -> effects += Effect.KnockOutOpponentPokemonWithExactlySixDamageCounters
                devolveOpponentActive -> effects += Effect.DevolveOpponentActive
                devolveOpponentAllIntoDeck -> effects += Effect.DevolveOpponentPokemonIntoDeck
                discardStadium -> effects += Effect.DiscardStadium
                returnSelfAndAttachments -> effects += Effect.ReturnSelfAndAttachmentsToHand
                shuffleSelfAndAttachmentsIntoDeck -> effects += Effect.ShuffleSelfAndAttachmentsIntoDeck
                optionalReturnSelf -> effects += Effect.OptionalReturnSelfAndAttachmentsToHand
                attachBasicEnergyFromDeckAny != null -> effects += Effect.AttachBasicEnergyFromDeckAny(attachBasicEnergyFromDeckAny.groupValues[1].toInt())
                attachEnergyFromTopDeck -> effects += Effect.AttachEnergyFromTopDeck(20)
                attachBasicEnergyFromDeckBench != null -> effects += Effect.AttachBasicEnergyFromDeckAny(attachBasicEnergyFromDeckBench.groupValues[1].toInt(), benchedOnly = true)
                attachTypedEnergyFromDeckBench != null -> effects += Effect.AttachBasicEnergyFromDeckAny(attachTypedEnergyFromDeckBench.groupValues[1].toInt(), symbolType(attachTypedEnergyFromDeckBench.groupValues[2]) ?: return null, benchedOnly = true)
                attachTypedEnergyFromDiscardBench != null -> effects += Effect.AttachBasicEnergyFromDiscardAny(attachTypedEnergyFromDiscardBench.groupValues[1].toInt(), symbolType(attachTypedEnergyFromDiscardBench.groupValues[2]) ?: return null, benchedOnly = true)
                attachBasicEnergyFromDiscardToBenched -> effects += Effect.AttachBasicEnergyFromDiscardAny(1, benchedOnly = true, required = true)
                attachFightingEnergyFromDiscardEachBench -> effects += Effect.AttachBasicFightingEnergyFromDiscardToEachBenchedPokemon
                attachLightningEnergyFromDiscardByOpponentEnergyCount -> effects += Effect.AttachLightningEnergyFromDiscardUpToOpponentEnergyCount
                attachBasicFightingEnergyFromDiscardToSelf != null -> effects += Effect.AttachBasicFightingEnergyFromDiscardToSelf(attachBasicFightingEnergyFromDiscardToSelf.groupValues[1].toInt())
                attachOneBasicFightingEnergyFromDiscardToSelf -> effects += Effect.AttachBasicFightingEnergyFromDiscardToSelf(1)
                attachEnergyToEachBench != null -> effects += Effect.AttachBasicEnergyToEachBenchedPokemon(symbolType(attachEnergyToEachBench.groupValues[1]) ?: return null)
                coinKnockOutBasic -> effects += Effect.CoinKnockOutBasic
                statusThenRetreat != null -> {
                    val condition = SpecialCondition.valueOf(statusThenRetreat.groupValues[1].uppercase())
                    effects += Effect.StatusCondition(condition)
                    effects += Effect.PreventRetreatNextTurn
                }
                exactHandSize != null -> effects += Effect.RequiresExactHandSize(exactHandSize.groupValues[1].toInt())
                requiresOwnBenchCountAtLeast != null -> effects += Effect.DamageOnlyWithOwnBenchCountAtLeast(requiresOwnBenchCountAtLeast.groupValues[1].toInt() + 1)
                requiresUndamagedOpponentActive -> effects += Effect.RequiresUndamagedOpponentActive
                multiStatus != null -> multiStatus.forEach { effects += Effect.StatusCondition(it) }
                selfOnly != null && damage == 0 -> effects += Effect.SelfDamage(selfOnly.groupValues[1].toInt())
                text == "Flip a coin. If tails, this attack does nothing." -> effects += Effect.CoinMiss
                text == "Flip a coin. If tails, this attack does nothing. If heads, during your opponent's next turn, prevent all damage from and effects of attacks done to this Pokémon." -> {
                    effects += Effect.CoinMiss
                    effects += Effect.PreventAttackDamageNextTurnOnHeads
                }
                text == "Flip a coin. If heads, during your opponent's next turn, prevent all damage from and effects of attacks done to this Pokémon." ||
                    text == "Flip a coin. If heads, during your opponent's next turn, prevent all damage done to this Pokémon by attacks." -> effects += Effect.PreventAttackDamageNextTurnOnHeads
                moveEnergy != null -> effects += Effect.MoveEnergyToBench(moveEnergy.groupValues[1].toInt())
                moveEnergyToOneBench != null -> effects += Effect.MoveEnergyToOneBench(moveEnergyToOneBench.groupValues[1].toInt())
                moveAllEnergyToBench -> effects += Effect.MoveAllEnergyToBench
                benchEnergyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.DiscardBenchEnergyDamage(benchEnergyBonus.groupValues[1].toInt(), benchEnergyBonus.groupValues[3].toInt(),
                        basicOnly = benchEnergyBonus.groupValues[2].isNotBlank())
                }
                handEnergyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.DiscardHandEnergyDamageBonus(handEnergyBonus.groupValues[1].toInt(), handEnergyBonus.groupValues[2].toInt())
                }
                discardOwnEnergyDamage -> {
                    val match = discardTypedAttackerEnergy ?: discardAnyAttackerEnergy ?: discardAnyBasicEnergyFromTeam ?: discardAnyTypedEnergyFromTeam ?: discardTypedEnergyFromTeam!!
                    val max = when {
                        discardTypedAttackerEnergy != null -> discardTypedAttackerEnergy.groupValues[1].toInt()
                        discardAnyAttackerEnergy != null -> discardAnyAttackerEnergy.groupValues[1].toInt()
                        discardAnyBasicEnergyFromTeam != null -> 99
                        discardAnyTypedEnergyFromTeam != null -> 99
                        else -> discardTypedEnergyFromTeam!!.groupValues[1].toInt()
                    }
                    val type = when {
                        discardTypedAttackerEnergy != null -> symbolType(discardTypedAttackerEnergy.groupValues[2]) ?: return null
                        discardAnyAttackerEnergy != null -> null
                        discardAnyTypedEnergyFromTeam != null -> symbolType(discardAnyTypedEnergyFromTeam.groupValues[1]) ?: return null
                        discardTypedEnergyFromTeam != null -> symbolType(discardTypedEnergyFromTeam.groupValues[2]) ?: return null
                        else -> null
                    }
                    val perEnergy = when {
                        discardAnyAttackerEnergy != null -> discardAnyAttackerEnergy.groupValues[2].toInt()
                        discardAnyTypedEnergyFromTeam != null -> discardAnyTypedEnergyFromTeam.groupValues[2].toInt()
                        else -> match.groupValues.last().toIntOrNull() ?: return null
                    }
                    if (damageText != "${perEnergy}×" && damageText != "${perEnergy}x") return null
                    effects += Effect.DiscardOwnEnergyDamageBonus(max, perEnergy, type,
                        basicOnly = discardAnyBasicEnergyFromTeam != null, attackerOnly = discardTypedAttackerEnergy != null || discardAnyAttackerEnergy != null)
                }
                discardAllAttackerEnergyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.DiscardOwnEnergyDamageBonus(99, discardAllAttackerEnergyBonus.groupValues[1].toInt(),
                        attackerOnly = true, discardAll = true)
                }
                opponentExBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentExDamage(opponentExBonus.groupValues[1].toInt())
                }
                opponentTypeBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentTypeDamage(symbolType(opponentTypeBonus.groupValues[1]) ?: return null, opponentTypeBonus.groupValues[2].toInt())
                }
                opponentTypePresentBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentTypePresentDamageBonus(symbolType(opponentTypePresentBonus.groupValues[1]) ?: return null, opponentTypePresentBonus.groupValues[2].toInt())
                }
                opponentSpecificConditionBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentActiveConditionDamageBonus(
                        SpecialCondition.valueOf(opponentSpecificConditionBonus.groupValues[1].uppercase()),
                        opponentSpecificConditionBonus.groupValues[2].toInt())
                }
                opponentEvolutionBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentEvolutionActiveDamageBonus(opponentEvolutionBonus.groupValues[1].toInt())
                }
                opponentStageTwoBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentStageTwoActiveDamageBonus(opponentStageTwoBonus.groupValues[1].toInt())
                }
                ownEnergyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    val energyType = ownEnergyBonus.groupValues[2].takeIf(String::isNotEmpty)?.let(::symbolType)
                    if (energyType == null) effects += Effect.OwnEnergyCountDamage(ownEnergyBonus.groupValues[1].toInt())
                    else effects += Effect.OwnEnergyTypeCountDamage(energyType, ownEnergyBonus.groupValues[1].toInt())
                }
                opponentEnergyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentEnergyCountDamage(opponentEnergyBonus.groupValues[1].toInt())
                }
                opponentHandEnergyDamage != null -> {
                    val perEnergy = opponentHandEnergyDamage.groupValues[1].toInt()
                    if (damageText != "${perEnergy}×" && damageText != "${perEnergy}x") return null
                    effects += Effect.OpponentHandEnergyCountDamage(perEnergy)
                }
                opponentHandTrainerDamage != null -> {
                    val perTrainer = opponentHandTrainerDamage.groupValues[1].toInt()
                    if (damageText != "${perTrainer}×" && damageText != "${perTrainer}x") return null
                    effects += Effect.RevealOpponentHand
                    effects += Effect.OpponentHandTrainerCountDamage(perTrainer)
                }
                opponentRetreatCostBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentActiveRetreatCostDamage(opponentRetreatCostBonus.groupValues[1].toInt())
                }
                ownBenchEnergyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnBenchWithEnergyDamage(ownBenchEnergyBonus.groupValues[1].toInt(),
                        symbolType(ownBenchEnergyBonus.groupValues[2]) ?: return null)
                }
                bothActiveEnergyDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.BothActiveEnergyCountDamage(bothActiveEnergyDamage.groupValues[1].toInt())
                }
                opponentConditionBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentConditionDamage(opponentConditionBonus.groupValues[1].toInt())
                }
                attackerPoisonBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.AttackerConditionDamageBonus(SpecialCondition.POISONED, attackerPoisonBonus.groupValues[1].toInt())
                }
                attackerBurnOrPoisonBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.AttackerAnyConditionDamageBonus(setOf(SpecialCondition.BURNED, SpecialCondition.POISONED), attackerBurnOrPoisonBonus.groupValues[1].toInt())
                }
                opponentActiveDamagedBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentActiveDamagedDamage(opponentActiveDamagedBonus.groupValues[1].toInt())
                }
                ownDiscardEnergyDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnDiscardEnergyDamage(ownDiscardEnergyDamage.groupValues[1].toInt())
                }
                discardEnergyThresholdDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.DiscardEnergyThresholdDamage(symbolType(discardEnergyThresholdDamage.groupValues[2]) ?: return null,
                        discardEnergyThresholdDamage.groupValues[1].toInt(), discardEnergyThresholdDamage.groupValues[3].toInt())
                }
                opponentPrizeTakenDamage != null || opponentPrizeTakenDamageWithDiscard != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentPrizeTakenDamage((opponentPrizeTakenDamage ?: opponentPrizeTakenDamageWithDiscard!!).groupValues[1].toInt())
                    if (opponentPrizeTakenDamageWithDiscard != null) effects += Effect.DiscardAttackEnergy(1)
                }
                opponentPrizeCountDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentPrizeCountDamage(opponentPrizeCountDamage.groupValues[1].toInt(), opponentPrizeCountDamage.groupValues[2].toInt())
                }
                ownPrizeLeadDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnPrizeLeadDamageBonus(ownPrizeLeadDamage.groupValues[1].toInt())
                }
                opponentNoRetreatDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentNoRetreatDamage(opponentNoRetreatDamage.groupValues[1].toInt())
                }
                opponentResistanceDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentResistanceDamage(symbolType(opponentResistanceDamage.groupValues[1]) ?: return null, opponentResistanceDamage.groupValues[2].toInt())
                }
                ownToolAttachedDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnToolAttachedDamage(ownToolAttachedDamage.groupValues[1].toInt())
                }
                opponentChoosesHandShuffle != null -> effects += Effect.OpponentChoosesHandShuffle(opponentChoosesHandShuffle.groupValues[1].toInt())
                opponentChoosesHandDiscard != null -> effects += Effect.OpponentChoosesHandDiscard(opponentChoosesHandDiscard.groupValues[1].let { if (it == "a") 1 else it.toInt() })
                salazzleHandDiscard -> effects += Effect.SalazzleHandDiscard(1, 2)
                revealAndChooseOpponentHandDiscard -> effects += Effect.RevealAndChooseOpponentHandDiscard
                revealAndChooseOpponentHandBottomDeck -> effects += Effect.RevealAndChooseOpponentHandBottomDeck
                opponentHandAndDeckDiscard -> {
                    effects += Effect.DiscardRandomOpponentHand(1)
                    effects += Effect.DiscardOpponentDeck(1)
                }
                opponentDiscard != null -> effects += Effect.DiscardRandomOpponentHand(1)
                opponentShuffleRandom != null -> effects += Effect.ShuffleRandomOpponentHand(1)
                opponentDeckDiscard != null -> effects += Effect.DiscardOpponentDeck(opponentDeckDiscard.groupValues[1].toIntOrNull() ?: 1)
                reorderOpponentDeckTop != null -> effects += Effect.ReorderOpponentDeckTop(reorderOpponentDeckTop.groupValues[1].toInt())
                discardOwnDeckTop -> effects += Effect.DiscardOwnDeckTop
                discardOwnDeckTopCards != null -> effects += Effect.DiscardOwnDeckTopCards(discardOwnDeckTopCards.groupValues[1].toInt())
                optionalDiscardOwnDeckTop -> effects += Effect.OptionalDiscardOwnDeckTop
                lookTopChooseAndDiscardRest != null -> effects += Effect.LookTopChooseAndDiscardRest(lookTopChooseAndDiscardRest.groupValues[1].toInt(), lookTopChooseAndDiscardRest.groupValues[2].toInt())
                revealOpponentHand -> effects += Effect.RevealOpponentHand
                searchEvolutionAny -> effects += Effect.SearchEvolutionForActiveToEvolve
                searchEvolutionSelf -> effects += Effect.SearchEvolutionForSelfToEvolve
                searchEvolutionEachBenchedPokemon -> effects += Effect.SearchEvolutionForEachBenchedPokemon
                delayedDefendingCounters != null -> effects += Effect.DelayedDefendingDamageCounters(delayedDefendingCounters.groupValues[1].toInt())
                ironBoulderRetaliationCounters != null -> effects += Effect.RetaliateDamageCountersNextTurn(ironBoulderRetaliationCounters.groupValues[1].toInt())
                drawToHand != null -> effects += Effect.DrawToHand(drawToHand.groupValues[1].toInt())
                discardHandDraw != null -> effects += Effect.DiscardHandDraw(discardHandDraw.groupValues[1].toInt())
                coinBonus != null || coinBonusMultiple != null || coinBonusAllHeads != null || coinBonusUntilTails != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    val thresholdCoin = coinBonusAllHeads != null
                    effects += Effect.CoinDamage(when {
                        coinBonusUntilTails != null -> 0
                        coinBonusAllHeads != null -> coinBonusAllHeads.groupValues[1].toInt()
                        coinBonusMultiple != null -> coinBonusMultiple.groupValues[1].toInt()
                        else -> 1
                    },
                        coinBonus?.groupValues?.get(1)?.toInt() ?: coinBonusMultiple?.groupValues?.get(2)?.toInt()
                        ?: coinBonusAllHeads?.groupValues?.get(2)?.toInt() ?: coinBonusUntilTails?.groupValues?.get(1)?.toInt() ?: return null,
                        untilTails = coinBonusUntilTails != null, bonusOnly = true,
                        minimumHeads = if (thresholdCoin) coinBonusAllHeads!!.groupValues[1].toInt() else 0)
                }
                conditionalExtraEnergy != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.ConditionalAttachedEnergyDamage(
                        cost.size + conditionalExtraEnergy.groupValues[1].toInt(), conditionalExtraEnergy.groupValues[2].toInt())
                }
                conditionalEnergy != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.ConditionalEnergyDamage(
                        symbolType(conditionalEnergy.groupValues[2]) ?: return null,
                        conditionalEnergy.groupValues[1].toInt(), conditionalEnergy.groupValues[3].toInt())
                }
                conditionalInPlayEnergy != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.ConditionalInPlayEnergyDamage(
                        symbolType(conditionalInPlayEnergy.groupValues[2]) ?: return null,
                        conditionalInPlayEnergy.groupValues[1].toInt(), conditionalInPlayEnergy.groupValues[3].toInt())
                }
                ownKnockoutBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnKnockoutLastTurnDamage(ownKnockoutBonus.groupValues[1].toInt())
                }
                ownKnockoutBonusHop != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnKnockoutLastTurnDamage(ownKnockoutBonusHop.groupValues[1].toInt())
                }
                ownKnockoutBonusHop != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnKnockoutLastTurnDamage(ownKnockoutBonusHop.groupValues[1].toInt())
                }
                movedFromBenchBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.MovedFromBenchDamageBonus(movedFromBenchBonus.groupValues[1].toInt())
                }
                stadiumDamageBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.StadiumDamageBonus(stadiumDamageBonus.groupValues[1].toInt())
                    effects += Effect.DiscardStadium
                }
                stadiumDamageOnlyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.StadiumDamageBonus(stadiumDamageOnlyBonus.groupValues[1].toInt())
                }
                stadiumDamageOnlyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.StadiumDamageBonus(stadiumDamageOnlyBonus.groupValues[1].toInt())
                }
                opponentTypePresentBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentTypePresentDamageBonus(symbolType(opponentTypePresentBonus.groupValues[1]) ?: return null, opponentTypePresentBonus.groupValues[2].toInt())
                }
                ownBenchNameBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.BenchPokemonPresenceDamageBonus(ownBenchNameBonus.groupValues[1], ownBenchNameBonus.groupValues[2].toInt())
                }
                ownBenchTypeBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.BenchTypePresenceDamageBonus(symbolType(ownBenchTypeBonus.groupValues[1]) ?: return null, ownBenchTypeBonus.groupValues[2].toInt())
                }
                allOwnBenchedDamagedBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.AllOwnBenchedPokemonDamagedBonus(allOwnBenchedDamagedBonus.groupValues[1].toInt())
                }
                anyOwnBenchedDamagedBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.AnyOwnBenchedPokemonDamagedBonus(anyOwnBenchedDamagedBonus.groupValues[1].toInt())
                }
                ownDamagedBenchedPokemonBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnDamagedBenchedPokemonCountDamage(ownDamagedBenchedPokemonBonus.groupValues[1].toInt())
                }
                sameTypePokemonBonus -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.SameTypePokemonInPlayDamageBonus(120)
                }
                sameHandSizeDamageBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.SameHandSizeDamageBonus(sameHandSizeDamageBonus.groupValues[1].toInt())
                }
                ownHandEmptyDamageBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnHandEmptyDamageBonus(ownHandEmptyDamageBonus.groupValues[1].toInt())
                }
                ownDiscardAbilityBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnDiscardAbilityCountBonus(ownDiscardAbilityBonus.groupValues[2],
                        ownDiscardAbilityBonus.groupValues[1].toInt(), ownDiscardAbilityBonus.groupValues[3].toInt())
                }
                ownDiscardNamedCardDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnDiscardNamedCardDamage(ownDiscardNamedCardDamage.groupValues[2], ownDiscardNamedCardDamage.groupValues[1].toInt())
                }
                ownHealedThisTurnBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnHealedThisTurnDamageBonus(ownHealedThisTurnBonus.groupValues[1].toInt())
                }
                ownUndamagedBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnUndamagedDamageBonus(ownUndamagedBonus.groupValues[1].toInt())
                }
                ownAnyDamageCounterBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnAnyDamageCounterBonus(ownAnyDamageCounterBonus.groupValues[1].toInt())
                }
                benchedDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.BenchedCountDamage(benchedDamage.groupValues[1].toInt())
                }
                (status != null || coinStatus != null) && condition != null -> effects += Effect.StatusCondition(condition, onHeads = coinStatus != null)
                strongPoison != null -> effects += Effect.StatusCondition(SpecialCondition.POISONED,
                    poisonDamageCounters = strongPoison.groupValues[1].toInt())
                opponentTypeStatus != null -> {
                    effects += Effect.OpponentActiveTypeCondition(symbolType(opponentTypeStatus.groupValues[1]) ?: return null)
                    effects += Effect.StatusCondition(SpecialCondition.valueOf(opponentTypeStatus.groupValues[2].uppercase()))
                }
                discardEnergyBenchDamage != null -> effects += Effect.DiscardEnergyThenBenchDamage(discardEnergyBenchDamage.groupValues[1].toInt(), discardEnergyBenchDamage.groupValues[2].toInt())
                discardAllEnergyThenBenchDamage != null -> {
                    effects += Effect.DiscardAttackEnergy(0, all = true)
                    effects += Effect.BenchDamage(discardAllEnergyThenBenchDamage.groupValues[1].toInt())
                }
                selectedPokemonDamage != null -> effects += Effect.TargetedDamage(selectedPokemonDamage.groupValues[1].toInt())
                ownBenchAttackDamage != null -> effects += Effect.OwnBenchDamage(ownBenchAttackDamage.groupValues[1].toInt())
                Regex("This attack also does (\\d+) damage to 1 of your Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text) != null -> {
                    val amount = Regex("This attack also does (\\d+) damage to 1 of your Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)!!.groupValues[1].toInt()
                    effects += Effect.OwnBenchDamage(amount)
                }
                benchDamage != null || damagedBenchDamage != null -> effects += Effect.BenchDamage(
                    (damagedBenchDamage ?: benchDamage!!).groupValues[1].toInt(), requiresDamageCounters = damagedBenchDamage != null)
                benchDamageCounters != null || spreadBenchDamageCounters != null || spreadOpponentDamageCounters != null -> effects += Effect.BenchDamageCounters(
                    (benchDamageCounters ?: spreadBenchDamageCounters ?: spreadOpponentDamageCounters!!).groupValues[1].toInt(),
                    distribute = spreadBenchDamageCounters != null || spreadOpponentDamageCounters != null,
                    benchedOnly = spreadOpponentDamageCounters == null)
                damagedOpponentCounters != null -> effects += Effect.DamageCountersOnDamagedOpponentPokemon(damagedOpponentCounters.groupValues[1].toInt())
                grassDiscardEnergyTargetCounters -> effects += Effect.GrassDiscardEnergyTargetCounters
                placeDamageCounter -> effects += Effect.PlaceDamageCounter
                discardTeamRocketEnergyToDiscardOpponentActive -> effects += Effect.DiscardTeamRocketEnergyToDiscardOpponentActive
                multiTargetDamage != null -> {
                    effects += Effect.DiscardAttackEnergy(multiTargetDamage.groupValues[1].toInt())
                    effects += Effect.MultiTargetDamage(multiTargetDamage.groupValues[2].toInt(), multiTargetDamage.groupValues[3].toInt())
                }
                splitBombDamage != null -> effects += Effect.MultiTargetDamage(
                    splitBombDamage.groupValues[1].toInt(), splitBombDamage.groupValues[2].toInt())
                knockOutBothActive -> effects += Effect.KnockOutBothActive
                opponentBasicKnockout -> effects += Effect.KnockOutOpponentActiveIfBasic
                opponentActiveSpecialConditionKnockout -> effects += Effect.KnockOutOpponentActiveIfSpecialCondition
                opponentActiveExactlySixCountersKnockout -> effects += Effect.KnockOutOpponentActiveAtExactlySixDamageCounters
                knockOutLeastHpPokemon -> effects += Effect.KnockOutPokemonWithLeastHpRemainingExceptAttacker
                protectAttackerOnDamageKnockout -> effects += Effect.ProtectAttackerNextTurnIfKnocksOutByDamage
                repeatedMultiTargetDamage != null -> effects += Effect.MultiTargetDamage(
                    repeatedMultiTargetDamage.groupValues[2].toInt(), repeatedMultiTargetDamage.groupValues[1].toInt(),
                    allowRepeat = true, ignoreWeaknessResistance = true)
                multiTargetDamageIgnoringEffects != null -> {
                    effects += Effect.MultiTargetDamage(multiTargetDamageIgnoringEffects.groupValues[1].toInt(),
                        multiTargetDamageIgnoringEffects.groupValues[2].toInt(), ignoreWeaknessResistance = true)
                    effects += Effect.IgnoreOpponentActiveEffects
                }
                selectedMultiTargetDamage != null -> effects += Effect.MultiTargetDamage(
                    selectedMultiTargetDamage.groupValues[1].toInt(), selectedMultiTargetDamage.groupValues[2].toInt(),
                    ignoreWeaknessResistanceForBench = true)
                text == "This Pokémon recovers from all Special Conditions." -> effects += Effect.RecoverSelfFromSpecialConditions
                text == "Move an Energy from 1 of your opponent's Pokémon to another of their Pokémon." -> effects += Effect.MoveEnergyBetweenOpponentPokemon
                text == "You may move an Energy from your opponent's Active Pokémon to 1 of their Benched Pokémon." -> effects += Effect.MoveEnergyFromOpponentActiveToBench
                text == "Move all damage counters from 1 of your Benched Pokémon to 1 of your opponent's Pokémon." -> effects += Effect.MoveOwnBenchDamageCountersToOpponent
                text == "During your opponent's next turn, this Pokémon has no Weakness." -> effects += Effect.NoWeaknessNextTurn
                discardDefendingCoin != null -> effects += Effect.DiscardDefendingEnergy(if (discardDefendingCoin.groupValues[1] in listOf("an", "a")) 1 else discardDefendingCoin.groupValues[1].toInt(), onHeads = true)
                discardDefending != null -> effects += Effect.DiscardDefendingEnergy(
                    if (discardDefending.groupValues[1] in listOf("an", "a")) 1 else discardDefending.groupValues[1].toInt(),
                    type = discardDefending.groupValues[2].takeIf { it.startsWith("{") }?.let { symbolType(it.removeSurrounding("{", "}")) },
                    specialOnly = discardDefending.groupValues[2] == "Special")
                discardAllOpponentSpecialEnergy -> effects += Effect.DiscardAllOpponentSpecialEnergy
                discardOpponentToolsAndSpecialEnergy -> effects += Effect.DiscardOpponentToolsAndSpecialEnergy
                draw != null -> effects += Effect.Draw(if (draw.groupValues[1] == "a") 1 else draw.groupValues[1].toInt())
                eachPlayerDraw != null -> effects += Effect.EachPlayerDraw(eachPlayerDraw.groupValues[1].toInt())
                shuffleHandDraw != null -> effects += Effect.ShuffleHandDraw(shuffleHandDraw.groupValues[1].toInt())
                energySearchAnyType -> effects += Effect.EnergyFromDeck(1, null)
                energySearchSelfPoison != null -> effects += Effect.EnergyFromDeck(
                    energySearchSelfPoison.groupValues[1].toInt(),
                    symbolType(energySearchSelfPoison.groupValues[2]) ?: return null,
                    poisonSelfIfAttached = true)
                energySearch != null || energySearchUpTo != null -> effects += Effect.EnergyFromDeck(
                    energySearchUpTo?.groupValues?.get(1)?.toInt() ?: 1,
                    symbolType(energySearchUpTo?.groupValues?.get(2) ?: energySearch!!.groupValues[1]) ?: return null)
                namedPokemonBenchSearch != null && namedPokemonBenchSearch.groupValues[2] != "Basic Pokémon" -> effects += Effect.NamedPokemonToBench(namedPokemonBenchSearch.groupValues[2], namedPokemonBenchSearch.groupValues[1].toInt())
                benchSearch != null -> effects += Effect.BasicPokemonToBench(benchSearch.groupValues[1].toIntOrNull() ?: 1)
                recoverPokemonType != null -> effects += Effect.RecoverPokemonTypeToBench(
                    symbolType(recoverPokemonType.groupValues[2]) ?: return null, recoverPokemonType.groupValues[1].toInt())
                recoverPokemonToHand != null -> effects += Effect.RecoverPokemonToHand(if (recoverPokemonToHand.groupValues[1] == "a") 1 else recoverPokemonToHand.groupValues[1].toInt())
                recoverNamedPokemon != null -> effects += Effect.RecoverNamedPokemonToBench(recoverNamedPokemon.groupValues[2], recoverNamedPokemon.groupValues[1].toInt())
                rotomSearch != null -> effects += Effect.SearchRotomToBench
                recoil != null -> effects += Effect.Recoil(recoil.groupValues[1].toInt())
                heal != null -> effects += Effect.Heal(heal.groupValues[1].toInt())
                Regex("During your next turn, this Pokémon can't use (.+)\\.").matchEntire(text) != null -> effects += Effect.RestrictNamedAttackNextTurn(Regex("During your next turn, this Pokémon can't use (.+)\\.").matchEntire(text)!!.groupValues[1])
                Regex("This Pokémon can't use (.+) again until it leaves the Active Spot\\.").matchEntire(text)?.groupValues?.get(1) == raw.s("name") -> effects += Effect.CannotRepeatAttackUntilLeavingActive
                text == "During your next turn, this Pokémon can't attack." -> effects += Effect.RestrictNextTurn()
                text == "During your next turn, this Pokémon can't use attacks." -> effects += Effect.RestrictNextTurn()
                text == "During your opponent's next turn, they can't play any Item cards from their hand." -> effects += Effect.LockOpponentItemsNextTurn
                text == "Before doing damage, discard all Pokémon Tools from your opponent's Active Pokémon." -> effects += Effect.DiscardDefendingTools
                text == "During your opponent's next turn, the Defending Pokémon can't retreat." -> effects += Effect.PreventRetreatNextTurn
                text == "During your opponent's next turn, they can't play any Pokémon from their hand to evolve their Pokémon." -> effects += Effect.LockOpponentEvolutionNextTurn
                reduceEvolutionDamage != null -> effects += Effect.ReduceEvolutionDamageNextTurn(reduceEvolutionDamage.groupValues[1].toInt())
                preventEvolutionDamage -> effects += Effect.PreventEvolutionPokemonDamageNextTurn
                preventAbilityPokemonDamage -> effects += Effect.PreventAbilityPokemonDamageNextTurn
                preventBurnedPokemonDamage -> effects += Effect.PreventBurnedPokemonDamageNextTurn
                preventDamageAtMost != null -> effects += Effect.PreventAttackDamageAtMostNextTurn(preventDamageAtMost.groupValues[1].toInt())
                retaliationCounters != null -> effects += Effect.RetaliateDamageCountersNextTurn(retaliationCounters.groupValues[1].toInt())
                reduceDamage != null -> effects += Effect.ReduceDamageNextTurn(reduceDamage.groupValues[1].toInt())
                reduceDefenderAttack != null -> effects += Effect.ReduceDefendingAttackDamageNextTurn(reduceDefenderAttack.groupValues[1].toInt())
                preventBasicNonColorlessDamage != null -> effects += Effect.PreventBasicNonColorlessDamageNextTurn
                preventBasicDamage -> effects += Effect.PreventBasicPokemonDamageNextTurn
                text == "This Pokémon is now Asleep." -> effects += Effect.StatusCondition(SpecialCondition.ASLEEP, onSelf = true)
                text == "This Pokémon is now Confused." -> effects += Effect.StatusCondition(SpecialCondition.CONFUSED, onSelf = true)
                text == "Your opponent's Active Pokémon is now Confused." -> effects += Effect.StatusCondition(SpecialCondition.CONFUSED)
                text == "During your opponent's next turn, the Defending Pokémon can't attack." ||
                    text == "During your opponent's next turn, the Defending Pokémon can't use attacks." -> effects += Effect.PreventDefendingAttackNextTurn
                text == "During your opponent's next turn, if the Defending Pokémon tries to use an attack, your opponent flips a coin. If tails, that attack doesn't happen." -> effects += Effect.DefendingAttackFailsOnTailsNextTurn
                text == "Switch this Pokémon with 1 of your Benched Pokémon." -> effects += Effect.SwitchOwn()
                text == "You may switch this Pokémon with 1 of your Benched Pokémon." -> effects += Effect.SwitchOwn(optional = true)
                returnEnergyToHand -> effects += Effect.ReturnAttachedEnergyToHand
                optionalWaterEnergyDamageBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OptionalReturnWaterEnergyDamageBonus(optionalWaterEnergyDamageBonus.groupValues[1].toInt())
                }
                optionalLightningParalysis != null -> effects += Effect.OptionalDiscardLightningForParalysis(optionalLightningParalysis.groupValues[1].toInt())
                text == "Switch out your opponent's Active Pokémon to the Bench. (Your opponent chooses the new Active Pokémon.)" -> effects += Effect.SwitchOpponent
                text == "This attack's damage isn't affected by Weakness or Resistance." -> effects += Effect.IgnoreWeaknessResistance
                text == "This attack's damage isn't affected by Resistance." -> effects += Effect.IgnoreResistance
                text == "This attack's damage isn't affected by any effects on your opponent's Active Pokémon." -> effects += Effect.IgnoreOpponentActiveEffects
                requiresOpponentBurned -> effects += Effect.RequiresOpponentBurned
                requiredOpponentCondition != null -> effects += Effect.RequiresOpponentCondition(SpecialCondition.valueOf(requiredOpponentCondition.groupValues[1].uppercase()))
                requiresStadium -> effects += Effect.RequiresStadium
                text == "This attack's damage isn't affected by Weakness or Resistance, or by any effects on your opponent's Active Pokémon." -> {
                    effects += Effect.IgnoreWeaknessResistance
                    effects += Effect.IgnoreOpponentActiveEffects
                }
                discardOwn != null -> {
                    val count = discardOwn.groupValues[1]
                    effects += Effect.DiscardAttackEnergy(if (count in listOf("an", "a")) 1 else count.toInt(),
                        discardOwn.groupValues[2].takeIf(String::isNotEmpty)?.let(::symbolType))
                }
                Regex("Discard (an|a|1|2|3) Energy from your opponent's Active Pokémon\\.").matchEntire(text) != null -> {
                    val count = Regex("Discard (an|a|1|2|3) Energy from your opponent's Active Pokémon\\.").matchEntire(text)!!.groupValues[1]
                    effects += Effect.DiscardDefendingEnergy(if (count in listOf("an", "a")) 1 else count.toInt())
                }
                discardAllEnergyTakePrize -> {
                    effects += Effect.DiscardAttackEnergy(0, all = true)
                    effects += Effect.TakePrizeCard
                }
                discardAllOwn != null -> effects += Effect.DiscardAttackEnergy(0,
                    discardAllOwn.groupValues[1].takeIf(String::isNotEmpty)?.let(::symbolType), all = true)
                Regex("During your next turn, this Pokémon can't use (.+)\\.").matchEntire(text) != null -> {
                    val attackName = Regex("During your next turn, this Pokémon can't use (.+)\\.").matchEntire(text)!!.groupValues[1]
                    if (attackName != raw.s("name")) return null
                    effects += Effect.RestrictNextTurn("$id-a$index")
                }
                else -> return null
            }
        }
        return AttackDefinition("$id-a$index", raw.s("name") ?: return null, cost, effects, text)
    }
}

