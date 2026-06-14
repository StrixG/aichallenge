package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// A persisted chat turn, scoped to a branch of the conversation tree ([branchId], default the root
// branch 1). Insertion order (ascending id) is the replay order within a branch.
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" | "assistant"
    val content: String,
    val branchId: Long = 1,
    val createdAt: Long = System.currentTimeMillis()
)
