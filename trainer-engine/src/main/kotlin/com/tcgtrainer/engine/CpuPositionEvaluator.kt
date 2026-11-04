package com.tcgtrainer.engine

/** Public board and known resources only. Scores are heuristic, never guaranteed win probabilities. */
internal class CpuPositionEvaluator(private val engine: RulesEngine, private val catalog: CardCatalog, private val weights: CpuBoardWeights = CpuBoardWeights()) {
    private val benchSafety = CpuBenchSafety(engine, catalog)
    data class Features(val attackers: Double, val engines: Double, val recovery: Double, val exposure: Double, val locks: Double) {
        val total get() = attackers + engines + recovery - exposure - locks
    }

    fun value(state: GameState, actor: Int): Double {
        if (state.phase == Phase.GAME_OVER) return if (state.winner == actor) 100_000.0 else if (state.winner == 1-actor) -100_000.0 else 0.0
        val phaseWeights = weights.forPosition(state)
        fun weighted(f: Features) = f.attackers*phaseWeights.attackers + f.engines*phaseWeights.engines + f.recovery*phaseWeights.recovery -
            f.exposure*phaseWeights.exposure - f.locks*phaseWeights.locks
        return weighted(features(state, actor)) - weighted(features(state, 1-actor))*phaseWeights.opponent +
            (state.players[1-actor].prizes.size-state.players[actor].prizes.size)*phaseWeights.prizeAdvantage
    }

    private fun expectedEnergyNeed(state: GameState, owner: Int, pokemon: PokemonInstance, frame: GameState): Int {
        val definition = catalog.get(pokemon.top.definitionId)
        val ownAttacks = engine.availableAttacks(frame, owner, pokemon)
        val ownMaxCost = ownAttacks.maxOfOrNull { engine.attackCost(frame, pokemon, it).size } ?: 0
        val player = state.players[owner]
        val knownPoolIds = (player.hand.map { it.definitionId } + state.deckLists.getOrNull(owner).orEmpty())
            .filter { it != "cpu-hidden-card" }
            .distinct()
        val evolutionMaxCost = knownPoolIds.mapNotNull { id ->
            runCatching { catalog.get(id) }.getOrNull()
        }.filter { cand ->
            cand.category == Category.POKEMON && (cand.canEvolveFrom(definition) || cand.canRareCandyEvolveFrom(definition))
        }.flatMap { it.attacks }.maxOfOrNull { it.cost.size } ?: 0
        return maxOf(ownMaxCost, evolutionMaxCost)
    }

    private fun hasScalingAttack(attacks: List<AttackDefinition>): Boolean = attacks.any { a ->
        a.effects.any { eff ->
            eff is Effect.DiscardHandEnergyDamageBonus ||
                eff is Effect.DiscardOwnEnergyDamageBonus ||
                eff is Effect.ConditionalAttachedEnergyDamage ||
                (eff is Effect.TargetedDamage && eff.perOwnEnergy > 0) ||
                (eff is Effect.CoinDamage && eff.perEnergy)
        }
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
            val expectedCost = expectedEnergyNeed(state, owner, pokemon, frame)
            val potential = attacks.maxOfOrNull { a -> a.effects.filterIsInstance<Effect.Damage>().sumOf { it.amount }.toDouble() /
                (1+(engine.attackCost(frame, pokemon, a).size-pokemon.energies.size).coerceAtLeast(0)) } ?: 0.0
            if (ready.isNotEmpty()) readyCount++
            val stageBonus = when (definition.stage) {
                Stage.STAGE_TWO -> 55.0
                Stage.STAGE_ONE -> 30.0
                else -> 0.0
            }
            val usefulEnergyCount = pokemon.energies.size.coerceAtMost(expectedCost.coerceAtLeast(1))
            val scalingBonus = if (hasScalingAttack(attacks) && pokemon.energies.size > expectedCost) (pokemon.energies.size - expectedCost) * 14.0 else 0.0
            attackers += potential*.18 + (if (ready.isNotEmpty()) 40 else 0) +
                usefulEnergyCount * 14.0 + stageBonus + scalingBonus + remaining*.08 + pokemon.tools.size * 12.0
            val abilities = engine.effectiveAbilities(state, owner, pokemon)
            engines += abilities.sumOf { (it.draw + it.drawTo.coerceAtMost(5))*4.0 + if (it.attachHandEnergyType != null || it.attachBasicEnergyFromDiscard || it.attachBasicEnergyFromDiscardType != null) 22.0 else 6.0 }
            if (player.bench.any { it.id == pokemon.id }) {
                if (benchSafety.exposesFinalPrizes(state, owner, pokemon)) exposure += 150.0
                val fragile = 1.0-remaining.toDouble()/hp
                exposure += fragile*definition.prizes*(if (benchThreat) 65 else 20)
                if (definition.prizes >= opponent.prizes.size && fragile > .5) exposure += if (benchThreat) 60 else 15
            }
        }
        // Multiple independently ready attackers give resilience after a knockout.
        attackers += (readyCount-1).coerceAtLeast(0).coerceAtMost(2)*35

