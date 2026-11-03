package com.tcgtrainer.engine

/** Physical copies with the same definition and target are interchangeable at this decision. */
internal fun cpuActionKey(state: GameState, action: GameAction): String {
    val hand = state.players[state.currentPlayer].hand
    fun definition(id: String) = hand.firstOrNull { it.id == id }?.definitionId ?: id
    return when (action) {
        is GameAction.AttachEnergy -> "energy:${definition(action.cardId)}:${action.pokemonId}"
        is GameAction.Evolve -> "evolve:${definition(action.cardId)}:${action.pokemonId}"
        is GameAction.PlayTrainer -> "trainer:${definition(action.cardId)}"
        is GameAction.PlayBasic -> "basic:${definition(action.cardId)}"
        else -> action.toString()
    }
}

/** Cheap subset for the two-step energy/evolution reply probe; same order and rule validation. */
internal fun RulesEngine.cpuKnownPreparationActions(state: GameState): List<GameAction> {
    if (state.phase != Phase.MAIN) return emptyList()
    val own = state.players[actingPlayer(state)]
    return own.hand.filter { it.definitionId != "cpu-hidden-card" }.flatMap { card ->
        when (definition(card).category) {
            Category.ENERGY -> own.pokemon().map { GameAction.AttachEnergy(card.id, it.id) }
            Category.POKEMON -> own.pokemon().map { GameAction.Evolve(card.id, it.id) }
            else -> emptyList()
        }
    }.filter { validate(state, it) == Legality.Legal }
}

/** Preserve different preparation types before applying the width limit, without imposing a play order. */
internal fun cpuReplyPreparations(state: GameState, actions: List<GameAction>, limit: Int): List<GameAction> {
    val groups = actions.filter { it !is GameAction.Attack && it != GameAction.EndTurn }
        .distinctBy { cpuActionKey(state, it) }.groupBy { it.javaClass }.values.toList()
    return buildList {
        for (index in 0 until (groups.maxOfOrNull { it.size } ?: 0)) {
            for (group in groups) group.getOrNull(index)?.let { action ->
                if (size < limit) add(action)
            }
            if (size >= limit) break
        }
    }
}

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
