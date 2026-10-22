package com.tcgtrainer.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

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
