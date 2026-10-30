package com.tcgtrainer.engine

import kotlin.test.*

class CpuTuningTest {
    @Test fun `non finite and unsafe parameters are rejected`() {
        assertFailsWith<IllegalArgumentException> { CpuTuning(counterplay = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { CpuTuning(prizeRace = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { CpuTuning(improvement = -1.0) }
        assertFailsWith<IllegalArgumentException> { CpuTuning(counterplay = 1.1) }
    }
    @Test fun `candidates cannot change the default configuration`() {
        val baseline = CpuTuning()
        val candidate = baseline.copy(counterplay = .5, improvement = 60.0)
        assertEquals(.35, baseline.counterplay)
        assertEquals(80.0, baseline.improvement)
        assertNotEquals(baseline, candidate)
        assertEquals(baseline, CpuTuning())
    }
}
