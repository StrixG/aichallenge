package me.obrekht.wishu.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TaskStateDao {
    @Query("SELECT * FROM task_state WHERE id = 0")
    suspend fun get(): TaskStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: TaskStateEntity)

    @Query("DELETE FROM task_state")
    suspend fun clear()
}
