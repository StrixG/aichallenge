package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// The working-memory key-value store, kept as one pinned row (id = 0): durable task facts pulled
// from the dialog (recipient, occasion, budget, likes/dislikes, decisions).
// The [factsJson] column now holds TOON ('key: value' lines), not JSON — column name kept to avoid
// a migration; it's an opaque serialized blob to the DB either way.
@Entity(tableName = "chat_facts")
data class ChatFactsEntity(
    @PrimaryKey val id: Int = 0,
    val factsJson: String
)
