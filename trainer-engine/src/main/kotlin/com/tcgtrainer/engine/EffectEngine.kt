package com.tcgtrainer.engine

class EffectEngine(private val catalog: CardCatalog) {
    fun effectiveHp(pokemon: PokemonInstance, state: GameState? = null, opponentPrizeCardsRemaining: Int = 6): Int {
        val def = catalog.get(pokemon.top.definitionId)
        val stadiumEffect = state?.stadium?.let { catalog.get(it.definitionId).trainerEffect } as? TrainerEffect.HpModifier
        val owner = state?.players?.indexOfFirst { player -> player.pokemon().any { it.id == pokemon.id } } ?: -1
        val prizesRemaining = if (owner >= 0) state!!.players[1-owner].prizes.size else opponentPrizeCardsRemaining
        val prizeBonus = def.abilities.sumOf { it.hpBonusPerOpponentPrizeTaken } * (6-prizesRemaining).coerceAtLeast(0)
        return (def.hp + pokemon.hpModifier + prizeBonus + if (stadiumEffect?.stage == def.stage) stadiumEffect.amount else 0).coerceAtLeast(0)
    }
    fun damage(attacker: PokemonInstance, defender: PokemonInstance, effects: List<Effect>, boost: Int = 0,
        ignoreWeaknessResistance: Boolean = false, ownBenchCount: Int = 0, opponentBenchCount: Int = 0,
        ownInPlayCount: Int = ownBenchCount + 1, ignoreOpponentActiveEffects: Boolean = false,
        ignoreResistance: Boolean = false, turn: Int = -1, ownHandCount: Int = 0,
        opponentActiveIsEx: Boolean = false, targetIsOpponentActive: Boolean = true, opponentActiveEnergyCount: Int = 0,
        opponentHasSpecialCondition: Boolean = false, opponentActiveDamage: Int = 0, opponentHandCount: Int = 0, ownToolCount: Int = 0, ownDamagedPokemonCount: Int = 0,
        ownEnergyCounts: Map<EnergyType, Int> = emptyMap(), ownKnockedOutByAttackLastTurn: Boolean = false,
        ownPokemonNames: List<String> = emptyList(),
        friendlyPokemon: List<PokemonInstance> = listOf(attacker), friendlyBenchPokemon: List<PokemonInstance> = emptyList(),
        opponentDamageCounterCount: Int = 0, opponentHandEnergyCount: Int = 0,
        opponentActiveRetreatCost: Int = 0, ownBenchWithEnergyCounts: Map<EnergyType, Int> = emptyMap(),
        ownDiscardEnergyCount: Int = 0, ownSpecialEnergyCount: Int = 0, opponentPrizeCardsRemaining: Int = 6,
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
        val base = effects.filterIsInstance<Effect.Damage>().sumOf { it.amount } + abilityHandBonus + abilityDamageBonus +
            futureDamageBonus +
            (if (targetIsOpponentActive && target.stage != Stage.BASIC) attackerPokemon.flatMap { catalog.get(it.top.definitionId).abilities }.sumOf { it.attackDamageBonusAgainstEvolutionActive } else 0) +
            effects.filterIsInstance<Effect.TargetedDamage>().sumOf { it.damage } +
            effects.filterIsInstance<Effect.BenchedCountDamage>().sumOf { it.perPokemon * (ownBenchCount + opponentBenchCount) } +
            effects.filterIsInstance<Effect.OwnBenchCountDamage>().sumOf { it.perPokemon * ownBenchCount } +
            effects.filterIsInstance<Effect.OwnBenchWithEnergyDamage>().sumOf { it.perPokemon * (ownBenchWithEnergyCounts[it.type] ?: 0) } +
            effects.filterIsInstance<Effect.OwnDamagedPokemonCountDamage>().sumOf { it.perPokemon * ownDamagedPokemonCount } +
            effects.filterIsInstance<Effect.OwnInPlayCountDamage>().sumOf { it.perPokemon * ownInPlayCount } +
            effects.filterIsInstance<Effect.OwnSpeciesCountDamage>().sumOf { effect ->
                effect.perPokemon * ownPokemonNames.count { it == effect.speciesName || it == "${effect.speciesName} ex" }
            } +
            effects.filterIsInstance<Effect.OwnDamageCounterDamage>().sumOf { it.perCounter * (attacker.damage / 10) } +
            effects.filterIsInstance<Effect.OwnDamageCounterPenalty>().sumOf { it.perCounter * (attacker.damage / 10) } * -1 +
            effects.filterIsInstance<Effect.OwnEnergyTypeCountDamage>().sumOf { effect ->
                effect.damagePerEnergy * if (effect.allPokemon) (ownEnergyCounts[effect.type] ?: 0) else attacker.energies.count { catalog.get(it.definitionId).type == effect.type }
            } +
            effects.filterIsInstance<Effect.OpponentFieldDamageCounterDamage>().sumOf { it.damagePerCounter * opponentDamageCounterCount } +
            effects.filterIsInstance<Effect.OwnUndamagedDamageBonus>().filter { attacker.damage == 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentExDamage>().filter { targetIsOpponentActive && opponentActiveIsEx }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentTypeDamage>().filter { targetIsOpponentActive && target.type == it.type }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnEnergyCountDamage>().sumOf { it.perEnergy * attacker.energies.size } +
            effects.filterIsInstance<Effect.OpponentEnergyCountDamage>().filter { targetIsOpponentActive }.sumOf { it.perEnergy * opponentActiveEnergyCount } +
            effects.filterIsInstance<Effect.OpponentHandEnergyCountDamage>().sumOf { it.perEnergy * opponentHandEnergyCount } +
            effects.filterIsInstance<Effect.OpponentActiveRetreatCostDamage>().filter { targetIsOpponentActive }.sumOf { it.perCost * opponentActiveRetreatCost } +
            effects.filterIsInstance<Effect.OpponentConditionDamage>().filter { targetIsOpponentActive && opponentHasSpecialCondition }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentActiveDamagedDamage>().filter { targetIsOpponentActive && opponentActiveDamage > 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentActiveDamageCounterDamage>().filter { targetIsOpponentActive }.sumOf { it.perCounter * (opponentActiveDamage / 10) } +
            effects.filterIsInstance<Effect.OwnDiscardEnergyDamage>().sumOf { it.perEnergy * ownDiscardEnergyCount } +
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
            effects.filterIsInstance<Effect.ConditionalInPlayEnergyDamage>().filter { conditional ->
                (ownEnergyCounts[conditional.type] ?: 0) >= conditional.minimum
            }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnKnockoutLastTurnDamage>().filter { ownKnockedOutByAttackLastTurn }.sumOf { it.bonus }
        val attackBase = (base - if (turn == attacker.outgoingDamageReductionTurn) attacker.outgoingDamageReduction else 0).coerceAtLeast(0)
        if (attackBase == 0) return counterDamage
        var value = (attackBase + attacker.damageModifier + boost).coerceAtLeast(0)
        val sourceTypes = setOf(source.type) + source.abilities.flatMap { it.additionalPokemonTypes }
        if (!ignoreWeaknessResistance && !(defender.weaknessSuppressedTurn >= 0 && defender.weaknessSuppressedTurn == turn) && target.weakness in sourceTypes) value *= 2
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
        is Effect.RestrictNamedAttackNextTurn, Effect.LockOpponentItemsNextTurn, Effect.LockOpponentEvolutionNextTurn,
        is Effect.ReduceEvolutionDamageNextTurn,
        is Effect.CoinDamage, is Effect.DiscardAttackEnergy, is Effect.EnergyFromDeck, is Effect.SupporterBonus, is Effect.Draw,
        Effect.PreventRetreatNextTurn, Effect.DiscardDefendingTools, is Effect.ConditionalEnergyDamage, is Effect.ConditionalInPlayEnergyDamage, is Effect.SwitchOwn,
        is Effect.BenchedCountDamage, is Effect.OwnBenchWithEnergyDamage, is Effect.OwnInPlayCountDamage, is Effect.OwnSpeciesCountDamage, is Effect.OwnDamageCounterDamage, is Effect.OwnUndamagedDamageBonus,
        Effect.SwitchOpponent, Effect.PullOpponentBench, Effect.PullSelectedBenchBeforeDamage, Effect.RecoverSupporterFromDiscard, is Effect.DiscardTopDeckEnergyDamage,
        is Effect.SearchBasicEnergyToHand, is Effect.OpponentFieldDamageCounterDamage, is Effect.OwnEnergyTypeCountDamage,
        is Effect.OpponentChoosesHandShuffle, Effect.RequiresUndamagedOpponentActive, Effect.RequiresOpponentBurned,
        is Effect.BasicPokemonToBench, is Effect.RecoverNamedPokemonToBench, is Effect.SearchPokemonTypeAndStadiumToHand, Effect.SearchRotomToBench,
        Effect.RestrictIfSecondPlayerFirstTurn, Effect.PreventBasicNonColorlessDamageNextTurn, Effect.PreventBasicPokemonDamageNextTurn, is Effect.OwnBenchCountDamage,
        is Effect.OwnDamagedPokemonCountDamage,
        is Effect.StatusCondition, is Effect.OpponentActiveTypeCondition, is Effect.DiscardDefendingEnergy, is Effect.BenchDamage, is Effect.DiscardEnergyThenBenchDamage, is Effect.BenchDamageCounters, Effect.PlaceDamageCounter, is Effect.MultiTargetDamage, Effect.KnockOutBothActive, Effect.RecoverSelfFromSpecialConditions, Effect.NoWeaknessNextTurn, Effect.CoinMiss,
        is Effect.ReduceDamageNextTurn, is Effect.SelfDamage, Effect.PreventDefendingAttackNextTurn,
        is Effect.OwnHandCounterDamage, is Effect.OpponentExDamage, is Effect.OpponentTypeDamage, is Effect.OwnEnergyCountDamage,
        is Effect.OpponentEnergyCountDamage, is Effect.OpponentHandEnergyCountDamage, is Effect.OpponentActiveRetreatCostDamage, is Effect.ReduceDefendingAttackDamageNextTurn,
        is Effect.OpponentConditionDamage, is Effect.OpponentActiveDamagedDamage, is Effect.OpponentActiveDamageCounterDamage,
        is Effect.OwnDiscardEnergyDamage, is Effect.OwnInPlayEnergyDamage, is Effect.OwnSpecialEnergyDamage,
        is Effect.OpponentPrizeTakenDamage, is Effect.OpponentPrizeCountDamage, is Effect.OpponentNoRetreatDamage,
        is Effect.OpponentResistanceDamage, is Effect.OwnToolAttachedDamage,
        is Effect.OwnKnockoutLastTurnDamage,
        is Effect.RequiresExactHandSize,
        is Effect.OwnToolCountDamage, is Effect.OwnDamageCounterPenalty,
        is Effect.AllPokemonToolCountDamage,
        is Effect.BothActiveEnergyCountDamage,
        Effect.MoveOpponentDamageCounters,
        is Effect.SearchPokemonMatchingAttachedBasicEnergy,
        Effect.AttachAnyBasicEnergyFromHand, is Effect.AttachBasicEnergyFromDeckAny, Effect.CoinKnockOutBasic,
        Effect.PreventAttackDamageNextTurnOnHeads,
        is Effect.MoveEnergyToBench, is Effect.DiscardRandomOpponentHand, is Effect.ShuffleRandomOpponentHand, is Effect.DiscardOpponentDeck -> true
        is Effect.DiscardBenchEnergyDamage -> true
        is Effect.Heal -> true
        is Effect.Unsupported -> false
    } }
}
