package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// A line of the conversation tree. The root branch (id = 1, label "main", no parent) always exists;
// forking creates a child whose effective transcript is the parent's transcript truncated to
// [forkAtCount] (the checkpoint) followed by this branch's own chat_messages rows.
@Entity(tableName = "chat_branches")
data class ChatBranchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val parentBranchId: Long? = null,
    val forkAtCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
