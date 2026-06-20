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
 */
data class TaskState(
    val stage: TaskStage = TaskStage.PLANNING,
    val currentStep: String = "",
    val expectedAction: String = ""
) {
    companion object {
        val EMPTY = TaskState()
    }
}

/**
 * The state machine. The ONLY mutator of [TaskState.stage] is [advance], which moves forward by a
 * single [TaskStage.next] and refuses to move past terminal. There is deliberately no `jumpTo(stage)`
 * / stage setter — so no caller (and no model output) can make the task skip a stage. The model only
 * influences the *pace* (whether to advance at all), never the *destination*.
 */
class TaskStateMachine(initial: TaskState = TaskState()) {

    var state: TaskState = initial
        private set

    /** Advance exactly one stage. No-op (returns false) while already DONE. */
    fun advance(): Boolean {
        val next = state.stage.next ?: return false
        state = state.copy(stage = next)
        return true
    }

    /**
     * Day 14: regress to an EARLIER stage when an invariant is violated at VALIDATION — the only way
     * the FSM moves backward. Still code-gated: the destination must be in [ALLOWED_REGRESSIONS], so a
     * caller (or model output) cannot jump to an arbitrary stage. Returns false if the regression isn't
     * in the table (e.g. forward, self, or an undefined source). The task can never reach DONE with an
     * open violation because [WishChatAgent] calls this instead of [advance] in that case.
     */
    fun regressTo(target: TaskStage): Boolean {
        val allowed = ALLOWED_REGRESSIONS[state.stage] ?: return false
        if (target !in allowed) return false
        state = state.copy(stage = target)
        return true
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

    private companion object {
        // The ONLY backward transitions the FSM permits — the "table of allowed transitions". A
        // VALIDATION-stage invariant violation sends the task back to EXECUTION (re-propose ideas),
        // never silently past DONE. Anything not listed here is refused by [regressTo].
        val ALLOWED_REGRESSIONS: Map<TaskStage, Set<TaskStage>> = mapOf(
            TaskStage.VALIDATION to setOf(TaskStage.EXECUTION)
        )
    }
}
