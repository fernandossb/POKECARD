package com.tcgtrainer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class CpuErrorType {
    REDUNDANT_ENERGY_ATTACHMENT,
    UNPROMOTED_HAND_STAGNATION,
    MISSED_SETUP_COMBO,
    FAILURE_TO_PREPARE_BACKUP,
    SUICIDAL_ATTACK,
    PREMATURE_ATTACK_WITHOUT_SETUP,
    UNATTACHED_TURN_ENERGY,
    PLAYER_BENCH_SWEEPER_STRATEGY,
    PLAYER_HAND_BURST_COMBO,
    PLAYER_GUST_BENCH_HUNTER
}

@Serializable
data class DetectedCpuIssue(
    val turn: Int,
    val type: CpuErrorType,
    val description: String,
    val severityScore: Double,
    val targetPokemonId: String? = null,
    val cardIdInvolved: String? = null,
    val recommendedAction: String? = null
)

@Serializable
data class CpuLesson(
    val lessonId: String,
    val errorType: CpuErrorType,
    val archetype: String,
    var occurrences: Int = 1,
    var confidence: Double = 0.6,
    val recommendedBehavior: String,
    val contextSummary: String,
    var lastObservedMatchId: String? = null,
    var verifiedInTest: Boolean = false
) {
    val type: String get() = errorType.name
    val description: String get() = recommendedBehavior
}

@Serializable
data class AdaptiveTuningParameters(
    val redundantEnergyPenaltyMultiplier: Double = 1.0,
    val backupAttackerBonusMultiplier: Double = 1.0,
    val handUtilizationBonusMultiplier: Double = 1.0,
    val searchCardBonusMultiplier: Double = 1.0,
    val thinningBonusMultiplier: Double = 1.0,
    val preAttackSetupBonusMultiplier: Double = 1.0,
    val evolutionPriorityMultiplier: Double = 1.0,
    val benchDevelopmentBonusMultiplier: Double = 1.0,
    val counterStrategyBonusMultiplier: Double = 1.0,
    val handDisruptionBonusMultiplier: Double = 1.0,
    val benchProtectionMultiplier: Double = 1.0,
    val learnedPlayerThreatPokemonIds: Set<String> = emptySet()
) {
    fun clamp(): AdaptiveTuningParameters = copy(
        redundantEnergyPenaltyMultiplier = redundantEnergyPenaltyMultiplier.coerceIn(0.5, 3.0),
        backupAttackerBonusMultiplier = backupAttackerBonusMultiplier.coerceIn(0.5, 2.5),
        handUtilizationBonusMultiplier = handUtilizationBonusMultiplier.coerceIn(0.5, 2.5),
        searchCardBonusMultiplier = searchCardBonusMultiplier.coerceIn(0.5, 2.0),
        thinningBonusMultiplier = thinningBonusMultiplier.coerceIn(0.5, 2.0),
        preAttackSetupBonusMultiplier = preAttackSetupBonusMultiplier.coerceIn(0.5, 2.5),
        evolutionPriorityMultiplier = evolutionPriorityMultiplier.coerceIn(0.5, 2.5),
        benchDevelopmentBonusMultiplier = benchDevelopmentBonusMultiplier.coerceIn(0.5, 2.5),
        counterStrategyBonusMultiplier = counterStrategyBonusMultiplier.coerceIn(0.5, 2.5),
        handDisruptionBonusMultiplier = handDisruptionBonusMultiplier.coerceIn(0.5, 2.5),
        benchProtectionMultiplier = benchProtectionMultiplier.coerceIn(0.5, 2.5)
    )
}

@Serializable
data class MatchReviewReport(
    val gameId: String,
    val archetype: String,
    val cpuLost: Boolean,
    val primaryWeakness: String,
    val detectedIssues: List<DetectedCpuIssue>,
    val generatedLessons: List<CpuLesson>,
    val reviewSummaryText: String
) {
    val lessonsLearned: List<CpuLesson> get() = generatedLessons
}

object EnergyEfficiencyAnalyzer {
    private fun extractTargetId(action: RecordedAction): String? {
        action.cpuDecision?.target?.takeIf { it.isNotBlank() }?.let { return it }
        val details = action.actionDetails
        val regex = Regex("""pokemonId=([^,)\s]+)""")
        regex.find(details)?.groupValues?.getOrNull(1)?.let { return it }
        val beforePokemon = listOfNotNull(action.stateBefore.players.getOrNull(1)?.active) +
            action.stateBefore.players.getOrNull(1)?.bench.orEmpty()
        val afterPokemon = listOfNotNull(action.stateAfter.players.getOrNull(1)?.active) +
            action.stateAfter.players.getOrNull(1)?.bench.orEmpty()
        return afterPokemon.firstOrNull { afterP ->
            val beforeP = beforePokemon.firstOrNull { it.id == afterP.id }
            beforeP != null && afterP.energies.size > beforeP.energies.size
        }?.id
    }

