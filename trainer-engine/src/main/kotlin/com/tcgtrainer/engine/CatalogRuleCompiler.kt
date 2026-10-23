package com.tcgtrainer.engine

import kotlinx.serialization.json.*

/** Closed grammar: an unrecognized clause rejects the entire card, never just that clause. */
internal object CatalogRuleCompiler {
    private fun JsonObject.s(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.a(key: String) = (get(key) as? JsonArray).orEmpty()
    val rows: Map<String, JsonObject> by lazy {
        javaClass.getResourceAsStream("/library/catalog.json")!!.bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject["cards"]!!.jsonArray
                .associate { row -> row.jsonObject.s("id")!! to row.jsonObject }
        }
    }
    private fun type(value: String?) = EnergyType.entries.firstOrNull { it.name == value?.uppercase() }
    private fun signature(raw: JsonObject) = listOf("category", "name", "trainerType", "energyType", "effect").map { raw[it] } +
        JsonPrimitive(raw.s("rarity").orEmpty().contains("ACE SPEC", ignoreCase = true))

    fun basicEnergy(raw: JsonObject): Boolean = raw.s("category") == "Energy" &&
        Regex("(?:Basic )?(?:Grass|Fire|Water|Lightning|Psychic|Fighting|Darkness|Metal) Energy").matches(raw.s("name").orEmpty())

    fun compile(raw: JsonObject, reviewed: Map<String, CardDefinition>): CardDefinition? {
        val id = raw.s("id") ?: return null
        val image = raw.s("image") ?: return null
        val source = "TCGdex • https://api.tcgdex.net/v2/en/cards/$id • gramática executável v1"
        // Reuse only identical trainer/energy print semantics, including ACE SPEC rarity.
        if (raw.s("category") in listOf("Trainer", "Energy")) {
            val same = reviewed.entries.firstOrNull { (key, _) ->
                val original = rows[key] ?: TrainerCatalog.sourceData(key)
                signature(original) == signature(raw)
            }?.value ?: return null
            return same.copy(id = id, number = raw.s("localId")!!, set = raw.s("setCode").orEmpty(),
                artAsset = "remote:$image/high.webp", source = source)
        }
        if (raw.s("category") != "Pokemon" || raw.s("stage") != "Basic" ||
            !raw.s("suffix").isNullOrBlank() || raw.a("abilities").isNotEmpty() ||
            raw.a("rules").isNotEmpty() || !raw.s("effect").isNullOrBlank() ||
            raw.a("types").size != 1 || raw.a("weaknesses").size > 1 || raw.a("resistances").size > 1) return null
        val element = type(raw.a("types").single().jsonPrimitive.content) ?: return null
        val weakness = raw.a("weaknesses").firstOrNull()?.jsonObject
        val resistance = raw.a("resistances").firstOrNull()?.jsonObject
        if (weakness != null && (weakness.s("value") != "×2" || type(weakness.s("type")) == null)) return null
        if (resistance != null && (resistance.s("value") != "-30" || type(resistance.s("type")) == null)) return null
        val attacks = raw.a("attacks").mapIndexed { index, attack ->
            compileAttack(id, index, attack.jsonObject) ?: return null
        }
        if (attacks.isEmpty()) return null
        val hp = raw.s("hp")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val retreat = raw.s("retreat")?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return CardDefinition(id, raw.s("name")!!, raw.s("setCode").orEmpty(), raw.s("localId")!!,
            category = Category.POKEMON, hp = hp, type = element, weakness = type(weakness?.s("type")),
            resistance = type(resistance?.s("type")), retreatCost = retreat, attacks = attacks,
            verified = true, source = source, artAsset = "remote:$image/high.webp")
    }

    fun compileAttack(id: String, index: Int, raw: JsonObject): AttackDefinition? {
        val cost = raw.a("cost").map { type(it.jsonPrimitive.content) ?: return null }
        val text = raw.s("effect").orEmpty().trim()
        val damageText = raw.s("damage").orEmpty()
        val effects = mutableListOf<Effect>()
        val coin = Regex("Flip (2|3|4) coins\\. This attack does (\\d+) damage for each heads\\.").matchEntire(text)
        if (coin != null) {
            val amount = coin.groupValues[2].toInt()
            if (damageText != "${amount}×" && damageText != "${amount}x") return null
            effects += Effect.CoinDamage(coin.groupValues[1].toInt(), amount)
        } else {
            val damage = if (damageText.isEmpty()) 0 else damageText.toIntOrNull() ?: return null
            if (damage < 0) return null
            if (damage > 0) effects += Effect.Damage(damage)
            val draw = Regex("Draw (a|1|2|3) cards?\\.").matchEntire(text)
            val recoil = Regex("This Pokémon also does (\\d+) damage to itself\\.").matchEntire(text)
            val heal = Regex("Heal (\\d+) damage from this Pokémon\\.").matchEntire(text)
            when {
                text.isEmpty() -> Unit
                draw != null -> effects += Effect.Draw(if (draw.groupValues[1] == "a") 1 else draw.groupValues[1].toInt())
                recoil != null -> effects += Effect.Recoil(recoil.groupValues[1].toInt())
                heal != null -> effects += Effect.Heal(heal.groupValues[1].toInt())
                text == "During your next turn, this Pokémon can't attack." -> effects += Effect.RestrictNextTurn()
                else -> return null
            }
        }
        return AttackDefinition("$id-a$index", raw.s("name") ?: return null, cost, effects, text)
    }
}
