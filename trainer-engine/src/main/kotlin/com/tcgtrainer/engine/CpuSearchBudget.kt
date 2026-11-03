package com.tcgtrainer.engine

enum class CpuThinking { DETERMINISTIC, ADAPTIVE }

/** Monotonic cooperative deadline. No worker thread or simulated game survives a decision. */
internal class CpuSearchBudget(private val milliseconds: Long?, private val preparationFraction: Double = .6, private val now: () -> Long = System::nanoTime) {
    init { require(milliseconds == null || milliseconds in 0..10_000) }
    private val started = now()
    fun preparationFinished(): Boolean = milliseconds != null && now()-started >= milliseconds*1_000_000*preparationFraction
    fun check() {
        if (Thread.currentThread().isInterrupted || milliseconds != null && now()-started >= milliseconds*1_000_000)
            throw CpuSearchExpired
    }
    companion object {
        fun limitMillis(input: CpuInput): Long = AiDifficultyConfig().limitMillis(input)
        fun create(input: CpuInput, thinking: CpuThinking, config: AiDifficultyConfig = AiDifficultyConfig()) =
            CpuSearchBudget(if (thinking == CpuThinking.ADAPTIVE) config.limitMillis(input) else null, config.preparationFraction)
    }
}

internal object CpuSearchExpired : RuntimeException(null, null, false, false)
