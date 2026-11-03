package com.tcgtrainer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RecordedCard(
    val id: String,
    val definitionId: String,
    val name: String,
    val category: String,
    val energyType: String? = null
)

@Serializable
data class RecordedPokemon(
    val id: String,
    val definitionId: String,
    val name: String,
    val hp: Int,
    val maxHp: Int,
    val damage: Int,
    val energies: List<RecordedCard> = emptyList(),
    val tools: List<RecordedCard> = emptyList(),
    val conditions: List<SpecialCondition> = emptyList()
)

@Serializable
data class RecordedPlayerState(
    val playerIndex: Int,
    val active: RecordedPokemon?,
    val bench: List<RecordedPokemon> = emptyList(),
    val hand: List<RecordedCard> = emptyList(),
    val handCount: Int,
    val prizesRemaining: Int,
    val deckCount: Int,
    val discardCount: Int,
    val discard: List<RecordedCard> = emptyList()
)

@Serializable
data class RecordedBoardState(
    val turn: Int,
    val phase: Phase,
    val currentPlayer: Int,
    val stadium: RecordedCard? = null,
    val players: List<RecordedPlayerState>
)

@Serializable
data class CpuDecisionTelemetry(
    val turn: Int,
    val difficulty: String,
    val objective: String,
    val legalActionsCount: Int,
    val legalAttacksCount: Int,
    val playableTrainersCount: Int,
    val selectedAction: String,
    val target: String? = null,
    val energyBefore: Int? = null,
    val energyAfter: Int? = null,
    val alternativeActions: List<String> = emptyList(),
    val decisionScore: Double = 0.0,
    val alternativeScores: List<Double> = emptyList(),
    val searchDepth: Int = 0,
    val sequencesEvaluated: Int = 0,
    val searchTimeMs: Long = 0,
    val opponentThreatBefore: Double = 0.0,
    val opponentThreatAfter: Double = 0.0
)

@Serializable
data class RecordedAction(
    val stepIndex: Int,
    val turn: Int,
    val player: Int,
    val actionType: String,
    val actionDetails: String,
    val stateBefore: RecordedBoardState,
    val stateAfter: RecordedBoardState,
    val events: List<GameEvent> = emptyList(),
    val cpuDecision: CpuDecisionTelemetry? = null
)

@Serializable
data class RecordedTurn(
    val turnNumber: Int,
    val player: Int,
    val handAtStart: List<RecordedCard> = emptyList(),
    val handAtEnd: List<RecordedCard> = emptyList(),
    val actions: MutableList<RecordedAction> = mutableListOf(),
    var attackExecuted: Boolean = false
)

