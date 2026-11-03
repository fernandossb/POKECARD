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
    SUICIDAL_ATTACK
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
    val thinningBonusMultiplier: Double = 1.0
) {
    fun clamp(): AdaptiveTuningParameters = copy(
        redundantEnergyPenaltyMultiplier = redundantEnergyPenaltyMultiplier.coerceIn(0.5, 3.0),
        backupAttackerBonusMultiplier = backupAttackerBonusMultiplier.coerceIn(0.5, 2.5),
        handUtilizationBonusMultiplier = handUtilizationBonusMultiplier.coerceIn(0.5, 2.5),
        searchCardBonusMultiplier = searchCardBonusMultiplier.coerceIn(0.5, 2.0),
        thinningBonusMultiplier = thinningBonusMultiplier.coerceIn(0.5, 2.0)
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
    fun analyze(recording: MatchRecording, catalog: CardCatalog): List<DetectedCpuIssue> {
        val issues = mutableListOf<DetectedCpuIssue>()

        for (turn in recording.turns.filter { it.player == 1 }) {
            for (action in turn.actions) {
                if (action.actionType == "AttachEnergy" || action.actionDetails.startsWith("AttachEnergy")) {
                    val targetId = action.cpuDecision?.target
                    val targetPkmnBefore = action.stateBefore.players[1].active?.takeIf { it.id == targetId }
                        ?: action.stateBefore.players[1].bench.firstOrNull { it.id == targetId }

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
                        val otherBenchPokemon = action.stateBefore.players[1].bench.filter { it.id != targetPkmnBefore.id }
                        val benchNeedsEnergy = otherBenchPokemon.any { b ->
                            val bDef = runCatching { catalog.get(b.definitionId) }.getOrNull()
                            val bCost = bDef?.attacks?.maxOfOrNull { it.cost.size } ?: 1
                            b.energies.size < bCost
                        }

                        if (!hasScalingAttack && energyCountBefore >= maxAttackCost && benchNeedsEnergy) {
                            issues += DetectedCpuIssue(
                                turn = turn.turnNumber,
                                type = CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT,
                                description = "Anexou energia no Pokémon ${targetPkmnBefore.name} que já possuía $energyCountBefore energias (custo máx: $maxAttackCost), enquanto havia atacante reserva no banco precisando de energia.",
                                severityScore = 40.0 + (energyCountBefore - maxAttackCost) * 15.0,
                                targetPokemonId = targetPkmnBefore.id,
                                recommendedAction = "Anexar energia no Pokémon reserva do banco"
                            )
                        }
                    }
                }
            }
        }
        return issues
    }
}

