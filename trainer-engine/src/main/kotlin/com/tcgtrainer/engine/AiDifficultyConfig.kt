package com.tcgtrainer.engine

enum class AiDifficulty { EASY, NORMAL, HARD, EXPERT, MASTER, CHAMPION }

/** Work limits, not rule advantages. Counts are deterministic; wall time is used only in ADAPTIVE mode.
 * Depth counts actions/choices inside a turn, not complete alternating player turns.
 */
data class AiDifficultyConfig(
    val beamWidth: Int = 8,
    val maxActionsPerSequence: Int = 7,
    val maxTransitions: Int = 260,
    val rootBranches: Int = 24,
    val childBranches: Int = 12,
    val choiceCandidates: Int = 24,
    val finalistCount: Int = 4,
    val replyDepth: Int = 4,
    val replyTransitions: Int = 28,
    val replyBranches: Int = 8,
    val continuationCount: Int = 4,
    val continuationDepth: Int = 3,
    val continuationTransitions: Int = 24,
    val continuationBeam: Int = 2,
    val simpleTimeBudgetMs: Long = 200,
    val timeBudgetMs: Long = 800,
    val criticalTimeBudgetMs: Long = 2000,
    val preparationFraction: Double = .6,
    val evaluationWeights: CpuBoardWeights = CpuBoardWeights(),
    val winningReplyPenalty: Double = 250.0
) {
    init {
        require(beamWidth in 1..64 && maxActionsPerSequence in 1..32 && maxTransitions in 1..10000)
        require(rootBranches in 1..128 && childBranches in 1..128 && choiceCandidates in 1..128)
        require(finalistCount in 1..32 && replyDepth in 0..16 && replyTransitions in 1..2000 && replyBranches in 1..64)
        require(continuationCount in 0..32 && continuationDepth in 0..16 && continuationTransitions in 1..2000 && continuationBeam in 1..32)
        require(simpleTimeBudgetMs in 1..timeBudgetMs && timeBudgetMs in 1..criticalTimeBudgetMs && criticalTimeBudgetMs <= 2000)
        require(preparationFraction.isFinite() && preparationFraction in .1.. .9)
        require(winningReplyPenalty.isFinite() && winningReplyPenalty in 0.0..10000.0)
    }

    fun limitMillis(input: CpuInput): Long = when {
        input.observation.ownPrizeCount <= 2 || input.observation.opponentPrizeCount <= 2 -> criticalTimeBudgetMs
        input.actions.size <= 2 && input.observation.ownField.size <= 1 && input.observation.ownHand.size <= 2 -> simpleTimeBudgetMs
        else -> timeBudgetMs
    }

    companion object {
        fun forDifficulty(difficulty: AiDifficulty): AiDifficultyConfig = when (difficulty) {
            AiDifficulty.EASY -> AiDifficultyConfig(beamWidth = 1, maxActionsPerSequence = 1, maxTransitions = 12,
                rootBranches = 6, childBranches = 3, choiceCandidates = 4, finalistCount = 1, replyDepth = 0,
                continuationCount = 0, simpleTimeBudgetMs = 50, timeBudgetMs = 75, criticalTimeBudgetMs = 100)
            AiDifficulty.NORMAL -> AiDifficultyConfig(beamWidth = 2, maxActionsPerSequence = 3, maxTransitions = 48,
                rootBranches = 10, childBranches = 6, choiceCandidates = 8, finalistCount = 2, replyDepth = 0,
                continuationCount = 0, simpleTimeBudgetMs = 100, timeBudgetMs = 150, criticalTimeBudgetMs = 250)
            AiDifficulty.HARD -> AiDifficultyConfig(beamWidth = 4, maxActionsPerSequence = 5, maxTransitions = 140,
                rootBranches = 16, childBranches = 8, choiceCandidates = 12, finalistCount = 3, replyDepth = 2,
                continuationCount = 0, simpleTimeBudgetMs = 150, timeBudgetMs = 300, criticalTimeBudgetMs = 500)
            AiDifficulty.EXPERT -> AiDifficultyConfig(beamWidth = 6, maxTransitions = 220, replyDepth = 3,
                continuationCount = 2, continuationDepth = 2, simpleTimeBudgetMs = 200, timeBudgetMs = 600, criticalTimeBudgetMs = 1000)
            AiDifficulty.MASTER -> AiDifficultyConfig()
            AiDifficulty.CHAMPION -> AiDifficultyConfig(beamWidth = 12, maxActionsPerSequence = 10, maxTransitions = 520,
                rootBranches = 32, childBranches = 16, finalistCount = 6, replyTransitions = 48,
                continuationCount = 6, timeBudgetMs = 1200)
        }
    }
}

/** Tuning is separate from feature extraction. Scores are not calibrated win probabilities. */
data class CpuBoardWeights(
    val attackers: Double = 1.0, val engines: Double = 1.0, val recovery: Double = 1.0,
    val exposure: Double = 1.0, val locks: Double = 1.0,
    val opponent: Double = .35, val prizeAdvantage: Double = 45.0
) {
    init {
        require(listOf(attackers, engines, recovery, exposure, locks, opponent, prizeAdvantage).all { it.isFinite() && it in 0.0..10000.0 })
    }

    /** Either player approaching victory makes exposed prizes and ready attackers more urgent. */
    internal fun forPosition(state: GameState): CpuBoardWeights {
        val closestToWin = state.players.minOf { it.prizes.size }
        fun scale(value: Double, factor: Double) = (value*factor).coerceAtMost(10000.0)
        return when {
            closestToWin <= 2 -> copy(attackers = scale(attackers, 1.2), engines = scale(engines, .8),
                exposure = scale(exposure, 1.5), prizeAdvantage = scale(prizeAdvantage, 1.5))
            closestToWin >= 5 -> copy(engines = scale(engines, 1.3), recovery = scale(recovery, 1.2))
            else -> this
        }
    }
}
