package com.tcgtrainer.engine

import kotlinx.serialization.json.*

/** Included in the frozen test artifact, never in the Android application. */
class FrozenCpuBridge {
    private val engine = RulesEngine(TrainerCatalog)
    private val cpu = CpuPlayer(TrainerCatalog, CpuStrategy.MASTER)
    private val json = Json { encodeDefaults = true }

    fun knowledgeMode(): String = CpuKnowledge.appMode.name

    fun choose(stateJson: String): String {
        val state = json.decodeFromString(GameState.serializer(), stateJson)
        val input = engine.cpuInput(state, CpuKnowledge.appMode)
        val start = System.nanoTime()
        val action = cpu.choose(input)
        val nanos = System.nanoTime()-start
        return buildJsonObject {
            put("action", json.encodeToString(GameAction.serializer(), action))
            put("nanos", nanos)
        }.toString()
    }
}
