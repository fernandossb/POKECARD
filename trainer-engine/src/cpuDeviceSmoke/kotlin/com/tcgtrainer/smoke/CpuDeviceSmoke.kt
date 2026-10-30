package com.tcgtrainer.smoke

import com.tcgtrainer.engine.*

/** Standalone synthetic matches in ART; no app storage, collection or saved game is accessed. */
object CpuDeviceSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val engine = RulesEngine(TrainerCatalog)
        val deck = TrainingDecks.all.first { it.id == "lucario" }.cards
        val reference = CpuPlayer(TrainerCatalog, CpuStrategy.HEURISTIC)
        val adaptive = CpuPlayer(thinking = CpuThinking.ADAPTIVE)
        var state = engine.newGame(42, listOf(deck, deck), Mode.MATCH).copy(deckLists = listOf(deck, deck))
        val snapshots = mutableListOf<GameState>()
        var moves = 0
        while (state.phase != Phase.GAME_OVER && moves++ < 350) {
            if (state.phase == Phase.MAIN && snapshots.none { it.turn == state.turn }) snapshots += state
            val action = reference.choose(engine.cpuInput(state))
            val result = engine.apply(state, action)
            check(result.legality == Legality.Legal)
            state = result.state
            if (snapshots.size >= 16 && state.players.any { it.prizes.size <= 2 } || snapshots.size >= 30) break
        }
        check(snapshots.isNotEmpty())
        val selected = listOf(snapshots.first(), snapshots[snapshots.size/2], snapshots.last()).distinct()
        for ((index, game) in selected.withIndex()) repeat(2) { sample ->
            val start = System.nanoTime()
            val input = engine.cpuInput(game)
            val inputTime = System.nanoTime()-start
            val chooseStart = System.nanoTime()
            val action = adaptive.choose(input)
            val elapsed = System.nanoTime()-chooseStart
            check(engine.validate(game, action) == Legality.Legal)
            println("CPU_DEVICE position=$index sample=$sample turn=${game.turn} prizes=${input.observation.ownPrizeCount}/${input.observation.opponentPrizeCount} limitMs=${adaptive.thinkingLimitMillis(input)} inputMs=${inputTime/1_000_000.0} chooseMs=${elapsed/1_000_000.0} legal=true action=${action.javaClass.simpleName}")
        }
        // Access internal search only in this diagnostic harness, to force actual ART deadline paths.
        val budgetClass = Class.forName("com.tcgtrainer.engine.CpuSearchBudget")
        val searchClass = Class.forName("com.tcgtrainer.engine.CpuTurnSearch")
        val game = selected[selected.size/2]
        val input = engine.cpuInput(game)
        val fallback = reference.choose(input)
        for (limit in listOf(0L, 1L, 10L)) {
            val clock: () -> Long = System::nanoTime
            val budget = budgetClass.getConstructor(java.lang.Long::class.java, Function0::class.java).newInstance(limit, clock)
            val search = searchClass.getConstructor(CardCatalog::class.java, CpuStrategy::class.java, CpuTuning::class.java, budgetClass)
                .newInstance(TrainerCatalog, CpuStrategy.MASTER, CpuTuning(), budget)
            val start = System.nanoTime()
            val action = searchClass.getMethod("choose", CpuInput::class.java, GameAction::class.java).invoke(search, input, fallback) as GameAction
            val elapsed = (System.nanoTime()-start)/1_000_000.0
            check(engine.validate(game, action) == Legality.Legal)
            if (limit == 0L) check(action == fallback)
            println("CPU_DEVICE_BUDGET limitMs=$limit chooseMs=$elapsed legal=true")
        }
        println("CPU_DEVICE_OK positions=${selected.size}")
    }
}
