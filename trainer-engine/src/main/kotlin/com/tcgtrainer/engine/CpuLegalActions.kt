package com.tcgtrainer.engine

/** CPU candidate generation avoids factorial retreat payments; the normal rules/validation remain authoritative. */
internal fun RulesEngine.cpuLegalActions(state: GameState): List<GameAction> {
    if (state.phase != Phase.MAIN) return legalActions(state)
    val actor = actingPlayer(state)
    val own = state.players[actor]
    val opponent = state.players[1-actor]
    val candidates = mutableListOf<GameAction>(GameAction.EndTurn)
    own.hand.filter { it.definitionId != "cpu-hidden-card" }.forEach { card ->
        when (definition(card).category) {
            Category.TRAINER -> candidates += GameAction.PlayTrainer(card.id)
            Category.ENERGY -> own.pokemon().forEach { candidates += GameAction.AttachEnergy(card.id, it.id) }
            Category.POKEMON -> {
                candidates += GameAction.PlayBasic(card.id)
                own.pokemon().forEach { candidates += GameAction.Evolve(card.id, it.id) }
            }
        }
    }
    own.pokemon().forEach { p -> effectiveAbilities(state, actor, p).forEach { candidates += GameAction.UseAbility(p.id, it.id) } }
    candidates += GameAction.UseStadiumAbility
    own.active?.let { active ->
        val attacks = availableAttacks(state, actor, active)
        for (attack in attacks) {
            val targets = when {
                attack.effects.any { it is Effect.DiscardEnergyThenBenchDamage } -> opponent.bench
                attack.effects.filterIsInstance<Effect.TargetedDamage>().any { it.benchedOnly } -> opponent.bench
                attack.effects.any { it is Effect.TargetedDamage } -> opponent.pokemon()
                else -> emptyList()
            }
            if (attack.effects.any { it is Effect.TargetedDamage || it is Effect.DiscardEnergyThenBenchDamage })
                targets.filter { !attackTargetEffectsPrevented(state, 1-actor, it) }.forEach { candidates += GameAction.Attack(attack.id, it.id) }
            else candidates += GameAction.Attack(attack.id)
        }
        val cost = retreatCost(active, own.pokemon(), opponent.pokemon(), state)
        if (cost <= active.energies.size) {
            val needed = attacks.flatMap { attackCost(state, active, it) }.filter { it != EnergyType.COLORLESS }.toSet()
            val payments = listOf(active.energies, active.energies.sortedBy { energy ->
                val d = definition(energy)
                (if (d.providesEveryEnergyType) 10 else 0) + if (d.type in needed) 2 else 0
            }).map { it.take(cost).map { card -> card.id } }.distinct()
            own.bench.forEach { target -> payments.forEach { candidates += GameAction.Retreat(target.id, it) } }
        }
    }
    return candidates.distinct().filter { validate(state, it) == Legality.Legal }
}
