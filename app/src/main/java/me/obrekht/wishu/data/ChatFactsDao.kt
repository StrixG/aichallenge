package me.obrekht.wishu.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChatFactsDao {
    @Query("SELECT * FROM chat_facts WHERE id = 0")
    suspend fun get(): ChatFactsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(facts: ChatFactsEntity)

    @Query("DELETE FROM chat_facts")
    suspend fun clear()
}
