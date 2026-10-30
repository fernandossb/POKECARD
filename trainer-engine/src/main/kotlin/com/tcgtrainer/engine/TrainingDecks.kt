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

/** Curated practice decks used by the existing player and CPU selectors. */
object TrainingDecks {
    fun masterOpponents(eligible: List<TrainingDeck>, format: DeckFormat): List<TrainingDeck> {
        if (format == DeckFormat.ACADEMY) return eligible.filter { it.format == DeckFormat.ACADEMY }.ifEmpty { eligible }
        return eligible.filter { it.id.startsWith("competitive-") }.ifEmpty {
            eligible.filter { it.format != DeckFormat.ACADEMY }.ifEmpty { eligible }
        }
    }
    private fun list(vararg counts: Pair<String, Int>) = counts.flatMap { (id, n) -> List(n) { id } }.also { require(it.size == 60) }
    private val curated = listOf(
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

    /** Published 60-card tournament lists from the current TEF–30C Standard format. */
    private val competitive = listOf(
        TrainingDeck("competitive-dragapult", "Dragapult", "Limitless · 5º Regional Nice 2026 · Yuya Kogai.", "sv06-130", list(
            "sv06-128" to 4, "sv06-129" to 4, "sv06-130" to 3, "sv08.5-004" to 2, "sv09-120" to 1, "sv05-129" to 1,
            "sv06-095" to 2, "sv06.5-038" to 1, "me03-062" to 1,
            "me01-119" to 4, "sv07-133" to 2, "me03-084" to 1, "me03-076" to 1, "me01-114" to 3,
            "me01-167" to 4, "me03-081" to 4, "me01-131" to 3, "me01-173" to 3, "me03-071" to 4,
            "sv06-165" to 1, "me01-127" to 2,
            "sve-002" to 3, "sve-005" to 3, "sve-007" to 3), DeckFormat.STANDARD),
        TrainingDeck("competitive-basic-box", "Basic Box", "Limitless · 1º Regional Nice 2026 · Luc Gantner.", "me01-104", list(
            "me01-104" to 4, "me03-062" to 3, "sv06-025" to 2, "sv06-064" to 2, "sv08-076" to 2,
            "me02.5-076" to 2, "sv05-123" to 1, "sv08-056" to 1, "sv05-081" to 1, "sv08-111" to 1,
            "sv06.5-038" to 1, "sv05-025" to 1,
            "sv07-133" to 4, "sv08-170" to 2, "me01-114" to 2, "sv05-145" to 1,
            "me01-115" to 4, "me01-131" to 4, "me01-173" to 2, "sv06-165" to 1, "sv07-135" to 1,
            "sv07-131" to 3,
            "sve-001" to 6, "sve-005" to 3, "sve-004" to 2, "sve-006" to 2, "sve-003" to 2), DeckFormat.STANDARD),
        TrainingDeck("competitive-n-zoroark", "N's Zoroark", "Limitless · 25º Regional Nice 2026 · Arthur Gibelin.", "sv09-098", list(
            "me02.5-136" to 4, "sv09-098" to 4, "me02.5-155" to 2, "sv06-095" to 2, "sv09-116" to 1,
            "sv09-096" to 1, "sv06.5-035" to 1, "sv06.5-039" to 1, "sv08.5-004" to 1, "me03-062" to 1,
            "me01-119" to 4, "me01-114" to 4, "sv08-170" to 2, "sv08.5-096" to 1, "me03-076" to 1,
            "sv06.5-059" to 1, "me01-167" to 4, "me01-131" to 3, "sv09-153" to 3, "me03-081" to 2,
            "me01-173" to 2, "me04-082" to 1, "sv06-163" to 1, "sv06.5-055" to 2,
            "sv09-152" to 2, "sv10-180" to 2, "sve-007" to 7), DeckFormat.STANDARD),
        TrainingDeck("competitive-slowking", "Slowking", "Limitless · 11º Regional Nice 2026 · Tomás Malaca.", "sv07-058", list(
            "sv07-057" to 4, "sv07-058" to 3, "me01-104" to 3, "sv08-076" to 2, "sv09-063" to 2,
            "sv06.5-047" to 2, "sv08-100" to 1, "sv07-055" to 1, "me03-062" to 1, "sv06.5-038" to 1,
            "me02.5-076" to 1,
            "me01-119" to 4, "sv05-145" to 3, "sv08-187" to 1, "me03-081" to 4,
            "me02-094" to 3, "me01-173" to 3, "me01-131" to 3, "sv06-163" to 1, "me01-130" to 1,
            "me04-082" to 1, "sv06-158" to 1, "sv06.5-054" to 4,
            "me03-088" to 4, "sve-005" to 4, "sv06-166" to 2), DeckFormat.STANDARD),
        TrainingDeck("competitive-dhelmise", "Dhelmise", "Limitless · 2º Regional Nice 2026 · Maxime Launay.", "sv09-070", list(
            "sv09-059" to 4, "sv09-060" to 4, "sv06-020" to 4, "sv06-022" to 4, "sv09-070" to 4,
            "me02.5-076" to 2, "me01-087" to 1, "sv08-076" to 1, "sv06-141" to 1,
            "sv05-147" to 4, "me01-119" to 3, "me05-078" to 3, "me01-114" to 3, "me03-079" to 2,
            "me01-131" to 4, "me03-081" to 3, "me01-173" to 3, "me04-080" to 2,
            "me03-088" to 4, "sve-005" to 3, "sv06-167" to 1), DeckFormat.STANDARD),
        TrainingDeck("competitive-alakazam-dudunsparce", "Alakazam / Dudunsparce", "Limitless · 21º Regional Nice 2026 · Luke Sullivan.", "sv06-082", list(
            "sv06-080" to 4, "sv06-081" to 4, "sv06-082" to 3, "sv09-120" to 3, "sv05-129" to 2,
            "sv06.5-040" to 1, "me02.5-076" to 1, "sv06.5-038" to 1, "sv10-010" to 1,
            "me02-087" to 4, "sv10.5w-084" to 3, "me01-114" to 2, "sv06-155" to 1, "sv05-146" to 1,
            "me01-167" to 4, "me03-081" to 4, "me01-125" to 2, "me02-094" to 2, "me01-173" to 1,
            "me03-115" to 1, "me04-082" to 1, "sv06-150" to 2, "sv09-151" to 1, "me02-085" to 4,
            "me03-088" to 4, "sve-005" to 2, "sv08-191" to 1), DeckFormat.STANDARD),
        TrainingDeck("competitive-dragapult-dusknoir", "Dragapult / Dusknoir", "Limitless · 31º Regional Nice 2026 · Tarran Flatt.", "sv06-130", list(
            "sv06-128" to 4, "sv06-129" to 4, "sv06-130" to 3, "sv06.5-018" to 2, "sv06.5-019" to 2,
            "sv06.5-020" to 1, "sv08.5-004" to 2, "sv06-095" to 1, "me02-014" to 1, "sv10-010" to 1,
            "sv06.5-038" to 1, "me03-062" to 1,
            "me01-119" to 4, "me01-114" to 3, "sv07-133" to 2, "me03-076" to 1, "me03-084" to 1,
            "me03-081" to 4, "me01-167" to 4, "me01-131" to 3, "me01-173" to 3,
            "me04-082" to 1, "sv06-165" to 1, "sv10-180" to 2,
            "sve-005" to 3, "sve-002" to 3, "sve-007" to 2), DeckFormat.STANDARD),
        TrainingDeck("competitive-mega-excadrill", "Mega Excadrill", "Limitless · 44º Regional Louisville 2026 · Raymond Liu.", "me05-065", list(
            "sv10.5b-045" to 4, "me05-065" to 2, "sv09-061" to 4, "sv09-062" to 4, "sv09-063" to 1,
            "sv10.5b-067" to 2,
            "sv10-176" to 4, "me01-119" to 4, "me01-114" to 3, "me03-076" to 1, "sv06-154" to 1,
            "me01-167" to 4, "sv10.5b-084" to 2, "me04-109" to 2, "me01-131" to 1,
            "me03-108" to 1, "me04-082" to 1, "me01-130" to 1, "sv05-152" to 1, "sv08-177" to 1,
            "sve-008" to 16), DeckFormat.STANDARD),
        TrainingDeck("competitive-sharpedo-toxtricity", "Sharpedo / Toxtricity", "Limitless · 47º Regional Nice 2026 · Tapio Ikonen.", "me02-061", list(
            "me02-060" to 4, "me02-061" to 4, "sv06.5-039" to 3, "me02-067" to 2, "me02-068" to 2,
            "sv06-095" to 2, "sv06-131" to 2, "sv06.5-038" to 1,
            "me01-114" to 4, "me01-119" to 4, "sv10.5w-083" to 2, "sv09-149" to 1, "sv10-176" to 1,
            "me01-167" to 4, "me03-081" to 4, "me01-131" to 4, "sv06-165" to 1,
            "sv06.5-055" to 3, "sv10-180" to 2, "sv08-177" to 1, "sve-007" to 9), DeckFormat.STANDARD),
        TrainingDeck("competitive-mega-lucario-hariyama", "Mega Lucario / Hariyama", "Limitless · 54º Regional Nice 2026 · Julian Patz.", "me01-077", list(
            "me01-076" to 3, "me01-077" to 3, "me01-075" to 3, "me01-072" to 2, "me01-073" to 2,
            "me01-074" to 2, "me03-003" to 1, "me03-062" to 1,
            "me01-119" to 4, "me03-076" to 2, "me01-114" to 2, "sv09-146" to 1, "me01-132" to 1,
            "me01-116" to 4, "me01-124" to 4, "me01-131" to 4, "me03-081" to 3,
            "me01-130" to 1, "sv06-163" to 1, "sv10.5b-079" to 2, "sv08-177" to 2, "sv10-180" to 1,
            "sve-006" to 11), DeckFormat.STANDARD)
    )
    val all = curated + competitive
    fun get(id: String) = all.first { it.id == id }
}
