package com.tcgtrainer.engine

import kotlin.math.ceil

/** Estimates routes through visible prize targets; it does not know future draws or unrevealed Pokémon. */
internal class CpuPrizeRace(private val engine: RulesEngine, private val catalog: CardCatalog) {
    private val forecast = CpuForecast(catalog)
    fun value(state: GameState, actor: Int): Double {
        if (state.phase == Phase.GAME_OVER) return when(state.winner) { actor -> 100_000.0; 1-actor -> -100_000.0; else -> 0.0 }
        val ours = turnsToPrizes(state, actor)
        val theirs = turnsToPrizes(state, 1-actor)
        val prizeLead = state.players[1-actor].prizes.size-state.players[actor].prizes.size
        return ((theirs-ours)*190.0).coerceIn(-1140.0, 1140.0) + prizeLead*85.0
    }

    internal fun turnsToPrizes(state: GameState, owner: Int): Double {
        val remaining = state.players[owner].prizes.size
        if (remaining == 0) return 0.0
        val targets = state.players[1-owner].pokemon()
        val sources = state.players[owner].pokemon()
        val costs = targets.map { target ->
            val cost = sources.minOfOrNull { source ->
                val frame = frame(state, owner, source, target)
                engine.availableAttacks(frame, owner, source).minOfOrNull { attack ->
                    val preview = forecast.preview(frame, GameAction.Attack(attack.id)) ?: return@minOfOrNull 20.0
                    if (preview.damage <= 0 && !preview.knockout) return@minOfOrNull 20.0
                    val hp = (preview.effectiveHp-preview.currentDamage).coerceAtLeast(1)
                    val hits = if (preview.knockout && !preview.uncertain) 1.0 else ceil(hp.toDouble()/preview.damage.coerceAtLeast(1)) + if (preview.uncertain) .5 else 0.0
                    val preparation = (missingEnergy(frame, owner, source, attack)-1).coerceAtLeast(0)
                    val discard = attack.effects.filterIsInstance<Effect.DiscardAttackEnergy>().sumOf { if (it.all) source.energies.size else it.count }
                    hits + preparation + (hits-1).coerceAtLeast(0.0)*(discard-1).coerceAtLeast(0)
                } ?: 20.0
            } ?: 20.0
            catalog.get(target.top.definitionId).prizes to cost
        }
        val dp = DoubleArray(remaining+1) { 50.0 }
        dp[0] = 0.0
        for ((prizes, cost) in costs) {
            val previous = dp.copyOf()
            for (taken in 0 until remaining) {
                val next = minOf(remaining, taken+prizes)
                dp[next] = minOf(dp[next], previous[taken]+cost)
            }
        }
        // Future unseen targets are possible; insufficient visible Prizes do not imply an immediate win.
        return if (dp[remaining] >= 50) remaining+6.0 else dp[remaining]
    }

    private fun frame(state: GameState, owner: Int, source: PokemonInstance, target: PokemonInstance) = state.copy(
        currentPlayer = owner, turn = state.turn+2, phase = Phase.MAIN, resolution = null,
        players = state.players.mapIndexed { index, player -> when(index) {
            owner -> player.copy(active = source, bench = player.pokemon().filter { it.id != source.id }, flags = TurnFlags())
            else -> player.copy(active = target, bench = player.pokemon().filter { it.id != target.id })
        } })

    private fun missingEnergy(state: GameState, owner: Int, source: PokemonInstance, attack: AttackDefinition): Int {
        val doubledGrass = state.players[owner].pokemon().any { p -> engine.effectiveAbilities(state, owner, p).any { it.providesDoubleBasicGrassEnergy } }
        val available = source.energies.flatMap { energy ->
            val definition = catalog.get(energy.definitionId)
            if (doubledGrass && definition.basicEnergy && definition.type == EnergyType.GRASS) listOf(definition, definition) else listOf(definition)
        }.toMutableList()
        val cost = engine.attackCost(state, source, attack)
        var missing = 0
        for (type in cost.filter { it != EnergyType.COLORLESS }) {
            val index = available.indexOfFirst { it.type == type || it.providesEveryEnergyType }
            if (index >= 0) available.removeAt(index) else missing++
        }
        return missing + (cost.count { it == EnergyType.COLORLESS }-available.size).coerceAtLeast(0)
    }
}