object HandEfficiencyAnalyzer {
    fun analyze(recording: MatchRecording, catalog: CardCatalog): List<DetectedCpuIssue> {
        val issues = mutableListOf<DetectedCpuIssue>()

        for (turn in recording.turns.filter { it.player == 1 }) {
            val trainersAtStart = turn.handAtStart.filter { it.category == "TRAINER" }
            val playedTrainerActions = turn.actions.filter { it.actionType == "PlayTrainer" || it.actionDetails.startsWith("PlayTrainer") }

            val unplayedTrainers = turn.handAtEnd.filter { it.category == "TRAINER" }
            val hasSearchOrDraw = unplayedTrainers.any {
                val def = runCatching { catalog.get(it.definitionId) }.getOrNull()
                def?.trainerEffect is TrainerEffect.DrawCards ||
                def?.trainerEffect is TrainerEffect.ShuffleBothHandsDraw ||
                def?.trainerEffect is TrainerEffect.Search
            }

            val lastState = turn.actions.lastOrNull()?.stateAfter ?: continue
            val cpuBoard = lastState.players[1]
            val activeNeedsEnergyOrHelp = cpuBoard.active?.let { it.energies.isEmpty() } ?: true
            val benchNeedsSetup = cpuBoard.bench.isEmpty()

            if (turn.handAtStart.size >= 4 && playedTrainerActions.isEmpty() && hasSearchOrDraw && (activeNeedsEnergyOrHelp || benchNeedsSetup)) {
                issues += DetectedCpuIssue(
                    turn = turn.turnNumber,
                    type = CpuErrorType.UNPROMOTED_HAND_STAGNATION,
                    description = "A CPU encerrou o turno ${turn.turnNumber} acumulando ${turn.handAtEnd.size} cartas sem usar cartas de busca/compra disponíveis, enquanto o campo precisava de preparação.",
                    severityScore = 35.0,
                    recommendedAction = "Utilizar Itens ou Apoiadores para acelerar o desenvolvimento do campo"
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
            val hasUltraBallOrNest = turn.handAtStart.any {
                it.definitionId.contains("ultra-ball", ignoreCase = true) || it.definitionId.contains("nest-ball", ignoreCase = true)
            }
            val playedUltraBallOrNest = turn.actions.any {
                it.actionDetails.contains("ultra-ball", ignoreCase = true) || it.actionDetails.contains("nest-ball", ignoreCase = true)
            }

            val lastBoard = turn.actions.lastOrNull()?.stateAfter
            val activeIsBasic = lastBoard?.players?.getOrNull(1)?.active?.let {
                val def = runCatching { catalog.get(it.definitionId) }.getOrNull()
                def?.stage == Stage.BASIC
            } ?: false

            if (hasUltraBallOrNest && !playedUltraBallOrNest && activeIsBasic && turn.handAtStart.size >= 3) {
                issues += DetectedCpuIssue(
                    turn = turn.turnNumber,
                    type = CpuErrorType.MISSED_SETUP_COMBO,
                    description = "Oportunidade de combo de busca/evolução não explorada no turno ${turn.turnNumber}: Ultra Ball/Nest Ball retida na mão.",
                    severityScore = 30.0,
                    recommendedAction = "Jogar carta de busca para encontrar evolução ou atacante chave"
                )
            }
        }
        return issues
    }
}

class PostMatchCpuReview(
    private val catalog: CardCatalog = TrainerCatalog
) {
    fun review(recording: MatchRecording): MatchReviewReport {
        val cpuLost = recording.winner == 0
        val archetype = recording.cpuDeckId

        val energyIssues = EnergyEfficiencyAnalyzer.analyze(recording, catalog)
        val handIssues = HandEfficiencyAnalyzer.analyze(recording, catalog)
        val comboIssues = MissedComboAnalyzer.analyze(recording, catalog)

        val allIssues = energyIssues + handIssues + comboIssues

        val primaryWeakness = when {
            energyIssues.size >= handIssues.size && energyIssues.size >= comboIssues.size && energyIssues.isNotEmpty() -> "Alocação Ineficiente de Energia"
            handIssues.size >= comboIssues.size && handIssues.isNotEmpty() -> "Retenção Passiva de Recursos na Mão"
            comboIssues.isNotEmpty() -> "Combinações de Setup Não Aproveitadas"
            else -> "Presença de Campo e Ritmo da Partida"
        }

        val lessons = mutableListOf<CpuLesson>()

        if (energyIssues.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "AVOID_REDUNDANT_ENERGY",
                errorType = CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT,
                archetype = archetype,
                occurrences = energyIssues.size,
                confidence = (0.5 + energyIssues.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Evitar anexar energias extras acima do custo de ataque ao mesmo Pokémon quando o banco precisa de preparação.",
                contextSummary = "Detectado em ${energyIssues.size} jogada(s) no deck $archetype."
            )
        }

        if (handIssues.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "ACTIVATE_HAND_RESOURCES",
                errorType = CpuErrorType.UNPROMOTED_HAND_STAGNATION,
                archetype = archetype,
                occurrences = handIssues.size,
                confidence = (0.5 + handIssues.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Utilizar cartas de busca e compra para acelerar atacantes em vez de retê-las passivamente.",
                contextSummary = "Detectado em ${handIssues.size} turno(s) no deck $archetype."
            )
        }

        if (comboIssues.isNotEmpty()) {
            lessons += CpuLesson(
                lessonId = "SEEK_COMBO_SETUP",
                errorType = CpuErrorType.MISSED_SETUP_COMBO,
                archetype = archetype,
                occurrences = comboIssues.size,
                confidence = (0.5 + comboIssues.size * 0.1).coerceAtMost(0.95),
                recommendedBehavior = "Priorizar linhas de busca precoce de Pokémon de evolução.",
                contextSummary = "Detectado em ${comboIssues.size} oportunidade(s) no deck $archetype."
            )
        }

        val summaryText = buildString {
            appendLine("=== CPU MATCH REVIEW ===")
            appendLine("Partida: ${recording.gameId} | Deck: $archetype | Resultado: ${if (cpuLost) "DERROTA DA CPU" else "VITÓRIA DA CPU"}")
            appendLine("Fraqueza Principal: $primaryWeakness")
            appendLine("Erros Detectados: ${allIssues.size} (Energia: ${energyIssues.size}, Mão: ${handIssues.size}, Combos: ${comboIssues.size})")
            appendLine()
            for (issue in allIssues) {
                appendLine("- [T${issue.turn}] ${issue.type}: ${issue.description}")
            }
            appendLine()
            appendLine("Lições Geradas: ${lessons.size}")
            for (lesson in lessons) {
                appendLine("• ${lesson.lessonId} (${"%.0f".format(lesson.confidence * 100)}% confiança): ${lesson.recommendedBehavior}")
            }
            append("=== END REVIEW ===")
        }

        return MatchReviewReport(
            gameId = recording.gameId,
            archetype = archetype,
            cpuLost = cpuLost,
            primaryWeakness = primaryWeakness ?: "GENERAL_PLAY",
            detectedIssues = allIssues,
            generatedLessons = lessons,
            reviewSummaryText = summaryText
        )
    }
}

class CpuExperienceMemory {
    private val lessonsMap = mutableMapOf<String, CpuLesson>()
    val lessons: List<CpuLesson> get() = lessonsMap.values.toList()
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
            val key = "${newLesson.archetype}:${newLesson.lessonId}"
            val existing = lessonsMap[key]
            if (existing != null) {
                existing.occurrences += newLesson.occurrences
                existing.confidence = (existing.confidence + 0.1).coerceAtMost(0.99)
                existing.lastObservedMatchId = matchId
            } else {
                newLesson.lastObservedMatchId = matchId
                lessonsMap[key] = newLesson
            }
        }
    }

