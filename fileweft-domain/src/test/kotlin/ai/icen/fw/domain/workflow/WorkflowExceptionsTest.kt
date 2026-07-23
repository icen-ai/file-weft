package ai.icen.fw.domain.workflow

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowExceptionsTest {
    @Test
    fun `business task failures no longer misuse JDK signal types`() {
        assertFalse(
            SecurityException::class.java.isAssignableFrom(WorkflowTaskDeniedException::class.java),
            "WorkflowTaskDeniedException must not be a SecurityException.",
        )
        assertFalse(
            NoSuchElementException::class.java.isAssignableFrom(WorkflowTaskMissingException::class.java),
            "WorkflowTaskMissingException must not be a NoSuchElementException.",
        )
    }

    @Test
    fun `aggregate throws the new business types with their evidence intact`() {
        val workflow = WorkflowInstance(
            Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DOCUMENT_REVIEW",
            tasks = listOf(
                WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1")),
            ),
        )

        val denied = assertFailsWith<WorkflowTaskDeniedException> {
            workflow.approve(Identifier("task-1"), Identifier("other-reviewer"))
        }
        assertEquals(Identifier("task-1"), denied.taskId)

        val missing = assertFailsWith<WorkflowTaskMissingException> {
            workflow.approve(Identifier("task-other"), Identifier("reviewer-1"))
        }
        assertEquals(Identifier("workflow-1"), missing.workflowId)
        assertEquals(Identifier("task-other"), missing.taskId)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `deprecated types remain as ABI shims over the new business types`() {
        assertTrue(
            WorkflowTaskDeniedException::class.java.isAssignableFrom(WorkflowTaskAssignmentDeniedException::class.java),
            "WorkflowTaskAssignmentDeniedException must remain a WorkflowTaskDeniedException for legacy catch clauses.",
        )
        assertTrue(
            WorkflowTaskMissingException::class.java.isAssignableFrom(WorkflowTaskNotFoundException::class.java),
            "WorkflowTaskNotFoundException must remain a WorkflowTaskMissingException for legacy catch clauses.",
        )
        assertTrue(WorkflowTaskAssignmentDeniedException::class.java.isAnnotationPresent(Deprecated::class.java))
        assertTrue(WorkflowTaskNotFoundException::class.java.isAnnotationPresent(Deprecated::class.java))

        val legacyDenied: WorkflowTaskDeniedException = WorkflowTaskAssignmentDeniedException(Identifier("task-1"))
        assertEquals(Identifier("task-1"), legacyDenied.taskId)
        val legacyMissing: WorkflowTaskMissingException =
            WorkflowTaskNotFoundException(Identifier("workflow-1"), Identifier("task-1"))
        assertEquals(Identifier("workflow-1"), legacyMissing.workflowId)
        assertEquals(Identifier("task-1"), legacyMissing.taskId)
    }
}