    fun analyze(recording: MatchRecording, catalog: CardCatalog): List<DetectedCpuIssue> {
        val issues = mutableListOf<DetectedCpuIssue>()

        for (turn in recording.turns.filter { it.player == 1 }) {
            var attachedEnergyThisTurn = false
            for (action in turn.actions) {
                if (action.actionType == "AttachEnergy" || action.actionDetails.startsWith("AttachEnergy")) {
                    attachedEnergyThisTurn = true
                    val targetId = extractTargetId(action)
                    val cpuBefore = action.stateBefore.players.getOrNull(1) ?: continue
                    val targetPkmnBefore = cpuBefore.active?.takeIf { it.id == targetId }
                        ?: cpuBefore.bench.firstOrNull { it.id == targetId }

                    if (targetPkmnBefore != null) {
                        val def = runCatching { catalog.get(targetPkmnBefore.definitionId) }.getOrNull()
                        val maxAttackCost = def?.attacks?.maxOfOrNull { it.cost.size } ?: 1
                        val hasScalingAttack = def?.attacks?.any { a ->
                            a.effects.any { eff ->
                                eff is Effect.DiscardHandEnergyDamageBonus ||
                                eff is Effect.DiscardOwnEnergyDamageBonus ||
                                eff is Effect.ConditionalAttachedEnergyDamage ||
                                (eff is Effect.TargetedDamage && eff.perOwnEnergy > 0) ||
                                (eff is Effect.CoinDamage && eff.perEnergy)
                            }
                        } ?: false

                        val energyCountBefore = targetPkmnBefore.energies.size
                        val otherBenchPokemon = cpuBefore.bench.filter { it.id != targetPkmnBefore.id }
                        val benchNeedsEnergy = otherBenchPokemon.any { b ->
                            val bDef = runCatching { catalog.get(b.definitionId) }.getOrNull()
                            val bCost = bDef?.attacks?.maxOfOrNull { it.cost.size } ?: 1
                            b.energies.size < bCost
                        }

                        if (!hasScalingAttack && energyCountBefore >= maxAttackCost && benchNeedsEnergy) {
                            issues += DetectedCpuIssue(
                                turn = turn.turnNumber,
                                type = CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT,
                                description = "Anexou energia excedente em ${targetPkmnBefore.name} ($energyCountBefore energias, custo máx: $maxAttackCost) enquanto o próximo atacante no banco precisava de energia.",
                                severityScore = 45.0 + (energyCountBefore - maxAttackCost) * 15.0,
                                targetPokemonId = targetPkmnBefore.id,
                                recommendedAction = "Direcionar a energia do turno para preparar o próximo atacante no banco"
                            )
                        }
                    }
                }
            }

            // Detect unattached turn energy when CPU had energy in hand and a Pokemon needing energy
            if (!attachedEnergyThisTurn && turn.actions.isNotEmpty()) {
                val energyInHand = turn.handAtEnd.firstOrNull { card ->
                    card.category == "ENERGY" || runCatching { catalog.get(card.definitionId).category == Category.ENERGY }.getOrDefault(false)
                }
                val lastBoard = turn.actions.last().stateAfter.players.getOrNull(1)
                if (energyInHand != null && lastBoard != null) {
                    val needyPokemon = (listOfNotNull(lastBoard.active) + lastBoard.bench).firstOrNull { p ->
                        val def = runCatching { catalog.get(p.definitionId) }.getOrNull()
                        val maxCost = def?.attacks?.maxOfOrNull { it.cost.size } ?: 1
                        p.energies.size < maxCost
                    }
                    if (needyPokemon != null) {
                        issues += DetectedCpuIssue(
                            turn = turn.turnNumber,
                            type = CpuErrorType.UNATTACHED_TURN_ENERGY,
                            description = "Encerrou/atacou no turno ${turn.turnNumber} mantendo ${energyInHand.name} na mão sem anexar energia em ${needyPokemon.name} (${needyPokemon.energies.size} energia(s)).",
                            severityScore = 42.0,
                            targetPokemonId = needyPokemon.id,
                            cardIdInvolved = energyInHand.id,
                            recommendedAction = "Anexar a energia manual do turno no atacante ativo ou no próximo atacante do banco antes de atacar"
                        )
                    }
                }
            }
        }
        return issues
    }
}

object BackupAttackerAnalyzer {
    fun analyze(recording: MatchRecording, catalog: CardCatalog): List<DetectedCpuIssue> {
        val issues = mutableListOf<DetectedCpuIssue>()

        for (turn in recording.turns.filter { it.player == 1 && it.turnNumber >= 2 }) {
            val endState = turn.actions.lastOrNull()?.stateAfter?.players?.getOrNull(1) ?: continue
            val active = endState.active ?: continue
            val bench = endState.bench

            val activeDef = runCatching { catalog.get(active.definitionId) }.getOrNull()
            val activeMaxCost = activeDef?.attacks?.maxOfOrNull { it.cost.size } ?: 1
            val activeReady = active.energies.size >= activeMaxCost
            val activeDamaged = active.damage >= 60 || active.hp <= 90

            val hasBasicOrSearchInHand = turn.handAtEnd.any { card ->
                val def = runCatching { catalog.get(card.definitionId) }.getOrNull() ?: return@any false
                (def.category == Category.POKEMON && def.stage == Stage.BASIC) ||
                    def.trainerEffect is TrainerEffect.Search ||
                    def.trainerEffect is TrainerEffect.BuddyBuddyPoffin ||
                    def.trainerEffect is TrainerEffect.GreatBall ||
                    def.trainerEffect is TrainerEffect.SearchBasicPokemonToBench
            }

            if (bench.isEmpty() && hasBasicOrSearchInHand) {
                issues += DetectedCpuIssue(
                    turn = turn.turnNumber,
                    type = CpuErrorType.FAILURE_TO_PREPARE_BACKUP,
                    description = "No turno ${turn.turnNumber}, manteve o banco vazio tendo recursos na mão para baixar ou buscar o próximo atacante.",
                    severityScore = 50.0,
                    targetPokemonId = active.id,
                    recommendedAction = "Baixar ou buscar um Pokémon básico para o banco imediatamente para garantir o próximo atacante"
                )
            } else if ((activeReady || activeDamaged) && bench.isNotEmpty() && bench.all { it.energies.isEmpty() }) {
                val hadEnergyOrAccel = turn.handAtStart.any { card ->
                    card.category == "ENERGY" || runCatching { catalog.get(card.definitionId).category == Category.ENERGY }.getOrDefault(false)
                }
                if (hadEnergyOrAccel) {
                    val candidateBackup = bench.first()
                    issues += DetectedCpuIssue(
                        turn = turn.turnNumber,
                        type = CpuErrorType.FAILURE_TO_PREPARE_BACKUP,
                        description = "No turno ${turn.turnNumber}, ${active.name} ativo já estava pronto/ameaçado, mas o reserva ${candidateBackup.name} no banco permaneceu com 0 energias.",
                        severityScore = 44.0,
                        targetPokemonId = candidateBackup.id,
                        recommendedAction = "Preparar o próximo atacante no banco (${candidateBackup.name}) com energia enquanto o ativo ataca"
                    )
                }
            }
        }
        return issues
    }
}

