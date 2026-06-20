package me.obrekht.wishu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskStateMachineTest {

    @Test fun advance_isForwardOnly_andStopsAtDone() {
        val m = TaskStateMachine()
        assertEquals(TaskStage.PLANNING, m.state.stage)
        assertTrue(m.advance()); assertEquals(TaskStage.EXECUTION, m.state.stage)
        assertTrue(m.advance()); assertEquals(TaskStage.VALIDATION, m.state.stage)
        assertTrue(m.advance()); assertEquals(TaskStage.DONE, m.state.stage)
        // Terminal: no further advance.
        assertFalse(m.advance())
        assertEquals(TaskStage.DONE, m.state.stage)
    }

    @Test fun regressTo_validationToExecution_isAllowed() {
        val m = TaskStateMachine()
        m.advance(); m.advance() // -> VALIDATION
        assertEquals(TaskStage.VALIDATION, m.state.stage)
        assertTrue(m.regressTo(TaskStage.EXECUTION))
        assertEquals(TaskStage.EXECUTION, m.state.stage)
    }

    @Test fun regressTo_illegalTransitions_areRefused() {
        val m = TaskStateMachine()
        // From PLANNING nothing may regress.
        assertFalse(m.regressTo(TaskStage.EXECUTION))
        assertEquals(TaskStage.PLANNING, m.state.stage)

        // From VALIDATION, jumping to PLANNING or forward to DONE is not in the table.
        m.advance(); m.advance() // VALIDATION
        assertFalse(m.regressTo(TaskStage.PLANNING))
        assertFalse(m.regressTo(TaskStage.DONE))
        assertEquals(TaskStage.VALIDATION, m.state.stage)
    }

    @Test fun validationViolation_landsInExecution_neverDone() {
        // Simulates the agent's FSM coupling: an invariant violation at VALIDATION regresses
        // instead of advancing — the task must not reach DONE.
        val m = TaskStateMachine()
        m.advance(); m.advance() // VALIDATION
        val violated = true
        if (violated) m.regressTo(TaskStage.EXECUTION) else m.advance()
        assertEquals(TaskStage.EXECUTION, m.state.stage)
    }

    @Test fun stageNext_followsTheFixedOrder() {
        assertEquals(TaskStage.EXECUTION, TaskStage.PLANNING.next)
        assertEquals(TaskStage.VALIDATION, TaskStage.EXECUTION.next)
        assertEquals(TaskStage.DONE, TaskStage.VALIDATION.next)
        assertEquals(null, TaskStage.DONE.next)
    }
}