        // Attacker Preparation: currentAttacker vs nextAttacker / backupAttacker
        val active = player.active
        val benched = player.bench
        if (active != null && benched.isEmpty()) {
            // Danger: no backup Pokemon on bench at all!
            exposure += 95.0
        } else {
            attackers += minOf(benched.size, 3) * 18.0
        }

        val activeReady = active?.let { act ->
            val actAttacks = engine.availableAttacks(state, owner, act)
            val actMaxCost = actAttacks.maxOfOrNull { engine.attackCost(state, act, it).size } ?: 1
            act.energies.size >= actMaxCost && actAttacks.any { engine.validate(state, GameAction.Attack(it.id)) == Legality.Legal }
        } ?: false

        val nextAttacker = benched.maxByOrNull { b ->
            val bFrame = state.copy(currentPlayer = owner, phase = Phase.MAIN, resolution = null,
                players = state.players.mapIndexed { idx, p -> if (idx != owner) p else p.copy(active = b, bench = p.pokemon().filter { it.id != b.id }) })
            val bAttacks = engine.availableAttacks(bFrame, owner, b)
            val ready = bAttacks.any { engine.validate(bFrame, GameAction.Attack(it.id)) == Legality.Legal }
            val bNeed = expectedEnergyNeed(state, owner, b, bFrame).coerceAtLeast(1)
            minOf(b.energies.size, bNeed) * 30 + (if (ready) 65 else 0) + (if (b.cards.size > 1) 25 else 0)
        }
        val activeIsDoomed = active != null && (active.damage > 0 && (engine.effectiveHp(state, active) - active.damage) <= 80)
        if (activeIsDoomed) {
            if (nextAttacker == null || nextAttacker.energies.isEmpty()) {
                // Severe penalty: active will fall and there is no prepared follow-up attacker!
                exposure += 180.0
            } else {
                val bFrame = state.copy(currentPlayer = owner, phase = Phase.MAIN, resolution = null,
                    players = state.players.mapIndexed { idx, p -> if (idx != owner) p else p.copy(active = nextAttacker, bench = p.pokemon().filter { it.id != nextAttacker.id }) })
                val bNeed = expectedEnergyNeed(state, owner, nextAttacker, bFrame).coerceAtLeast(1)
                attackers += 85.0 + minOf(nextAttacker.energies.size, bNeed) * 28.0
            }
        } else if (nextAttacker != null && nextAttacker.energies.isNotEmpty()) {
            val bFrame = state.copy(currentPlayer = owner, phase = Phase.MAIN, resolution = null,
                players = state.players.mapIndexed { idx, p -> if (idx != owner) p else p.copy(active = nextAttacker, bench = p.pokemon().filter { it.id != nextAttacker.id }) })
            val bNeed = expectedEnergyNeed(state, owner, nextAttacker, bFrame).coerceAtLeast(1)
            val perEnergyBonus = if (activeReady) 32.0 else 20.0
            attackers += 50.0 + minOf(nextAttacker.energies.size, bNeed) * perEnergyBonus
        }

        // Hand readiness for next turn (evolutions, follow-up energy, supporters)
        val knownHand = player.hand.filter { it.definitionId != "cpu-hidden-card" }
        if (knownHand.isNotEmpty()) {
            val hasMatchingEvolutionInHand = knownHand.any { card ->
                val def = catalog.get(card.definitionId)
                def.category == Category.POKEMON && def.stage != Stage.BASIC &&
                    player.pokemon().any { inPlay -> def.canEvolveFrom(catalog.get(inPlay.top.definitionId)) }
            }
            if (hasMatchingEvolutionInHand) engines += 26.0
            if (player.flags.manualEnergy && knownHand.any { catalog.get(it.definitionId).category == Category.ENERGY }) {
                engines += 12.0
            }
            engines += minOf(knownHand.size, 7) * 3.5
        }

        var recovery = 0.0
        val damaged = player.pokemon().any { it.damage > 0 }
        for (card in knownHand) {
            val d = catalog.get(card.definitionId)
            if (d.category != Category.TRAINER) continue
            if (d.trainerType == TrainerType.ITEM && player.itemPlayLockedUntilTurn >= state.turn) continue
            if (d.trainerType == TrainerType.SUPPORTER && (player.supporterPlayLockedUntilTurn >= state.turn || player.flags.supporter)) continue
            recovery += when (d.trainerEffect) {
                is TrainerEffect.HealPokemon, is TrainerEffect.HealEachOwnPokemon -> if (damaged) 6.0 else 0.0
                is TrainerEffect.RecoverEnergy -> if (player.discard.any { catalog.get(it.definitionId).category == Category.ENERGY }) 10.0 else 0.0
                is TrainerEffect.RecoverPokemonToDeck, is TrainerEffect.RecoverPokemonAndBasicEnergy, TrainerEffect.Recover ->
                    if (player.discard.any { catalog.get(it.definitionId).category == Category.POKEMON }) 10.0 else 0.0
                else -> 0.0
            }
        }
        val locks = (if (player.itemPlayLockedUntilTurn >= state.turn) 20 else 0) +
            (if (player.supporterPlayLockedUntilTurn >= state.turn) 25 else 0) + (if (player.evolutionLockedUntilTurn >= state.turn) 15 else 0)
        return Features(attackers, engines, recovery.coerceAtMost(30.0), exposure, locks.toDouble())
    }
}