object PrematureAttackAnalyzer {
    fun analyze(recording: MatchRecording, catalog: CardCatalog): List<DetectedCpuIssue> {
        val issues = mutableListOf<DetectedCpuIssue>()

        for (turn in recording.turns.filter { it.player == 1 }) {
            if (!turn.attackExecuted) continue
            val preAttackActions = turn.actions.filter { it.actionType != "Attack" && it.actionType != "TakePrize" && it.actionType != "Promote" }
            val startBoard = turn.actions.firstOrNull()?.stateBefore?.players?.getOrNull(1) ?: continue

            val unusedPlayableCards = turn.handAtEnd.filter { card ->
                val def = runCatching { catalog.get(card.definitionId) }.getOrNull() ?: return@filter false
                when (def.category) {
                    Category.POKEMON -> {
                        if (def.stage == Stage.BASIC) startBoard.bench.size < 3
                        else (listOfNotNull(startBoard.active) + startBoard.bench).any { inPlay ->
                            val inPlayDef = runCatching { catalog.get(inPlay.definitionId) }.getOrNull()
                            inPlayDef != null && def.canEvolveFrom(inPlayDef)
                        }
                    }
                    Category.ENERGY -> turn.actions.none { it.actionType == "AttachEnergy" }
                    Category.TRAINER -> when (val eff = def.trainerEffect) {
                        is TrainerEffect.Search, is TrainerEffect.GreatBall, is TrainerEffect.BuddyBuddyPoffin,
                        is TrainerEffect.SearchEachEvolutionStage, is TrainerEffect.SearchBasicPokemonToBench -> startBoard.deckCount > 0
                        is TrainerEffect.DrawCards, is TrainerEffect.DrawTo -> startBoard.deckCount > 4 && turn.actions.none { a ->
                            a.actionType == "PlayTrainer"
                        }
                        is TrainerEffect.ShuffleHandDraw -> startBoard.deckCount > 5 && turn.handAtStart.size <= 5 && turn.actions.none { a ->
                            a.actionType == "PlayTrainer"
                        }
                        else -> false
                    }
                }
            }

            if (preAttackActions.isEmpty() && unusedPlayableCards.isNotEmpty() && turn.handAtStart.size >= 3) {
                val sampleNames = unusedPlayableCards.take(3).joinToString(", ") { it.name }
                issues += DetectedCpuIssue(
                    turn = turn.turnNumber,
                    type = CpuErrorType.PREMATURE_ATTACK_WITHOUT_SETUP,
                    description = "No turno ${turn.turnNumber}, atacou direto sem melhorar o próprio jogo, deixando de usar recursos disponíveis na mão ($sampleNames).",
                    severityScore = 48.0 + minOf(unusedPlayableCards.size, 4) * 4.0,
                    cardIdInvolved = unusedPlayableCards.first().id,
                    recommendedAction = "Executar toda a preparação do turno (baixar básicos, evoluir, usar busca/apoiador e ligar energia) antes de declarar o ataque"
                )
            }
        }
        return issues
    }
}

object HandEfficiencyAnalyzer {
    fun analyze(recording: MatchRecording, catalog: CardCatalog): List<DetectedCpuIssue> {
        val issues = mutableListOf<DetectedCpuIssue>()

        for (turn in recording.turns.filter { it.player == 1 }) {
            val legalTrainerIds = turn.actions.mapNotNull { it.cpuDecision?.legalTrainerCardIds }.flatten().toSet()
            if (legalTrainerIds.isEmpty()) continue
            val retainedDevelopmentCard = turn.handAtEnd.firstOrNull { card ->
                card.id in legalTrainerIds && runCatching { catalog.get(card.definitionId).trainerEffect }.getOrNull().let { effect ->
                    effect is TrainerEffect.Search || effect is TrainerEffect.GreatBall ||
                        effect is TrainerEffect.BuddyBuddyPoffin || effect is TrainerEffect.SearchEachEvolutionStage ||
                        effect is TrainerEffect.SearchBasicPokemonToBench || effect is TrainerEffect.DrawCards ||
                        effect is TrainerEffect.DrawTo || effect is TrainerEffect.ShuffleHandDraw ||
                        effect is TrainerEffect.ShuffleBothHandsDraw
                }
            } ?: continue
            val initialBoard = turn.actions.firstOrNull()?.stateBefore?.players?.getOrNull(1) ?: continue
            val hasReadyAttacker = listOfNotNull(initialBoard.active).plus(initialBoard.bench).any { pokemon ->
                val definition = runCatching { catalog.get(pokemon.definitionId) }.getOrNull() ?: return@any false
                definition.attacks.any { attack -> attack.cost.size <= pokemon.energies.size }
            }
            val benchHasPreparedBackup = initialBoard.bench.any { pokemon ->
                pokemon.energies.isNotEmpty()
            }

            if (turn.handAtStart.size >= 4 && (!hasReadyAttacker || !benchHasPreparedBackup)) {
                issues += DetectedCpuIssue(
                    turn = turn.turnNumber,
                    type = CpuErrorType.UNPROMOTED_HAND_STAGNATION,
                    description = "A CPU encerrou o turno ${turn.turnNumber} retendo ${retainedDevelopmentCard.name} na mão (${turn.handAtEnd.size} cartas), sem completar a preparação do atacante ativo e do reserva no banco.",
                    severityScore = 38.0,
                    cardIdInvolved = retainedDevelopmentCard.id,
                    recommendedAction = "Utilizar Itens de busca e Apoiadores de compra para montar o atacante ativo e o próximo atacante do banco"
                )
            }
        }
        return issues
    }
}

