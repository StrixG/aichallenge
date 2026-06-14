package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// The Sticky Facts strategy's key-value store, kept as one pinned row (id = 0): a JSON object of
// durable facts pulled from the dialog (recipient, occasion, budget, likes/dislikes, decisions).
@Entity(tableName = "chat_facts")
data class ChatFactsEntity(
    @PrimaryKey val id: Int = 0,
    val factsJson: String
)
