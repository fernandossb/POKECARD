package com.tcgtrainer.engine

import kotlin.test.*

class AiDifficultyConfigTest {
    @Test fun `invalid budgets and evaluator weights are rejected`() {
        assertFailsWith<IllegalArgumentException> { AiDifficultyConfig(maxTransitions = 0) }
        assertFailsWith<IllegalArgumentException> { AiDifficultyConfig(beamWidth = 0) }
        assertFailsWith<IllegalArgumentException> { AiDifficultyConfig(criticalTimeBudgetMs = 6001) }
        assertFailsWith<IllegalArgumentException> { AiDifficultyConfig(preparationFraction = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { CpuBoardWeights(exposure = -1.0) }
        assertFailsWith<IllegalArgumentException> { CpuBoardWeights(prizeAdvantage = Double.POSITIVE_INFINITY) }
    }

    @Test fun `custom catalog drives strategy fallback without reading the global card catalogue`() {
        val plan = CpuDeckPlan.forDeck("custom-list", listOf("TEST-001", "TEST-001"), TestCatalog)
        assertTrue("TEST-001" in plan.attackers)
    }

    @Test fun `configured preparation fraction reserves counterplay time`() {
        var clock = 0L
        val budget = CpuSearchBudget(100, .4) { clock }
        clock = 39_000_000
        assertFalse(budget.preparationFinished())
        clock = 40_000_000
        assertTrue(budget.preparationFinished())
        budget.check()
        clock = 100_000_000
        assertFailsWith<CpuSearchExpired> { budget.check() }
    }
}
