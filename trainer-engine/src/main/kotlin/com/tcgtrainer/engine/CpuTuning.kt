package com.tcgtrainer.engine

/** Explicit, immutable parameters; benchmark candidates never mutate the app default. */
data class CpuTuning(val counterplay: Double = .35, val prizeRace: Double = .15, val improvement: Double = 80.0) {
    init { require(counterplay.isFinite() && counterplay in 0.0..1.0 && prizeRace.isFinite() && prizeRace in 0.0..1.0 && improvement.isFinite() && improvement in 0.0..300.0) }
}
