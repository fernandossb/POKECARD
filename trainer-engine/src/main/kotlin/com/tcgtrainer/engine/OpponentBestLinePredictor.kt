package com.tcgtrainer.engine

data class PredictionResult(
    val bestLine: List<GameAction>,
    val threatScore: Double,
    val maxDamage: Int,
    val expectedPrizes: Int,
    val isLethal: Boolean,
    val targetPokemonId: String?
)

object OpponentBestLinePredictor {

    fun predict(
        state: GameState,
        actor: Int,
        catalog: CardCatalog,
        engine: RulesEngine,
        oracleNextDraw: CardInstance? = null
    ): PredictionResult {
        val opponent = 1 - actor
        val oppPlayer = state.players[opponent]
        val cpuPlayer = state.players[actor]
        val forecast = CpuForecast(catalog)

        val fullHand = oppPlayer.hand + listOfNotNull(oracleNextDraw)
        val stateWithHand = state.copy(
            players = state.players.mapIndexed { idx, p ->
                if (idx == opponent) p.copy(hand = fullHand) else p
            }
        )

        val handAnalysis = PlayerHandAnalyzer.analyze(stateWithHand, actor, catalog, oracleNextDraw)
        val neededPrizes = oppPlayer.prizes.size

        // Build potential preparatory sequence for opponent
        val predictedSequence = mutableListOf<GameAction>()
        var bestDamage = 0
        var bestPrizes = 0
        var isLethal = false
        var targetId: String? = null

        val hasBoss = handAnalysis.hasBoss
        val hasEnergy = handAnalysis.hasEnergy

        // Check each possible opponent attacker
        val readyAttackers = oppPlayer.pokemon().filter { p ->
            val pDef = catalog.get(p.top.definitionId)
            pDef.attacks.any { it.cost.size <= p.energies.size + (if (hasEnergy) 1 else 0) }
        }

        val cpuTargets = listOfNotNull(cpuPlayer.active) + (if (hasBoss) cpuPlayer.bench else emptyList())

        for (attacker in readyAttackers) {
            val pDef = catalog.get(attacker.top.definitionId)
            val legalAttacks = pDef.attacks.filter { it.cost.size <= attacker.energies.size + (if (hasEnergy) 1 else 0) }

            for (atk in legalAttacks) {
                for (target in cpuTargets) {
                    val tDef = catalog.get(target.top.definitionId)
                    val dmg = atk.effects.filterIsInstance<Effect.Damage>().maxOfOrNull { it.amount } ?: 0
                    val currentHp = tDef.hp - target.damage
                    val isKo = dmg >= currentHp
                    val prizes = if (isKo) tDef.prizes else 0

                    if (prizes >= neededPrizes && isKo) {
                        isLethal = true
                    }

                    if (dmg > bestDamage || (isKo && prizes > bestPrizes)) {
                        bestDamage = dmg
                        bestPrizes = prizes
                        targetId = target.id
                        predictedSequence.clear()
                        if (hasEnergy) {
                            val energyCard = fullHand.firstOrNull { catalog.get(it.definitionId).category == Category.ENERGY }
                            if (energyCard != null) {
                                predictedSequence += GameAction.AttachEnergy(energyCard.id, attacker.id)
                            }
                        }
                        if (target.id != cpuPlayer.active?.id && hasBoss) {
                            val bossCard = fullHand.firstOrNull {
                                val d = catalog.get(it.definitionId)
                                d.trainerEffect is TrainerEffect.Switch && (d.trainerEffect as TrainerEffect.Switch).opponent
                            }
                            if (bossCard != null) {
                                predictedSequence += GameAction.PlayTrainer(bossCard.id)
                            }
                        }
                        predictedSequence += GameAction.Attack(atk.id, target.id)
                    }
                }
            }
        }

        val actionThreat = when {
            isLethal -> 100.0
            bestPrizes >= 2 -> 85.0
            bestPrizes >= 1 -> 70.0
            bestDamage >= 100 -> 60.0
            bestDamage > 0 -> 40.0
            handAnalysis.isDeadHand -> 10.0
            else -> 0.0
        }
        val calculatedThreat = if (handAnalysis.isDeadHand && !isLethal && bestDamage == 0) 10.0 else maxOf(actionThreat, handAnalysis.threatScore)

        return PredictionResult(
            bestLine = predictedSequence,
            threatScore = calculatedThreat,
            maxDamage = bestDamage,
            expectedPrizes = bestPrizes,
            isLethal = isLethal,
            targetPokemonId = targetId
        )
    }
}