object MissedComboAnalyzer {
    fun analyze(recording: MatchRecording, catalog: CardCatalog): List<DetectedCpuIssue> {
        val issues = mutableListOf<DetectedCpuIssue>()

        for (turn in recording.turns.filter { it.player == 1 }) {
            val searchOrEvolutionInHand = turn.handAtEnd.firstOrNull { card ->
                val def = runCatching { catalog.get(card.definitionId) }.getOrNull()
                val isSearchByIdOrName = card.definitionId.contains("ultra-ball", ignoreCase = true) ||
                    card.definitionId.contains("nest-ball", ignoreCase = true) ||
                    card.name.contains("Ball", ignoreCase = true) ||
                    card.name.contains("Poffin", ignoreCase = true)
                val isSearchEffect = def?.trainerEffect is TrainerEffect.Search ||
                    def?.trainerEffect is TrainerEffect.GreatBall ||
                    def?.trainerEffect is TrainerEffect.BuddyBuddyPoffin ||
                    def?.trainerEffect is TrainerEffect.SearchEachEvolutionStage ||
                    def?.trainerEffect is TrainerEffect.RareCandy
                isSearchByIdOrName || isSearchEffect
            }

            val playedSearchOrEvolve = turn.actions.any { a ->
                a.actionType == "Evolve" || (a.actionType == "PlayTrainer" && (
                    a.actionDetails.contains("ball", ignoreCase = true) ||
                        a.actionDetails.contains("poffin", ignoreCase = true) ||
                        turn.handAtStart.firstOrNull { a.actionDetails.contains(it.id) }?.let { card ->
                            val def = runCatching { catalog.get(card.definitionId) }.getOrNull()
                            def?.trainerEffect is TrainerEffect.Search ||
                                def?.trainerEffect is TrainerEffect.GreatBall ||
                                def?.trainerEffect is TrainerEffect.BuddyBuddyPoffin ||
                                def?.trainerEffect is TrainerEffect.RareCandy
                        } == true
                    ))
            }

            val lastBoard = turn.actions.lastOrNull()?.stateAfter?.players?.getOrNull(1)
            val hasUnevolvedPokemon = lastBoard?.let { board ->
                (listOfNotNull(board.active) + board.bench).any { p ->
                    val def = runCatching { catalog.get(p.definitionId) }.getOrNull()
                    def?.stage == Stage.BASIC
                }
            } ?: false

            if (searchOrEvolutionInHand != null && !playedSearchOrEvolve && hasUnevolvedPokemon && turn.handAtStart.size >= 3) {
                issues += DetectedCpuIssue(
                    turn = turn.turnNumber,
                    type = CpuErrorType.MISSED_SETUP_COMBO,
                    description = "Oportunidade de busca/evolução não explorada no turno ${turn.turnNumber}: ${searchOrEvolutionInHand.name} permaneceu na mão com Pokémon básico em campo.",
                    severityScore = 36.0,
                    cardIdInvolved = searchOrEvolutionInHand.id,
                    recommendedAction = "Jogar ${searchOrEvolutionInHand.name} para buscar evolução ou próximo atacante do banco antes de atacar"
                )
            }
        }
        return issues
    }
}

/**
 * Analyzes the HUMAN player's moves (player == 0) in a recorded match so the CPU learns:
 * 1. Which Pokémon/evolution lines the player builds on the bench as their win condition (PLAYER_BENCH_SWEEPER_STRATEGY).
 * 2. Whether the player accumulates large hands via searches/draws before executing multi-card combo turns (PLAYER_HAND_BURST_COMBO).
 * 3. Whether the player uses Gust (Boss's Orders) or bench snipes to hunt the CPU's bench (PLAYER_GUST_BENCH_HUNTER).
 */
object HumanStrategyAnalyzer {
    fun analyze(recording: MatchRecording, catalog: CardCatalog): List<DetectedCpuIssue> {
        val issues = mutableListOf<DetectedCpuIssue>()
        val humanTurns = recording.turns.filter { it.player == 0 }
        if (humanTurns.isEmpty()) return issues

        val benchPreparedThreats = linkedSetOf<String>()
        val benchPreparedNames = linkedSetOf<String>()
        var firstBenchPrepTurn = 1

        for (turn in humanTurns) {
            // 1. Detect bench attacker preparation & evolution lines played by the human
            for (action in turn.actions) {
                val beforeHuman = action.stateBefore.players.getOrNull(0) ?: continue
                val afterHuman = action.stateAfter.players.getOrNull(0) ?: continue

                if (action.actionType == "AttachEnergy") {
                    for (afterBench in afterHuman.bench) {
                        val beforeBench = beforeHuman.bench.find { it.id == afterBench.id }
                        if (beforeBench != null && afterBench.energies.size > beforeBench.energies.size) {
                            val def = runCatching { catalog.get(afterBench.definitionId) }.getOrNull()
                            benchPreparedThreats += afterBench.definitionId
                            if (def != null) {
                                benchPreparedThreats += def.canonicalName
                                def.evolvesFrom?.let { benchPreparedThreats += it }
                                benchPreparedNames += def.name
                            } else {
                                benchPreparedNames += afterBench.name
                            }
                            if (firstBenchPrepTurn == 1) firstBenchPrepTurn = turn.turnNumber
                        }
                    }
                }

                if (action.actionType == "Evolve") {
                    val allBefore = (listOfNotNull(beforeHuman.active) + beforeHuman.bench).associateBy { it.id }
                    val allAfter = listOfNotNull(afterHuman.active) + afterHuman.bench
                    for (afterMon in allAfter) {
                        val beforeMon = allBefore[afterMon.id]
                        if (beforeMon != null && afterMon.definitionId != beforeMon.definitionId) {
                            val beforeDef = runCatching { catalog.get(beforeMon.definitionId) }.getOrNull()
                            val afterDef = runCatching { catalog.get(afterMon.definitionId) }.getOrNull()
                            benchPreparedThreats += beforeMon.definitionId
                            benchPreparedThreats += afterMon.definitionId
                            if (beforeDef != null) benchPreparedThreats += beforeDef.canonicalName
                            if (afterDef != null) {
                                benchPreparedThreats += afterDef.canonicalName
                                afterDef.evolvesFrom?.let { benchPreparedThreats += it }
                                benchPreparedNames += afterDef.name
                            } else {
                                benchPreparedNames += afterMon.name
                            }
                            if (firstBenchPrepTurn == 1) firstBenchPrepTurn = turn.turnNumber
                        }
                    }
                }

                // 3. Detect if the human used Boss's Orders / Gust to pull a benched CPU Pokémon
                if (action.actionType == "PlayTrainer") {
                    val beforeCpuActive = action.stateBefore.players.getOrNull(1)?.active?.id
                    val afterCpuActive = action.stateAfter.players.getOrNull(1)?.active?.id
                    val playedGust = (beforeCpuActive != null && afterCpuActive != null && beforeCpuActive != afterCpuActive) ||
                        turn.handAtStart.any { c ->
                            action.actionDetails.contains(c.id) &&
                                (runCatching { catalog.get(c.definitionId).trainerEffect }.getOrNull() as? TrainerEffect.Switch)?.opponent == true
                        }
                    if (playedGust) {
                        issues += DetectedCpuIssue(
                            turn = turn.turnNumber,
                            type = CpuErrorType.PLAYER_GUST_BENCH_HUNTER,
                            description = "Jogador usou puxador (Boss's Orders / troca forçada) no turno ${turn.turnNumber} para caçar alvo do banco da CPU.",
                            severityScore = 44.0,
                            recommendedAction = "Contra este baralho, proteger Pokémon de 2 Prêmios e evitar deixar alvos danificados ou vulneráveis no banco"
                        )
                    }
                }
            }

            // 2. Detect hand accumulation / burst combo turns by the human player
            val nonTrivialActions = turn.actions.count { it.actionType in listOf("PlayBasic", "Evolve", "PlayTrainer", "UseAbility", "AttachEnergy") }
            if (turn.handAtStart.size >= 5 && nonTrivialActions >= 3) {
                issues += DetectedCpuIssue(
                    turn = turn.turnNumber,
                    type = CpuErrorType.PLAYER_HAND_BURST_COMBO,
                    description = "Jogador acumulou ${turn.handAtStart.size} cartas na mão e executou turno explosivo com $nonTrivialActions ações de preparação no turno ${turn.turnNumber}.",
                    severityScore = 46.0,
                    recommendedAction = "Usar Iono, Judge ou Hand Trimmer preventivamente quando o jogador terminar o turno com 5+ cartas na mão após buscas"
                )
            }
        }

        if (benchPreparedThreats.isNotEmpty()) {
            val namesSummary = benchPreparedNames.take(3).joinToString(", ").ifBlank { benchPreparedThreats.take(3).joinToString(", ") }
            issues += DetectedCpuIssue(
                turn = firstBenchPrepTurn,
                type = CpuErrorType.PLAYER_BENCH_SWEEPER_STRATEGY,
                description = "Jogador estruturou sua estratégia preparando [$namesSummary] no banco antes de avançar para o ataque.",
                severityScore = 50.0,
                cardIdInvolved = benchPreparedThreats.joinToString("|"),
                recommendedAction = "Reconhecer [$namesSummary] como ameaça prioritária no banco do jogador e usar Boss's Orders ou dano no banco para neutralizá-lo antes que complete o setup"
            )
        }

        return issues
    }
}

