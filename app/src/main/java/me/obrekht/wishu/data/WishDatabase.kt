package me.obrekht.wishu.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Wish::class,
        ChatMessageEntity::class,
        ChatSummaryEntity::class,
        ChatFactsEntity::class,
        ChatBranchEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class WishDatabase : RoomDatabase() {
    abstract fun wishDao(): WishDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSummaryDao(): ChatSummaryDao
    abstract fun chatFactsDao(): ChatFactsDao
    abstract fun chatBranchDao(): ChatBranchDao

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

        // v3 -> v4: context strategies. Add the branch tree (with a root branch), the Sticky Facts
        // table, and a branchId on chat_messages (existing rows default to the root branch 1).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_branches` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`label` TEXT NOT NULL, `parentBranchId` INTEGER, " +
                        "`forkAtCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `chat_branches` (`id`, `label`, `parentBranchId`, `forkAtCount`, `createdAt`) " +
                        "VALUES (1, 'main', NULL, 0, ${System.currentTimeMillis()})"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_facts` " +
                        "(`id` INTEGER PRIMARY KEY NOT NULL, `factsJson` TEXT NOT NULL)"
                )
                db.execSQL(
                    "ALTER TABLE `chat_messages` ADD COLUMN `branchId` INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        fun getDatabase(context: Context): WishDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, WishDatabase::class.java, "wish_database")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
