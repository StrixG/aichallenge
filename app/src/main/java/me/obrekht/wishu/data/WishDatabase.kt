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
        ChatBranchEntity::class,
        LongTermMemoryEntity::class,
        TaskStateEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class WishDatabase : RoomDatabase() {
    abstract fun wishDao(): WishDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun chatSummaryDao(): ChatSummaryDao
    abstract fun chatFactsDao(): ChatFactsDao
    abstract fun chatBranchDao(): ChatBranchDao
    abstract fun longTermMemoryDao(): LongTermMemoryDao
    abstract fun taskStateDao(): TaskStateDao

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

        // v4 -> v5: memory layers. Add the long_term_memory table (persists across session clears).
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `long_term_memory` " +
                        "(`key` TEXT PRIMARY KEY NOT NULL, " +
                        "`value` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
            }
        }

        // v5 -> v6: task state machine (Day 13). Add the single-row task_state table — stage + the
        // model-described step/expected-action, plus the awaitingApproval human-validation gate (later
        // removed in v7). Session-scoped: wiped by clear() alongside facts/summary.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_state` " +
                        "(`id` INTEGER PRIMARY KEY NOT NULL, `stage` TEXT NOT NULL, " +
                        "`currentStep` TEXT NOT NULL, `expectedAction` TEXT NOT NULL, " +
                        "`awaitingApproval` INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        // v6 -> v7: drop the human-validation gate. The `awaitingApproval` column is gone — the gate
        // was removed from the flow (transitions now run pre-turn, PLANNING is deterministic). The
        // table is session-scoped throwaway state, so recreate it fresh rather than copy rows across.
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `task_state`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_state` " +
                        "(`id` INTEGER PRIMARY KEY NOT NULL, `stage` TEXT NOT NULL, " +
                        "`currentStep` TEXT NOT NULL, `expectedAction` TEXT NOT NULL)"
                )
            }
        }

        fun getDatabase(context: Context): WishDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context, WishDatabase::class.java, "wish_database")
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_6_7
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