class PostMatchCpuReview(
    private val catalog: CardCatalog = TrainerCatalog
) {
    fun review(recording: MatchRecording): MatchReviewReport {
        val cpuLost = recording.winner == 0
        val archetype = recording.cpuDeckId.ifBlank { "general" }
        val humanDeck = recording.humanDeckId.ifBlank { "player" }

        val energyIssues = EnergyEfficiencyAnalyzer.analyze(recording, catalog)
        val backupIssues = BackupAttackerAnalyzer.analyze(recording, catalog)
        val prematureAttackIssues = PrematureAttackAnalyzer.analyze(recording, catalog)
        val handIssues = HandEfficiencyAnalyzer.analyze(recording, catalog)
        val comboIssues = MissedComboAnalyzer.analyze(recording, catalog)
        val humanStrategyIssues = HumanStrategyAnalyzer.analyze(recording, catalog)

        val allIssues = (energyIssues + backupIssues + prematureAttackIssues + handIssues + comboIssues + humanStrategyIssues)
            .sortedByDescending { it.severityScore }

        val topIssue = allIssues.firstOrNull()
        val primaryWeakness = when (topIssue?.type) {
            CpuErrorType.PREMATURE_ATTACK_WITHOUT_SETUP -> "Atacou sem preparar o próprio jogo e usar recursos da mão"
            CpuErrorType.FAILURE_TO_PREPARE_BACKUP -> "Falta de preparação do próximo atacante no banco"
            CpuErrorType.PLAYER_BENCH_SWEEPER_STRATEGY -> "Permitir que o jogador prepare seu atacante principal no banco sem contestação"
            CpuErrorType.PLAYER_HAND_BURST_COMBO -> "Permitir que o jogador acumule mão cheia para combo sem disrupção"
            CpuErrorType.PLAYER_GUST_BENCH_HUNTER -> "Exposição de alvos no banco contra puxadores (Boss's Orders) do jogador"
            CpuErrorType.UNATTACHED_TURN_ENERGY -> "Energia do turno retida na mão sem alimentar ativo/banco"
            CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT -> "Alocação ineficiente de energia no ativo em vez do banco"
            CpuErrorType.MISSED_SETUP_COMBO -> "Combinações de busca e evolução não executadas antes do ataque"
            CpuErrorType.UNPROMOTED_HAND_STAGNATION -> "Retenção passiva de cartas de desenvolvimento na mão"
            else -> if (cpuLost) "Ritmo de preparação de atacantes e transição ativo/banco" else "Consolidação de campo e pressão contínua"
        }

        val lessons = mutableListOf<CpuLesson>()

        // 1. Consolidated pattern lessons for AdaptiveStrategyTuner & backward compatibility
        val redundantEnergy = energyIssues.filter { it.type == CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT }
        if (redundantEnergy.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "AVOID_REDUNDANT_ENERGY",
                errorType = CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT,
                archetype = archetype,
                occurrences = redundantEnergy.size,
                confidence = (0.55 + redundantEnergy.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Evitar anexar energias extras acima do custo de ataque ao mesmo Pokémon quando o próximo atacante no banco precisa de energia.",
                contextSummary = "Detectado em ${redundantEnergy.size} jogada(s) no deck $archetype."
            )
        }

        val unattachedEnergy = energyIssues.filter { it.type == CpuErrorType.UNATTACHED_TURN_ENERGY }
        if (unattachedEnergy.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "ATTACH_ENERGY_EVERY_TURN",
                errorType = CpuErrorType.UNATTACHED_TURN_ENERGY,
                archetype = archetype,
                occurrences = unattachedEnergy.size,
                confidence = (0.6 + unattachedEnergy.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Garantir a ligação de energia do turno no atacante ativo ou no próximo atacante do banco antes de declarar ataque.",
                contextSummary = "Energia não utilizada em ${unattachedEnergy.size} turno(s) no deck $archetype."
            )
        }

        if (backupIssues.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "PREPARE_BACKUP_ATTACKER",
                errorType = CpuErrorType.FAILURE_TO_PREPARE_BACKUP,
                archetype = archetype,
                occurrences = backupIssues.size,
                confidence = (0.6 + backupIssues.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Pensar dois turnos à frente: manter o atacante ativo pressionando enquanto já baixa, evolui e energiza o próximo atacante no banco.",
                contextSummary = "Falta de reserva preparado em ${backupIssues.size} turno(s) no deck $archetype."
            )
        }

        if (prematureAttackIssues.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "SETUP_BEFORE_ATTACK",
                errorType = CpuErrorType.PREMATURE_ATTACK_WITHOUT_SETUP,
                archetype = archetype,
                occurrences = prematureAttackIssues.size,
                confidence = (0.65 + prematureAttackIssues.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Nunca atacar direto no início do turno tendo cartas úteis na mão: baixar básicos, evoluir, usar habilidades, apoiadores e anexar energia antes de atacar.",
                contextSummary = "Ataque prematuro sem setup em ${prematureAttackIssues.size} turno(s) no deck $archetype."
            )
        }

        if (comboIssues.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "SEEK_COMBO_SETUP",
                errorType = CpuErrorType.MISSED_SETUP_COMBO,
                archetype = archetype,
                occurrences = comboIssues.size,
                confidence = (0.55 + comboIssues.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Priorizar linhas de busca e evolução (Ultra Ball, Nest Ball, Poffin) para evoluir o ativo e o banco antes de encerrar o turno.",
                contextSummary = "Detectado em ${comboIssues.size} oportunidade(s) no deck $archetype."
            )
        }

        if (handIssues.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "ACTIVATE_HAND_RESOURCES",
                errorType = CpuErrorType.UNPROMOTED_HAND_STAGNATION,
                archetype = "general",
                occurrences = handIssues.size,
                confidence = (0.55 + handIssues.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Ativar Apoiadores de compra e Itens da mão para desenvolver o campo em vez de acumular cartas sem uso.",
                contextSummary = "Detectado em ${handIssues.size} turno(s) no deck $archetype."
            )
        }

        // 1b. Lessons learned from observing the HUMAN PLAYER's deck & strategy
        val benchSweeperIssues = humanStrategyIssues.filter { it.type == CpuErrorType.PLAYER_BENCH_SWEEPER_STRATEGY }
        for (sweeperIssue in benchSweeperIssues) {
            val encodedThreats = sweeperIssue.cardIdInvolved.orEmpty()
            lessons += CpuLesson(
                lessonId = "COUNTER_PLAYER_BENCH_SETUP_${humanDeck.uppercase()}",
                errorType = CpuErrorType.PLAYER_BENCH_SWEEPER_STRATEGY,
                archetype = "general",
                occurrences = 1,
                confidence = 0.80,
                recommendedBehavior = sweeperIssue.recommendedAction ?: sweeperIssue.description,
                contextSummary = "THREATS=$encodedThreats | Deck do jogador: $humanDeck"
            )
        }

        val handBurstIssues = humanStrategyIssues.filter { it.type == CpuErrorType.PLAYER_HAND_BURST_COMBO }
        if (handBurstIssues.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "DISRUPT_PLAYER_HAND_COMBO",
                errorType = CpuErrorType.PLAYER_HAND_BURST_COMBO,
                archetype = "general",
                occurrences = handBurstIssues.size,
                confidence = (0.65 + handBurstIssues.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Quando o jogador acumular 5+ cartas na mão após buscas, priorizar Iono/Judge/Hand Trimmer para desmontar o combo antes que evolua.",
                contextSummary = "Padrão explosivo de mão observado em ${handBurstIssues.size} turno(s) do jogador ($humanDeck)."
            )
        }

        val playerGustIssues = humanStrategyIssues.filter { it.type == CpuErrorType.PLAYER_GUST_BENCH_HUNTER }
        if (playerGustIssues.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "GUARD_AGAINST_PLAYER_GUST",
                errorType = CpuErrorType.PLAYER_GUST_BENCH_HUNTER,
                archetype = "general",
                occurrences = playerGustIssues.size,
                confidence = (0.65 + playerGustIssues.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Jogador caça alvos no banco com Boss's Orders: evitar expor Pokémon de 2 Prêmios sem necessidade e priorizar cura/evolução do banco.",
                contextSummary = "Uso de puxadores pelo jogador em ${playerGustIssues.size} turno(s) ($humanDeck)."
            )
        }

        // 2. Guarantee a distinct, concrete per-match lesson from EVERY match
        val shortMatchId = recording.gameId.takeLast(6).uppercase()
        val cpuTurns = recording.turns.filter { it.player == 1 }
        val matchLesson = if (topIssue != null) {
            val encodedThreats = topIssue.cardIdInvolved?.takeIf { topIssue.type == CpuErrorType.PLAYER_BENCH_SWEEPER_STRATEGY }
            CpuLesson(
                lessonId = "PARTIDA_${shortMatchId}_T${topIssue.turn}_${topIssue.type.name}",
                errorType = topIssue.type,
                archetype = archetype,
                occurrences = 1,
                confidence = 0.85,
                recommendedBehavior = "[Partida #$shortMatchId · T${topIssue.turn}] ${topIssue.description} Lição: ${topIssue.recommendedAction ?: "Executar setup completo do ativo e do próximo atacante no banco antes de atacar."}",
                contextSummary = buildString {
                    if (!encodedThreats.isNullOrBlank()) append("THREATS=$encodedThreats | ")
                    append("Deck: $archetype vs $humanDeck · Foco: $primaryWeakness")
                }
            )
        } else {
            val totalTrainersUsed = cpuTurns.sumOf { t -> t.actions.count { it.actionType == "PlayTrainer" } }
            val totalEnergiesAttached = cpuTurns.sumOf { t -> t.actions.count { it.actionType == "AttachEnergy" } }
            val fallbackErrorType = if (totalEnergiesAttached < cpuTurns.size) CpuErrorType.FAILURE_TO_PREPARE_BACKUP else CpuErrorType.PREMATURE_ATTACK_WITHOUT_SETUP
            CpuLesson(
                lessonId = "PARTIDA_${shortMatchId}_EVOLUCAO_TATICA",
                errorType = fallbackErrorType,
                archetype = archetype,
                occurrences = 1,
                confidence = 0.78,
                recommendedBehavior = "[Partida #$shortMatchId] Em ${cpuTurns.size} turno(s) com $archetype (usou $totalTrainersUsed treinador(es) e ligou $totalEnergiesAttached energia(s)): reforçar preparação simultânea do atacante ativo e do próximo atacante no banco antes de cada ataque.",
                contextSummary = "Deck: $archetype vs $humanDeck · Análise pós-partida completa"
            )
        }
        lessons += matchLesson

        val summaryText = buildString {
            appendLine("=== ANÁLISE DE APRENDIZADO DA CPU ===")
            appendLine("Partida: #$shortMatchId | CPU ($archetype) vs Jogador ($humanDeck) | Resultado: ${if (cpuLost) "DERROTA DA CPU" else "VITÓRIA DA CPU"}")
            appendLine("Foco de Melhoria: $primaryWeakness")
            appendLine("Pontos Analisados: ${allIssues.size} (Ataque s/ setup: ${prematureAttackIssues.size}, Reserva no banco: ${backupIssues.size}, Estratégia do Jogador: ${humanStrategyIssues.size}, Energia: ${energyIssues.size}, Combos: ${comboIssues.size})")
            if (allIssues.isNotEmpty()) {
                appendLine()
                for (issue in allIssues.take(4)) {
                    appendLine("• [Turno ${issue.turn}] ${issue.description}")
                }
            }
            appendLine()
            appendLine("Lição Extraída desta Partida:")
            appendLine("→ ${matchLesson.recommendedBehavior}")
        }

        return MatchReviewReport(
            gameId = recording.gameId,
            archetype = archetype,
            cpuLost = cpuLost,
            primaryWeakness = primaryWeakness,
            detectedIssues = allIssues,
            generatedLessons = lessons,
            reviewSummaryText = summaryText
        )
    }
}

