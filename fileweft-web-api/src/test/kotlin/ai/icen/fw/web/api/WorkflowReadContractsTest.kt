package ai.icen.fw.web.api

import ai.icen.fw.web.api.v1.document.DocumentDto
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDto
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowPageQuery
import ai.icen.fw.web.api.v1.workflow.WorkflowHistoryTaskDto
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskDto
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskInboxItemDto
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskPageQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowReadContractsTest {
    @Test
    fun `constructs immutable UTF8 inbox and history projections`() {
        val task = WorkflowTaskDto("task-审批", "workflow-1", "PENDING", 10, 20, true)
        val inbox = WorkflowTaskInboxItemDto(
            task = task,
            document = document(),
            workflowType = "DUAL_REVIEW",
            workflowState = "PENDING",
        )
        val sourceTasks = mutableListOf(
            WorkflowHistoryTaskDto("task-1", "APPROVED", 10, 20),
            WorkflowHistoryTaskDto("task-2", "REJECTED", 11, 21),
        )
        val history = DocumentWorkflowDto(
            id = "workflow-1",
            documentId = "document-1",
            workflowType = "DUAL_REVIEW",
            state = "REJECTED",
            createdTime = 10,
            updatedTime = 21,
            tasks = sourceTasks,
        )

        sourceTasks.clear()

        assertEquals("task-审批", inbox.task.id)
        assertEquals("清税证明", inbox.document.title)
        assertTrue(inbox.task.assignedToCurrentUser)
        assertTrue(inbox.actionableByCurrentUser)
        assertEquals(listOf("task-1", "task-2"), history.tasks.map { it.id })
        assertThrows<UnsupportedOperationException> {
            (history.tasks as MutableList<WorkflowHistoryTaskDto>).clear()
        }
    }

    @Test
    fun `page queries retain stable defaults and reject unsafe bounds`() {
        assertEquals(20, WorkflowTaskPageQuery().limit)
        assertEquals(20, DocumentWorkflowPageQuery().limit)
        assertEquals("task-cursor", WorkflowTaskPageQuery("task-cursor", 100).cursor)
        assertEquals("history-cursor", DocumentWorkflowPageQuery("history-cursor", 1).cursor)

        listOf(0, 101).forEach { limit ->
            assertThrows<IllegalArgumentException> { WorkflowTaskPageQuery(limit = limit) }
            assertThrows<IllegalArgumentException> { DocumentWorkflowPageQuery(limit = limit) }
        }
        assertThrows<IllegalArgumentException> { WorkflowTaskPageQuery(cursor = " ") }
        assertThrows<IllegalArgumentException> { DocumentWorkflowPageQuery(cursor = "x".repeat(513)) }
        assertThrows<IllegalArgumentException> { WorkflowTaskPageQuery(cursor = "cursor\u0000") }
    }

    @Test
    fun `workflow DTOs reject inconsistent times duplicate tasks and unsafe text`() {
        assertThrows<IllegalArgumentException> {
            WorkflowTaskDto("task-1", "workflow-1", "PENDING", -1, 0)
        }
        assertThrows<IllegalArgumentException> {
            WorkflowTaskDto("task-1", "workflow-1", "PENDING", 2, 1)
        }
        assertThrows<IllegalArgumentException> {
            WorkflowHistoryTaskDto("task-1", "APPROVED", 2, 1)
        }
        assertThrows<IllegalArgumentException> {
            DocumentWorkflowDto("workflow-1", "document-1", "TYPE", "APPROVED", 1, 1, emptyList())
        }
        assertThrows<IllegalArgumentException> {
            DocumentWorkflowDto(
                "workflow-1",
                "document-1",
                "TYPE",
                "APPROVED",
                1,
                2,
                listOf(
                    WorkflowHistoryTaskDto("task-1", "APPROVED", 1, 1),
                    WorkflowHistoryTaskDto("task-1", "APPROVED", 1, 1),
                ),
            )
        }
        assertThrows<IllegalArgumentException> {
            WorkflowTaskDto("task-1", "workflow-1", "PENDING\r\nInjected", 1, 1)
        }
        assertThrows<IllegalArgumentException> {
            DocumentWorkflowDto(
                "workflow-1",
                "document-1",
                "x".repeat(65),
                "APPROVED",
                1,
                1,
                listOf(WorkflowHistoryTaskDto("task-1", "APPROVED", 1, 1)),
            )
        }
    }

    @Test
    fun `public workflow projections omit identities comments tenants and persistence details`() {
        val taskFields = fields(WorkflowTaskDto::class.java)
        val inboxFields = fields(WorkflowTaskInboxItemDto::class.java)
        val historyTaskFields = fields(WorkflowHistoryTaskDto::class.java)
        val historyFields = fields(DocumentWorkflowDto::class.java)
        val allFields = taskFields + inboxFields + historyTaskFields + historyFields
        val forbidden = setOf(
            "tenantId",
            "assigneeId",
            "assignee",
            "reviewerId",
            "operatorId",
            "operatorName",
            "comment",
            "commentText",
            "attributes",
            "assetId",
            "fileObjectId",
            "storagePath",
            "externalId",
        )

        assertTrue("assignedToCurrentUser" in taskFields)
        assertTrue("actionableByCurrentUser" in inboxFields)
        assertTrue(allFields.intersect(forbidden).isEmpty())
        assertFalse("assignedToCurrentUser" in historyTaskFields)
    }

    private fun fields(type: Class<*>): Set<String> = type.declaredFields.map { it.name }.toSet()

    private fun document(): DocumentDto = DocumentDto(
        id = "document-1",
        documentNumber = "DOC-1",
        title = "清税证明",
        lifecycleState = "PENDING_REVIEW",
        createdTime = 1,
        updatedTime = 2,
        currentVersionId = "version-1",
        folderId = "finance",
    )
}
