package com.tcgtrainer.engine

object FutureThreatEvaluator {

    fun evaluatePokemonThreat(
        state: GameState,
        pokemon: PokemonInstance,
        actor: Int,
        catalog: CardCatalog,
        handAnalysis: HandAnalysis? = null
    ): Double {
        val opponent = 1 - actor
        val oppPlayer = state.players[opponent]
        val def = catalog.get(pokemon.top.definitionId)

        // 1. Engine abilities: active or bench draw/search/acceleration
        val hasEngineAbility = def.abilities.any {
            it.draw > 0 || it.searchAnyCardToHand || it.attachBasicEnergyFromDiscard
        }
        if (hasEngineAbility) return 85.0

        // 2. Ready active or ready benched attacker
        val rules = RulesEngine(catalog)
        val readyAttackDamage = def.attacks.filter {
            rules.energyCost(state, opponent, pokemon, rules.attackCost(state, pokemon, it)) == Legality.Legal
        }
            .maxOfOrNull { atk -> atk.effects.filterIsInstance<Effect.Damage>().maxOfOrNull { it.amount } ?: 0 } ?: 0
        if (readyAttackDamage >= 100) return 90.0
        if (readyAttackDamage > 0) return 70.0

        // 3. Basic Pokémon with evolution / Rare Candy in hand
        val hand = oppPlayer.hand
        val evolutionInHand = hand.firstOrNull { h ->
            val hDef = catalog.get(h.definitionId)
            (hDef.stage == Stage.STAGE_ONE && hDef.evolvesFrom == def.canonicalName) ||
            (hDef.stage == Stage.STAGE_TWO && handAnalysis?.hasRareCandyCombo == true && hDef.canRareCandyEvolveFrom(def))
        }

        if (evolutionInHand != null) {
            val evoDef = catalog.get(evolutionInHand.definitionId)
            val evoDamage = evoDef.attacks.maxOfOrNull { atk -> atk.effects.filterIsInstance<Effect.Damage>().maxOfOrNull { it.amount } ?: 0 } ?: 0
            if (evoDamage >= 100 || evoDef.hp >= 200) {
                return 95.0 // Immediate high priority threat!
            }
            return 80.0
        }

        // 4. Energy attached to benched backup
        if (pokemon.energies.isNotEmpty()) {
            return 50.0 + pokemon.energies.size * 15.0
        }

        // 5. High retreat wall with no energy (low active threat, high lock target)
        if (def.retreatCost >= 2 && pokemon.energies.isEmpty()) {
            return 25.0
        }

        return 30.0
    }
}