@Serializable
data class MatchRecording(
    val schemaVersion: Int = 1,
    val gameId: String,
    val seed: Long,
    val engineVersion: String = "lucario-v1",
    val appVersion: String = "5.99.48",
    val difficulty: String,
    val humanDeckId: String,
    val cpuDeckId: String,
    val mode: String,
    val deckLists: List<List<String>> = emptyList(),
    val deckNames: List<String> = emptyList(),
    var winner: Int? = null,
    var winReason: String? = null,
    var totalTurns: Int = 0,
    val startedAtEpochMs: Long = System.currentTimeMillis(),
    var finishedAtEpochMs: Long? = null,
    val turns: MutableList<RecordedTurn> = mutableListOf()
) {
    companion object {
        private val jsonFormat = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun fromJson(jsonText: String): MatchRecording = jsonFormat.decodeFromString(serializer(), jsonText)
    }

    fun toJson(): String = jsonFormat.encodeToString(this)

    fun toReadableTxt(catalog: CardCatalog = TrainerCatalog): String = buildString {
        appendLine("================================================================================")
        appendLine("POKECARD TCG TRAINER — RELATÓRIO COMPLETO DE PARTIDA")
        appendLine("================================================================================")
        appendLine("ID da Partida: $gameId | Seed: $seed | Dificuldade: $difficulty | Modo: $mode")
        appendLine("Versão Engine: $engineVersion | App: $appVersion")
        appendLine("Jogador Humano (P0): $humanDeckId (${deckNames.getOrElse(0) { "Humano" }})")
        appendLine("CPU Trainer    (P1): $cpuDeckId (${deckNames.getOrElse(1) { "CPU" }})")
        appendLine("Resultado: ${when (winner) { 0 -> "VITÓRIA DO HUMANO"; 1 -> "VITÓRIA DA CPU"; else -> "EM ANDAMENTO OU EMPATE" }}")
        if (winReason != null) appendLine("Motivo do Fim: $winReason")
        appendLine("Total de Turnos: $totalTurns")
        appendLine("================================================================================\n")

        for (t in turns) {
            val playerName = if (t.player == 0) "HUMANO (P0)" else "CPU (P1)"
            appendLine("--------------------------------------------------------------------------------")
            appendLine("TURNO ${t.turnNumber} • Jogador: $playerName")
            appendLine("--------------------------------------------------------------------------------")
            appendLine("Mão Inicial (${t.handAtStart.size} cartas):")
            appendLine(t.handAtStart.joinToString(", ") { "${it.name} [${it.id}]" }.ifEmpty { "Mão vazia" })
            appendLine()

            appendLine("Ações Executadas no Turno (${t.actions.size} ações):")
            for ((idx, a) in t.actions.withIndex()) {
                val actorLabel = if (a.player == 0) "Humano" else "CPU"
                appendLine("  [${idx + 1}] $actorLabel: ${a.actionType} -> ${a.actionDetails}")

                if (a.events.isNotEmpty()) {
                    appendLine("      Eventos: ${a.events.joinToString(" | ") { it.message }}")
                }

                if (a.cpuDecision != null) {
                    val cpu = a.cpuDecision
                    appendLine("      --- RACIOCÍNIO DA CPU ---")
                    appendLine("      Objetivo: ${cpu.objective} | Dificuldade: ${cpu.difficulty}")
                    appendLine("      Ações Legais: ${cpu.legalActionsCount} (Ataques: ${cpu.legalAttacksCount}, Treinadores: ${cpu.playableTrainersCount})")
                    appendLine("      Ação Escolhida: ${cpu.selectedAction}${if (cpu.target != null) " -> Alvo: ${cpu.target}" else ""}")
                    if (cpu.energyBefore != null && cpu.energyAfter != null) {
                        appendLine("      Energia no Alvo: ${cpu.energyBefore} -> ${cpu.energyAfter}")
                    }
                    appendLine("      Score da Decisão: ${"%.1f".format(cpu.decisionScore)}")
                    if (cpu.alternativeActions.isNotEmpty()) {
                        appendLine("      Alternativas Consideradas: ${cpu.alternativeActions.take(3).joinToString("; ")}")
                    }
                    appendLine("      Profundidade: ${cpu.searchDepth} | Sequências Avaliadas: ${cpu.sequencesEvaluated} | Tempo: ${cpu.searchTimeMs}ms")
                    appendLine("      Ameaça Oponente: Antes=${"%.1f".format(cpu.opponentThreatBefore)} -> Depois=${"%.1f".format(cpu.opponentThreatAfter)}")
                    appendLine("      --------------------------")
                }
            }

            appendLine()
            appendLine("Mão Final (${t.handAtEnd.size} cartas):")
            appendLine(t.handAtEnd.joinToString(", ") { "${it.name} [${it.id}]" }.ifEmpty { "Mão vazia" })

            val lastAction = t.actions.lastOrNull()
            if (lastAction != null) {
                val board = lastAction.stateAfter
                appendLine()
                appendLine("Estado do Tabuleiro ao Fim do Turno ${t.turnNumber}:")
                for (p in board.players) {
                    val pLabel = if (p.playerIndex == 0) "Humano" else "CPU"
                    val activeDesc = p.active?.let {
                        "${it.name} (${it.hp}/${it.maxHp} HP, ${it.energies.size} energias: [${it.energies.joinToString(",") { e -> e.name }}])"
                    } ?: "Sem ativo"
                    val benchDesc = p.bench.joinToString("; ") {
                        "${it.name} (${it.hp}/${it.maxHp} HP, ${it.energies.size} energias)"
                    }.ifEmpty { "Vazio" }
                    appendLine("  $pLabel: Ativo: $activeDesc | Banco: [$benchDesc] | Prêmios Restantes: ${p.prizesRemaining} | Deck: ${p.deckCount} | Descarte: ${p.discardCount}")
                }
            }
            appendLine()
        }

        appendLine("================================================================================")
        appendLine("FIM DO RELATÓRIO DA PARTIDA")
        appendLine("================================================================================")
    }
}

