package com.tcgtrainer.engine

enum class CpuThinking { DETERMINISTIC, ADAPTIVE }

/** Monotonic cooperative deadline. No worker thread or simulated game survives a decision. */
internal class CpuSearchBudget(private val milliseconds: Long?, private val now: () -> Long = System::nanoTime) {
    init { require(milliseconds == null || milliseconds in 0..10_000) }
    private val started = now()
    fun preparationFinished(): Boolean = milliseconds != null && now()-started >= milliseconds*600_000
    fun check() {
        if (Thread.currentThread().isInterrupted || milliseconds != null && now()-started >= milliseconds*1_000_000)
            throw CpuSearchExpired
    }
    companion object {
        fun limitMillis(input: CpuInput): Long = when {
            input.observation.ownPrizeCount <= 2 || input.observation.opponentPrizeCount <= 2 -> 2_000
            input.actions.size <= 2 && input.observation.ownField.size <= 1 && input.observation.ownHand.size <= 2 -> 200
            else -> 800
        }
        fun create(input: CpuInput, thinking: CpuThinking) = CpuSearchBudget(if (thinking == CpuThinking.ADAPTIVE) limitMillis(input) else null)
    }
}

internal object CpuSearchExpired : RuntimeException(null, null, false, false)
