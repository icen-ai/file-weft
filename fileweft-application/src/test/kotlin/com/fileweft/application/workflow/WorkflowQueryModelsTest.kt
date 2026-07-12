package com.fileweft.application.workflow

import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTaskState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowQueryModelsTest {
    @Test
    fun `uses bounded Java friendly request defaults and validates stable cursors`() {
        val taskRequest = WorkflowTaskPageRequest()
        val historyRequest = DocumentWorkflowPageRequest()

        assertEquals(20, taskRequest.limit)
        assertNull(taskRequest.cursor)
        assertEquals(20, historyRequest.limit)
        assertNull(historyRequest.cursor)
        assertEquals("task-a", WorkflowTaskPageCursor(0, Identifier("task-a")).id.value)
        assertEquals("workflow-a", DocumentWorkflowPageCursor(0, Identifier("workflow-a")).id.value)

        assertFailsWith<IllegalArgumentException> { WorkflowTaskPageRequest(limit = 0) }
        assertFailsWith<IllegalArgumentException> { WorkflowTaskPageRequest(limit = 101) }
        assertFailsWith<IllegalArgumentException> { DocumentWorkflowPageRequest(limit = 0) }
        assertFailsWith<IllegalArgumentException> { DocumentWorkflowPageRequest(limit = 101) }
        assertFailsWith<IllegalArgumentException> { WorkflowTaskPageCursor(-1, Identifier("task-a")) }
        assertFailsWith<IllegalArgumentException> { DocumentWorkflowPageCursor(-1, Identifier("workflow-a")) }
    }

    @Test
    fun `enforces pending-only inbox invariants and accepts assigned and pooled tasks`() {
        val assigned = WorkflowTaskView(
            Identifier("task-assigned"), Identifier("workflow-a"), WorkflowTaskState.PENDING,
            10, 20, assignedToCurrentUser = true,
        )
        val pooled = WorkflowTaskView(
            Identifier("task-pooled"), Identifier("workflow-a"), WorkflowTaskState.PENDING,
            10, 10, assignedToCurrentUser = false,
        )

        assertTrue(assigned.assignedToCurrentUser)
        assertTrue(assigned.actionableByCurrentUser)
        assertFalse(pooled.assignedToCurrentUser)
        assertTrue(pooled.actionableByCurrentUser)
        assertFailsWith<IllegalArgumentException> {
            WorkflowTaskView(
                Identifier("task-approved"), Identifier("workflow-a"), WorkflowTaskState.APPROVED,
                10, 20, assignedToCurrentUser = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowTaskView(
                Identifier("task-a"), Identifier("workflow-a"), WorkflowTaskState.PENDING,
                -1, 20, assignedToCurrentUser = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowTaskView(
                Identifier("task-a"), Identifier("workflow-a"), WorkflowTaskState.PENDING,
                20, 19, assignedToCurrentUser = true,
            )
        }
    }

    @Test
    fun `requires a safe pending workflow type for an inbox item`() {
        val task = pendingTask()
        val document = document()
        val item = WorkflowTaskInboxItemView(task, document, "DOCUMENT_REVIEW", WorkflowState.PENDING)

        assertEquals("DOCUMENT_REVIEW", item.workflowType)
        assertEquals(WorkflowState.PENDING, item.workflowState)
        assertFailsWith<IllegalArgumentException> {
            WorkflowTaskInboxItemView(task, document, " ", WorkflowState.PENDING)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowTaskInboxItemView(task, document, "x".repeat(65), WorkflowState.PENDING)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowTaskInboxItemView(task, document, "DOCUMENT\u000aREVIEW", WorkflowState.PENDING)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowTaskInboxItemView(task, document, "DOCUMENT\u200BREVIEW", WorkflowState.PENDING)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowTaskInboxItemView(task, document, "DOCUMENT_REVIEW", WorkflowState.APPROVED)
        }
    }

    @Test
    fun `copies and freezes inbox pages without retaining caller lists`() {
        val source = mutableListOf(inboxItem("task-a"))
        val next = WorkflowTaskPageCursor(10, Identifier("task-a"))
        val page = WorkflowTaskPageResult(source, next)

        source.clear()

        assertEquals(listOf("task-a"), page.items.map { it.task.id.value })
        assertEquals(next, page.nextCursor)
        @Suppress("UNCHECKED_CAST")
        val mutableItems = page.items as MutableList<WorkflowTaskInboxItemView>
        assertFailsWith<UnsupportedOperationException> { mutableItems.add(inboxItem("task-b")) }
        assertFailsWith<IllegalArgumentException> {
            WorkflowTaskPageResult(List(WorkflowTaskPageRequest.MAX_LIMIT + 1) { inboxItem("task-$it") })
        }
    }

    @Test
    fun `validates complete workflow history and freezes nested task and page lists`() {
        val tasks = mutableListOf(
            WorkflowHistoryTaskView(Identifier("task-a"), WorkflowTaskState.APPROVED, 10, 20),
            WorkflowHistoryTaskView(Identifier("task-b"), WorkflowTaskState.REJECTED, 10, 21),
        )
        val workflow = WorkflowView(
            Identifier("workflow-a"), Identifier("document-a"), "DOCUMENT_REVIEW", WorkflowState.REJECTED,
            10, 21, tasks,
        )
        val workflows = mutableListOf(workflow)
        val page = DocumentWorkflowPageResult(workflows, DocumentWorkflowPageCursor(10, workflow.id))

        tasks.clear()
        workflows.clear()

        assertEquals(listOf("task-a", "task-b"), workflow.tasks.map { it.id.value })
        assertEquals(listOf("workflow-a"), page.items.map { it.id.value })
        @Suppress("UNCHECKED_CAST")
        val mutableTasks = workflow.tasks as MutableList<WorkflowHistoryTaskView>
        @Suppress("UNCHECKED_CAST")
        val mutableWorkflows = page.items as MutableList<WorkflowView>
        assertFailsWith<UnsupportedOperationException> {
            mutableTasks.add(WorkflowHistoryTaskView(Identifier("task-c"), WorkflowTaskState.PENDING, 10, 20))
        }
        assertFailsWith<UnsupportedOperationException> { mutableWorkflows.add(history("workflow-b")) }
    }

    @Test
    fun `rejects malformed history timestamps types and duplicate tasks`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowHistoryTaskView(Identifier("task-a"), WorkflowTaskState.PENDING, -1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowHistoryTaskView(Identifier("task-a"), WorkflowTaskState.PENDING, 20, 19)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowView(
                Identifier("workflow-a"), Identifier("document-a"), "DOCUMENT_REVIEW", WorkflowState.PENDING,
                -1, 20, listOf(WorkflowHistoryTaskView(Identifier("task-a"), WorkflowTaskState.PENDING, 10, 20)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowView(
                Identifier("workflow-a"), Identifier("document-a"), "DOCUMENT_REVIEW", WorkflowState.PENDING,
                20, 19, listOf(WorkflowHistoryTaskView(Identifier("task-a"), WorkflowTaskState.PENDING, 10, 20)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowView(
                Identifier("workflow-a"), Identifier("document-a"), "DOCUMENT_REVIEW", WorkflowState.PENDING,
                10, 20, emptyList(),
            )
        }
        val duplicate = WorkflowHistoryTaskView(Identifier("task-a"), WorkflowTaskState.APPROVED, 10, 20)
        assertFailsWith<IllegalArgumentException> {
            WorkflowView(
                Identifier("workflow-a"), Identifier("document-a"), "DOCUMENT_REVIEW", WorkflowState.APPROVED,
                10, 20, listOf(duplicate, duplicate),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentWorkflowPageResult(List(DocumentWorkflowPageRequest.MAX_LIMIT + 1) { history("workflow-$it") })
        }
    }

    @Test
    fun `keeps workflow query projections free of tenant assignment comments and operator identities`() {
        val forbiddenGetters = setOf(
            "getTenantId", "getAssigneeId", "getAssignee", "getComment", "getOperatorId",
            "getOperatorName", "getReviewerId", "getDecisionOperatorId", "getAttributes",
        )
        val projectionTypes = listOf(
            WorkflowTaskView::class.java,
            WorkflowTaskInboxItemView::class.java,
            WorkflowTaskPageResult::class.java,
            WorkflowHistoryTaskView::class.java,
            WorkflowView::class.java,
            DocumentWorkflowPageResult::class.java,
        )
        val getters = projectionTypes.flatMap { type ->
            type.methods.filter { method -> method.parameterCount == 0 }.map { method -> method.name }
        }.toSet()

        assertTrue(forbiddenGetters.none(getters::contains))
    }

    private fun pendingTask(id: String = "task-a"): WorkflowTaskView = WorkflowTaskView(
        Identifier(id), Identifier("workflow-a"), WorkflowTaskState.PENDING,
        10, 20, assignedToCurrentUser = true,
    )

    private fun inboxItem(id: String): WorkflowTaskInboxItemView = WorkflowTaskInboxItemView(
        pendingTask(id), document(), "DOCUMENT_REVIEW", WorkflowState.PENDING,
    )

    private fun history(id: String): WorkflowView = WorkflowView(
        Identifier(id), Identifier("document-a"), "DOCUMENT_REVIEW", WorkflowState.APPROVED,
        10, 20, listOf(WorkflowHistoryTaskView(Identifier("task-$id"), WorkflowTaskState.APPROVED, 10, 20)),
    )

    private fun document(): DocumentSummaryView = DocumentSummaryView(
        Identifier("document-a"), "DOC-A", "Document A", LifecycleState.PENDING_REVIEW,
        1, 20, Identifier("version-a"), "finance",
    )
}
