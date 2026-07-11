package com.fileweft.domain.workflow

import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowInstanceTest {
    @Test
    fun `approves workflow only after its pending task is approved`() {
        val workflow = workflow()

        workflow.approve(Identifier("task-1"), Identifier("reviewer-1"), "Looks good")

        assertEquals(WorkflowState.APPROVED, workflow.state)
        assertEquals(WorkflowTaskState.APPROVED, workflow.tasks.single().state)
        assertEquals("Looks good", workflow.tasks.single().comment)
    }

    @Test
    fun `rejects workflow and prevents a second decision`() {
        val workflow = workflow()

        workflow.reject(Identifier("task-1"), Identifier("reviewer-1"), "Needs revision")

        assertEquals(WorkflowState.REJECTED, workflow.state)
        assertFailsWith<WorkflowDecisionConflictException> {
            workflow.approve(Identifier("task-1"), Identifier("reviewer-1"))
        }
    }

    @Test
    fun `keeps a parallel workflow pending until every task is approved including after reload`() {
        val workflow = WorkflowInstance(
            Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DUAL_REVIEW",
            tasks = listOf(
                WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1")),
                WorkflowTask(Identifier("task-2"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-2")),
            ),
        )

        workflow.approve(Identifier("task-1"), Identifier("reviewer-1"))

        assertEquals(WorkflowState.PENDING, workflow.state)
        val reloaded = WorkflowInstance(
            workflow.id, workflow.tenantId, workflow.documentId, workflow.workflowType, workflow.state, workflow.tasks,
        )
        reloaded.approve(Identifier("task-2"), Identifier("reviewer-2"))

        assertEquals(WorkflowState.APPROVED, reloaded.state)
    }

    @Test
    fun `predicts a completing approval without mutating parallel workflow state`() {
        val workflow = WorkflowInstance(
            Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DUAL_REVIEW",
            tasks = listOf(
                WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1")),
                WorkflowTask(Identifier("task-2"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-2")),
            ),
        )

        assertFalse(workflow.willCompleteAfterApproval(Identifier("task-1"), Identifier("reviewer-1")))
        assertEquals(WorkflowState.PENDING, workflow.state)
        assertEquals(WorkflowTaskState.PENDING, workflow.tasks.first().state)

        workflow.approve(Identifier("task-1"), Identifier("reviewer-1"))

        assertTrue(workflow.willCompleteAfterApproval(Identifier("task-2"), Identifier("reviewer-2")))
        assertFailsWith<WorkflowTaskAssignmentDeniedException> {
            workflow.willCompleteAfterApproval(Identifier("task-2"), Identifier("other-reviewer"))
        }
    }

    @Test
    fun `classifies a repeated task decision as a workflow conflict while another task remains pending`() {
        val workflow = WorkflowInstance(
            Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DUAL_REVIEW",
            tasks = listOf(
                WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1")),
                WorkflowTask(Identifier("task-2"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-2")),
            ),
        )
        workflow.approve(Identifier("task-1"), Identifier("reviewer-1"))

        assertFailsWith<WorkflowDecisionConflictException> {
            workflow.approve(Identifier("task-1"), Identifier("reviewer-1"))
        }
        assertEquals(WorkflowState.PENDING, workflow.state)
        assertEquals(WorkflowTaskState.PENDING, workflow.tasks[1].state)
    }

    @Test
    fun `classifies a task outside the workflow as not found`() {
        val failure = assertFailsWith<WorkflowTaskNotFoundException> {
            workflow().approve(Identifier("task-other"), Identifier("reviewer-1"))
        }

        assertEquals(Identifier("workflow-1"), failure.workflowId)
        assertEquals(Identifier("task-other"), failure.taskId)
    }

    @Test
    fun `checks task membership then assignment before exposing workflow state`() {
        val completed = workflow().also { workflow ->
            workflow.approve(Identifier("task-1"), Identifier("reviewer-1"))
        }

        assertFailsWith<WorkflowTaskNotFoundException> {
            completed.approve(Identifier("task-other"), Identifier("reviewer-1"))
        }
        assertFailsWith<WorkflowTaskAssignmentDeniedException> {
            completed.approve(Identifier("task-1"), Identifier("other-reviewer"))
        }
        assertFailsWith<WorkflowDecisionConflictException> {
            completed.approve(Identifier("task-1"), Identifier("reviewer-1"))
        }
    }

    @Test
    fun `enforces task tenant assignment and lifecycle invariants`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowInstance(
                Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DOCUMENT_REVIEW",
                tasks = listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-2"), Identifier("workflow-1"))),
            )
        }
        val denied = assertFailsWith<WorkflowTaskAssignmentDeniedException> {
            workflow().approve(Identifier("task-1"), Identifier("other-reviewer"))
        }
        assertEquals(Identifier("task-1"), denied.taskId)
    }

    @Test
    fun `keeps malformed comments classified as invalid input`() {
        assertFailsWith<IllegalArgumentException> {
            workflow().approve(Identifier("task-1"), Identifier("reviewer-1"), " ")
        }
    }

    private fun workflow() = WorkflowInstance(
        Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DOCUMENT_REVIEW",
        tasks = listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1"))),
    )
}
