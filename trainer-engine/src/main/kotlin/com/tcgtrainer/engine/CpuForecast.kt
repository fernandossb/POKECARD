package com.tcgtrainer.engine

/** Damage forecasts use masked positions and ranges for effects that depend on unrevealed cards. */
internal class CpuForecast(private val catalog: CardCatalog) {
    private fun rules(category: Category): RulesEngine = RulesEngine(object : CardCatalog {
        private val probe = CardDefinition("cpu-hidden-card", if (category == Category.POKEMON) "Iron Unknown" else "Unknown", "CPU", "?",
            category = category, hp = 1, basicEnergy = category == Category.ENERGY)
        override fun get(id: String) = if (id == probe.id) probe else catalog.get(id)
    })
    private val energyRules by lazy { rules(Category.ENERGY) }
    private val trainerRules by lazy { rules(Category.TRAINER) }
    private val pokemonRules by lazy { rules(Category.POKEMON) }

    fun preview(state: GameState, action: GameAction.Attack): AttackPreview? {
        val base = energyRules.preview(state, action.attackId, action.targetId) ?: return null
        val source = state.players[state.currentPlayer].active ?: return base
        val attack = energyRules.availableAttacks(state, state.currentPlayer, source).firstOrNull { it.id == action.attackId } ?: return base
        val hiddenHand = state.players[1-state.currentPlayer].hand.any { it.definitionId == "cpu-hidden-card" }
        val handDependent = hiddenHand && attack.effects.any { it is Effect.OpponentHandEnergyCountDamage || it is Effect.OpponentHandTrainerCountDamage }
        val hiddenDeck = attack.effects.any { it is Effect.DiscardTopDeckFutureDamage || it is Effect.DiscardTopDeckEnergyDamage ||
            it is Effect.DiscardBothDeckTopEnergyDamage || it == Effect.CopyAttackFromOwnDeckTop || it == Effect.CopyAttackFromOpponentDeckTop }
        if (!handDependent && !hiddenDeck && Effect.CoinMiss !in attack.effects) return base
        val scenarios = if (handDependent) listOfNotNull(base, trainerRules.preview(state, action.attackId, action.targetId),
            pokemonRules.preview(state, action.attackId, action.targetId)) else listOf(base)
        val minimum = if (Effect.CoinMiss in attack.effects) 0 else scenarios.minOf { it.minDamage }
        val deckBonus = attack.effects.sumOf { effect -> when(effect) {
            is Effect.DiscardTopDeckFutureDamage -> minOf(effect.count, state.players[state.currentPlayer].deck.size)*effect.damagePerPokemon
            is Effect.DiscardBothDeckTopEnergyDamage -> effect.damagePerEnergy*2
            else -> 0
        } }
        // A conservative upper bound, not knowledge of the actual top cards.
        val maximum = scenarios.maxOf { it.maxDamage } + deckBonus*2
        val pool = CpuDrawOdds.pool(state, state.currentPlayer)
        val energyEffect = attack.effects.filterIsInstance<Effect.DiscardTopDeckEnergyDamage>().singleOrNull()
        val energyFraction = if (pool != null && pool.values.sum() > 0 && energyEffect != null) {
            pool.entries.sumOf { (id, count) -> val card = catalog.get(id)
                if (card.category == Category.ENERGY && card.basicEnergy && card.type == energyEffect.type) count else 0
            }.toDouble()/pool.values.sum()
        } else null
        val opponentOdds = if (handDependent && !hiddenDeck && Effect.CoinMiss !in attack.effects && attack.effects.none { it is Effect.CoinDamage })
            CpuDrawOdds.opponentCategories(state, catalog) else null
        val estimate = if (opponentOdds != null && scenarios.size == 3)
            (scenarios[0].damage*opponentOdds.getValue(Category.ENERGY) + scenarios[1].damage*opponentOdds.getValue(Category.TRAINER) +
                scenarios[2].damage*opponentOdds.getValue(Category.POKEMON)).toInt()
        else if (energyFraction != null && !handDependent && Effect.CoinMiss !in attack.effects && attack.effects.none { it is Effect.CoinDamage })
            (minimum+(maximum-minimum)*energyFraction).toInt()
        else if (handDependent || hiddenDeck || Effect.CoinMiss in attack.effects) (minimum+maximum)/2 else base.damage
        val remaining = (base.effectiveHp-base.currentDamage-estimate).coerceAtLeast(0)
        return base.copy(damage = estimate, remainingHp = remaining, knockout = minimum >= base.effectiveHp-base.currentDamage && scenarios.all { it.knockout },
            uncertain = true, minDamage = minimum, maxDamage = maximum)
    }
}
