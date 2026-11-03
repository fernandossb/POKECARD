package com.tcgtrainer.engine

data class PredictionResult(
    val bestLine: List<GameAction>,
    val threatScore: Double,
    val maxDamage: Int,
    val expectedPrizes: Int,
    val isLethal: Boolean,
    val targetPokemonId: String?,
    val lethalSequenceDescription: String? = null
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

        if (state.phase == Phase.GAME_OVER || oppPlayer.pokemon().isEmpty() || cpuPlayer.pokemon().isEmpty()) {
            return PredictionResult(emptyList(), 0.0, 0, 0, false, null)
        }

        val fullHand = oppPlayer.hand + listOfNotNull(oracleNextDraw)
        val neededPrizes = oppPlayer.prizes.size

        // Build state representing opponent's turn with full known hand
        val simBase = if (state.currentPlayer == opponent && state.phase == Phase.MAIN) {
            state.copy(
                players = state.players.mapIndexed { idx, p ->
                    if (idx == opponent) p.copy(hand = fullHand) else p
                }
            )
        } else {
            state.copy(
                currentPlayer = opponent,
                turn = if (state.currentPlayer == opponent) state.turn else state.turn + 1,
                phase = Phase.MAIN,
                resolution = null,
                players = state.players.mapIndexed { idx, p ->
                    if (idx == opponent) p.copy(hand = fullHand, flags = TurnFlags()) else p
                }
            )
        }

        val currentActive = simBase.players[opponent].active
        val oppBench = simBase.players[opponent].bench

        // Can any benched Pokémon become active via switch or retreat?
        val hasSwitchTrainer = fullHand.any { card ->
            val def = catalog.get(card.definitionId)
            def.category == Category.TRAINER && (
                (def.trainerEffect is TrainerEffect.Switch && !(def.trainerEffect as TrainerEffect.Switch).opponent) ||
                def.trainerEffect is TrainerEffect.SwitchAndHealMovedEx
            )
        }

        val canRetreat = currentActive != null && oppBench.isNotEmpty() &&
            !simBase.players[opponent].flags.retreat &&
            currentActive.retreatBlockedTurn != simBase.turn &&
            SpecialCondition.PARALYZED !in currentActive.conditions &&
            SpecialCondition.ASLEEP !in currentActive.conditions &&
            engine.retreatCost(currentActive, simBase.players[opponent].pokemon(), simBase.players[actor].pokemon(), simBase) <= currentActive.energies.size

        val canSwitchOrPromote = currentActive == null || hasSwitchTrainer || canRetreat
        val potentialActives = if (canSwitchOrPromote) simBase.players[opponent].pokemon() else listOfNotNull(currentActive)

        val hasBoss = fullHand.any { card ->
            val def = catalog.get(card.definitionId)
            def.category == Category.TRAINER && def.trainerEffect is TrainerEffect.Switch && (def.trainerEffect as TrainerEffect.Switch).opponent
        }

        val energiesInHand = fullHand.filter { catalog.get(it.definitionId).category == Category.ENERGY }

        var bestDamage = 0
        var bestPrizes = 0
        var isLethal = false
        var targetId: String? = null
        var bestSequence = emptyList<GameAction>()
        var lethalDesc: String? = null

        for (activeCandidate in potentialActives) {
            val isAlreadyActive = activeCandidate.id == currentActive?.id
            val switchPrep = mutableListOf<GameAction>()

            val stateWithActive = if (isAlreadyActive) {
                simBase
            } else if (hasSwitchTrainer) {
                val switchCard = fullHand.first { card ->
                    val def = catalog.get(card.definitionId)
                    def.category == Category.TRAINER && (def.trainerEffect is TrainerEffect.Switch && !(def.trainerEffect as TrainerEffect.Switch).opponent || def.trainerEffect is TrainerEffect.SwitchAndHealMovedEx)
                }
                switchPrep += GameAction.PlayTrainer(switchCard.id)
                simBase.copy(players = simBase.players.mapIndexed { idx, p ->
                    if (idx == opponent) {
                        p.copy(
                            active = activeCandidate,
                            bench = p.pokemon().filter { it.id != activeCandidate.id }
                        )
                    } else p
                })
            } else if (canRetreat && currentActive != null) {
                val cost = engine.retreatCost(currentActive, simBase.players[opponent].pokemon(), simBase.players[actor].pokemon(), simBase)
                val payment = currentActive.energies.take(cost).map { it.id }
                switchPrep += GameAction.Retreat(activeCandidate.id, payment)
                simBase.copy(players = simBase.players.mapIndexed { idx, p ->
                    if (idx == opponent) {
                        p.copy(
                            active = activeCandidate,
                            bench = p.pokemon().filter { it.id != activeCandidate.id }
                                .map { if (it.id == currentActive.id) it.copy(energies = it.energies.drop(cost)) else it },
                            flags = p.flags.copy(retreat = true)
                        )
                    } else p
                })
            } else {
                continue
            }

            // Energy attachment combinations: 0 attachments, or attach 1 energy from hand
            val energyOptions: List<CardInstance?> = if (!stateWithActive.players[opponent].flags.manualEnergy && energiesInHand.isNotEmpty()) {
                listOf(null) + energiesInHand.distinctBy { catalog.get(it.definitionId).type }
            } else {
                listOf(null)
            }

            for (energyCard in energyOptions) {
                val attachPrep = switchPrep.toMutableList()
                val stateWithEnergy = if (energyCard != null) {
                    attachPrep += GameAction.AttachEnergy(energyCard.id, activeCandidate.id)
                    stateWithActive.copy(players = stateWithActive.players.mapIndexed { idx, p ->
                        if (idx == opponent) {
                            p.copy(
                                active = p.active?.copy(energies = p.active.energies + energyCard),
                                hand = p.hand.filter { it.id != energyCard.id },
                                flags = p.flags.copy(manualEnergy = true)
                            )
                        } else p
                    })
                } else {
                    stateWithActive
                }

                val currentSimActive = stateWithEnergy.players[opponent].active ?: continue
                val availableAttacks = engine.availableAttacks(stateWithEnergy, opponent, currentSimActive)

                for (attack in availableAttacks) {
                    val canTargetBenchDirectly = attack.effects.any { it is Effect.DiscardEnergyThenBenchDamage } ||
                        attack.effects.filterIsInstance<Effect.TargetedDamage>().any { it.benchedOnly } ||
                        attack.effects.any { it is Effect.TargetedDamage }

                    val possibleTargets = if (canTargetBenchDirectly || hasBoss) {
                        stateWithEnergy.players[actor].pokemon()
                    } else {
                        listOfNotNull(stateWithEnergy.players[actor].active)
                    }

                    for (target in possibleTargets) {
                        val attackAction = if (target.id == stateWithEnergy.players[actor].active?.id) {
                            GameAction.Attack(attack.id)
                        } else {
                            GameAction.Attack(attack.id, target.id)
                        }

                        val targetPrep = attachPrep.toMutableList()
                        val stateWithTarget = if (target.id != stateWithEnergy.players[actor].active?.id && !canTargetBenchDirectly && hasBoss) {
                            val bossCard = fullHand.first { card ->
                                val def = catalog.get(card.definitionId)
                                def.category == Category.TRAINER && def.trainerEffect is TrainerEffect.Switch && (def.trainerEffect as TrainerEffect.Switch).opponent
                            }
                            targetPrep += GameAction.PlayTrainer(bossCard.id)
                            stateWithEnergy.copy(players = stateWithEnergy.players.mapIndexed { idx, p ->
                                if (idx == actor) {
                                    p.copy(
                                        active = target,
                                        bench = p.pokemon().filter { it.id != target.id }
                                    )
                                } else p
                            })
                        } else {
                            stateWithEnergy
                        }

                        // Validate legality via RulesEngine
                        if (engine.validate(stateWithTarget, attackAction) != Legality.Legal) continue

                        // Calculate damage via CpuForecast preview (includes weakness, resistance, modifiers)
                        val preview = forecast.preview(stateWithTarget, attackAction) ?: continue
                        val damage = preview.damage
                        val ko = preview.knockout && !preview.uncertain
                        val prizes = if (ko) preview.prizes else 0
                        val lethal = ko && prizes >= neededPrizes

                        if (lethal) {
                            isLethal = true
                            bestDamage = maxOf(bestDamage, damage)
                            bestPrizes = maxOf(bestPrizes, prizes)
                            targetId = target.id
                            bestSequence = targetPrep + attackAction
                            lethalDesc = "Legal lethal: ${targetPrep.joinToString(" -> ") { it.toString() }} -> Attack ${attack.name} on ${catalog.get(target.top.definitionId).name} dealing $damage damage for $prizes prize(s)"
                            break
                        }

                        if (damage > bestDamage || (ko && prizes > bestPrizes)) {
                            bestDamage = damage
                            bestPrizes = prizes
                            targetId = target.id
                            bestSequence = targetPrep + attackAction
                        }
                    }
                    if (isLethal) break
                }
                if (isLethal) break
            }
            if (isLethal) break
        }

        val handAnalysis = PlayerHandAnalyzer.analyze(simBase, actor, catalog, oracleNextDraw)
        val actionThreat = when {
            isLethal -> 100.0
            bestPrizes >= 2 -> 85.0
            bestPrizes >= 1 -> 70.0
            bestDamage >= 120 -> 60.0
            bestDamage >= 60 -> 45.0
            bestDamage > 0 -> 35.0
            handAnalysis.isDeadHand -> 10.0
            else -> 0.0
        }

        val threatScore = if (isLethal) 100.0 else maxOf(actionThreat, if (bestDamage > 0) handAnalysis.threatScore else minOf(40.0, handAnalysis.threatScore))

        return PredictionResult(
            bestLine = bestSequence,
            threatScore = threatScore,
            maxDamage = bestDamage,
            expectedPrizes = bestPrizes,
            isLethal = isLethal,
            targetPokemonId = targetId,
            lethalSequenceDescription = lethalDesc
        )
    }
}
