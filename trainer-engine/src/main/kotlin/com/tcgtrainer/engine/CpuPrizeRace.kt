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
        // Only known, currently playable, unrestricted gust cards count as access resources.
        // Hidden hands and future draws never provide guaranteed access in this estimate.
        val accessFrame = state.copy(currentPlayer = owner, phase = Phase.MAIN, resolution = null)
        val gusts = state.players[owner].hand.count { card ->
            if (card.definitionId == "cpu-hidden-card") false else {
                val effect = catalog.get(card.definitionId).trainerEffect as? TrainerEffect.Switch
                effect != null && effect.opponent && !effect.opponentBasicOnly && effect.ownPokemonNamePrefix == null &&
                    engine.validate(accessFrame, GameAction.PlayTrainer(card.id)) == Legality.Legal
            }
        }.coerceAtMost(remaining)
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
            Triple(catalog.get(target.top.definitionId).prizes, cost, target.id != state.players[1-owner].active?.id)
        }
        val dp = Array(remaining+1) { DoubleArray(gusts+1) { Double.POSITIVE_INFINITY } }
        dp[0][0] = 0.0
        for ((prizes, cost, benched) in costs) {
            val previous = dp.map { it.copyOf() }
            for (taken in 0 until remaining) for (used in 0..gusts) {
                val next = minOf(remaining, taken+prizes)
                // A target may eventually be promoted without gust, but timing is uncertain.
                // This is a tempo penalty, not a claim that the opponent must promote it.
                val accessDelay = if (benched) 1.0 else 0.0
                dp[next][used] = minOf(dp[next][used], previous[taken][used]+cost+accessDelay)
                if (benched && used < gusts) {
                    // Spending a scarce gust has a small resource cost, even with no turn delay.
                    dp[next][used+1] = minOf(dp[next][used+1], previous[taken][used]+cost+0.15)
                }
            }
        }
        // Future unseen targets are possible; insufficient visible Prizes do not imply an immediate win.
        return dp[remaining].minOrNull()!!.takeIf { it.isFinite() } ?: (remaining+6.0)
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
