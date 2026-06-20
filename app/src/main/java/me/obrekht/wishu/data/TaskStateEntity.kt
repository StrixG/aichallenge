package me.obrekht.wishu.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// The task state machine's persisted snapshot. A single pinned row (id = 0): the current stage plus
// the model-described step/expected-action, so the task survives an app restart and resumes without
// re-explaining. Session-scoped — wiped by clear() like the working memory.
@Entity(tableName = "task_state")
data class TaskStateEntity(
    @PrimaryKey val id: Int = 0,
    val stage: String,
    val currentStep: String,
    val expectedAction: String,
    // Day 14: the human-validation gate — true when a low-confidence completion paused the FSM on a
    // stage boundary awaiting the user's Approve/Keep-refining. Persisted so the gate survives restart.
    val awaitingApproval: Boolean = false
)