class CpuExperienceMemory {
    private val lessonsMap = mutableMapOf<String, CpuLesson>()
    val lessons: List<CpuLesson> get() = lessonsMap.values.toList().asReversed()
    var matchesAnalyzed: Int = 0

    companion object {
        fun fromJson(jsonText: String): CpuExperienceMemory = CpuExperienceMemory().apply { loadFromJson(jsonText) }
    }

    fun getAllLessons(): List<CpuLesson> = lessonsMap.values.toList()

    fun getLessonsForArchetype(archetype: String): List<CpuLesson> {
        return if (archetype == "general") lessonsMap.values.toList()
        else lessonsMap.values.filter { it.archetype == archetype || it.archetype == "general" }
    }

    fun learn(lessons: List<CpuLesson>, matchId: String) {
        for (newLesson in lessons) {
            val normalizedLesson = if (newLesson.lessonId == "ACTIVATE_HAND_RESOURCES") newLesson.copy(archetype = "general") else newLesson
            val key = "${normalizedLesson.archetype}:${normalizedLesson.lessonId}"
            val existing = lessonsMap[key]
            if (existing != null) {
                existing.occurrences += normalizedLesson.occurrences
                existing.confidence = (existing.confidence + 0.08).coerceAtMost(0.99)
                existing.lastObservedMatchId = matchId
            } else {
                normalizedLesson.lastObservedMatchId = matchId
                lessonsMap[key] = normalizedLesson
            }
        }
        // Keep memory bounded while preserving all consolidated rules + recent match lessons
        if (lessonsMap.size > 60) {
            val removableKeys = lessonsMap.entries
                .filter { it.value.lessonId.startsWith("PARTIDA_") }
                .map { it.key }
                .take(lessonsMap.size - 60)
            removableKeys.forEach { lessonsMap.remove(it) }
        }
    }

