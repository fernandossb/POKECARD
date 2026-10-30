package com.tcgtrainer.engine

/** Public board and known resources only. Scores are heuristic, never guaranteed win probabilities. */
internal class CpuPositionEvaluator(private val engine: RulesEngine, private val catalog: CardCatalog) {
    data class Features(val attackers: Double, val engines: Double, val recovery: Double, val exposure: Double, val locks: Double) {
        val total get() = attackers + engines + recovery - exposure - locks
    }

    fun value(state: GameState, actor: Int): Double {
        if (state.phase == Phase.GAME_OVER) return if (state.winner == actor) 100_000.0 else if (state.winner == 1-actor) -100_000.0 else 0.0
        return features(state, actor).total - features(state, 1-actor).total*.35 +
            (state.players[1-actor].prizes.size-state.players[actor].prizes.size)*45.0
    }

    internal fun features(state: GameState, owner: Int): Features {
        val player = state.players[owner]
        val opponent = state.players[1-owner]
        var attackers = 0.0
        var engines = 0.0
        var exposure = 0.0
        var readyCount = 0
        val benchThreat = opponent.pokemon().any { p -> engine.availableAttacks(state, 1-owner, p).any { a ->
            a.effects.any { it is Effect.TargetedDamage || it is Effect.AllOpponentBenchDamage || it is Effect.MultiTargetDamage }
        } }
        for (pokemon in player.pokemon()) {
            val definition = catalog.get(pokemon.top.definitionId)
            val hp = engine.effectiveHp(state, pokemon).coerceAtLeast(1)
            val remaining = (hp-pokemon.damage).coerceAtLeast(0)
            val frame = state.copy(currentPlayer = owner, phase = Phase.MAIN, resolution = null,
                players = state.players.mapIndexed { index, p -> if (index != owner) p else p.copy(active = pokemon, bench = p.pokemon().filter { it.id != pokemon.id }) })
            val attacks = engine.availableAttacks(frame, owner, pokemon)
            val ready = attacks.filter { engine.validate(frame, GameAction.Attack(it.id)) == Legality.Legal }
            val potential = attacks.maxOfOrNull { a -> a.effects.filterIsInstance<Effect.Damage>().sumOf { it.amount }.toDouble() /
                (1+(engine.attackCost(frame, pokemon, a).size-pokemon.energies.size).coerceAtLeast(0)) } ?: 0.0
            if (ready.isNotEmpty()) readyCount++
            attackers += potential*.16 + (if (ready.isNotEmpty()) 35 else 0) +
                pokemon.energies.size.coerceAtMost(attacks.maxOfOrNull { engine.attackCost(frame, pokemon, it).size } ?: 0)*9 + remaining*.02
            val abilities = engine.effectiveAbilities(state, owner, pokemon)
            engines += abilities.sumOf { (it.draw + it.drawTo.coerceAtMost(5))*3.0 + if (it.attachHandEnergyType != null) 15.0 else 0.0 }
            if (player.bench.any { it.id == pokemon.id }) {
                val fragile = 1.0-remaining.toDouble()/hp
                exposure += fragile*definition.prizes*(if (benchThreat) 65 else 20)
                if (definition.prizes >= opponent.prizes.size && fragile > .5) exposure += if (benchThreat) 60 else 15
            }
        }
        // Multiple independently ready attackers give resilience after a knockout.
        attackers += (readyCount-1).coerceAtLeast(0).coerceAtMost(2)*25
        var recovery = 0.0
        val damaged = player.pokemon().any { it.damage > 0 }
        for (card in player.hand.filter { it.definitionId != "cpu-hidden-card" }) {
            val d = catalog.get(card.definitionId)
            if (d.category != Category.TRAINER) continue
            if (d.trainerType == TrainerType.ITEM && player.itemPlayLockedUntilTurn >= state.turn) continue
            if (d.trainerType == TrainerType.SUPPORTER && (player.supporterPlayLockedUntilTurn >= state.turn || player.flags.supporter)) continue
            recovery += when (d.trainerEffect) {
                is TrainerEffect.HealPokemon, is TrainerEffect.HealEachOwnPokemon -> if (damaged) 14.0 else 0.0
                is TrainerEffect.RecoverEnergy -> if (player.discard.any { catalog.get(it.definitionId).category == Category.ENERGY }) 18.0 else 0.0
                is TrainerEffect.RecoverPokemonToDeck, is TrainerEffect.RecoverPokemonAndBasicEnergy, TrainerEffect.Recover ->
                    if (player.discard.any { catalog.get(it.definitionId).category == Category.POKEMON }) 18.0 else 0.0
                else -> 0.0
            }
        }
        val locks = (if (player.itemPlayLockedUntilTurn >= state.turn) 20 else 0) +
            (if (player.supporterPlayLockedUntilTurn >= state.turn) 25 else 0) + (if (player.evolutionLockedUntilTurn >= state.turn) 15 else 0)
        return Features(attackers, engines, recovery.coerceAtMost(45.0), exposure, locks.toDouble())
    }
}
