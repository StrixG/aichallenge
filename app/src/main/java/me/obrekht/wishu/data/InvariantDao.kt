package me.obrekht.wishu.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InvariantDao {
    // Reactive: the invariants screen and the chat both observe the same source of truth.
    @Query("SELECT * FROM invariants ORDER BY severity")
    fun getAll(): Flow<List<InvariantEntity>>

    @Query("SELECT COUNT(*) FROM invariants")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InvariantEntity)

    @Delete
    suspend fun delete(entity: InvariantEntity)

    @Query("DELETE FROM invariants WHERE id = :id")
    suspend fun deleteById(id: String)
}
