package me.obrekht.wishu.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ChatBranchDao {
    @Query("SELECT * FROM chat_branches ORDER BY id ASC")
    suspend fun getAll(): List<ChatBranchEntity>

    @Query("SELECT * FROM chat_branches WHERE id = :id")
    suspend fun get(id: Long): ChatBranchEntity?

    @Insert
    suspend fun insert(branch: ChatBranchEntity): Long

    @Query("DELETE FROM chat_branches WHERE id != 1")
    suspend fun clearForks()
}
