package com.tcgtrainer.engine

class EffectEngine(private val catalog: CardCatalog) {
    fun effectiveHp(pokemon: PokemonInstance, state: GameState? = null): Int {
        val def = catalog.get(pokemon.top.definitionId)
        val stadiumEffect = state?.stadium?.let { catalog.get(it.definitionId).trainerEffect } as? TrainerEffect.HpModifier
        return (def.hp + pokemon.hpModifier + if (stadiumEffect?.stage == def.stage) stadiumEffect.amount else 0).coerceAtLeast(0)
    }
    fun damage(attacker: PokemonInstance, defender: PokemonInstance, effects: List<Effect>, boost: Int = 0,
        ignoreWeaknessResistance: Boolean = false, ownBenchCount: Int = 0, opponentBenchCount: Int = 0,
        ownInPlayCount: Int = ownBenchCount + 1, ignoreOpponentActiveEffects: Boolean = false,
        ignoreResistance: Boolean = false, turn: Int = -1, ownHandCount: Int = 0,
        opponentActiveIsEx: Boolean = false, targetIsOpponentActive: Boolean = true, opponentActiveEnergyCount: Int = 0,
        opponentHasSpecialCondition: Boolean = false, opponentHandCount: Int = 0, ownToolCount: Int = 0, ownDamagedPokemonCount: Int = 0,
        ownEnergyCounts: Map<EnergyType, Int> = emptyMap(), ownKnockedOutByAttackLastTurn: Boolean = false,
        opponentActiveRetreatCost: Int = 0, ownBenchWithEnergyCounts: Map<EnergyType, Int> = emptyMap()): Int {
        if (defender.attackImmunityTurn >= 0 && turn == defender.attackImmunityTurn) return 0
        val source = catalog.get(attacker.top.definitionId)
        val target = catalog.get(defender.top.definitionId)
        if (defender.preventBasicNonColorlessDamageTurn >= 0 && defender.preventBasicNonColorlessDamageTurn == turn &&
            source.stage == Stage.BASIC && source.type != EnergyType.COLORLESS) return 0
        if (defender.preventBasicPokemonDamageTurn >= 0 && defender.preventBasicPokemonDamageTurn == turn && source.stage == Stage.BASIC) return 0
        val counterDamage = effects.filterIsInstance<Effect.OwnHandCounterDamage>().sumOf { it.countersPerCard * 10 * ownHandCount }
        val abilityHandBonus = if (ownHandCount == opponentHandCount) source.abilities.sumOf { it.damageBonusIfHandsEqual } else 0
        val abilityDamageBonus = if (targetIsOpponentActive) source.abilities.sumOf { ability ->
            if (ability.damageBonusAgainstActiveWhenDamageAtLeast > 0 && attacker.damage >= ability.damageBonusAgainstActiveWhenDamageAtLeast)
                ability.damageBonusAmount else 0
        } else 0
        val base = effects.filterIsInstance<Effect.Damage>().sumOf { it.amount } + abilityHandBonus + abilityDamageBonus +
            effects.filterIsInstance<Effect.TargetedDamage>().sumOf { it.damage } +
            effects.filterIsInstance<Effect.BenchedCountDamage>().sumOf { it.perPokemon * (ownBenchCount + opponentBenchCount) } +
            effects.filterIsInstance<Effect.OwnBenchCountDamage>().sumOf { it.perPokemon * ownBenchCount } +
            effects.filterIsInstance<Effect.OwnBenchWithEnergyDamage>().sumOf { it.perPokemon * (ownBenchWithEnergyCounts[it.type] ?: 0) } +
            effects.filterIsInstance<Effect.OwnDamagedPokemonCountDamage>().sumOf { it.perPokemon * ownDamagedPokemonCount } +
            effects.filterIsInstance<Effect.OwnInPlayCountDamage>().sumOf { it.perPokemon * ownInPlayCount } +
            effects.filterIsInstance<Effect.OwnDamageCounterDamage>().sumOf { it.perCounter * (attacker.damage / 10) } +
            effects.filterIsInstance<Effect.OwnUndamagedDamageBonus>().filter { attacker.damage == 0 }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentExDamage>().filter { targetIsOpponentActive && opponentActiveIsEx }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OpponentTypeDamage>().filter { targetIsOpponentActive && target.type == it.type }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnEnergyCountDamage>().sumOf { it.perEnergy * attacker.energies.size } +
            effects.filterIsInstance<Effect.OpponentEnergyCountDamage>().filter { targetIsOpponentActive }.sumOf { it.perEnergy * opponentActiveEnergyCount } +
            effects.filterIsInstance<Effect.OpponentActiveRetreatCostDamage>().filter { targetIsOpponentActive }.sumOf { it.perCost * opponentActiveRetreatCost } +
            effects.filterIsInstance<Effect.OpponentConditionDamage>().filter { targetIsOpponentActive && opponentHasSpecialCondition }.sumOf { it.bonus } +
            effects.filterIsInstance<Effect.OwnToolCountDamage>().sumOf { it.perTool * ownToolCount } +
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
        if (!ignoreWeaknessResistance && !(defender.weaknessSuppressedTurn >= 0 && defender.weaknessSuppressedTurn == turn) && target.weakness == source.type) value *= 2
        if (!ignoreWeaknessResistance && !ignoreResistance && target.resistance == source.type) value -= target.resistanceAmount
        val ignoresOpponentActiveEffects = ignoreOpponentActiveEffects || source.abilities.any { it.ignoreOpponentActiveEffects }
        if (!ignoresOpponentActiveEffects) value -= target.abilities.sumOf { it.damageReduction }
        if (!ignoresOpponentActiveEffects && turn == defender.damageReductionTurn) value -= defender.incomingDamageReduction
        return value.coerceAtLeast(0) + counterDamage
    }
    fun supported(attack: AttackDefinition): Boolean = attack.effects.all { effect -> when (effect) {
        is Effect.Damage, is Effect.DrawToHand, is Effect.AccelerateEnergy, is Effect.RestrictNextTurn, is Effect.Recoil,
        is Effect.RequiresBenched, is Effect.TargetedDamage, is Effect.SearchPokemonToHand, is Effect.HealOwnPokemon, Effect.IgnoreWeaknessResistance, Effect.IgnoreResistance, Effect.IgnoreOpponentActiveEffects,
        is Effect.CoinDamage, is Effect.DiscardAttackEnergy, is Effect.EnergyFromDeck, is Effect.SupporterBonus, is Effect.Draw,
        Effect.PreventRetreatNextTurn, Effect.DiscardDefendingTools, is Effect.ConditionalEnergyDamage, is Effect.ConditionalInPlayEnergyDamage, is Effect.SwitchOwn,
        is Effect.BenchedCountDamage, is Effect.OwnBenchWithEnergyDamage, is Effect.OwnInPlayCountDamage, is Effect.OwnDamageCounterDamage, is Effect.OwnUndamagedDamageBonus,
        Effect.SwitchOpponent, Effect.PullOpponentBench, Effect.RecoverSupporterFromDiscard, is Effect.BasicPokemonToBench, Effect.SearchRotomToBench,
        Effect.RestrictIfSecondPlayerFirstTurn, Effect.PreventBasicNonColorlessDamageNextTurn, Effect.PreventBasicPokemonDamageNextTurn, is Effect.OwnBenchCountDamage,
        is Effect.OwnDamagedPokemonCountDamage,
        is Effect.StatusCondition, is Effect.DiscardDefendingEnergy, is Effect.BenchDamage, is Effect.BenchDamageCounters, is Effect.MultiTargetDamage, Effect.KnockOutBothActive, Effect.RecoverSelfFromSpecialConditions, Effect.NoWeaknessNextTurn, Effect.CoinMiss,
        is Effect.ReduceDamageNextTurn, is Effect.SelfDamage, Effect.PreventDefendingAttackNextTurn,
        is Effect.OwnHandCounterDamage, is Effect.OpponentExDamage, is Effect.OpponentTypeDamage, is Effect.OwnEnergyCountDamage,
        is Effect.OpponentEnergyCountDamage, is Effect.OpponentActiveRetreatCostDamage, is Effect.ReduceDefendingAttackDamageNextTurn,
        is Effect.OpponentConditionDamage,
        is Effect.OwnKnockoutLastTurnDamage,
        is Effect.RequiresExactHandSize,
        is Effect.OwnToolCountDamage,
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
