package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// The chat's running compression summary. A single pinned row (id = 0): the summary text plus
// how many chat_messages rows it already folds in, so a restart can rebuild the agent's state.
@Entity(tableName = "chat_summary")
data class ChatSummaryEntity(
    @PrimaryKey val id: Int = 0,
    val summary: String,
    val summarizedCount: Int
)
