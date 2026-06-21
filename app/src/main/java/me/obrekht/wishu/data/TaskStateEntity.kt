package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// Single-row task state snapshot (id = 0). Session-scoped — wiped by clear().
// Day 15: strategyPending, briefSnapshot, ideasSnapshot added via MIGRATION_9_10.
@Entity(tableName = "task_state")
data class TaskStateEntity(
    @PrimaryKey val id: Int = 0,
    val stage: String,
    val currentStep: String,
    val expectedAction: String,
    val strategyPending: Boolean = false,
    val briefSnapshot: String? = null,
    val ideasSnapshot: String? = null
)
