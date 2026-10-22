package com.tcgtrainer.engine

import kotlinx.serialization.json.*

/** Exact print IDs only: a different artwork/reprint never silently grants ownership. */
data class OwnedCollection(val counts: Map<String, Int>, val available: Boolean = true) {
    fun quantity(id: String) = counts[id] ?: 0
    fun missing(cards: List<String>): Map<String, Int> = cards.groupingBy { it }.eachCount()
        .mapValues { (id, wanted) -> (wanted - quantity(id)).coerceAtLeast(0) }.filterValues { it > 0 }
    fun allows(cards: List<String>) = available && missing(cards).isEmpty()
    fun message(cards: List<String>): String? {
        if (!available) return "Volte ao PokeCard e abra o jogo novamente para carregar sua coleção."
        val absent = missing(cards)
        if (absent.isEmpty()) return null
        return "Faltam ${absent.values.sum()} cópias na sua coleção: " + absent.entries.take(4).joinToString { (id, n) ->
            "$n × ${CardLibrary.byId[id]?.name ?: id} ($id)"
        } + if (absent.size > 4) "…" else ""
    }
    companion object {
        fun decode(text: String): OwnedCollection {
            val data = Json.parseToJsonElement(text).jsonObject
            require(data["version"]?.jsonPrimitive?.int == 1)
            val counts = data.getValue("counts").jsonObject.mapValues { (id, value) ->
                require(id.length in 1..120)
                value.jsonPrimitive.int.also { require(it in 0..1_000_000) }
            }.filterValues { it > 0 }
            return OwnedCollection(counts)
        }
    }
}
