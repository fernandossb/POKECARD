package com.tcgtrainer.engine

data class PlayerIntentionReport(
    val buildingBenchThreatId: String? = null,
    val buildingBenchThreatName: String? = null,
    val benchThreatScore: Double = 0.0,
    val holdingComboHand: Boolean = false,
    val stallingWithBaitActive: Boolean = false,
    val summary: String = ""
)

object PlayerIntentionDetector {
    fun detect(
        state: GameState,
        actor: Int,
        catalog: CardCatalog,
        engine: RulesEngine,
        adaptive: AdaptiveTuningParameters = AdaptiveTuningParameters()
    ): PlayerIntentionReport {
        val opponent = 1 - actor
        val oppPlayer = state.players.getOrNull(opponent) ?: return PlayerIntentionReport()
        val oppActive = oppPlayer.active
        val oppBench = oppPlayer.bench

        // Check recent actions taken by the player in their last turn
        val recentOppHistory = state.history.takeLast(12).filter { it.player == opponent }
        val oppSearchedRecently = recentOppHistory.any { entry ->
            entry.action is GameAction.PlayTrainer || entry.action is GameAction.UseAbility
        }
        val oppEvolvedRecently = recentOppHistory.any { it.action is GameAction.Evolve }
        val oppAttachedToBenchRecently = recentOppHistory.any { entry ->
            val attach = entry.action as? GameAction.AttachEnergy ?: return@any false
            oppBench.any { it.id == attach.pokemonId }
        }

        // 1. Evaluate whether the player is stalling with a low-threat bait Active while building on the bench
        val activeDef = oppActive?.let { catalog.get(it.top.definitionId) }
        val activeReadyDmg = if (oppActive != null && activeDef != null) {
            activeDef.attacks.filter {
                engine.energyCost(state, opponent, oppActive, engine.attackCost(state, oppActive, it)) == Legality.Legal
            }.maxOfOrNull { atk -> atk.effects.filterIsInstance<Effect.Damage>().maxOfOrNull { it.amount } ?: 0 } ?: 0
        } else 0

        val stallingWithBait = oppActive != null && activeDef != null &&
            activeDef.prizes == 1 && activeReadyDmg <= 30 && oppBench.isNotEmpty()

        // 2. Find the highest-priority threat the player is preparing on the bench
        var bestBenchPokemon: PokemonInstance? = null
        var bestBenchScore = 0.0
        for (benchMon in oppBench) {
            val bDef = catalog.get(benchMon.top.definitionId)
            var score = FutureThreatEvaluator.evaluatePokemonThreat(state, benchMon, actor, catalog, null, adaptive)
            val isLearnedKeyThreat = bDef.id in adaptive.learnedPlayerThreatPokemonIds ||
                bDef.canonicalName in adaptive.learnedPlayerThreatPokemonIds
            if (isLearnedKeyThreat) {
                score = maxOf(score, if (benchMon.energies.isNotEmpty() || bDef.stage != Stage.BASIC) 96.0 else 90.0)
            }
            if (benchMon.energies.isNotEmpty() && (stallingWithBait || oppAttachedToBenchRecently || bDef.prizes >= 2 || bDef.stage != Stage.BASIC)) {
                score = maxOf(score, 88.0 + minOf(benchMon.energies.size, 3) * 3.0)
            }
            if (score > bestBenchScore) {
                bestBenchScore = score
                bestBenchPokemon = benchMon
            }
        }

        // 3. Detect if the player is holding a large combo hand (either >= 5 cards, or searched recently and kept >= 4 cards without evolving yet)
        val handSize = oppPlayer.hand.size
        val holdingComboHand = handSize >= 6 ||
            (handSize >= 5 && (oppSearchedRecently || !oppEvolvedRecently)) ||
            (handSize >= 4 && oppSearchedRecently && !oppEvolvedRecently && adaptive.handDisruptionBonusMultiplier > 1.05)

        val benchName = bestBenchPokemon?.let { catalog.get(it.top.definitionId).name }
        val summaryParts = mutableListOf<String>()
        if (bestBenchPokemon != null && bestBenchScore >= 85.0) {
            summaryParts += "Preparando $benchName no banco (ameaça ${bestBenchScore.toInt()})"
        }
        if (stallingWithBait) {
            summaryParts += "Ativo de 1 prêmio usado como pivô/isca"
        }
        if (holdingComboHand) {
            summaryParts += "Acumulando mão ($handSize cartas) para turno de combo"
        }

        return PlayerIntentionReport(
            buildingBenchThreatId = bestBenchPokemon?.id?.takeIf { bestBenchScore >= 85.0 },
            buildingBenchThreatName = benchName?.takeIf { bestBenchScore >= 85.0 },
            benchThreatScore = bestBenchScore,
            holdingComboHand = holdingComboHand,
            stallingWithBaitActive = stallingWithBait,
            summary = summaryParts.joinToString(" · ")
        )
    }
}

object FutureThreatEvaluator {

    fun evaluatePokemonThreat(
        state: GameState,
        pokemon: PokemonInstance,
        actor: Int,
        catalog: CardCatalog,
        handAnalysis: HandAnalysis? = null,
        adaptive: AdaptiveTuningParameters? = null
    ): Double {
        val opponent = 1 - actor
        val oppPlayer = state.players[opponent]
        val def = catalog.get(pokemon.top.definitionId)

        // 0. Learned key threat from player's deck history across matches
        val isLearnedThreat = adaptive != null && (
            def.id in adaptive.learnedPlayerThreatPokemonIds ||
            def.canonicalName in adaptive.learnedPlayerThreatPokemonIds
        )
        if (isLearnedThreat && (pokemon.energies.isNotEmpty() || def.stage != Stage.BASIC)) {
            return 96.0
        }

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
        if (readyAttackDamage > 0) return if (isLearnedThreat) 90.0 else 70.0

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

        if (isLearnedThreat) {
            return 88.0
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

