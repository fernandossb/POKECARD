package com.tcgtrainer.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tcgtrainer.engine.*
import java.io.File
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "saved_game")
data class SavedGame(@PrimaryKey val slot: Int = 1, val payload: String)
@Dao interface SaveDao {
    @Query("SELECT * FROM saved_game WHERE slot = 1") suspend fun load(): SavedGame?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(game: SavedGame)
}
@Entity(tableName = "user_deck")
data class DeckRecord(@PrimaryKey val id: String, val payload: String, val updatedAt: Long)
@Dao interface DeckDao {
    @Query("SELECT * FROM user_deck ORDER BY updatedAt DESC") fun observe(): Flow<List<DeckRecord>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(deck: DeckRecord)
    @Query("DELETE FROM user_deck WHERE id = :id") suspend fun delete(id: String)
}
@Database(entities = [SavedGame::class, DeckRecord::class], version = 2, exportSchema = false)
abstract class GameDatabase : RoomDatabase() {
    abstract fun saves(): SaveDao
    abstract fun decks(): DeckDao
    companion object {
        private val migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `user_deck` (`id` TEXT NOT NULL, `payload` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
        fun open(context: Context) = Room.databaseBuilder(context, GameDatabase::class.java, "tcg-trainer.db").addMigrations(migration).build()
    }
}

object TrainerDeckBridge {
    private const val SYNC_FILE = "trainer-decks.json"

    @JvmStatic
    fun exportInitialDecksJson(context: Context): String {
        val array = JSONArray()
        val seenIds = mutableSetOf<String>()

        for (deck in TrainingDecks.all) {
            seenIds.add(deck.id)
            val obj = JSONObject()
            obj.put("id", deck.id)
            obj.put("name", deck.name)
            obj.put("strategy", deck.strategy)
            obj.put("cover", deck.cover)
            obj.put("format", deck.format.name)
            obj.put("preset", true)
            val counts = JSONObject()
            for ((cardId, qty) in deck.cards.groupingBy { it }.eachCount()) {
                counts.put(cardId, qty)
            }
            obj.put("counts", counts)
            array.put(obj)
        }

        try {
            val dbFile = context.getDatabasePath("tcg-trainer.db")
            if (dbFile.exists()) {
                SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                    sqlite.rawQuery("SELECT payload FROM user_deck ORDER BY updatedAt DESC", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val payload = cursor.getString(0) ?: continue
                            val saved = runCatching { DeckCodec.decode(payload) }.getOrNull() ?: continue
                            if (!seenIds.add(saved.id)) continue
                            val playable = saved.playableDeck()
                            val obj = JSONObject()
                            obj.put("id", saved.id)
                            obj.put("name", saved.name)
                            obj.put("strategy", playable.strategy)
                            obj.put("cover", playable.cover)
                            obj.put("format", saved.format.name)
                            obj.put("preset", false)
                            val counts = JSONObject()
                            for ((cardId, qty) in saved.counts) {
                                if (qty > 0) counts.put(cardId, qty)
                            }
                            obj.put("counts", counts)
                            array.put(obj)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val root = JSONObject()
        root.put("version", 1)
        root.put("decks", array)
        return root.toString()
    }

    @JvmStatic
    fun saveSyncedDecks(context: Context, rawJson: String) {
        try {
            val root = JSONObject(rawJson)
            val decksArray = root.optJSONArray("decks") ?: return
            val sanitized = JSONArray()
            for (i in 0 until decksArray.length()) {
                val item = decksArray.optJSONObject(i) ?: continue
                val id = item.optString("id", "").trim()
                val name = item.optString("name", "").trim()
                val countsObj = item.optJSONObject("counts") ?: continue
                if (id.isEmpty() || name.isEmpty()) continue
                val cleanCounts = JSONObject()
                var total = 0
                val keys = countsObj.keys()
                while (keys.hasNext()) {
                    val cardId = keys.next()
                    val qty = countsObj.optInt(cardId, 0)
                    if (cardId.isNotBlank() && qty in 1..60) {
                        cleanCounts.put(cardId, qty)
                        total += qty
                    }
                }
                if (total <= 0) continue
                val out = JSONObject()
                out.put("id", id)
                out.put("name", name)
                out.put("strategy", item.optString("strategy", "Seu deck salvo na aba Decks"))
                out.put("cover", item.optString("cover", ""))
                out.put("format", item.optString("format", "TRAINING"))
                out.put("counts", cleanCounts)
                sanitized.put(out)
            }
            val payload = JSONObject()
            payload.put("version", 1)
            payload.put("decks", sanitized)
            val atomic = AtomicFile(File(context.filesDir, SYNC_FILE))
            val stream = atomic.startWrite()
            try {
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
        } catch (_: Exception) {
        }
    }

    @JvmStatic
    fun loadSyncedDecks(context: Context): List<TrainingDeck>? {
        return try {
            val file = File(context.filesDir, SYNC_FILE)
            if (!file.exists()) return null
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("decks") ?: return null
            val list = mutableListOf<TrainingDeck>()
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                val id = d.optString("id", "").trim()
                val name = d.optString("name", "").trim()
                val countsObj = d.optJSONObject("counts") ?: continue
                if (id.isEmpty() || name.isEmpty()) continue
                val counts = linkedMapOf<String, Int>()
                val keys = countsObj.keys()
                while (keys.hasNext()) {
                    val cardId = keys.next()
                    val qty = countsObj.optInt(cardId, 0).coerceIn(0, 60)
                    if (qty > 0) counts[cardId] = qty
                }
                val cards = counts.flatMap { (cardId, qty) -> List(qty) { cardId } }
                if (cards.isEmpty()) continue
                val preset = TrainingDecks.all.firstOrNull { it.id == id }
                val formatStr = d.optString("format", preset?.format?.name ?: "TRAINING")
                val format = runCatching { DeckFormat.valueOf(formatStr) }.getOrDefault(preset?.format ?: DeckFormat.TRAINING)
                val cover = d.optString("cover", "").takeIf { it.isNotBlank() }
                    ?: preset?.cover
                    ?: cards.firstOrNull { CardLibrary.byId[it]?.category == Category.POKEMON }
                    ?: cards.first()
                val strategy = d.optString("strategy", "").takeIf { it.isNotBlank() }
                    ?: preset?.strategy
                    ?: "Deck salvo na aba Decks · ${format.label()}"
                list.add(
                    TrainingDeck(
                        id = id,
                        name = name,
                        strategy = strategy,
                        cover = cover,
                        cards = cards,
                        format = format,
                        custom = preset == null
                    )
                )
            }
            list.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}

