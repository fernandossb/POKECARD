package com.tcgtrainer.engine

class EffectEngine(private val catalog: CardCatalog) {
    fun effectiveHp(pokemon: PokemonInstance, state: GameState? = null, opponentPrizeCardsRemaining: Int = 6): Int {
        val def = catalog.get(pokemon.top.definitionId)
        val stadiumEffect = state?.stadium?.let { catalog.get(it.definitionId).trainerEffect } as? TrainerEffect.HpModifier
        val owner = state?.players?.indexOfFirst { player -> player.pokemon().any { it.id == pokemon.id } } ?: -1
        val prizesRemaining = if (owner >= 0) state!!.players[1-owner].prizes.size else opponentPrizeCardsRemaining
        val prizeBonus = def.abilities.sumOf { it.hpBonusPerOpponentPrizeTaken } * (6-prizesRemaining).coerceAtLeast(0)
        val energyBonus = def.abilities.sumOf { ability -> ability.hpBonusPerAttachedEnergy * pokemon.energies.count {
            ability.hpBonusPerAttachedEnergyType != null && catalog.get(it.definitionId).type == ability.hpBonusPerAttachedEnergyType
        } }
        return (def.hp + pokemon.hpModifier + prizeBonus + energyBonus + if (stadiumEffect?.stage == def.stage) stadiumEffect.amount else 0).coerceAtLeast(0)
    }
    fun damage(attacker: PokemonInstance, defender: PokemonInstance, effects: List<Effect>, boost: Int = 0,
        ignoreWeaknessResistance: Boolean = false, ownBenchCount: Int = 0, opponentBenchCount: Int = 0,
        ownInPlayCount: Int = ownBenchCount + 1, ignoreOpponentActiveEffects: Boolean = false,
        ignoreResistance: Boolean = false, ignoreWeakness: Boolean = false, turn: Int = -1, ownHandCount: Int = 0,
        opponentActiveIsEx: Boolean = false, targetIsOpponentActive: Boolean = true, opponentActiveEnergyCount: Int = 0,
        opponentHasSpecialCondition: Boolean = false, opponentActiveDamage: Int = 0, opponentHandCount: Int = 0, ownToolCount: Int = 0, ownDamagedPokemonCount: Int = 0,
        ownDamagedBenchedPokemonCount: Int = 0,
        ownEnergyCounts: Map<EnergyType, Int> = emptyMap(), ownKnockedOutByAttackLastTurn: Boolean = false,
        ownPokemonNames: List<String> = emptyList(),
        ownBenchPokemonNames: List<String> = emptyList(),
        ownBenchPokemonTypes: List<EnergyType> = emptyList(),
        friendlyPokemon: List<PokemonInstance> = listOf(attacker), friendlyBenchPokemon: List<PokemonInstance> = emptyList(),
        opponentDamageCounterCount: Int = 0, opponentHandEnergyCount: Int = 0, stadiumInPlay: Boolean = false,
        opponentActiveRetreatCost: Int = 0, ownBenchWithEnergyCounts: Map<EnergyType, Int> = emptyMap(),
        ownDiscardEnergyCount: Int = 0, ownSpecialEnergyCount: Int = 0, ownBasicDiscardEnergyCounts: Map<EnergyType, Int> = emptyMap(), opponentPrizeCardsRemaining: Int = 6,
        opponentBasicDiscardEnergyCount: Int = 0,
        ownDiscardPokemonAbilityNames: List<String> = emptyList(),
        ownBenchPokemonDetails: List<Pair<String, Int>> = emptyList(),
        ownBenchPokemonTypeDetails: List<Pair<EnergyType, Int>> = emptyList(),
        allPokemonToolCount: Int = 0, attackerPokemon: List<PokemonInstance> = listOf(attacker)): Int {
        if (defender.attackImmunityTurn >= 0 && turn == defender.attackImmunityTurn) return 0
        val source = catalog.get(attacker.top.definitionId)
        val target = catalog.get(defender.top.definitionId)
        if (Effect.RequiresOpponentBurned in effects && (!targetIsOpponentActive || SpecialCondition.BURNED !in defender.conditions)) return 0
        if (target.abilities.any { it.preventDamageFromPokemonWithAbilities } && source.abilities.isNotEmpty()) return 0
        if (target.abilities.any { it.preventDamageFromSpecialEnergyAttacker } && attacker.energies.any {
                catalog.get(it.definitionId).category == Category.ENERGY && !catalog.get(it.definitionId).basicEnergy
            }) return 0
        if (target.abilities.any { it.preventDamageFromOpponentEx } && source.name.endsWith(" ex")) return 0
        if (target.abilities.any { it.preventDamageFromBasicPokemonEx } && source.stage == Stage.BASIC && source.name.endsWith(" ex")) return 0
        if (friendlyBenchPokemon.any { benchPokemon ->
                catalog.get(benchPokemon.top.definitionId).abilities.any { ability ->
                    ability.benchDamageImmunityForAttackerMaxEnergy > 0 && attacker.energies.size <= ability.benchDamageImmunityForAttackerMaxEnergy
                }
            }) return 0
        if (defender.preventBasicNonColorlessDamageTurn >= 0 && defender.preventBasicNonColorlessDamageTurn == turn &&
            source.stage == Stage.BASIC && source.type != EnergyType.COLORLESS) return 0
        if (defender.preventBasicPokemonDamageTurn >= 0 && defender.preventBasicPokemonDamageTurn == turn && source.stage == Stage.BASIC) return 0
        val targetPreventsEffects = target.abilities.any { it.preventOpponentEffects ||
            it.preventDamageFromSpecialEnergyAttacker && attacker.energies.any { energy ->
                catalog.get(energy.definitionId).category == Category.ENERGY && !catalog.get(energy.definitionId).basicEnergy
            } }
        val counterDamage = if (targetPreventsEffects) 0 else effects.filterIsInstance<Effect.OwnHandCounterDamage>().sumOf { it.countersPerCard * 10 * ownHandCount }
        val abilityHandBonus = if (ownHandCount == opponentHandCount) source.abilities.sumOf { it.damageBonusIfHandsEqual } else 0
        val abilityDamageBonus = if (targetIsOpponentActive) source.abilities.sumOf { ability ->
            if (ability.damageBonusAgainstActiveWhenDamageAtLeast > 0 && attacker.damage >= ability.damageBonusAgainstActiveWhenDamageAtLeast)
                ability.damageBonusAmount else 0
        } else 0
        val futureDamageBonus = if (targetIsOpponentActive && source.name != "Iron Crown ex" &&
            (source.name.startsWith("Iron ") || source.name.startsWith("Miraidon")))
            attackerPokemon.flatMap { catalog.get(it.top.definitionId).abilities }.sumOf { it.futurePokemonDamageBonus } else 0
        val attackerTypes = setOf(source.type) + source.abilities.flatMap { it.additionalPokemonTypes }
        val teamAttackDamageBonus = if (targetIsOpponentActive)
            attackerPokemon.sumOf { pokemon -> catalog.get(pokemon.top.definitionId).abilities.filter { it.teamAttackDamageBonusType in attackerTypes }.sumOf { it.teamAttackDamageBonus } }
        else 0
        val ownInPlayTypes = attackerPokemon.flatMap { pokemon ->
            val def = catalog.get(pokemon.top.definitionId)
            listOf(def.type) + def.abilities.flatMap { it.additionalPokemonTypes }
        }.toSet()
        val opponentInPlayTypes = friendlyPokemon.flatMap { pokemon ->
            val def = catalog.get(pokemon.top.definitionId)
            listOf(def.type) + def.abilities.flatMap { it.additionalPokemonTypes }
        }.toSet()
        val sameTypeBonus = if (targetIsOpponentActive && ownInPlayTypes.intersect(opponentInPlayTypes).isNotEmpty())
            effects.filterIsInstance<Effect.SameTypePokemonInPlayDamageBonus>().sumOf { it.bonus } else 0
        val sameHandSizeBonus = if (ownHandCount == opponentHandCount)
            effects.filterIsInstance<Effect.SameHandSizeDamageBonus>().sumOf { it.bonus } else 0
        val opponentExCountDamage = effects.filterIsInstance<Effect.OpponentExCountDamage>().sumOf { effect ->
            effect.perPokemon * friendlyPokemon.count { catalog.get(it.top.definitionId).name.endsWith(" ex") }
        }
        val base = effects.filterIsInstance<Effect.Damage>().sumOf { it.amount } +
            effects.filterIsInstance<Effect.StadiumDamageBonus>().filter { stadiumInPlay }.sumOf { it.bonus } + abilityHandBonus + abilityDamageBonus +
            futureDamageBonus + teamAttackDamageBonus + sameTypeBonus + sameHandSizeBonus + opponentExCountDamage +
            (if (targetIsOpponentActive && target.stage != Stage.BASIC) attackerPokemon.flatMap { catalog.get(it.top.definitionId).abilities }.sumOf { it.attackDamageBonusAgainstEvolutionActive } else 0) +
            effects.filterIsInstance<Effect.TargetedDamage>().sumOf { it.damage } +
            effects.filterIsInstance<Effect.BenchedCountDamage>().sumOf { it.perPokemon * (ownBenchCount + opponentBenchCount) } +
            effects.filterIsInstance<Effect.OpponentBenchCountDamage>().sumOf { it.perPokemon * opponentBenchCount } +
            effects.filterIsInstance<Effect.OwnBenchCountDamage>().sumOf { it.perPokemon * ownBenchCount } +
            effects.filterIsInstance<Effect.OwnBenchWithEnergyDamage>().sumOf { it.perPokemon * (ownBenchWithEnergyCounts[it.type] ?: 0) } +
            effects.filterIsInstance<Effect.OwnDamagedPokemonCountDamage>().sumOf { it.perPokemon * ownDamagedPokemonCount } +
            effects.filterIsInstance<Effect.OwnDamagedBenchedPokemonCountDamage>().sumOf { it.perPokemon * ownDamagedBenchedPokemonCount } +
            effects.filterIsInstance<Effect.OwnInPlayCountDamage>().sumOf { it.perPokemon * ownInPlayCount } +
            effects.filterIsInstance<Effect.OwnSpeciesCountDamage>().sumOf { effect ->
                effect.perPokemon * ownPokemonNames.count { it == effect.speciesName || it == "${effect.speciesName} ex" }
            } +
            effects.filterIsInstance<Effect.OwnAttackNameCountDamage>().sumOf { effect ->
                effect.perPokemon * attackerPokemon.count { pokemon ->
                    catalog.get(pokemon.top.definitionId).attacks.any { it.name == effect.attackName }
                }
            } +
            effects.filterIsInstance<Effect.BenchPokemonPresenceDamageBonus>().filter { effect ->
                ownBenchPokemonNames.any { it == effect.speciesName || it == "${effect.speciesName} ex" }
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.BenchTypePresenceDamageBonus>().filter { effect ->
                effect.type in ownBenchPokemonTypes
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.AllOwnBenchedPokemonDamagedBonus>().filter {
                ownBenchPokemonDetails.all { (_, damage) -> damage > 0 }
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.AnyOwnBenchedPokemonDamagedBonus>().filter {
                ownBenchPokemonDetails.any { (_, damage) -> damage > 0 }
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnNamedBenchDamageCounterDamage>().sumOf { effect ->
                effect.perCounter * ownBenchPokemonDetails.filter { (name, _) -> name.startsWith(effect.namePrefix) }.sumOf { (_, damage) -> damage / 10 }
            } +
            effects.filterIsInstance<Effect.OwnBenchTypeDamageCounterDamage>().sumOf { effect ->
                effect.perCounter * ownBenchPokemonTypeDetails.filter { (type, _) -> type == effect.type }.sumOf { (_, damage) -> damage / 10 }
            } +
            effects.filterIsInstance<Effect.OwnDamageCounterDamage>().sumOf { it.perCounter * (attacker.damage / 10) } +
            effects.filterIsInstance<Effect.ConditionalOwnDamageCounterBonus>().filter { attacker.damage / 10 >= it.minimumCounters }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnDamageCounterPenalty>().sumOf { it.perCounter * (attacker.damage / 10) } * -1 +
            effects.filterIsInstance<Effect.OwnEnergyTypeCountDamage>().sumOf { effect ->
                effect.damagePerEnergy * if (effect.allPokemon) (ownEnergyCounts[effect.type] ?: 0) else attacker.energies.count { catalog.get(it.definitionId).type == effect.type }
            } +
            effects.filterIsInstance<Effect.OpponentFieldDamageCounterDamage>().sumOf { it.damagePerCounter * opponentDamageCounterCount } +
            effects.filterIsInstance<Effect.OwnUndamagedDamageBonus>().filter { attacker.damage == 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnAnyDamageCounterBonus>().filter { attacker.damage > 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentExDamage>().filter { targetIsOpponentActive && opponentActiveIsEx }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentTypeDamage>().filter { targetIsOpponentActive && target.type == it.type }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentEvolutionActiveDamageBonus>().filter { targetIsOpponentActive && target.stage != Stage.BASIC }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnEnergyCountDamage>().sumOf { it.perEnergy * attacker.energies.size } +
            effects.filterIsInstance<Effect.OpponentEnergyCountDamage>().filter { targetIsOpponentActive }.sumOf { it.perEnergy * opponentActiveEnergyCount } +
            effects.filterIsInstance<Effect.OpponentHandEnergyCountDamage>().sumOf { it.perEnergy * opponentHandEnergyCount } +
            effects.filterIsInstance<Effect.OpponentHandCountDamage>().sumOf { it.perCard * opponentHandCount } +
            effects.filterIsInstance<Effect.OpponentBasicDiscardEnergyDamage>().sumOf { it.perEnergy * opponentBasicDiscardEnergyCount } +
            effects.filterIsInstance<Effect.OpponentHandAtMostDamageBonus>().filter { opponentHandCount <= it.maxCards }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentActiveRetreatCostDamage>().filter { targetIsOpponentActive }.sumOf { it.perCost * opponentActiveRetreatCost } +
            effects.filterIsInstance<Effect.OpponentConditionDamage>().filter { targetIsOpponentActive && opponentHasSpecialCondition }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentActiveDamagedDamage>().filter { targetIsOpponentActive && opponentActiveDamage > 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentActiveDamageCounterDamage>().filter { targetIsOpponentActive }.sumOf { it.perCounter * (opponentActiveDamage / 10) } +
            effects.filterIsInstance<Effect.OwnDiscardEnergyDamage>().sumOf { it.perEnergy * ownDiscardEnergyCount } +
            effects.filterIsInstance<Effect.DiscardEnergyThresholdDamage>().filter { (ownBasicDiscardEnergyCounts[it.type] ?: 0) >= it.minimum }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnDiscardAbilityCountBonus>().filter { effect ->
                ownDiscardPokemonAbilityNames.count { it == effect.abilityName } >= effect.minimum
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnInPlayEnergyDamage>().sumOf { it.perEnergy * ownEnergyCounts.values.sum() } +
            effects.filterIsInstance<Effect.OwnSpecialEnergyDamage>().sumOf { it.perEnergy * ownSpecialEnergyCount } +
            effects.filterIsInstance<Effect.OpponentPrizeTakenDamage>().filter { targetIsOpponentActive }.sumOf { it.perPrize * (6-opponentPrizeCardsRemaining) } +
            effects.filterIsInstance<Effect.OpponentPrizeCountDamage>().filter { targetIsOpponentActive && opponentPrizeCardsRemaining <= it.maxRemaining }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentNoRetreatDamage>().filter { targetIsOpponentActive && opponentActiveRetreatCost == 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentResistanceDamage>().filter { targetIsOpponentActive && target.resistance == it.type }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnToolAttachedDamage>().filter { attacker.tool != null }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnToolCountDamage>().sumOf { it.perTool * ownToolCount } +
            effects.filterIsInstance<Effect.AllPokemonToolCountDamage>().sumOf { it.perTool * allPokemonToolCount } +
            effects.filterIsInstance<Effect.BothActiveEnergyCountDamage>().sumOf { it.perEnergy * (attacker.energies.size + opponentActiveEnergyCount) } +
            effects.filterIsInstance<Effect.ConditionalEnergyDamage>().filter { conditional ->
                attacker.energies.count { catalog.get(it.definitionId).type == conditional.type } >= conditional.minimum
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.ConditionalAttachedEnergyDamage>().filter { attacker.energies.size >= it.minimum }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.ConditionalInPlayEnergyDamage>().filter { conditional ->
                (ownEnergyCounts[conditional.type] ?: 0) >= conditional.minimum
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnKnockoutLastTurnDamage>().filter { ownKnockedOutByAttackLastTurn }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.MovedFromBenchDamageBonus>().filter { attacker.enteredActiveTurn == turn }.sumOf { it.bonus }
        val attackBase = (base - if (turn == attacker.outgoingDamageReductionTurn) attacker.outgoingDamageReduction else 0).coerceAtLeast(0)
        if (attackBase == 0) return counterDamage
        var value = (attackBase + attacker.damageModifier + boost).coerceAtLeast(0)
        val sourceTypes = setOf(source.type) + source.abilities.flatMap { it.additionalPokemonTypes }
        val weakness = defender.weaknessOverride ?: target.weakness
        if (!ignoreWeaknessResistance && !ignoreWeakness && !(defender.weaknessSuppressedTurn >= 0 && defender.weaknessSuppressedTurn == turn) && weakness in sourceTypes) value *= 2
        if (!ignoreWeaknessResistance && !ignoreResistance && target.resistance in sourceTypes) value -= target.resistanceAmount
        val ignoresOpponentActiveEffects = ignoreOpponentActiveEffects || source.abilities.any { it.ignoreOpponentActiveEffects }
        if (!ignoresOpponentActiveEffects) {
            val curlyWall = if (target.stage == Stage.BASIC && target.type == EnergyType.COLORLESS && friendlyPokemon.count {
                    catalog.get(it.top.definitionId).name == "Bouffalant"
                } >= 2) friendlyPokemon.flatMap { catalog.get(it.top.definitionId).abilities }
                    .maxOfOrNull { it.protectBasicColorlessWithSecondBouffalant } ?: 0 else 0
            value -= target.abilities.sumOf { if (it.damageReductionTypes.isEmpty() || source.type in it.damageReductionTypes) it.damageReduction else 0 } + curlyWall
        }
        val tundraWallReduction = if (defender.energies.any { catalog.get(it.definitionId).type == EnergyType.WATER })
            friendlyPokemon.flatMap { catalog.get(it.top.definitionId).abilities }
                .maxOfOrNull { it.tundraWallWaterDamageReduction } ?: 0 else 0
        value -= tundraWallReduction
        value -= friendlyPokemon.flatMap { catalog.get(it.top.definitionId).abilities }.sumOf { it.teamDamageReduction }
        if (!ignoresOpponentActiveEffects && turn == defender.damageReductionTurn && (!defender.incomingDamageReductionEvolutionOnly || source.stage != Stage.BASIC)) value -= defender.incomingDamageReduction
        return value.coerceAtLeast(0) + counterDamage
    }
    fun supported(attack: AttackDefinition): Boolean = attack.effects.all { effect -> when (effect) {
        is Effect.Damage, is Effect.DrawToHand, is Effect.DiscardHandDraw, is Effect.DiscardHandEnergyDamageBonus, is Effect.DiscardOwnEnergyDamageBonus, is Effect.ShuffleHandDraw, is Effect.AccelerateEnergy, is Effect.RestrictNextTurn, is Effect.Recoil, is Effect.RetaliateDamageCountersNextTurn,
        is Effect.AttachBasicEnergyToEachBenchedPokemon,
        is Effect.RequiresBenched, is Effect.TargetedDamage, is Effect.SearchPokemonToHand, Effect.SearchAnyCardToHand, Effect.SearchSupporterToHand, Effect.SearchItemToHand, Effect.ChooseOpponentAttackToRestrict, Effect.HealSelfByDamageDealt, is Effect.HealOwnPokemon, Effect.IgnoreWeaknessResistance, Effect.IgnoreResistance, Effect.IgnoreOpponentActiveEffects,
        is Effect.RestrictNamedAttackNextTurn, Effect.CannotRepeatAttackUntilLeavingActive, Effect.LockOpponentItemsNextTurn, Effect.LockOpponentEvolutionNextTurn,
        is Effect.ReduceEvolutionDamageNextTurn,
        is Effect.CoinDamage, is Effect.DiscardAttackEnergy, is Effect.EnergyFromDeck, is Effect.AttachEnergyFromTopDeck, is Effect.SupporterBonus, is Effect.Draw, is Effect.EachPlayerDraw,
        Effect.PreventRetreatNextTurn, Effect.DiscardDefendingTools, is Effect.ConditionalEnergyDamage, is Effect.ConditionalAttachedEnergyDamage, is Effect.ConditionalInPlayEnergyDamage, is Effect.SwitchOwn,
        is Effect.BenchedCountDamage, is Effect.OpponentBenchCountDamage, is Effect.OwnBenchWithEnergyDamage, is Effect.OwnInPlayCountDamage, is Effect.OwnSpeciesCountDamage, is Effect.OwnAttackNameCountDamage, is Effect.OwnHandPokemonRevealDamage, is Effect.BenchPokemonPresenceDamageBonus, is Effect.BenchTypePresenceDamageBonus, is Effect.AnyOwnBenchedPokemonDamagedBonus, is Effect.AllOwnBenchedPokemonDamagedBonus, is Effect.OwnDamagedBenchedPokemonCountDamage, is Effect.SameTypePokemonInPlayDamageBonus, is Effect.SameHandSizeDamageBonus, is Effect.OpponentExCountDamage, is Effect.OwnNamedBenchDamageCounterDamage, is Effect.OwnBenchTypeDamageCounterDamage, Effect.IgnoreWeakness, is Effect.OwnDamageCounterDamage, is Effect.ConditionalOwnDamageCounterBonus, is Effect.OwnUndamagedDamageBonus, is Effect.OwnAnyDamageCounterBonus,
        Effect.SwitchOpponent, Effect.PullOpponentBench, Effect.PullSelectedBenchBeforeDamage, Effect.RecoverSupporterFromDiscard, is Effect.DiscardTopDeckEnergyDamage,
        is Effect.SearchBasicEnergyToHand, is Effect.OpponentFieldDamageCounterDamage, is Effect.OwnEnergyTypeCountDamage,
        is Effect.OpponentChoosesHandShuffle, Effect.RequiresUndamagedOpponentActive, Effect.RequiresOpponentBurned, Effect.RequiresStadium, is Effect.StadiumDamageBonus, Effect.MayAttackFirstTurnIfStarting, Effect.SearchEvolutionForSelfToEvolve, Effect.SearchEvolutionForEachBenchedPokemon,
        is Effect.BasicPokemonToBench, is Effect.NamedPokemonToBench, is Effect.RecoverNamedPokemonToBench, is Effect.SearchPokemonTypeAndStadiumToHand, Effect.SearchRotomToBench,
        Effect.RestrictIfSecondPlayerFirstTurn, Effect.PreventBasicNonColorlessDamageNextTurn, Effect.PreventBasicPokemonDamageNextTurn, is Effect.RequiresOwnBenchCountAtLeast, is Effect.OwnBenchCountDamage,
        is Effect.OwnDamagedPokemonCountDamage,
        is Effect.StatusCondition, is Effect.OpponentActiveTypeCondition, is Effect.DiscardDefendingEnergy, Effect.DiscardAllOpponentSpecialEnergy, Effect.DiscardOpponentToolsAndSpecialEnergy, is Effect.BenchDamage, is Effect.OwnBenchDamage, is Effect.DiscardEnergyThenBenchDamage, is Effect.OptionalShuffleEnergyThenBenchDamage, is Effect.BenchDamageCounters, Effect.PlaceDamageCounter, is Effect.MultiTargetDamage, is Effect.OptionalDiscardLightningForParalysis, is Effect.CoinDamageEachOpponentPokemon, Effect.KnockOutBothActive, Effect.KnockOutOpponentActiveIfSpecialCondition, Effect.KnockOutOpponentActiveAtExactlySixDamageCounters, Effect.RecoverSelfFromSpecialConditions, Effect.NoWeaknessNextTurn, Effect.CoinMiss, is Effect.HealEachOwnPokemon, is Effect.HealEachPokemon,
        is Effect.ReduceDamageNextTurn, is Effect.SelfDamage, Effect.PreventDefendingAttackNextTurn, Effect.DefendingAttackFailsOnTailsNextTurn,
        is Effect.OwnHandCounterDamage, is Effect.OpponentHandCountDamage, is Effect.OpponentHandAtMostDamageBonus, is Effect.OpponentExDamage, is Effect.OpponentTypeDamage, is Effect.OpponentEvolutionActiveDamageBonus, is Effect.OwnEnergyCountDamage,
        is Effect.OpponentEnergyCountDamage, is Effect.OpponentBasicDiscardEnergyDamage, is Effect.OpponentHandEnergyCountDamage, is Effect.OpponentActiveRetreatCostDamage, is Effect.ReduceDefendingAttackDamageNextTurn,
        is Effect.OpponentConditionDamage, is Effect.OpponentActiveDamagedDamage, is Effect.OpponentActiveDamageCounterDamage,
        is Effect.OwnDiscardEnergyDamage, is Effect.DiscardEnergyThresholdDamage, is Effect.OwnInPlayEnergyDamage, is Effect.OwnSpecialEnergyDamage,
        is Effect.OwnDiscardAbilityCountBonus,
        is Effect.OpponentPrizeTakenDamage, is Effect.OpponentPrizeCountDamage, is Effect.OpponentNoRetreatDamage,
        is Effect.OpponentResistanceDamage, is Effect.OwnToolAttachedDamage,
        is Effect.OwnKnockoutLastTurnDamage, is Effect.MovedFromBenchDamageBonus, is Effect.NextTurnAttackDamageBonus,
        is Effect.RequiresExactHandSize,
        is Effect.OwnToolCountDamage, is Effect.OwnDamageCounterPenalty,
        is Effect.AllPokemonToolCountDamage,
        is Effect.BothActiveEnergyCountDamage,
        Effect.MoveOpponentDamageCounters, Effect.MoveOwnBenchDamageCountersToOpponent, Effect.MoveEnergyBetweenOpponentPokemon, Effect.MoveEnergyFromOpponentActiveToBench,
        is Effect.SearchPokemonMatchingAttachedBasicEnergy,
        Effect.AttachAnyBasicEnergyFromHand, Effect.AttachOneBasicEnergyFromHand, Effect.ShuffleOwnBenchedPokemonIntoDeck, Effect.DevolveOpponentActive, Effect.DevolveOpponentPokemonIntoDeck, Effect.DiscardStadium, Effect.ReturnSelfAndAttachmentsToHand, Effect.OptionalReturnSelfAndAttachmentsToHand, is Effect.AttachBasicEnergyFromDeckAny, is Effect.AttachBasicEnergyFromDiscardAny, Effect.CoinKnockOutBasic,
        Effect.PreventAttackDamageNextTurnOnHeads, Effect.CopyBenchedNsAttack, Effect.CopyOpponentActiveAttack, is Effect.OptionalReturnWaterEnergyDamageBonus,
        is Effect.MoveEnergyToBench, Effect.MoveAllEnergyToBench, Effect.ReturnAttachedEnergyToHand, is Effect.DiscardRandomOpponentHand, is Effect.OpponentChoosesHandDiscard, is Effect.ShuffleRandomOpponentHand, is Effect.DiscardOpponentDeck, Effect.DiscardOwnDeckTop, Effect.RevealOpponentHand, Effect.RevealAndChooseOpponentHandDiscard, is Effect.DelayedDefendingDamageCounters -> true
        is Effect.DiscardBenchEnergyDamage -> true
        is Effect.Heal -> true
        is Effect.Unsupported -> false
    } }
}
