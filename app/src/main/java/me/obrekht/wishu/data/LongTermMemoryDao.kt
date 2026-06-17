package me.obrekht.wishu.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LongTermMemoryDao {
    @Query("SELECT * FROM long_term_memory")
    suspend fun getAll(): List<LongTermMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LongTermMemoryEntity)

    @Query("DELETE FROM long_term_memory")
    suspend fun clear()
}
