package me.obrekht.wishu.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Wish::class, ChatMessageEntity::class, ChatSummaryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class WishDatabase : RoomDatabase() {
    abstract fun wishDao(): WishDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSummaryDao(): ChatSummaryDao

    companion object {
        @Volatile
        private var INSTANCE: WishDatabase? = null

        // v1 -> v2: add the chat_messages table; existing wishes rows are preserved.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`role` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        // v2 -> v3: add the single-row chat_summary table (history compression). Existing rows kept.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_summary` " +
                        "(`id` INTEGER PRIMARY KEY NOT NULL, " +
                        "`summary` TEXT NOT NULL, `summarizedCount` INTEGER NOT NULL)"
                )
            }
        }

        fun getDatabase(context: Context): WishDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, WishDatabase::class.java, "wish_database")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