    fun reset() {
        lessonsMap.clear()
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
            lessonsMap["${lesson.archetype}:${lesson.lessonId}"] = lesson
        }
    }
}

class AdaptiveStrategyTuner(
    private val memory: CpuExperienceMemory
) {
    fun computeParameters(archetype: String): AdaptiveTuningParameters {
        val lessons = memory.getLessonsForArchetype(archetype)
        var redundantPenalty = 1.0
        var backupBonus = 1.0
        var handBonus = 1.0
        var searchBonus = 1.0
        var thinningBonus = 1.0

        for (l in lessons) {
            when (l.errorType) {
                CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT -> {
                    redundantPenalty += l.occurrences * 0.25 * l.confidence
                    backupBonus += l.occurrences * 0.15 * l.confidence
                }
                CpuErrorType.UNPROMOTED_HAND_STAGNATION -> {
                    handBonus += l.occurrences * 0.2 * l.confidence
                    searchBonus += l.occurrences * 0.15 * l.confidence
                }
                CpuErrorType.MISSED_SETUP_COMBO -> {
                    searchBonus += l.occurrences * 0.25 * l.confidence
                    thinningBonus += l.occurrences * 0.15 * l.confidence
                }
                CpuErrorType.FAILURE_TO_PREPARE_BACKUP -> {
                    backupBonus += l.occurrences * 0.3 * l.confidence
                }
                CpuErrorType.SUICIDAL_ATTACK -> {}
            }
        }

        return AdaptiveTuningParameters(
            redundantEnergyPenaltyMultiplier = redundantPenalty,
            backupAttackerBonusMultiplier = backupBonus,
            handUtilizationBonusMultiplier = handBonus,
            searchCardBonusMultiplier = searchBonus,
            thinningBonusMultiplier = thinningBonus
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

    var experienceMemory: CpuExperienceMemory
        get() = memory
        set(value) {
            memory.reset()
            memory.loadFromJson(value.toJson())
        }
    fun resetMemory() = memory.reset()

    fun reviewMatch(recording: MatchRecording): MatchReviewReport {
        matchesAnalyzedCount++
        memory.matchesAnalyzed++
        if (recording.winner == 1) {
            cpuWinsCount++
        } else {
            cpuLossesCount++
        }

        val report = reviewer.review(recording)
        if (learningEnabled && recording.winner == 0) {
            memory.learn(report.generatedLessons, recording.gameId)
        }
        return report
    }

    fun getTuningForArchetype(archetype: String): AdaptiveTuningParameters {
        return if (learningEnabled) tuner.computeParameters(archetype) else AdaptiveTuningParameters()
    }

    fun getDashboardStats(): String {
        val lessons = memory.getAllLessons()
        val redundantCount = lessons.filter { it.errorType == CpuErrorType.REDUNDANT_ENERGY_ATTACHMENT }.sumOf { it.occurrences }
        val handCount = lessons.filter { it.errorType == CpuErrorType.UNPROMOTED_HAND_STAGNATION }.sumOf { it.occurrences }
        val comboCount = lessons.filter { it.errorType == CpuErrorType.MISSED_SETUP_COMBO }.sumOf { it.occurrences }

        return buildString {
            appendLine("=== ESTATÍSTICAS DE APRENDIZADO DA CPU ===")
            appendLine("Status do Aprendizado: ${if (learningEnabled) "ATIVADO" else "DESATIVADO"}")
            appendLine("Partidas Analisadas: $matchesAnalyzedCount")
            appendLine("Vitórias da CPU: $cpuWinsCount")
            appendLine("Derrotas da CPU: $cpuLossesCount")
            appendLine()
            appendLine("Erros Detectados:")
            appendLine("- Erros de energia redundante: $redundantCount")
            appendLine("- Mãos estagnadas/improdutivas: $handCount")
            appendLine("- Combinações perdidas: $comboCount")
            appendLine()
            appendLine("Lições Ativas: ${lessons.size}")
            for (l in lessons) {
                appendLine("• [${l.archetype}] ${l.lessonId} (ocorrências: ${l.occurrences}, confiança: ${"%.0f".format(l.confidence * 100)}%)")
            }
            append("==========================================")
        }
    }
}

