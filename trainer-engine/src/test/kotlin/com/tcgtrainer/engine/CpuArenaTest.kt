package com.tcgtrainer.engine

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*
import kotlin.test.*

/** Explicit offline evaluation task; the regular unit suite does not run a thousand games. */
class CpuArenaTest {
    private data class Outcome(val game: Int, val seed: Long, val masterSeat: Int, val decks: List<String>, val baseline: CpuStrategy,
        val winner: Int?, val winReason: String?, val turns: Int, val actions: Int, val masterTimes: List<Long>,
        val recentActions: List<String>, val error: String?, val slowestDecision: String?, val baselineTimes: List<Long>)

    @Test fun `paired real deck arena`() {
        val games = System.getProperty("cpu.arena.games", "0").toInt()
        if (games == 0) return
        require(games in 2..10000 && games % 2 == 0)
        val baselineMode = System.getProperty("cpu.arena.baseline", "TACTICAL")
        val frozen = if (baselineMode == "FROZEN") FrozenCpuVersion(File(System.getProperty("cpu.arena.baselineJar", ""))) else null
        if (baselineMode == "MIXED") require(games % 4 == 0)
        val threads = System.getProperty("cpu.arena.threads", "4").toInt().coerceIn(1, 8)
        val eligibleDecks = TrainingDecks.all.filter { it.format != DeckFormat.ACADEMY && DeckValidator.validate(it).playable }
        val requestedDeck = System.getProperty("cpu.arena.deck")?.takeIf { it.isNotBlank() }
        val decks = if (requestedDeck == null) eligibleDecks else listOf(eligibleDecks.single { it.id == requestedDeck })
        require(decks.isNotEmpty())
        println("CPU_ARENA start games=$games threads=$threads decks=${decks.map { it.id }}")
        val completed = AtomicInteger()
        val progressWins = AtomicInteger()
        val progressLosses = AtomicInteger()
        val progressErrors = AtomicInteger()
        val live = File("build/cpu-arena/outcomes.ndjson")
        live.parentFile.mkdirs()
        live.writeText("")
        val reportLock = Any()
        val started = System.nanoTime()
        val executor = Executors.newFixedThreadPool(threads)
        val outcomes = try {
            val futures = (0 until games).map { index -> executor.submit(Callable {
                val pair = (if (baselineMode == "MIXED") index % (games/2) else index)/2
                val seed = System.getProperty("cpu.arena.seedBase", "1000003").toLong() + pair*104_729L
                val a = decks[pair % decks.size]
                val b = decks[(pair/decks.size) % decks.size]
                val baseline = if (baselineMode == "MIXED") {
                    if (index < games/2) CpuStrategy.TACTICAL else CpuStrategy.HEURISTIC
                } else if (frozen != null) CpuStrategy.MASTER else CpuStrategy.valueOf(baselineMode)
                val result = play(index, seed, index % 2, listOf(a, b), baseline, frozen)
                if (result.error != null) progressErrors.incrementAndGet()
                else if (result.winner == result.masterSeat) progressWins.incrementAndGet()
                else if (result.winner == 1-result.masterSeat) progressLosses.incrementAndGet()
                synchronized(reportLock) {
                    live.appendText(buildJsonObject {
                        put("game", result.game); put("seed", result.seed); put("masterSeat", result.masterSeat)
                        put("baseline", result.baseline.name)
                        put("winner", result.winner?.let(::JsonPrimitive) ?: JsonNull)
                        put("winReason", result.winReason?.let(::JsonPrimitive) ?: JsonNull)
                        putJsonArray("recentActions") { result.recentActions.forEach { add(it) } }
                        put("error", result.error?.let(::JsonPrimitive) ?: JsonNull)
                        putJsonArray("decks") { result.decks.forEach { add(it) } }
                    }.toString()+"\n")
                }
                val count = completed.incrementAndGet()
                if (count % 50 == 0 || count == games) println("CPU_ARENA completed=$count/$games wins=${progressWins.get()} losses=${progressLosses.get()} errors=${progressErrors.get()} elapsedSeconds=${(System.nanoTime()-started)/1_000_000_000}")
                result
            }) }
            futures.map { it.get() }
        } finally { executor.shutdownNow(); frozen?.close() }
        val valid = outcomes.filter { it.error == null }
        val wins = valid.count { it.winner == it.masterSeat }
        val losses = valid.count { it.winner == 1-it.masterSeat }
        val draws = valid.count { it.winner == null }
        val times = valid.flatMap { it.masterTimes }.sorted()
        val baselineTimes = valid.flatMap { it.baselineTimes }.sorted()
        fun percentile(p: Double) = if (times.isEmpty()) 0.0 else times[((times.size-1)*p).toInt()]/1_000_000.0
        val report = buildJsonObject {
            put("master", "MASTER: turn search + public counterplay + prize race")
            put("baseline", baselineMode)
            frozen?.let { put("baselineArtifact", it.file.absolutePath); put("baselineSha256", it.sha256); put("baselineKnowledge", it.knowledge) }
            put("baselineDecisionMedianMs", baselineTimes.getOrNull((baselineTimes.size-1).coerceAtLeast(0)/2)?.div(1_000_000.0) ?: 0.0)
            put("baselineDecisionP95Ms", baselineTimes.getOrNull(((baselineTimes.size-1).coerceAtLeast(0)*.95).toInt())?.div(1_000_000.0) ?: 0.0)
            put("candidateTuning", System.getProperty("cpu.arena.tuning", ".35,.15,80"))
            put("gamesRequested", games); put("gamesCompleted", valid.size); put("errors", games-valid.size)
            put("masterWins", wins); put("baselineWins", losses); put("draws", draws)
            put("masterWinRate", wins.toDouble()/games)
            put("masterDecisionMedianMs", percentile(.5)); put("masterDecisionP95Ms", percentile(.95)); put("masterDecisionMaxMs", percentile(1.0))
            put("timingScope", "CpuPlayer.choose only; excludes cpuInput and diagnostic heuristic/trace")
            put("elapsedSeconds", (System.nanoTime()-started)/1_000_000_000.0)
            put("pairedSeeds", true)
            put("publicPositionsHiddenZonesMasked", System.getProperty("cpu.arena.knowledge", "FAIR") == "FAIR")
            put("deckAndPrizeIdentitiesMasked", true)
            put("candidateKnowledge", System.getProperty("cpu.arena.knowledge", "FAIR"))
            put("masterKnowsOpponentHand", System.getProperty("cpu.arena.knowledge", "FAIR") == "OPPONENT_HAND")
            put("seedBase", System.getProperty("cpu.arena.seedBase", "1000003").toLong())
            putJsonArray("decks") { decks.forEach { add(it.id) } }
            putJsonArray("byBaseline") { outcomes.groupBy { it.baseline }.forEach { (baseline, group) -> add(buildJsonObject {
                put("baseline", if (frozen != null) "FROZEN_MASTER" else baseline.name); put("games", group.size)
                put("masterWins", group.count { it.error == null && it.winner == it.masterSeat })
                put("baselineWins", group.count { it.error == null && it.winner == 1-it.masterSeat })
                put("draws", group.count { it.error == null && it.winner == null })
                put("errors", group.count { it.error != null })
            }) } }
            putJsonArray("lossReasons") {
                valid.filter { it.winner == 1-it.masterSeat }
                    .groupBy { it.winReason ?: "Motivo não informado" }
                    .forEach { (reason, group) -> add(buildJsonObject {
                        put("reason", reason)
                        put("losses", group.size)
                        putJsonArray("games") { group.forEach { add(it.game) } }
                    }) }
            }
            putJsonArray("outcomes") { outcomes.forEach { outcome -> add(buildJsonObject {
                put("game", outcome.game); put("seed", outcome.seed); put("masterSeat", outcome.masterSeat)
                put("baseline", outcome.baseline.name)
                putJsonArray("decks") { outcome.decks.forEach { add(it) } }
                put("winner", outcome.winner?.let(::JsonPrimitive) ?: JsonNull)
                put("turns", outcome.turns); put("actions", outcome.actions)
                put("winReason", outcome.winReason?.let(::JsonPrimitive) ?: JsonNull)
                putJsonArray("recentActions") { outcome.recentActions.forEach { add(it) } }
                put("error", outcome.error?.let(::JsonPrimitive) ?: JsonNull)
                put("slowestMasterDecision", outcome.slowestDecision?.let(::JsonPrimitive) ?: JsonNull)
            }) } }
        }
        val output = File("build/cpu-arena/report.json")
        output.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        println("CPU_ARENA result wins=$wins losses=$losses draws=$draws errors=${games-valid.size} medianMs=${percentile(.5)} p95Ms=${percentile(.95)} report=${output.absolutePath}")
        assertEquals(games, valid.size, outcomes.filter { it.error != null }.take(5).joinToString("\n") { "${it.game} ${it.decks} seed=${it.seed}: ${it.error}" })
    }

