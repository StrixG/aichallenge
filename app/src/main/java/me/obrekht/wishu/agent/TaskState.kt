package me.obrekht.wishu.agent

/**
 * Day 15: TaskStage is now a sealed interface with data object entries.
 * Public names (PLANNING, EXECUTION, VALIDATION, DONE) are unchanged — zero API churn.
 * The [name] property mirrors the old enum.name (used in DB, logging, and the task-state prompt).
 */
sealed interface TaskStage {
    val name: String

    /** Convenience: the single legal forward successor per TRANSITION_TABLE, or null for DONE. */
    val next: TaskStage?
        get() = TRANSITION_TABLE[this]?.forward

    data object PLANNING   : TaskStage { override val name = "PLANNING" }
    data object EXECUTION  : TaskStage { override val name = "EXECUTION" }
    data object VALIDATION : TaskStage { override val name = "VALIDATION" }
    data object DONE       : TaskStage { override val name = "DONE" }

    companion object {
        /** Explicit when — sealed interfaces have no entries list. Unknown/null → PLANNING. */
        fun fromName(name: String?): TaskStage = when (name) {
            "PLANNING"   -> PLANNING
            "EXECUTION"  -> EXECUTION
            "VALIDATION" -> VALIDATION
            "DONE"       -> DONE
            else         -> PLANNING
        }

        /**
         * Ordered list of all stages — replaces the old enum.entries for UI iteration.
         * Must be a computed getter, NOT an initialized `val`: as an eager `val` the list literal
         * is built during companion `<clinit>`, which can run reentrantly while a `data object`
         * (PLANNING, reached first via TRANSITION_TABLE) is still mid-initialization — capturing a
         * null element. The getter defers construction to call-time, after all objects exist.
         */
        val all: List<TaskStage> get() = listOf(PLANNING, EXECUTION, VALIDATION, DONE)
    }
}

/**
 * Day 15: encodes the legal moves out of a single stage.
 * [forward] is the single stage advance() may reach (null for terminal DONE).
 * [regress] is the set of stages regressTo() may reach (empty for most stages).
 */
data class Transitions(
    val forward: TaskStage?,
    val regress: Set<TaskStage>
)

/**
 * Day 15: the single source of truth for ALL legal stage transitions.
 * Replaces both the old `TaskStage.next` property and `ALLOWED_REGRESSIONS` map.
 * Adding a new stage means adding one entry here and nowhere else.
 *
 *   PLANNING   → EXECUTION              (forward only)
 *   EXECUTION  → VALIDATION             (forward only)
 *   VALIDATION → DONE  or  EXECUTION   (forward + regression on invariant violation)
 *   DONE       → (terminal)
 */
val TRANSITION_TABLE: Map<TaskStage, Transitions> = mapOf(
    TaskStage.PLANNING   to Transitions(forward = TaskStage.EXECUTION,   regress = emptySet()),
    TaskStage.EXECUTION  to Transitions(forward = TaskStage.VALIDATION,  regress = emptySet()),
    TaskStage.VALIDATION to Transitions(forward = TaskStage.DONE,        regress = setOf(TaskStage.EXECUTION)),
    TaskStage.DONE       to Transitions(forward = null,                   regress = emptySet())
)

/**
 * Immutable snapshot of the task automaton.
 * Day 15 additions:
 *  - [strategyPending]: true while the agent is in PLANNING sub-phase 2 — all four data slots
 *    are filled, waiting for the user to confirm the proposed search strategy.
 *  - [briefSnapshot]: the factsToon captured when leaving PLANNING; injected into EXECUTION and
 *    VALIDATION context so requirements are always in scope even under aggressive trimming.
 *  - [ideasSnapshot]: the last assistant content captured when leaving EXECUTION; injected into
 *    VALIDATION context so the ideas-to-pick-from are always available.
 */
data class TaskState(
    val stage: TaskStage = TaskStage.PLANNING,
    val currentStep: String = "",
    val expectedAction: String = "",
    val strategyPending: Boolean = false,
    val briefSnapshot: String? = null,
    val ideasSnapshot: String? = null
) {
    companion object {
        val EMPTY = TaskState()
    }
}

/**
 * The state machine. Day 15: [advance] and [regressTo] both delegate to [TRANSITION_TABLE] —
 * the table is the only authority on legal moves. No other mutation of [TaskState.stage] exists.
 */
class TaskStateMachine(initial: TaskState = TaskState()) {

    var state: TaskState = initial
        private set

    /** Advance exactly one stage via TRANSITION_TABLE.forward. No-op (returns false) at DONE. */
    fun advance(): Boolean {
        val next = TRANSITION_TABLE[state.stage]?.forward ?: return false
        state = state.copy(stage = next, strategyPending = false)
        return true
    }

    /**
     * Regress to [target] if and only if it is in TRANSITION_TABLE[current].regress.
     * Returns false for self-transitions, forward jumps, or anything not in the regress set.
     */
    fun regressTo(target: TaskStage): Boolean {
        val allowed = TRANSITION_TABLE[state.stage]?.regress ?: return false
        if (target !in allowed) return false
        state = state.copy(stage = target)
        return true
    }

    /** Record the model's free-text description of the current step / expected action. */
    fun updateProgress(currentStep: String, expectedAction: String) {
        state = state.copy(currentStep = currentStep, expectedAction = expectedAction)
    }

    /** Day 15: toggle the strategy-confirmation sub-phase of PLANNING. */
    fun setStrategyPending(pending: Boolean) {
        state = state.copy(strategyPending = pending)
    }

    /**
     * Day 15: capture a stage artifact snapshot into the state. Pass [brief] when advancing FROM
     * PLANNING, [ideas] when advancing FROM EXECUTION. Both default to null so a single call can
     * set either or both without overwriting the other.
     */
    fun captureArtifact(brief: String? = null, ideas: String? = null) {
        state = state.copy(
            briefSnapshot = brief ?: state.briefSnapshot,
            ideasSnapshot = ideas ?: state.ideasSnapshot
        )
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
