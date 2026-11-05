package com.tcgtrainer.app

import android.content.Context
import com.tcgtrainer.engine.CardDefinition
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Presentation-only localization: never changes executable effects or identifiers. */
internal object PortugueseCardDetails {
    fun load(context: Context, card: CardDefinition): CardDefinition = runCatching {
        require(card.id.matches(Regex("[A-Za-z0-9.-]+")))
        val cache = File(context.filesDir, "card-details-pt").apply { mkdirs() }
        val file = File(cache, "${card.id}.json")
        val bundled = javaClass.classLoader?.getResourceAsStream("cards/${card.id}-pt.json")?.bufferedReader()?.use { it.readText() }
        val json = JSONObject(bundled ?: if (file.exists()) file.readText() else {
            val connection = URL("https://api.tcgdex.net/v2/pt/cards/${card.id}").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                require(connection.responseCode == 200)
                connection.inputStream.bufferedReader().use { it.readText() }.also {
                    require(JSONObject(it).optString("id") == card.id)
                    file.writeText(it)
                }
            } finally { connection.disconnect() }
        })
        fun text(obj: JSONObject?, key: String, fallback: String) = obj?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: fallback
        val attacks = json.optJSONArray("attacks")
        val abilities = json.optJSONArray("abilities")
        card.copy(
            text = text(json, "effect", card.text),
            attacks = card.attacks.mapIndexed { i, attack ->
                val translated = if (attacks?.length() == card.attacks.size) attacks.optJSONObject(i) else null
                attack.copy(name = text(translated, "name", attack.name), text = text(translated, "effect", attack.text))
            },
            abilities = card.abilities.mapIndexed { i, ability ->
                val translated = if (abilities?.length() == card.abilities.size) abilities.optJSONObject(i) else null
                ability.copy(name = text(translated, "name", ability.name), text = text(translated, "effect", ability.text))
            }
        )
    }.getOrDefault(card)
}