    private fun play(index: Int, seed: Long, masterSeat: Int, decks: List<TrainingDeck>, baselineStrategy: CpuStrategy, frozen: FrozenCpuVersion? = null): Outcome {
        val engine = RulesEngine(TrainerCatalog)
        val tuningParts = System.getProperty("cpu.arena.tuning", ".35,.15,80").split(',').map { it.toDouble() }
        require(tuningParts.size == 3)
        val master = CpuPlayer(TrainerCatalog, CpuStrategy.MASTER, CpuTuning(tuningParts[0], tuningParts[1], tuningParts[2]))
        val baseline = CpuPlayer(TrainerCatalog, baselineStrategy)
        val previousChoose = frozen?.player()
        var state = engine.newGame(seed, decks.map { it.cards }, Mode.MATCH).copy(
            deckLists = decks.map { it.cards }, humanDeckId = decks[0].id, cpuDeckId = decks[1].id,
            deckNames = decks.map { it.name }, deckFormats = decks.map { it.format })
        var actions = 0
        val times = mutableListOf<Long>()
        val baselineTimes = mutableListOf<Long>()
        val trace = ArrayDeque<String>()
        var finalReason: String? = null
        var slowestNanos = 0L
        var slowestDecision: String? = null
        val error = runCatching {
            while (state.phase != Phase.GAME_OVER) {
                check(actions++ < 1000) { "Stalled at turn ${state.turn}; recent=$trace" }
                val isMaster = engine.actingPlayer(state) == masterSeat
                val input = engine.cpuInput(state, if (isMaster) CpuKnowledge.valueOf(System.getProperty("cpu.arena.knowledge", "FAIR")) else CpuKnowledge.FAIR)
                val heuristicChoice = if (isMaster) master.chooseHeuristic(input) else null
                val start = System.nanoTime()
                val previousDecision = if (!isMaster) previousChoose?.invoke(state) else null
                val action = if (isMaster) master.choose(input) else previousDecision?.first ?: baseline.choose(input)
                val decisionNanos = previousDecision?.second ?: (System.nanoTime()-start)
                if (!isMaster) baselineTimes += decisionNanos
                if (index == System.getProperty("cpu.arena.traceGame", "-1").toInt() && state.phase == Phase.MAIN && isMaster) {
                    val old = previousChoose?.invoke(state)?.first ?: baseline.choose(input)
                    if (old != action) println("CPU_TRACE T${state.turn} ownPrizes=${state.players[masterSeat].prizes.size} oppPrizes=${state.players[1-masterSeat].prizes.size} master=$action baseline=$old active=${state.players[masterSeat].active?.top?.definitionId}")
                }
                if (isMaster) {
                    times += decisionNanos
                    if (decisionNanos > slowestNanos) {
                        slowestNanos = decisionNanos
                        slowestDecision = "T${state.turn} ${state.phase} ${describeAction(state, action, engine)}: ${decisionNanos/1_000_000.0} ms"
                    }
                }
                val decision = buildString {
                    append("T${state.turn} P${engine.actingPlayer(state)} ")
                    append(if (isMaster) "MASTER " else "BASELINE ")
                    append(describeAction(state, action, engine))
                    if (heuristicChoice != null && heuristicChoice != action) {
                        append(" [heurística simples escolheria: ${describeAction(state, heuristicChoice, engine)}]")
                    }
                }
                trace.addLast(decision)
                if (trace.size > 16) trace.removeFirst()
                val next = engine.apply(state, action)
                check(next.legality == Legality.Legal) { "${next.legality}; recent=$trace" }
                state = next.state
                if (state.phase == Phase.GAME_OVER) finalReason = state.winReason
                val all = state.players.flatMap { p -> p.deck + p.hand + p.prizes + p.discard + p.pokemon().flatMap { it.cards + it.energies + it.tools } } +
                    listOfNotNull(state.stadium) + listOfNotNull(state.resolution?.toolCard)
                check(all.size == 120 && all.map { it.id }.distinct().size == 120) { "Card conservation failed; recent=$trace" }
            }
        }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        return Outcome(index, seed, masterSeat, decks.map { it.id }, baselineStrategy, state.winner, finalReason,
            state.turn, actions, times, trace.toList(), error, slowestDecision, baselineTimes)
    }

