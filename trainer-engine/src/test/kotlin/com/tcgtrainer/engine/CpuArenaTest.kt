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
        val winner: Int?, val turns: Int, val actions: Int, val masterTimes: List<Long>, val error: String?)

    @Test fun `paired real deck arena`() {
        val games = System.getProperty("cpu.arena.games", "0").toInt()
        if (games == 0) return
        require(games in 2..10000 && games % 2 == 0)
        val baselineMode = System.getProperty("cpu.arena.baseline", "TACTICAL")
        if (baselineMode == "MIXED") require(games % 4 == 0)
        val threads = System.getProperty("cpu.arena.threads", "4").toInt().coerceIn(1, 8)
        val decks = TrainingDecks.all.filter { it.format != DeckFormat.ACADEMY && DeckValidator.validate(it).playable }
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
                } else CpuStrategy.valueOf(baselineMode)
                val result = play(index, seed, index % 2, listOf(a, b), baseline)
                if (result.error != null) progressErrors.incrementAndGet()
                else if (result.winner == result.masterSeat) progressWins.incrementAndGet()
                else if (result.winner == 1-result.masterSeat) progressLosses.incrementAndGet()
                synchronized(reportLock) {
                    live.appendText(buildJsonObject {
                        put("game", result.game); put("seed", result.seed); put("masterSeat", result.masterSeat)
                        put("baseline", result.baseline.name)
                        put("winner", result.winner?.let(::JsonPrimitive) ?: JsonNull)
                        put("error", result.error?.let(::JsonPrimitive) ?: JsonNull)
                        putJsonArray("decks") { result.decks.forEach { add(it) } }
                    }.toString()+"\n")
                }
                val count = completed.incrementAndGet()
                if (count % 50 == 0 || count == games) println("CPU_ARENA completed=$count/$games wins=${progressWins.get()} losses=${progressLosses.get()} errors=${progressErrors.get()} elapsedSeconds=${(System.nanoTime()-started)/1_000_000_000}")
                result
            }) }
            futures.map { it.get() }
        } finally { executor.shutdownNow() }
        val valid = outcomes.filter { it.error == null }
        val wins = valid.count { it.winner == it.masterSeat }
        val losses = valid.count { it.winner == 1-it.masterSeat }
        val draws = valid.count { it.winner == null }
        val times = valid.flatMap { it.masterTimes }.sorted()
        fun percentile(p: Double) = if (times.isEmpty()) 0.0 else times[((times.size-1)*p).toInt()]/1_000_000.0
        val report = buildJsonObject {
            put("master", "MASTER: turn search + public counterplay + prize race")
            put("baseline", baselineMode)
            put("gamesRequested", games); put("gamesCompleted", valid.size); put("errors", games-valid.size)
            put("masterWins", wins); put("baselineWins", losses); put("draws", draws)
            put("masterWinRate", wins.toDouble()/games)
            put("masterDecisionMedianMs", percentile(.5)); put("masterDecisionP95Ms", percentile(.95)); put("masterDecisionMaxMs", percentile(1.0))
            put("elapsedSeconds", (System.nanoTime()-started)/1_000_000_000.0)
            put("pairedSeeds", true); put("hiddenZonesMasked", true)
            put("seedBase", System.getProperty("cpu.arena.seedBase", "1000003").toLong())
            putJsonArray("decks") { decks.forEach { add(it.id) } }
            putJsonArray("byBaseline") { outcomes.groupBy { it.baseline }.forEach { (baseline, group) -> add(buildJsonObject {
                put("baseline", baseline.name); put("games", group.size)
                put("masterWins", group.count { it.error == null && it.winner == it.masterSeat })
                put("baselineWins", group.count { it.error == null && it.winner == 1-it.masterSeat })
                put("draws", group.count { it.error == null && it.winner == null })
                put("errors", group.count { it.error != null })
            }) } }
            putJsonArray("outcomes") { outcomes.forEach { outcome -> add(buildJsonObject {
                put("game", outcome.game); put("seed", outcome.seed); put("masterSeat", outcome.masterSeat)
                put("baseline", outcome.baseline.name)
                putJsonArray("decks") { outcome.decks.forEach { add(it) } }
                put("winner", outcome.winner?.let(::JsonPrimitive) ?: JsonNull)
                put("turns", outcome.turns); put("actions", outcome.actions)
                put("error", outcome.error?.let(::JsonPrimitive) ?: JsonNull)
            }) } }
        }
        val output = File("build/cpu-arena/report.json")
        output.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        println("CPU_ARENA result wins=$wins losses=$losses draws=$draws errors=${games-valid.size} medianMs=${percentile(.5)} p95Ms=${percentile(.95)} report=${output.absolutePath}")
        assertEquals(games, valid.size, outcomes.filter { it.error != null }.take(5).joinToString("\n") { "${it.game} ${it.decks} seed=${it.seed}: ${it.error}" })
    }

    private fun play(index: Int, seed: Long, masterSeat: Int, decks: List<TrainingDeck>, baselineStrategy: CpuStrategy): Outcome {
        val engine = RulesEngine(TrainerCatalog)
        val master = CpuPlayer(TrainerCatalog, CpuStrategy.MASTER)
        val baseline = CpuPlayer(TrainerCatalog, baselineStrategy)
        var state = engine.newGame(seed, decks.map { it.cards }, Mode.MATCH)
        var actions = 0
        val times = mutableListOf<Long>()
        val trace = ArrayDeque<String>()
        val error = runCatching {
            while (state.phase != Phase.GAME_OVER) {
                check(actions++ < 1000) { "Stalled at turn ${state.turn}; recent=$trace" }
                val input = engine.cpuInput(state)
                val isMaster = engine.actingPlayer(state) == masterSeat
                val start = System.nanoTime()
                val action = if (isMaster) master.choose(input) else baseline.choose(input)
                if (index == System.getProperty("cpu.arena.traceGame", "-1").toInt() && state.phase == Phase.MAIN && isMaster) {
                    val old = baseline.choose(input)
                    if (old != action) println("CPU_TRACE T${state.turn} ownPrizes=${state.players[masterSeat].prizes.size} oppPrizes=${state.players[1-masterSeat].prizes.size} master=$action baseline=$old active=${state.players[masterSeat].active?.top?.definitionId}")
                }
                if (isMaster) times += System.nanoTime()-start
                trace.addLast("T${state.turn} P${engine.actingPlayer(state)} $action")
                if (trace.size > 12) trace.removeFirst()
                val next = engine.apply(state, action)
                check(next.legality == Legality.Legal) { "${next.legality}; recent=$trace" }
                state = next.state
                val all = state.players.flatMap { p -> p.deck + p.hand + p.prizes + p.discard + p.pokemon().flatMap { it.cards + it.energies + it.tools } } +
                    listOfNotNull(state.stadium) + listOfNotNull(state.resolution?.toolCard)
                check(all.size == 120 && all.map { it.id }.distinct().size == 120) { "Card conservation failed; recent=$trace" }
            }
        }.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
        return Outcome(index, seed, masterSeat, decks.map { it.id }, baselineStrategy, state.winner, state.turn, actions, times, error)
    }
}
