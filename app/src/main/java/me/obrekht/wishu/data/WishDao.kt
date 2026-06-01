package me.obrekht.wishu.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WishDao {
    @Query("SELECT * FROM wishes ORDER BY createdAt DESC")
    fun getAllWishes(): Flow<List<Wish>>

    @Insert
    suspend fun insert(wish: Wish)

    @Delete
    suspend fun delete(wish: Wish)
}
