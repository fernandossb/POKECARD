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
        "P" -> "Psychic"; "M" -> "Metal"; "D" -> "Darkness"; "C" -> "Colorless"; else -> return null
        }
        return type(value)
    }
    private fun signature(raw: JsonObject) = listOf("category", "name", "trainerType", "energyType", "effect").map { raw[it] } +
        JsonPrimitive(raw.s("rarity").orEmpty().contains("ACE SPEC", ignoreCase = true))

    fun basicEnergy(raw: JsonObject): Boolean = raw.s("category") == "Energy" &&
        Regex("(?:Basic )?(?:Grass|Fire|Water|Lightning|Psychic|Fighting|Darkness|Metal) Energy").matches(raw.s("name").orEmpty())

    private fun compileAbility(id: String, index: Int, raw: JsonObject): AbilityDefinition? {
        val name = raw.s("name") ?: return null
        val text = raw.s("effect")?.trim().orEmpty()
        if (text == "All of your Pokémon that have any {W} Energy attached take 50 less damage from attacks from your opponent's Pokémon (after applying Weakness and Resistance). The effect of Tundra Wall doesn't stack.")
            return AbilityDefinition("$id-ability$index", name, text, tundraWallWaterDamageReduction = 50)
        if (text == "As often as you like during your turn, you may use this Ability. Move an Energy from 1 of your other Pokémon to this Pokémon.")
            return AbilityDefinition("$id-ability$index", name, text, moveEnergyFromOtherPokemonToSelf = true, unlimitedUse = true)
        if (text == "All of your Pokémon that have {M} Energy attached have no Retreat Cost.")
            return AbilityDefinition("$id-ability$index", name, text, freeRetreatWithAttachedEnergyType = EnergyType.METAL)
        if (text == "Once during your turn, if this Pokémon is in the Active Spot, you may devolve 1 of your opponent's evolved Pokémon by putting the highest Stage Evolution card on it into your opponent's hand.")
            return AbilityDefinition("$id-ability$index", name, text, requiresActive = true, usageKey = name, devolveOpponentPokemonMax = 1)
        if (text == "Your opponent's Active Evolution Pokémon's Retreat Cost is {C} more.")
            return AbilityDefinition("$id-ability$index", name, text, opponentActiveEvolutionRetreatCostBonus = 1)
        val searchEnergyToHand = Regex("Once during your turn, you may use this Ability\\. Search your deck for up to (\\d+) Basic \\{([GRFLWPMD])} Energy cards, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        if (searchEnergyToHand != null) return AbilityDefinition("$id-ability$index", name, text,
            usageKey = name, searchBasicEnergyTypeToHand = symbolType(searchEnergyToHand.groupValues[2]) ?: return null,
            searchBasicEnergyMaxToHand = searchEnergyToHand.groupValues[1].toInt())
        val evolveMetal = Regex("When you play this Pokémon from your hand to evolve 1 of your Pokémon during your turn, you may attach up to (\\d+) Basic \\{M} Energy cards from your discard pile to your \\{M} Pokémon in any way you like\\.").matchEntire(text)
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
        val equalHandBonus = Regex("If you have the same number of cards in your hand as your opponent, attacks used by this Pokémon do (\\d+) more damage to your opponent's Active Pokémon \\(before applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (equalHandBonus != null) return AbilityDefinition("$id-ability$index", name, text,
            damageBonusIfHandsEqual = equalHandBonus.groupValues[1].toInt())
        val damageWhenDamaged = Regex("If this Pokémon has (\\d+) or more damage counters on it, attacks used by this Pokémon do (\\d+) more damage to your opponent's Active Pokémon \\(before applying Weakness and Resistance\\)\\.").matchEntire(text)
        if (damageWhenDamaged != null) return AbilityDefinition("$id-ability$index", name, text,
            damageBonusAgainstActiveWhenDamageAtLeast = damageWhenDamaged.groupValues[1].toInt() * 10,
            damageBonusAmount = damageWhenDamaged.groupValues[2].toInt())
        val counterattack = Regex("If this Pokémon is in the Active Spot and is damaged by an attack from your opponent's Pokémon \\(even if this Pokémon is Knocked Out\\), put (\\d+) damage counters on the Attacking Pokémon\\.").matchEntire(text)
        if (counterattack != null) return AbilityDefinition("$id-ability$index", name, text,
            requiresActive = true, retaliateDamageCounters = counterattack.groupValues[1].toInt(), usageKey = name)
        val attachEnergyDraw = Regex("Once during your turn, you may attach a Basic \\{([GRFLWPMD])} Energy card from your hand to this Pokémon\\. If you attached Energy to a Pokémon in this way, draw a card\\.").matchEntire(text)
        if (attachEnergyDraw != null) return AbilityDefinition("$id-ability$index", name, text,
            usageKey = name, attachHandEnergyType = symbolType(attachEnergyDraw.groupValues[1]) ?: return null,
            drawIfAttached = 1, usagePerPokemon = true)
        val surviveAttackKnockout = Regex("If this Pokémon has full HP and would be Knocked Out by damage from an attack, it is not Knocked Out, and its remaining HP becomes (\\d+)\\.").matchEntire(text)
        if (surviveAttackKnockout != null) return AbilityDefinition("$id-ability$index", name, text,
            surviveAttackKnockoutAtHp = surviveAttackKnockout.groupValues[1].toInt())
        val surviveAttackKnockoutOnHeads = Regex("If this Pokémon would be Knocked Out by damage from an attack, flip a coin. If heads, this Pokémon is not Knocked Out, and its remaining HP becomes (\\d+)\\.").matchEntire(text)
        if (surviveAttackKnockoutOnHeads != null) return AbilityDefinition("$id-ability$index", name, text,
            surviveAttackKnockoutOnHeadsAtHp = surviveAttackKnockoutOnHeads.groupValues[1].toInt())
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
        val suffix = raw.s("suffix").orEmpty()
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
        return CardDefinition(id, raw.s("name")!!, raw.s("setCode").orEmpty(), raw.s("localId")!!,
            category = Category.POKEMON, stage = stage, evolvesFromName = raw.s("evolveFrom"),
            hp = hp, type = element, weakness = type(weakness?.s("type")),
            resistance = type(resistance?.s("type")), retreatCost = retreat, attacks = attacks,
            prizes = prizes, abilities = abilities, verified = true, source = source,
            artAsset = image.takeIf(String::isNotBlank)?.let { "remote:$it/high.webp" })
    }

    fun compileAttack(id: String, index: Int, raw: JsonObject): AttackDefinition? {
        val cost = raw.a("cost").map { type(it.jsonPrimitive.content) ?: return null }
        val text = raw.s("effect").orEmpty().trim()
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
        val coin = coinFixed != null || coinUntilTails != null || coinPerEnergy != null || coinPerEnergyBoth != null
        val coinBonus = Regex("Flip a coin\\. If heads, this attack does (\\d+) more damage\\.").matchEntire(text)
        val coinBonusMultiple = Regex("Flip (\\d{1,2}) coins?\\. This attack does (\\d+) more damage for each heads\\.").matchEntire(text)
        val coinBonusUntilTails = Regex("Flip a coin until you get tails\\. This attack does (\\d+) more damage for each heads\\.").matchEntire(text)
        val conditionalEnergy = Regex("If this Pokémon has (?:at least )?(\\d+) or more \\{([GRFLWPMD])} Energy attached, this attack does (\\d+) more damage\\.").matchEntire(text)
        val conditionalInPlayEnergy = Regex("If you have (?:at least )?(\\d+)(?: or more)? \\{([GRFLWPMD])} Energy in play, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownKnockoutBonus = Regex("If any of your Pokémon were Knocked Out by damage from an attack during your opponent's last turn, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownUndamagedBonus = Regex("If this Pokémon has no damage counters on it, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentExBonus = Regex("If your opponent's Active Pokémon is a Pokémon ex(?: or Pokémon V)?, this attack does (\\d+) more damage\\.").matchEntire(text)
        val opponentTypeBonus = Regex("If your opponent's Active Pokémon is a \\{([GRFLWPMD])} Pokémon, this attack does (\\d+) more damage\\.").matchEntire(text)
        val ownEnergyBonus = Regex("This attack does (\\d+) more damage for each Energy attached to this Pokémon\\.").matchEntire(text)
        val opponentEnergyBonus = Regex("This attack does (\\d+) more damage for each Energy attached to your opponent's Active Pokémon\\.").matchEntire(text)
        val opponentRetreatCostBonus = Regex("This attack does (\\d+) more damage for each \\{C} in your opponent's Active Pokémon's Retreat Cost\\.").matchEntire(text)
        val ownBenchEnergyBonus = Regex("This attack does (\\d+) more damage for each of your Benched Pokémon that has any \\{([GRFLWPMD])} Energy attached\\.").matchEntire(text)
        val opponentConditionBonus = Regex("If your opponent's Active Pokémon is affected by a Special Condition, this attack does (\\d+) more damage\\.").matchEntire(text)
        val benchedDamage = Regex("This attack does (\\d+) more damage for each Benched Pokémon \\(both yours and your opponent's\\)\\.").matchEntire(text)
        val ownInPlayDamage = Regex("This attack does (\\d+) damage for each of your Pokémon in play\\.").matchEntire(text)
        val ownBenchDamage = Regex("This attack does (\\d+) damage for each of your Benched Pokémon\\.").matchEntire(text)
        val ownDamagedPokemon = Regex("This attack does (\\d+) damage for each of your Pokémon that has any damage counters on it\\.").matchEntire(text)
        val ownDamageCounters = Regex("This attack does (\\d+) damage for each damage counter on this Pokémon\\.").matchEntire(text)
        val opponentFieldDamageCounters = Regex("This attack does (\\d+) more damage for each damage counter on all of your opponent's Pokémon\\.").matchEntire(text)
        val ownPsychicEnergyDamage = Regex("This attack does (\\d+) more damage for each \\{P} Energy attached to this Pokémon\\.").matchEntire(text)
        val ownHandCounters = Regex("Place (\\d+) damage counters on your opponent's Active Pokémon for each card in your hand\\.").matchEntire(text)
        val ownToolDamage = Regex("This attack does (\\d+) damage for each Pokémon Tool attached to all of your Pokémon\\.").matchEntire(text)
        val discardTopEnergyDamage = Regex("Discard the top (\\d+) cards of your deck, and this attack does (\\d+) damage for each Basic \\{([GRFLWPMD])} Energy card you discarded in this way\\.").matchEntire(text)
        val bothActiveEnergyDamage = Regex("This attack does (\\d+) more damage for each Energy attached to both Active Pokémon\\.").matchEntire(text)
        val moveOpponentDamageCounters = text == "Your opponent's Active Pokémon is now Confused. You may move any number of damage counters from your opponent's Pokémon to their other Pokémon in any way you like."
        val pullOpponentBench = text == "Switch in 1 of your opponent's Benched Pokémon to the Active Spot."
        val recoverSupporter = text == "Put a Supporter card from your discard pile into your hand."
        val benchSearch = Regex("Search your deck for up to (\\d+) Basic Pokémon and put them onto your Bench\\. Then, shuffle your deck\\.|Search your deck for a Basic Pokémon and put it onto your Bench\\. Then, shuffle your deck\\.").matchEntire(text)
        val rotomSearch = Regex("""You may search your deck for any number of Pokémon that have "Rotom" in their name and put them onto your Bench\. Then, shuffle your deck\.""").matchEntire(text)
        val matchingEnergySearch = Regex("Search your deck for up to (\\d+) Pokémon that are the same type as any Basic Energy attached to this Pokémon, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val basicEnergyToHandSearch = Regex("Search your deck for up to (\\d+) Basic Energy cards, reveal them, and put them into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val pokemonSearchToHand = Regex("Search your deck for a Pokémon, reveal it, and put it into your hand\\. Then, shuffle your deck\\.").matchEntire(text)
        val attachAnyBasicEnergy = text == "You may attach any number of Basic Energy cards from your hand to your Pokémon in any way you like."
        val attachBasicEnergyFromDeckAny = Regex("Search your deck for up to (\\d+) Basic Energy cards and attach them to your Pokémon in any way you like\\. Then, shuffle your deck\\.").matchEntire(text)
        val coinKnockOutBasic = text == "Flip a coin. If heads, Knock Out your opponent's Active Basic Pokémon. If tails, Knock Out 1 of your opponent's Benched Basic Pokémon."
        val coinStatus = Regex("Flip a coin\\. If heads, your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused)\\.").matchEntire(text)
        val status = Regex("Your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused)\\.").matchEntire(text)
        val discardDefendingCoin = Regex("Flip a coin\\. If heads, discard (an|a|1|2) Energy from your opponent's Active Pokémon\\.").matchEntire(text)
        val discardDefending = Regex("Discard (an|a|1|2) Energy from your opponent's Active Pokémon\\.").matchEntire(text)
        val benchDamage = Regex("This attack also does (\\d+) damage to 1 of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val discardFireTargetedBench = Regex("Discard all \\{R} Energy from this Pokémon, and this attack does (\\d+) damage to 1 of your opponent's Benched Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val benchDamageCounters = Regex("Place (\\d+) damage counters on 1 of your opponent's Benched Pokémon\\.").matchEntire(text)
        val multiTargetDamage = Regex("Discard (\\d+) Energy from this Pokémon, and this attack does (\\d+) damage to each of (\\d+) of your opponent's Pokémon\\. \\(Don't apply Weakness and Resistance for Benched Pokémon\\.\\)").matchEntire(text)
        val knockOutBothActive = text == "Both Active Pokémon are Knocked Out."
        val repeatedMultiTargetDamage = Regex("Choose 1 of your opponent's Pokémon (\\d+) times\\. \\(You can choose the same Pokémon more than once\\.\\) For each time you chose a Pokémon, do (\\d+) damage to it\\. This damage isn't affected by Weakness or Resistance\\.").matchEntire(text)
        val opponentDiscard = Regex("Discard a random card from your opponent's hand\\.").matchEntire(text)
        val opponentShuffleRandom = Regex("Choose a random card from your opponent's hand(?:, and your opponent reveals that card and shuffles it into their deck|\\. Your opponent reveals that card and shuffles it into their deck)\\.").matchEntire(text)
        val moveEnergy = Regex("Move an Energy from this Pokémon to (\\d+) of your Benched Pokémon\\.").matchEntire(text)
        val confusePoisonThenSwitch = text == "Your opponent's Active Pokémon is now Confused and Poisoned. Switch this Pokémon with 1 of your Benched Pokémon."
        val statusThenRetreat = Regex("Your opponent's Active Pokémon is now (Poisoned|Burned|Asleep|Paralyzed|Confused)\\. During your opponent's next turn, (?:that Pokémon|the Defending Pokémon) can't retreat\\.").matchEntire(text)
        val exactHandSize = Regex("If you don't have exactly (\\d+) cards? in your hand, this attack does nothing\\.").matchEntire(text)
        val opponentDeckDiscard = Regex("Discard the top (?:card|([2-5]) cards) of your opponent's deck\\.").matchEntire(text)
        val benchEnergyBonus = Regex("You may discard up to (\\d+) Energy from your Benched Pokémon\\. This attack does (\\d+) more damage for each card you discarded in this way\\.").matchEntire(text)
        val reduceDamage = Regex("During your opponent's next turn, this Pokémon takes (\\d+) less damage from attacks \\(after applying Weakness and Resistance\\)\\.").matchEntire(text)
        val reduceDefenderAttack = Regex("During your opponent's next turn, attacks used by the Defending Pokémon do (\\d+) less damage \\(before applying Weakness and Resistance\\)\\.").matchEntire(text)
        val preventBasicNonColorlessDamage = Regex("During your opponent's next turn, prevent all damage done to this Pokémon by attacks from Basic non-\\{C} Pokémon\\.").matchEntire(text)
        val preventBasicDamage = text == "During your opponent's next turn, prevent all damage done to this Pokémon by attacks from Basic Pokémon."
        val discardOwn = Regex("Discard (an|a|1|2|3) (?:\\{([GRFLWPMD])} )?Energy from this Pokémon\\.").matchEntire(text)
        val discardAllOwn = Regex("Discard all (?:\\{([GRFLWPMD])} )?Energy from this Pokémon\\.").matchEntire(text)
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
        } else if (discardTopEnergyDamage != null) {
            val perEnergy = discardTopEnergyDamage.groupValues[2].toInt()
            if (damageText != "${perEnergy}×" && damageText != "${perEnergy}x") return null
            effects += Effect.DiscardTopDeckEnergyDamage(discardTopEnergyDamage.groupValues[1].toInt(),
                symbolType(discardTopEnergyDamage.groupValues[3]) ?: return null, perEnergy)
        } else if (ownInPlayDamage != null || ownBenchDamage != null || ownDamagedPokemon != null || ownDamageCounters != null || ownHandCounters != null || ownToolDamage != null) {
            val formula = ownInPlayDamage ?: ownBenchDamage ?: ownDamagedPokemon ?: ownDamageCounters ?: ownHandCounters ?: ownToolDamage!!
            val amount = formula.groupValues[1].toInt()
            if (ownHandCounters != null) {
                if (damageText.isNotEmpty()) return null
                effects += Effect.OwnHandCounterDamage(amount)
            } else if (ownToolDamage != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.OwnToolCountDamage(amount)
            } else if (ownBenchDamage != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.OwnBenchCountDamage(amount)
            } else if (ownDamagedPokemon != null) {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                effects += Effect.OwnDamagedPokemonCountDamage(amount)
            } else {
                if (damageText != "${amount}×" && damageText != "${amount}x") return null
                if (ownInPlayDamage != null) effects += Effect.OwnInPlayCountDamage(amount)
                else effects += Effect.OwnDamageCounterDamage(amount)
            }
        } else if (discardFireTargetedBench != null) {
            if (damageText.isNotEmpty()) return null
            effects += Effect.DiscardAttackEnergy(0, EnergyType.FIRE, all = true)
            effects += Effect.TargetedDamage(discardFireTargetedBench.groupValues[1].toInt(), benchedOnly = true)
        } else if (coin) {
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
        } else {
            val damage = if (damageText.isEmpty()) 0 else
                (if (coinBonus != null || coinBonusMultiple != null || coinBonusUntilTails != null || conditionalEnergy != null || conditionalInPlayEnergy != null || ownKnockoutBonus != null || ownUndamagedBonus != null || opponentExBonus != null || opponentTypeBonus != null || ownEnergyBonus != null || opponentEnergyBonus != null || opponentRetreatCostBonus != null || ownBenchEnergyBonus != null || opponentConditionBonus != null || bothActiveEnergyDamage != null || benchedDamage != null || benchEnergyBonus != null) damageText.removeSuffix("+") else damageText).toIntOrNull() ?: return null
            if (damage < 0) return null
            if (damage > 0) effects += Effect.Damage(damage)
            val draw = Regex("Draw (a|1|2|3) cards?\\.").matchEntire(text)
            val drawToHand = Regex("You may draw cards until you have (\\d+) cards in your hand\\.").matchEntire(text)
            val recoil = Regex("This Pokémon also does (\\d+) damage to itself\\.").matchEntire(text)
            val selfOnly = Regex("This Pokémon does (\\d+) damage to itself\\.").matchEntire(text)
            val heal = Regex("Heal (\\d+) damage from this Pokémon\\.").matchEntire(text)
            val healAnyPokemon = Regex("Heal (\\d+) damage from 1 of your Pokémon\\.").matchEntire(text)
            val energySearch = Regex("Search your deck for a Basic \\{([GFRLWPMD])} Energy card and attach it to this Pokémon\\. Then, shuffle your deck\\.").matchEntire(text)
        val energySearchUpTo = Regex("Search your deck for up to (\\d+) Basic \\{([GFRLWPMD])} Energy cards and attach them to this Pokémon\\. Then, shuffle your deck\\.").matchEntire(text)
            when {
                text.isEmpty() -> Unit
                healAnyPokemon != null -> effects += Effect.HealOwnPokemon(healAnyPokemon.groupValues[1].toInt())
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
                pokemonSearchToHand != null -> effects += Effect.SearchPokemonToHand()
                attachAnyBasicEnergy -> effects += Effect.AttachAnyBasicEnergyFromHand
                attachBasicEnergyFromDeckAny != null -> effects += Effect.AttachBasicEnergyFromDeckAny(attachBasicEnergyFromDeckAny.groupValues[1].toInt())
                coinKnockOutBasic -> effects += Effect.CoinKnockOutBasic
                statusThenRetreat != null -> {
                    val condition = SpecialCondition.valueOf(statusThenRetreat.groupValues[1].uppercase())
                    effects += Effect.StatusCondition(condition)
                    effects += Effect.PreventRetreatNextTurn
                }
                exactHandSize != null -> effects += Effect.RequiresExactHandSize(exactHandSize.groupValues[1].toInt())
                multiStatus != null -> multiStatus.forEach { effects += Effect.StatusCondition(it) }
                selfOnly != null && damage == 0 -> effects += Effect.SelfDamage(selfOnly.groupValues[1].toInt())
                text == "Flip a coin. If tails, this attack does nothing." -> effects += Effect.CoinMiss
                text == "Flip a coin. If heads, during your opponent's next turn, prevent all damage from and effects of attacks done to this Pokémon." ||
                    text == "Flip a coin. If heads, during your opponent's next turn, prevent all damage done to this Pokémon by attacks." -> effects += Effect.PreventAttackDamageNextTurnOnHeads
                moveEnergy != null -> effects += Effect.MoveEnergyToBench(moveEnergy.groupValues[1].toInt())
                benchEnergyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.DiscardBenchEnergyDamage(benchEnergyBonus.groupValues[1].toInt(), benchEnergyBonus.groupValues[2].toInt())
                }
                opponentExBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentExDamage(opponentExBonus.groupValues[1].toInt())
                }
                opponentTypeBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentTypeDamage(symbolType(opponentTypeBonus.groupValues[1]) ?: return null, opponentTypeBonus.groupValues[2].toInt())
                }
                ownEnergyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnEnergyCountDamage(ownEnergyBonus.groupValues[1].toInt())
                }
                opponentEnergyBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OpponentEnergyCountDamage(opponentEnergyBonus.groupValues[1].toInt())
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
                opponentDiscard != null -> effects += Effect.DiscardRandomOpponentHand(1)
                opponentShuffleRandom != null -> effects += Effect.ShuffleRandomOpponentHand(1)
                opponentDeckDiscard != null -> effects += Effect.DiscardOpponentDeck(opponentDeckDiscard.groupValues[1].toIntOrNull() ?: 1)
                drawToHand != null -> effects += Effect.DrawToHand(drawToHand.groupValues[1].toInt())
                coinBonus != null || coinBonusMultiple != null || coinBonusUntilTails != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.CoinDamage(when {
                        coinBonusUntilTails != null -> 0
                        coinBonusMultiple != null -> coinBonusMultiple.groupValues[1].toInt()
                        else -> 1
                    },
                        coinBonus?.groupValues?.get(1)?.toInt() ?: coinBonusMultiple?.groupValues?.get(2)?.toInt()
                        ?: coinBonusUntilTails?.groupValues?.get(1)?.toInt() ?: return null,
                        untilTails = coinBonusUntilTails != null, bonusOnly = true)
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
                ownUndamagedBonus != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.OwnUndamagedDamageBonus(ownUndamagedBonus.groupValues[1].toInt())
                }
                benchedDamage != null -> {
                    if (damage <= 0 || !damageText.endsWith("+")) return null
                    effects += Effect.BenchedCountDamage(benchedDamage.groupValues[1].toInt())
                }
                (status != null || coinStatus != null) && condition != null -> effects += Effect.StatusCondition(condition, onHeads = coinStatus != null)
                benchDamage != null -> effects += Effect.BenchDamage(benchDamage.groupValues[1].toInt())
                benchDamageCounters != null -> effects += Effect.BenchDamageCounters(benchDamageCounters.groupValues[1].toInt())
                multiTargetDamage != null -> {
                    effects += Effect.DiscardAttackEnergy(multiTargetDamage.groupValues[1].toInt())
                    effects += Effect.MultiTargetDamage(multiTargetDamage.groupValues[2].toInt(), multiTargetDamage.groupValues[3].toInt())
                }
                knockOutBothActive -> effects += Effect.KnockOutBothActive
                repeatedMultiTargetDamage != null -> effects += Effect.MultiTargetDamage(
                    repeatedMultiTargetDamage.groupValues[2].toInt(), repeatedMultiTargetDamage.groupValues[1].toInt(),
                    allowRepeat = true, ignoreWeaknessResistance = true)
                text == "This Pokémon recovers from all Special Conditions." -> effects += Effect.RecoverSelfFromSpecialConditions
                text == "During your opponent's next turn, this Pokémon has no Weakness." -> effects += Effect.NoWeaknessNextTurn
                discardDefendingCoin != null -> effects += Effect.DiscardDefendingEnergy(if (discardDefendingCoin.groupValues[1] in listOf("an", "a")) 1 else discardDefendingCoin.groupValues[1].toInt(), onHeads = true)
                discardDefending != null -> effects += Effect.DiscardDefendingEnergy(if (discardDefending.groupValues[1] in listOf("an", "a")) 1 else discardDefending.groupValues[1].toInt())
                draw != null -> effects += Effect.Draw(if (draw.groupValues[1] == "a") 1 else draw.groupValues[1].toInt())
                energySearch != null || energySearchUpTo != null -> effects += Effect.EnergyFromDeck(
                    energySearchUpTo?.groupValues?.get(1)?.toInt() ?: 1,
                    symbolType(energySearchUpTo?.groupValues?.get(2) ?: energySearch!!.groupValues[1]) ?: return null)
                benchSearch != null -> effects += Effect.BasicPokemonToBench(benchSearch.groupValues[1].toIntOrNull() ?: 1)
                rotomSearch != null -> effects += Effect.SearchRotomToBench
                recoil != null -> effects += Effect.Recoil(recoil.groupValues[1].toInt())
                heal != null -> effects += Effect.Heal(heal.groupValues[1].toInt())
                text == "During your next turn, this Pokémon can't attack." -> effects += Effect.RestrictNextTurn()
                text == "During your next turn, this Pokémon can't use attacks." -> effects += Effect.RestrictNextTurn()
                text == "Before doing damage, discard all Pokémon Tools from your opponent's Active Pokémon." -> effects += Effect.DiscardDefendingTools
                text == "During your opponent's next turn, the Defending Pokémon can't retreat." -> effects += Effect.PreventRetreatNextTurn
                reduceDamage != null -> effects += Effect.ReduceDamageNextTurn(reduceDamage.groupValues[1].toInt())
                reduceDefenderAttack != null -> effects += Effect.ReduceDefendingAttackDamageNextTurn(reduceDefenderAttack.groupValues[1].toInt())
                preventBasicNonColorlessDamage != null -> effects += Effect.PreventBasicNonColorlessDamageNextTurn
                preventBasicDamage -> effects += Effect.PreventBasicPokemonDamageNextTurn
                text == "This Pokémon is now Confused." -> effects += Effect.StatusCondition(SpecialCondition.CONFUSED, onSelf = true)
                text == "Your opponent's Active Pokémon is now Confused." -> effects += Effect.StatusCondition(SpecialCondition.CONFUSED)
                text == "During your opponent's next turn, the Defending Pokémon can't attack." ||
                    text == "During your opponent's next turn, the Defending Pokémon can't use attacks." -> effects += Effect.PreventDefendingAttackNextTurn
                text == "Switch this Pokémon with 1 of your Benched Pokémon." -> effects += Effect.SwitchOwn()
                text == "You may switch this Pokémon with 1 of your Benched Pokémon." -> effects += Effect.SwitchOwn(optional = true)
                text == "Switch out your opponent's Active Pokémon to the Bench. (Your opponent chooses the new Active Pokémon.)" -> effects += Effect.SwitchOpponent
                text == "This attack's damage isn't affected by Weakness or Resistance." -> effects += Effect.IgnoreWeaknessResistance
                text == "This attack's damage isn't affected by Resistance." -> effects += Effect.IgnoreResistance
                text == "This attack's damage isn't affected by any effects on your opponent's Active Pokémon." -> effects += Effect.IgnoreOpponentActiveEffects
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