    fun reset() {
        lessonsMap.clear()
        matchesAnalyzed = 0
    }

    fun toJson(): String {
        val json = Json { prettyPrint = true; encodeDefaults = true }
        return json.encodeToString(lessonsMap.values.toList())
    }

    fun loadFromJson(jsonText: String) {
        if (jsonText.isBlank()) return
        val json = Json { ignoreUnknownKeys = true }
        val loaded = runCatching { json.decodeFromString<List<CpuLesson>>(jsonText) }.getOrNull() ?: return
        lessonsMap.clear()
        for (lesson in loaded) {
            val normalizedLesson = if (lesson.lessonId == "ACTIVATE_HAND_RESOURCES") lesson.copy(archetype = "general") else lesson
            val key = "${normalizedLesson.archetype}:${normalizedLesson.lessonId}"
            val existing = lessonsMap[key]
            if (existing == null) lessonsMap[key] = normalizedLesson
            else {
                existing.occurrences += normalizedLesson.occurrences
                existing.confidence = maxOf(existing.confidence, normalizedLesson.confidence)
                if (normalizedLesson.lastObservedMatchId != null) existing.lastObservedMatchId = normalizedLesson.lastObservedMatchId
            }
        }
    }
}

class AdaptiveStrategyTuner(
    private val memory: CpuExperienceMemory
) {
    fun computeParameters(archetype: String): AdaptiveTuningParameters {
        val allLessons = memory.getAllLessons()
        var redundantPenalty = 1.0
        var backupBonus = 1.0
        var handBonus = 1.0
        var searchBonus = 1.0
        var thinningBonus = 1.0
        var preAttackSetupBonus = 1.0
        var evolutionBonus = 1.0
        var benchBonus = 1.0
        var counterStrategyBonus = 1.0
        var handDisruptionBonus = 1.0
        var benchProtection = 1.0
        val learnedThreats = linkedSetOf<String>()

        for (l in allLessons) {
            // Extract any encoded player threat Pokémon IDs / canonical names from contextSummary
            val threatsPrefix = "THREATS="
            val idx = l.contextSummary.indexOf(threatsPrefix)
            if (idx >= 0) {
                val raw = l.contextSummary.substring(idx + threatsPrefix.length).substringBefore(" |")
                raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }.forEach { learnedThreats += it }
            }

            // Skip per-match narrative entries when computing aggregate multipliers if a pattern lesson is also present,
            // or weight them gently so multipliers grow smoothly across matches.
            val isMatchNarrative = l.lessonId.startsWith("PARTIDA_")
            val weight = (if (l.archetype == archetype || l.archetype == "general") 1.0 else 0.75) *
                (if (isMatchNarrative) 0.35 else 1.0)
            val impact = l.occurrences * l.confidence * weight

            when (l.errorType) {
                CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT -> {
                    redundantPenalty += impact * 0.25
                    backupBonus += impact * 0.20
                }
                CpuErrorType.UNATTACHED_TURN_ENERGY -> {
                    backupBonus += impact * 0.25
                    preAttackSetupBonus += impact * 0.25
                }
                CpuErrorType.UNPROMOTED_HAND_STAGNATION -> {
                    handBonus += impact * 0.22
                    searchBonus += impact * 0.18
                    preAttackSetupBonus += impact * 0.20
                }
                CpuErrorType.PREMATURE_ATTACK_WITHOUT_SETUP -> {
                    preAttackSetupBonus += impact * 0.30
                    handBonus += impact * 0.25
                    backupBonus += impact * 0.20
                    benchBonus += impact * 0.20
                }
                CpuErrorType.MISSED_SETUP_COMBO -> {
                    searchBonus += impact * 0.25
                    thinningBonus += impact * 0.15
                    evolutionBonus += impact * 0.25
                }
                CpuErrorType.FAILURE_TO_PREPARE_BACKUP -> {
                    backupBonus += impact * 0.30
                    benchBonus += impact * 0.25
                    preAttackSetupBonus += impact * 0.20
                }
                CpuErrorType.PLAYER_BENCH_SWEEPER_STRATEGY -> {
                    counterStrategyBonus += impact * 0.30
                    backupBonus += impact * 0.15
                }
                CpuErrorType.PLAYER_HAND_BURST_COMBO -> {
                    handDisruptionBonus += impact * 0.30
                    counterStrategyBonus += impact * 0.15
                }
                CpuErrorType.PLAYER_GUST_BENCH_HUNTER -> {
                    benchProtection += impact * 0.25
                    backupBonus += impact * 0.20
                }
                CpuErrorType.SUICIDAL_ATTACK -> {}
            }
        }

        return AdaptiveTuningParameters(
            redundantEnergyPenaltyMultiplier = redundantPenalty,
            backupAttackerBonusMultiplier = backupBonus,
            handUtilizationBonusMultiplier = handBonus,
            searchCardBonusMultiplier = searchBonus,
            thinningBonusMultiplier = thinningBonus,
            preAttackSetupBonusMultiplier = preAttackSetupBonus,
            evolutionPriorityMultiplier = evolutionBonus,
            benchDevelopmentBonusMultiplier = benchBonus,
            counterStrategyBonusMultiplier = counterStrategyBonus,
            handDisruptionBonusMultiplier = handDisruptionBonus,
            benchProtectionMultiplier = benchProtection,
            learnedPlayerThreatPokemonIds = learnedThreats
        ).clamp()
    }
}

