package com.tcgtrainer.engine

class EffectEngine(private val catalog: CardCatalog) {
    fun effectiveHp(pokemon: PokemonInstance, state: GameState? = null): Int {
        val def = catalog.get(pokemon.top.definitionId)
        val stadiumEffect = state?.stadium?.let { catalog.get(it.definitionId).trainerEffect } as? TrainerEffect.HpModifier
        return (def.hp + pokemon.hpModifier + if (stadiumEffect?.stage == def.stage) stadiumEffect.amount else 0).coerceAtLeast(0)
    }
    fun damage(attacker: PokemonInstance, defender: PokemonInstance, effects: List<Effect>, boost: Int = 0, ignoreWeaknessResistance: Boolean = false): Int {
        val source = catalog.get(attacker.top.definitionId)
        val target = catalog.get(defender.top.definitionId)
        val base = effects.filterIsInstance<Effect.Damage>().sumOf { it.amount } + effects.filterIsInstance<Effect.TargetedDamage>().sumOf { it.damage }
        if (base == 0) return 0
        var value = (base + attacker.damageModifier + boost).coerceAtLeast(0)
        if (!ignoreWeaknessResistance && target.weakness == source.type) value *= 2
        if (!ignoreWeaknessResistance && target.resistance == source.type) value -= target.resistanceAmount
        return value.coerceAtLeast(0)
    }
    fun supported(attack: AttackDefinition): Boolean = attack.effects.all { effect -> when (effect) {
        is Effect.Damage, is Effect.AccelerateEnergy, is Effect.RestrictNextTurn, is Effect.Recoil,
        is Effect.RequiresBenched, is Effect.TargetedDamage, Effect.IgnoreWeaknessResistance,
        is Effect.CoinDamage, is Effect.DiscardAttackEnergy, is Effect.EnergyFromDeck, is Effect.SupporterBonus, is Effect.Draw -> true
        is Effect.Unsupported, is Effect.Heal -> false
    } }
}
