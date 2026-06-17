package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "long_term_memory")
data class LongTermMemoryEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