class CpuLearningEngine(
    private val catalog: CardCatalog = TrainerCatalog,
    val memory: CpuExperienceMemory = CpuExperienceMemory()
) {
    private val reviewer = PostMatchCpuReview(catalog)
    private val tuner = AdaptiveStrategyTuner(memory)

    var learningEnabled: Boolean = true
    var matchesAnalyzedCount: Int = 0
    var cpuWinsCount: Int = 0
    var cpuLossesCount: Int = 0
    var lastMatchReview: MatchReviewReport? = null
        private set

    var experienceMemory: CpuExperienceMemory
        get() = memory
        set(value) {
            val analyzed = value.matchesAnalyzed
            memory.reset()
            memory.loadFromJson(value.toJson())
            memory.matchesAnalyzed = maxOf(analyzed, memory.getAllLessons().count { it.lessonId.startsWith("PARTIDA_") })
            matchesAnalyzedCount = memory.matchesAnalyzed
        }
    fun resetMemory() {
        memory.reset()
        matchesAnalyzedCount = 0
        cpuWinsCount = 0
        cpuLossesCount = 0
        lastMatchReview = null
    }

    fun reviewMatch(recording: MatchRecording): MatchReviewReport {
        matchesAnalyzedCount++
        memory.matchesAnalyzed = matchesAnalyzedCount
        if (recording.winner == 1) {
            cpuWinsCount++
        } else {
            cpuLossesCount++
        }

        val report = reviewer.review(recording)
        lastMatchReview = report
        if (learningEnabled) {
            memory.learn(report.generatedLessons, recording.gameId)
        }
        return report
    }

    fun getTuningForArchetype(archetype: String): AdaptiveTuningParameters {
        return if (learningEnabled) tuner.computeParameters(archetype) else AdaptiveTuningParameters()
    }

    fun getDashboardStats(): String {
        val lessons = memory.getAllLessons()
        val redundantCount = lessons.filter { it.errorType == CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT || it.errorType == CpuErrorType.UNATTACHED_TURN_ENERGY }.sumOf { it.occurrences }
        val backupCount = lessons.filter { it.errorType == CpuErrorType.FAILURE_TO_PREPARE_BACKUP }.sumOf { it.occurrences }
        val handCount = lessons.filter { it.errorType == CpuErrorType.UNPROMOTED_HAND_STAGNATION || it.errorType == CpuErrorType.PREMATURE_ATTACK_WITHOUT_SETUP }.sumOf { it.occurrences }
        val comboCount = lessons.filter { it.errorType == CpuErrorType.MISSED_SETUP_COMBO }.sumOf { it.occurrences }

        return buildString {
            appendLine("=== ESTATÍSTICAS DE APRENDIZADO DA CPU ===")
            appendLine("Status do Aprendizado: ${if (learningEnabled) "ATIVADO" else "DESATIVADO"}")
            appendLine("Partidas Analisadas: ${memory.matchesAnalyzed}")
            appendLine("Vitórias da CPU: $cpuWinsCount")
            appendLine("Derrotas da CPU: $cpuLossesCount")
            appendLine()
            appendLine("Padrões Corrigidos:")
            appendLine("- Preparação de atacante reserva no banco: $backupCount")
            appendLine("- Uso da mão e setup antes do ataque: $handCount")
            appendLine("- Gestão de energia (ativo vs banco): $redundantCount")
            appendLine("- Combinações de busca e evolução: $comboCount")
            appendLine()
            appendLine("Lições Ativas: ${lessons.size}")
            for (l in lessons.asReversed().take(15)) {
                appendLine("• [${l.archetype}] ${l.lessonId} (${"%.0f".format(l.confidence * 100)}%): ${l.recommendedBehavior}")
            }
            append("==========================================")
        }
    }
}

