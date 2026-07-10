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
