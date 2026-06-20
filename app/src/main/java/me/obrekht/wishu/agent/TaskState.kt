package me.obrekht.wishu.agent

/**
 * The task's progress as a formal finite-state machine (Day 13). A gift-planning task moves through
 * four fixed stages, strictly in order:
 *
 *   PLANNING -> EXECUTION -> VALIDATION -> DONE
 *
 * - PLANNING: gather requirements (recipient, occasion, budget, the recipient's tastes).
 * - EXECUTION: propose concrete gift ideas.
 * - VALIDATION: the user confirms / picks among the ideas.
 * - DONE: terminal — the task is closed.
 *
 * Skipping stages is impossible *by construction*, not by prompt: [next] is the ONLY way forward and
 * always returns the single immediate successor. The agent's helper call may signal that a stage is
 * complete, but it can never choose the destination — the code advances by exactly one [next].
 */
enum class TaskStage {
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE;

    /** The single legal successor, or null for the terminal stage. There is no other transition. */
    val next: TaskStage?
        get() = when (this) {
            PLANNING -> EXECUTION
            EXECUTION -> VALIDATION
            VALIDATION -> DONE
            DONE -> null
        }

    companion object {
        fun fromName(name: String?): TaskStage =
            entries.firstOrNull { it.name == name } ?: PLANNING
    }
}

/**
 * An immutable snapshot of the task automaton:
 *  - [stage]: which phase the task is in.
 *  - [currentStep]: a short human-readable note on what's happening now (model-described).
 *  - [expectedAction]: what's expected next (model-described).
 *  - [awaitingApproval]: the human-validation gate (Day 14). When true the helper judged the current
 *    stage complete but was *unsure*, so the FSM paused on the boundary instead of auto-advancing —
 *    it's waiting for the user to confirm (advance) or keep refining (dismiss). The proposed
 *    destination is never stored: it is always [stage].next, so the no-skip invariant is untouched.
 */
data class TaskState(
    val stage: TaskStage = TaskStage.PLANNING,
    val currentStep: String = "",
    val expectedAction: String = "",
    val awaitingApproval: Boolean = false
) {
    companion object {
        val EMPTY = TaskState()
    }
}

/**
 * The state machine. The ONLY mutators of [TaskState.stage] are [advance] and [confirmAdvance], both
 * of which move forward by a single [TaskStage.next] and refuse to move past terminal. There is
 * deliberately no `jumpTo(stage)` / stage setter — so no caller (and no model output) can make the
 * task skip a stage. The model only influences the *pace* (whether to advance at all), never the
 * *destination*.
 *
 * Day 14 — the human-validation gate ([requestAdvance] / [confirmAdvance] / [cancelAdvance]): when the
 * helper judges the stage complete but is unsure, the agent calls [requestAdvance] to *pause* on the
 * boundary ([TaskState.awaitingApproval] = true) instead of advancing. The user then either confirms
 * ([confirmAdvance], the only path that actually moves the stage) or keeps refining ([cancelAdvance]).
 */
class TaskStateMachine(initial: TaskState = TaskState()) {

    var state: TaskState = initial
        private set

    /** Advance exactly one stage. No-op (returns false) while already DONE. */
    fun advance(): Boolean {
        val next = state.stage.next ?: return false
        state = state.copy(stage = next, awaitingApproval = false)
        return true
    }

    /** Open the human-validation gate: pause on the boundary without advancing. No-op at terminal or
     *  when the gate is already open. */
    fun requestAdvance(): Boolean {
        if (state.stage.next == null || state.awaitingApproval) return false
        state = state.copy(awaitingApproval = true)
        return true
    }

    /** User approved the paused transition: advance exactly one [TaskStage.next] and close the gate. */
    fun confirmAdvance(): Boolean {
        if (!state.awaitingApproval) return false
        return advance()
    }

    /** User declined / kept refining: close the gate, stay in the current stage. */
    fun cancelAdvance() {
        if (state.awaitingApproval) state = state.copy(awaitingApproval = false)
    }

    /** Record the model's free-text description of the current step / expected action. */
    fun updateProgress(currentStep: String, expectedAction: String) {
        state = state.copy(currentStep = currentStep, expectedAction = expectedAction)
    }

    /** Back to a fresh task (PLANNING). Session-scoped, like working memory. */
    fun reset() {
        state = TaskState()
    }

    /** Re-seed from persisted state on app restart / restore. */
    fun restore(saved: TaskState) {
        state = saved
    }
}
