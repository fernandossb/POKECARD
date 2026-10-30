package com.tcgtrainer.engine

import kotlin.test.*

class CpuSearchBudgetTest {
    @Test fun `monotonic deadline preserves time for counterplay`() {
        var clock = 0L
        val budget = CpuSearchBudget(100) { clock }
        clock = 59_000_000
        assertFalse(budget.preparationFinished())
        clock = 60_000_000
        assertTrue(budget.preparationFinished())
        budget.check()
        clock = 100_000_000
        assertFailsWith<CpuSearchExpired> { budget.check() }
    }
    @Test fun `deterministic searches have no wall time deadline`() {
        var clock = 0L
        val budget = CpuSearchBudget(null) { clock }
        clock = Long.MAX_VALUE
        budget.check()
        assertFalse(budget.preparationFinished())
    }
}
