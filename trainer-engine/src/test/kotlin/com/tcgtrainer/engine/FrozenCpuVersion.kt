package com.tcgtrainer.engine

import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Engine classes AND catalog resources come from the snapshot. Dependencies come from the test runtime. */
internal class FrozenCpuVersion(val file: File) : AutoCloseable {
    init { require(file.isFile) { "Frozen CPU artifact not found: $file" } }
    val sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private val loader = object : URLClassLoader(arrayOf(file.toURI().toURL()), FrozenCpuVersion::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (!name.startsWith("com.tcgtrainer.engine.")) return@synchronized super.loadClass(name, resolve)
            val found = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(found)
            found
        }
        override fun getResource(name: String) = findResource(name) ?: super.getResource(name)
    }
    private val bridge = loader.loadClass("com.tcgtrainer.engine.FrozenCpuBridge")
    val knowledge: String = try {
        bridge.getMethod("knowledgeMode").invoke(bridge.getConstructor().newInstance()) as String
    } catch (_: NoSuchMethodException) { "UNREPORTED_LEGACY" }
    fun player(): (GameState) -> Pair<GameAction, Long> {
        val instance = bridge.getConstructor().newInstance()
        val choose = bridge.getMethod("choose", String::class.java)
        return { state ->
            val response = Json.parseToJsonElement(choose.invoke(instance,
                Json.encodeToString(GameState.serializer(), state)) as String).jsonObject
            Json.decodeFromString(GameAction.serializer(), response.getValue("action").jsonPrimitive.content) to
                response.getValue("nanos").jsonPrimitive.long
        }
    }
    override fun close() = loader.close()
}
