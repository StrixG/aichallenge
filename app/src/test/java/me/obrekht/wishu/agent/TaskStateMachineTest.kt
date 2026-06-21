package me.obrekht.wishu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {

    // ---- forward advance (TRANSITION_TABLE.forward) ----------------------------------------

    @Test fun advance_isForwardOnly_andStopsAtDone() {
        val m = TaskStateMachine()
        assertEquals(TaskStage.PLANNING, m.state.stage)
        assertTrue(m.advance()); assertEquals(TaskStage.EXECUTION, m.state.stage)
        assertTrue(m.advance()); assertEquals(TaskStage.VALIDATION, m.state.stage)
        assertTrue(m.advance()); assertEquals(TaskStage.DONE, m.state.stage)
        assertFalse(m.advance())
        assertEquals(TaskStage.DONE, m.state.stage)
    }

    @Test fun advance_planning_goesToExecution() {
        val m = TaskStateMachine()
        assertTrue(m.advance())
        assertEquals(TaskStage.EXECUTION, m.state.stage)
    }

    @Test fun advance_execution_goesToValidation() {
        val m = TaskStateMachine()
        m.advance()
        assertTrue(m.advance())
        assertEquals(TaskStage.VALIDATION, m.state.stage)
    }

    @Test fun advance_validation_goesToDone() {
        val m = TaskStateMachine()
        m.advance(); m.advance()
        assertTrue(m.advance())
        assertEquals(TaskStage.DONE, m.state.stage)
    }

    @Test fun advance_done_returnsFalse() {
        val m = TaskStateMachine()
        m.advance(); m.advance(); m.advance()
        assertFalse(m.advance())
        assertEquals(TaskStage.DONE, m.state.stage)
    }

    // ---- allowed regressions (TRANSITION_TABLE.regress) ------------------------------------

    @Test fun regressTo_validationToExecution_isAllowed() {
        val m = TaskStateMachine()
        m.advance(); m.advance() // -> VALIDATION
        assertEquals(TaskStage.VALIDATION, m.state.stage)
        assertTrue(m.regressTo(TaskStage.EXECUTION))
        assertEquals(TaskStage.EXECUTION, m.state.stage)
    }

    // ---- forbidden transitions — must all return false without side-effects -----------------

    @Test fun regressTo_planning_nothingAllowed() {
        val m = TaskStateMachine()
        assertFalse(m.regressTo(TaskStage.EXECUTION))
        assertFalse(m.regressTo(TaskStage.VALIDATION))
        assertFalse(m.regressTo(TaskStage.DONE))
        assertFalse(m.regressTo(TaskStage.PLANNING)) // self
        assertEquals(TaskStage.PLANNING, m.state.stage)
    }

    @Test fun regressTo_execution_nothingAllowed() {
        val m = TaskStateMachine()
        m.advance() // -> EXECUTION
        assertFalse(m.regressTo(TaskStage.PLANNING))
        assertFalse(m.regressTo(TaskStage.EXECUTION)) // self
        assertFalse(m.regressTo(TaskStage.VALIDATION))
        assertFalse(m.regressTo(TaskStage.DONE))
        assertEquals(TaskStage.EXECUTION, m.state.stage)
    }

    @Test fun regressTo_validation_onlyExecutionAllowed() {
        val m = TaskStateMachine()
        m.advance(); m.advance() // -> VALIDATION
        assertFalse(m.regressTo(TaskStage.PLANNING))
        assertFalse(m.regressTo(TaskStage.VALIDATION)) // self
        assertFalse(m.regressTo(TaskStage.DONE))       // forward — also forbidden via regressTo
        assertEquals(TaskStage.VALIDATION, m.state.stage)
    }

    @Test fun regressTo_done_nothingAllowed() {
        val m = TaskStateMachine()
        m.advance(); m.advance(); m.advance() // -> DONE
        assertFalse(m.regressTo(TaskStage.PLANNING))
        assertFalse(m.regressTo(TaskStage.EXECUTION))
        assertFalse(m.regressTo(TaskStage.VALIDATION))
        assertFalse(m.regressTo(TaskStage.DONE)) // self
        assertEquals(TaskStage.DONE, m.state.stage)
    }

    @Test fun regressTo_planningToDone_isRefused() {
        // PLANNING -> DONE is neither forward nor in regress set: forbidden
        val m = TaskStateMachine()
        assertFalse(m.regressTo(TaskStage.DONE))
        assertEquals(TaskStage.PLANNING, m.state.stage)
    }

    // ---- invariant coupling ----------------------------------------------------------------

    @Test fun validationViolation_landsInExecution_neverDone() {
        val m = TaskStateMachine()
        m.advance(); m.advance() // VALIDATION
        val violated = true
        if (violated) m.regressTo(TaskStage.EXECUTION) else m.advance()
        assertEquals(TaskStage.EXECUTION, m.state.stage)
    }

    // ---- next property via sealed interface ------------------------------------------------

    @Test fun stageNext_followsTheFixedOrder() {
        assertEquals(TaskStage.EXECUTION, TaskStage.PLANNING.next)
        assertEquals(TaskStage.VALIDATION, TaskStage.EXECUTION.next)
        assertEquals(TaskStage.DONE, TaskStage.VALIDATION.next)
        assertNull(TaskStage.DONE.next)
    }

    // ---- name property ---------------------------------------------------------------------

    @Test fun stageName_matchesObjectName() {
        assertEquals("PLANNING",   TaskStage.PLANNING.name)
        assertEquals("EXECUTION",  TaskStage.EXECUTION.name)
        assertEquals("VALIDATION", TaskStage.VALIDATION.name)
        assertEquals("DONE",       TaskStage.DONE.name)
    }

    // ---- fromName round-trips --------------------------------------------------------------

    @Test fun fromName_roundTrips() {
        for (s in listOf(TaskStage.PLANNING, TaskStage.EXECUTION, TaskStage.VALIDATION, TaskStage.DONE)) {
            assertEquals(s, TaskStage.fromName(s.name))
        }
    }

    @Test fun fromName_unknown_defaultsToPlanning() {
        assertEquals(TaskStage.PLANNING, TaskStage.fromName(null))
        assertEquals(TaskStage.PLANNING, TaskStage.fromName(""))
        assertEquals(TaskStage.PLANNING, TaskStage.fromName("bogus"))
    }

    // ---- strategyPending mutator -----------------------------------------------------------

    @Test fun setStrategyPending_setsFlag() {
        val m = TaskStateMachine()
        assertFalse(m.state.strategyPending)
        m.setStrategyPending(true)
        assertTrue(m.state.strategyPending)
        m.setStrategyPending(false)
        assertFalse(m.state.strategyPending)
    }

    // ---- captureArtifact -------------------------------------------------------------------

    @Test fun captureArtifact_brief_storesSnapshot() {
        val m = TaskStateMachine()
        m.captureArtifact(brief = "recipient: mom\noccasion: birthday")
        assertEquals("recipient: mom\noccasion: birthday", m.state.briefSnapshot)
        assertNull(m.state.ideasSnapshot)
    }

    @Test fun captureArtifact_ideas_storesSnapshot() {
        val m = TaskStateMachine()
        m.captureArtifact(ideas = "1. Book\n2. Scarf")
        assertEquals("1. Book\n2. Scarf", m.state.ideasSnapshot)
        assertNull(m.state.briefSnapshot)
    }

    @Test fun captureArtifact_bothAtOnce_storesBoth() {
        val m = TaskStateMachine()
        m.captureArtifact(brief = "brief text", ideas = "ideas text")
        assertEquals("brief text", m.state.briefSnapshot)
        assertEquals("ideas text", m.state.ideasSnapshot)
    }

    // ---- reset clears new fields -----------------------------------------------------------

    @Test fun reset_clearsStrategyPendingAndArtifacts() {
        val m = TaskStateMachine()
        m.advance()
        m.setStrategyPending(true)
        m.captureArtifact(brief = "b", ideas = "i")
        m.reset()
        assertEquals(TaskStage.PLANNING, m.state.stage)
        assertFalse(m.state.strategyPending)
        assertNull(m.state.briefSnapshot)
        assertNull(m.state.ideasSnapshot)
    }

    // ---- restore re-seeds new fields -------------------------------------------------------

    @Test fun restore_reseeds_allNewFields() {
        val m = TaskStateMachine()
        val saved = TaskState(
            stage = TaskStage.EXECUTION,
            currentStep = "step",
            expectedAction = "action",
            strategyPending = true,
            briefSnapshot = "the brief",
            ideasSnapshot = null
        )
        m.restore(saved)
        assertEquals(TaskStage.EXECUTION, m.state.stage)
        assertTrue(m.state.strategyPending)
        assertEquals("the brief", m.state.briefSnapshot)
        assertNull(m.state.ideasSnapshot)
    }
}
