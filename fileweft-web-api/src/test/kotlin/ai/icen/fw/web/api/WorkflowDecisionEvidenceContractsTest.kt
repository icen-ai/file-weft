package ai.icen.fw.web.api

import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDecisionEvidenceDto
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDecisionEvidencePageQuery
import ai.icen.fw.web.api.v1.workflow.WorkflowDecisionTaskEvidenceDto
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowDecisionEvidenceContractsTest {
    @Test
    fun `constructs recorded and legacy UTF8 evidence without exposing assignments or comments`() {
        val recorded = WorkflowDecisionTaskEvidenceDto(
            "task-1", "APPROVED", 10, 20, "oidc|90071992547409930001", "审批人甲", 20,
        )
        val legacy = WorkflowDecisionTaskEvidenceDto("task-legacy", "REJECTED", 11, 21)
        val source = mutableListOf(recorded, legacy)
        val workflow = DocumentWorkflowDecisionEvidenceDto(
            "workflow-1", "document-1", "DUAL_REVIEW", "REJECTED", 10, 21, source,
        )
        source.clear()

        assertTrue(recorded.decisionEvidenceRecorded)
        assertEquals("oidc|90071992547409930001", recorded.decisionOperatorId)
        assertEquals("审批人甲", recorded.decisionOperatorName)
        assertFalse(legacy.decisionEvidenceRecorded)
        assertNull(legacy.decisionOperatorId)
        assertNull(legacy.decidedTime)
        assertEquals(listOf("task-1", "task-legacy"), workflow.tasks.map { task -> task.id })
        assertThrows<UnsupportedOperationException> {
            (workflow.tasks as MutableList<WorkflowDecisionTaskEvidenceDto>).clear()
        }

        val fields = (WorkflowDecisionTaskEvidenceDto::class.java.declaredFields +
            DocumentWorkflowDecisionEvidenceDto::class.java.declaredFields).map { field -> field.name }.toSet()
        assertTrue("decisionOperatorId" in fields)
        assertTrue("decisionOperatorName" in fields)
        assertTrue("decidedTime" in fields)
        assertTrue(
            fields.intersect(setOf("tenantId", "assigneeId", "assignee", "reviewerId", "comment", "commentText", "attributes"))
                .isEmpty(),
        )
    }

    @Test
    fun `rejects contradictory evidence unsafe text times duplicates and page limits`() {
        assertThrows<IllegalArgumentException> {
            WorkflowDecisionTaskEvidenceDto("task-1", "PENDING", 10, 20, "reviewer-a", "审批人", 20)
        }
        assertThrows<IllegalArgumentException> {
            WorkflowDecisionTaskEvidenceDto("task-1", "APPROVED", 10, 20, null, "审批人", null)
        }
        assertThrows<IllegalArgumentException> {
            WorkflowDecisionTaskEvidenceDto("task-1", "APPROVED", 10, 20, "reviewer-a", "审批人", 21)
        }
        assertThrows<IllegalArgumentException> {
            WorkflowDecisionTaskEvidenceDto("task-1", "APPROVED", 10, 20, "reviewer\u0000", null, 20)
        }
        assertThrows<IllegalArgumentException> {
            DocumentWorkflowDecisionEvidenceDto(
                "workflow-1", "document-1", "TYPE", "APPROVED", 10, 20,
                listOf(
                    WorkflowDecisionTaskEvidenceDto("task-1", "APPROVED", 10, 20),
                    WorkflowDecisionTaskEvidenceDto("task-1", "APPROVED", 10, 20),
                ),
            )
        }
        listOf(0, 101).forEach { limit ->
            assertThrows<IllegalArgumentException> { DocumentWorkflowDecisionEvidencePageQuery(limit = limit) }
        }
        assertThrows<IllegalArgumentException> {
            DocumentWorkflowDecisionEvidencePageQuery(cursor = " ")
        }
    }

    @Test
    fun `page query keeps Java friendly defaults`() {
        assertEquals(20, DocumentWorkflowDecisionEvidencePageQuery().limit)
        assertNull(DocumentWorkflowDecisionEvidencePageQuery().cursor)
        assertEquals("cursor", DocumentWorkflowDecisionEvidencePageQuery("cursor", 100).cursor)
    }
}
