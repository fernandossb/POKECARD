package com.tcgtrainer.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.time.LocalDate

@Serializable enum class DeckFormat { STANDARD, ACADEMY, TRAINING }
object DeckCodec {
    fun encode(deck: SavedDeck): String = Json.encodeToString(SavedDeck.serializer(), deck)
    fun decode(text: String): SavedDeck = Json.decodeFromString(SavedDeck.serializer(), text)
}
@Serializable data class SavedDeck(val id: String, val name: String, val format: DeckFormat = DeckFormat.STANDARD,
    val counts: Map<String, Int> = emptyMap(), val updatedAt: Long = 0) {
    fun cards() = counts.flatMap { (id, count) -> List(count.coerceAtLeast(0)) { id } }
    fun playableDeck() = TrainingDeck(id, name, "Seu deck salvo · ${format.label()}",
        cards().firstOrNull { CardLibrary.byId[it]?.category == Category.POKEMON } ?: counts.keys.firstOrNull().orEmpty(), cards(), format, custom = true)
}
fun DeckFormat.label() = when (this) { DeckFormat.STANDARD -> "Padrão 2026"; DeckFormat.ACADEMY -> "Academia 2022"; DeckFormat.TRAINING -> "Treino livre" }

data class LibraryCard(val id: String, val name: String, val canonicalName: String, val set: String, val number: String,
    val category: Category, val stage: Stage, val basicEnergy: Boolean, val regulation: String?, val standard: Boolean,
    val implemented: Boolean, val image: String?, val text: String, val source: String, val aceSpec: Boolean = false) {
    val searchKey = normalize("$name $canonicalName $set $number $id")
    val copyKey = when {
        canonicalName.startsWith("Boss's Orders") -> "boss's orders"
        canonicalName.startsWith("Professor's Research") -> "professor's research"
        else -> normalize(canonicalName)
    }
    fun displayCard(): CardDefinition = TrainerCatalog.definitions[id] ?: CardDefinition(id, name, set, number,
        category = category, stage = stage, verified = true, source = source,
        artAsset = image?.let { "remote:$it/high.webp" }, text = text, implemented = false, basicEnergy = basicEnergy)
}
fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Browsing metadata never enables an executable card effect. */
object CardLibrary {
    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private val snapshot: JsonObject by lazy {
        javaClass.getResourceAsStream("/library/catalog.json")?.bufferedReader(Charsets.UTF_8)?.use { Json.parseToJsonElement(it.readText()).jsonObject }
            ?: buildJsonObject { put("cards", JsonArray(emptyList())) }
    }
    val fetchedAt get() = snapshot.s("fetchedAt") ?: "2026-10-22"
    private val policy by lazy { javaClass.getResourceAsStream("/library/rotation-policy.json")!!.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject } }
    val policyValidThrough get() = LocalDate.parse(policy.s("validThrough")!!)
    val entries: List<LibraryCard> by lazy {
        val rows = (snapshot["cards"] as? JsonArray).orEmpty().associate { it.jsonObject.s("id")!! to it.jsonObject }.toMutableMap()
        TrainerCatalog.definitions.keys.forEach { id -> if (id !in rows) rows[id] = TrainerCatalog.sourceData(id) }
        val marks = policy["legalMarks"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val waiting = policy["typicalReleaseWaitDays"]!!.jsonPrimitive.int.toLong()
        val banned = policy["bannedIds"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        fun eligible(raw: JsonObject): Boolean {
            val released = raw.s("setReleaseDate")?.let { runCatching { !LocalDate.parse(it).plusDays(waiting).isAfter(LocalDate.now()) }.getOrDefault(false) } ?: true
            return released && raw.s("regulationMark")?.uppercase() in marks && raw["legal"]?.jsonObject?.get("standard")?.jsonPrimitive?.booleanOrNull == true
        }
        fun trainerSignature(raw: JsonObject): String? = if (raw.s("category") == "Trainer" && !raw.s("effect").isNullOrBlank())
            listOf(raw.s("name"), raw.s("trainerType"), raw.s("effect")).joinToString("|") { normalize(it.orEmpty()).replace(Regex("\\s+"), " ").trim() } else null
        val currentTrainerTexts = rows.values.filter(::eligible).mapNotNull(::trainerSignature).toSet()
        rows.map { (id, raw) ->
            val reviewed = TrainerCatalog.definitions[id]
            val category = when (raw.s("category")) { "Pokemon" -> Category.POKEMON; "Energy" -> Category.ENERGY; else -> Category.TRAINER }
            val basicEnergy = CatalogRuleCompiler.basicEnergy(raw)
            val standard = id !in banned && (basicEnergy || trainerSignature(raw)?.let { it in currentTrainerTexts } == true ||
                id in policy["reviewedReprints"]!!.jsonArray.map { it.jsonPrimitive.content } ||
                eligible(raw))
            val text = buildList {
                raw.s("effect")?.let(::add)
                (raw["abilities"] as? JsonArray).orEmpty().forEach { add("${it.jsonObject.s("name")}: ${it.jsonObject.s("effect").orEmpty()}") }
                (raw["attacks"] as? JsonArray).orEmpty().forEach { add("${it.jsonObject.s("name")} · ${it.jsonObject.s("damage").orEmpty()}\n${it.jsonObject.s("effect").orEmpty()}") }
            }.joinToString("\n\n")
            LibraryCard(id, reviewed?.name ?: raw.s("name")!!, raw.s("name")!!,
                reviewed?.set ?: raw.s("setCode") ?: raw["set"]!!.jsonObject.s("id")!!.uppercase(), raw.s("localId")!!, category,
                when (raw.s("stage")) { "Stage1" -> Stage.STAGE_ONE; "Stage2" -> Stage.STAGE_TWO; else -> Stage.BASIC },
                basicEnergy, raw.s("regulationMark"), standard, reviewed?.implemented == true, raw.s("image"), text,
                "https://api.tcgdex.net/v2/en/cards/$id", reviewed?.aceSpec == true || raw.s("rarity").orEmpty().contains("ACE SPEC", ignoreCase = true))
        }.sortedWith(compareBy({ it.category.ordinal }, { it.name }, { it.id }))
    }
    val byId by lazy { entries.associateBy { it.id } }
    fun search(query: String, format: DeckFormat?, onlyPlayable: Boolean, category: Category? = null): List<LibraryCard> {
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
        val academy = Academy2022.decks.flatMap { it.cards }.toSet()
        return entries.filter { card -> (!onlyPlayable || card.implemented) && (category == null || category == card.category) &&
            (format != DeckFormat.STANDARD || card.standard) && (format != DeckFormat.ACADEMY || card.id in academy) && tokens.all { it in card.searchKey } }
    }
}

data class DeckValidation(val errors: List<String>, val unsupported: List<String>, val trainingErrors: List<String> = errors) {
    val playable get() = errors.isEmpty() && unsupported.isEmpty()
    /** A saved, supported list can be practiced without owning its cards or meeting format legality. */
    val trainingReady get() = trainingErrors.isEmpty() && unsupported.isEmpty()
}
object DeckValidator {
    fun validate(deck: SavedDeck): DeckValidation {
        val errors = mutableListOf<String>(); val trainingErrors = mutableListOf<String>(); val unsupported = mutableListOf<String>()
        if (deck.name.isBlank()) { errors += "Dê um nome ao deck."; trainingErrors += "Dê um nome ao deck." }
        if (deck.format == DeckFormat.STANDARD && LocalDate.now().isAfter(CardLibrary.policyValidThrough)) errors += "A política do Padrão precisa de revisão para esta temporada. Use Treino livre enquanto isso."
        if (deck.counts.values.any { it <= 0 }) { errors += "Quantidades devem ser positivas."; trainingErrors += "Quantidades devem ser positivas." }
        if (deck.counts.values.sum() !in 14..60) {
            val message = "Para treinar, use de 14 a 60 cartas (${deck.counts.values.sum()}/60)."
            errors += message; trainingErrors += message
        }
        val known = deck.counts.mapNotNull { (id, n) -> CardLibrary.byId[id]?.let { it to n } ?: run {
            val message = "Carta desconhecida: $id"; errors += message; trainingErrors += message; null
        } }
        if (known.none { it.first.category == Category.POKEMON && it.first.stage == Stage.BASIC }) {
            val message = "Inclua pelo menos um Pokémon Básico."; errors += message; trainingErrors += message
        }
        known.filter { !it.first.basicEnergy }.groupBy { it.first.copyKey }.forEach { (_, copies) ->
            if (copies.sumOf { it.second } > 4) {
                val message = "Máximo de 4 cópias pelo nome: ${copies.first().first.name}."
                errors += message; trainingErrors += message
            }
        }
        if (known.sumOf { (card, n) -> if (card.aceSpec) n else 0 } > 1) {
            val message = "Apenas 1 ACE SPEC por deck."; errors += message; trainingErrors += message
        }
        known.forEach { (card, _) ->
            if (deck.format == DeckFormat.STANDARD && !card.standard) errors += "Fora do Padrão confirmado: ${card.name} (${card.id})."
            if (deck.format == DeckFormat.ACADEMY && Academy2022.decks.none { card.id in it.cards }) errors += "Não pertence à Academia 2022: ${card.name}. Use Treino livre para misturar."
            if (!card.implemented) unsupported += card.id
        }
        return DeckValidation(errors.distinct(), unsupported.distinct(), trainingErrors.distinct())
    }
    fun validate(deck: TrainingDeck) = validate(SavedDeck(deck.id, deck.name, deck.format, deck.cards.groupingBy { it }.eachCount()))
}
