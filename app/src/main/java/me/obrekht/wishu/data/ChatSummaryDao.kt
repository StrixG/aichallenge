package me.obrekht.wishu.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChatSummaryDao {
    @Query("SELECT * FROM chat_summary WHERE id = 0")
    suspend fun get(): ChatSummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: ChatSummaryEntity)

    @Query("DELETE FROM chat_summary")
    suspend fun clear()
}
