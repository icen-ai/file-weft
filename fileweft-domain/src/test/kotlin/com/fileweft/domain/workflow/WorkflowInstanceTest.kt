package com.fileweft.domain.workflow

import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
        assertFailsWith<IllegalArgumentException> {
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
    fun `enforces task tenant assignment and lifecycle invariants`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowInstance(
                Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DOCUMENT_REVIEW",
                tasks = listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-2"), Identifier("workflow-1"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            workflow().approve(Identifier("task-1"), Identifier("other-reviewer"))
        }
    }

    private fun workflow() = WorkflowInstance(
        Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DOCUMENT_REVIEW",
        tasks = listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1"))),
    )
}
