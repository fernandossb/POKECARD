package com.tcgtrainer.engine

import kotlinx.serialization.json.*

data class TrainingDeck(val id: String, val name: String, val strategy: String, val cover: String, val cards: List<String>, val format: DeckFormat = DeckFormat.TRAINING, val custom: Boolean = false)

object Academy2022 {
    val decks: List<TrainingDeck> by lazy {
        val root = javaClass.getResourceAsStream("/academy-2022.json")!!.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        root["decks"]!!.jsonArray.map { entry ->
            val d = entry.jsonObject
            TrainingDeck(d["id"]!!.jsonPrimitive.content, d["name"]!!.jsonPrimitive.content,
                "Lista da Academia de Batalha 2022 · treino fora da rotação.", d["cover"]!!.jsonPrimitive.content,
                d["cards"]!!.jsonObject.flatMap { (id, count) -> List(count.jsonPrimitive.int) { id } }, DeckFormat.ACADEMY)
        }
    }
}

/** Curated practice lists, not claims about sealed products or tournament legality. */
object TrainingDecks {
    private fun list(vararg counts: Pair<String, Int>) = counts.flatMap { (id, n) -> List(n) { id } }.also { require(it.size == 60) }
    val all = listOf(
        TrainingDeck("lucario", "Mega Lucario ex", "Evolução e aceleração de Energia para o Banco.", "me01-077", TrainerCatalog.deck()),
        TrainingDeck("hariyama", "Hariyama • Impacto", "Troque o Ativo adversário ao evoluir e ataque com 210 de dano.", "me01-073", list(
            "me01-072" to 4, "me01-073" to 4, "me01-074" to 3, "me01-075" to 3, "sv06.5-038" to 1, "sv06-141" to 1,
            "me01-116" to 4, "me01-131" to 4, "me01-119" to 4, "sv09-149" to 2, "sv02-172" to 2,
            "sv06.5-061" to 3, "me01-124" to 4, "me01-130" to 2, "sv10.5b-079" to 2, "sv08-187" to 1, "sv01-258" to 16)),
        TrainingDeck("solrock", "Solrock & Lunatone", "Ataque com uma Energia e compre cartas com Ciclo Lunar.", "me01-075", list(
            "me01-075" to 4, "me01-074" to 4, "sv06.5-038" to 1, "sv06-141" to 2,
            "me01-116" to 4, "me01-131" to 4, "me01-119" to 4, "sv09-149" to 2, "sv02-172" to 3,
            "sv06.5-061" to 4, "me01-124" to 4, "me01-130" to 3, "sv10.5b-079" to 2, "sv08-187" to 1, "sv01-258" to 18)),
        TrainingDeck("ursaluna", "Ursaluna ex • Virada", "Prepare o Banco e aproveite o custo menor de Lua de Sangue no fim da partida.", "sv06-141", list(
            "sv06-141" to 3, "me01-075" to 4, "me01-074" to 4, "sv06.5-038" to 1,
            "me01-116" to 4, "me01-131" to 4, "me01-119" to 4, "sv09-149" to 2, "sv02-172" to 3,
            "sv06.5-061" to 3, "me01-124" to 3, "me01-130" to 3, "sv10.5b-079" to 3, "sv08-187" to 1, "sv01-258" to 18))
    ) + Academy2022.decks
    fun get(id: String) = all.first { it.id == id }
}
