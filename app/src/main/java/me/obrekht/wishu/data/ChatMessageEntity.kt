package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// A persisted chat turn. Insertion order (ascending id) is the conversation replay order.
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" | "assistant"
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