    private fun describeAction(state: GameState, action: GameAction, engine: RulesEngine): String {
        val actor = engine.actingPlayer(state)
        val player = state.players[actor]
        fun pokemon(id: String) = player.pokemon().firstOrNull { it.id == id }
            ?.let { engine.definition(it.top).name } ?: id
        fun card(id: String) = (player.hand + player.pokemon().flatMap { it.cards + it.energies + it.tools })
            .firstOrNull { it.id == id }?.let(engine::definition)?.name ?: id
        return when (action) {
            is GameAction.PlayTrainer -> "jogou ${card(action.cardId)}"
            is GameAction.AttachEnergy -> "ligou ${card(action.cardId)} em ${pokemon(action.pokemonId)}"
            is GameAction.Evolve -> "evoluiu ${pokemon(action.pokemonId)} com ${card(action.cardId)}"
            is GameAction.PlayBasic -> "colocou ${card(action.cardId)} no Banco"
            is GameAction.Attack -> {
                val attacker = player.active
                val attackName = attacker?.cards.orEmpty().asReversed().asSequence()
                    .flatMap { engine.definition(it).attacks.asSequence() }
                    .firstOrNull { it.id == action.attackId }?.name ?: action.attackId
                val target = state.players[1-actor].pokemon().firstOrNull { it.id == action.targetId }
                    ?.let { engine.definition(it.top).name } ?: action.targetId
                "atacou com $attackName mirando $target"
            }
            is GameAction.Retreat -> "recuou para ${pokemon(action.pokemonId)}"
            is GameAction.Promote -> "promoveu ${pokemon(action.pokemonId)}"
            GameAction.EndTurn -> "encerrou o turno"
            else -> action.toString()
        }
    }
}
