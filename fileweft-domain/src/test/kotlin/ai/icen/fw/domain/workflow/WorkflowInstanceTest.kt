package ai.icen.fw.domain.workflow

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowInstanceTest {
    @Test
    fun `approves workflow only after its pending task is approved`() {
        val workflow = workflow()

        workflow.approve(Identifier("task-1"), Identifier("reviewer-1"), "审批人一", "Looks good")

        assertEquals(WorkflowState.APPROVED, workflow.state)
        assertEquals(WorkflowTaskState.APPROVED, workflow.tasks.single().state)
        assertEquals("Looks good", workflow.tasks.single().comment)
        assertEquals(Identifier("reviewer-1"), workflow.tasks.single().decisionOperatorId)
        assertEquals("审批人一", workflow.tasks.single().decisionOperatorName)
    }

    @Test
    fun `rejects workflow and prevents a second decision`() {
        val workflow = workflow()

        workflow.reject(Identifier("task-1"), Identifier("reviewer-1"), "复核人一", "Needs revision")

        assertEquals(WorkflowState.REJECTED, workflow.state)
        assertEquals(Identifier("reviewer-1"), workflow.tasks.single().decisionOperatorId)
        assertEquals("复核人一", workflow.tasks.single().decisionOperatorName)
        assertFailsWith<WorkflowDecisionConflictException> {
            workflow.approve(Identifier("task-1"), Identifier("reviewer-1"))
        }
    }

    @Test
    fun `withdraws an unfinished workflow while retaining submitter and task decision evidence`() {
        val submittedBy = Identifier("submitter-1")
        val original = WorkflowInstance(
            Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DUAL_REVIEW",
            tasks = listOf(
                WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1")),
                WorkflowTask(Identifier("task-2"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-2")),
            ),
        )
        original.approve(Identifier("task-1"), Identifier("reviewer-1"), "审批人甲", "approved")
        val workflow = WorkflowInstance(
            original.id,
            original.tenantId,
            original.documentId,
            original.workflowType,
            original.state,
            original.tasks,
            submittedBy,
        )

        workflow.withdraw()

        assertEquals(WorkflowState.WITHDRAWN, workflow.state)
        assertEquals(submittedBy, workflow.submittedBy)
        assertEquals(
            listOf(WorkflowTaskState.APPROVED, WorkflowTaskState.PENDING),
            workflow.tasks.map { it.state },
        )
        assertEquals(Identifier("reviewer-1"), workflow.tasks.first().decisionOperatorId)
        assertEquals("approved", workflow.tasks.first().comment)
        assertFailsWith<WorkflowWithdrawalConflictException> { workflow.withdraw() }
    }

    @Test
    fun `completed workflows cannot be withdrawn and legacy constructor keeps an unknown submitter`() {
        val approved = workflow().also {
            it.approve(Identifier("task-1"), Identifier("reviewer-1"))
        }
        val rejected = workflow().also {
            it.reject(Identifier("task-1"), Identifier("reviewer-1"))
        }

        assertEquals(null, approved.submittedBy)
        assertFailsWith<WorkflowWithdrawalConflictException> { approved.withdraw() }
        assertFailsWith<WorkflowWithdrawalConflictException> { rejected.withdraw() }
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

        workflow.approve(Identifier("task-1"), Identifier("reviewer-1"), "审批人甲", null)

        assertEquals(WorkflowState.PENDING, workflow.state)
        val reloaded = WorkflowInstance(
            workflow.id, workflow.tenantId, workflow.documentId, workflow.workflowType, workflow.state, workflow.tasks,
        )
        reloaded.approve(Identifier("task-2"), Identifier("reviewer-2"), "审批人乙", null)

        assertEquals(WorkflowState.APPROVED, reloaded.state)
        assertEquals(
            listOf(Identifier("reviewer-1"), Identifier("reviewer-2")),
            reloaded.tasks.map { task -> task.decisionOperatorId },
        )
        assertEquals(listOf("审批人甲", "审批人乙"), reloaded.tasks.map { task -> task.decisionOperatorName })
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

    @Test
    fun `loads legacy completed tasks without guessing decision identity`() {
        val legacy = WorkflowTask(
            Identifier("task-legacy"),
            Identifier("tenant-1"),
            Identifier("workflow-legacy"),
            Identifier("reviewer-legacy"),
            WorkflowTaskState.APPROVED,
            "legacy approval",
            null,
            null,
        )
        val workflow = WorkflowInstance(
            Identifier("workflow-legacy"),
            Identifier("tenant-1"),
            Identifier("document-1"),
            "DOCUMENT_REVIEW",
            WorkflowState.APPROVED,
            listOf(legacy),
        )

        assertEquals(WorkflowState.APPROVED, workflow.state)
        assertEquals(null, workflow.tasks.single().decisionOperatorId)
        assertEquals(null, workflow.tasks.single().decisionOperatorName)
    }

    @Test
    fun `rejects decision evidence on pending tasks and names without an operator id`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowTask(
                Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), null,
                WorkflowTaskState.PENDING, null, Identifier("reviewer-1"), "审批人",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowTask(
                Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), null,
                WorkflowTaskState.APPROVED, null, null, "审批人",
            )
        }
    }

    @Test
    fun `old decision overload remains compatible and records the opaque operator id`() {
        val workflow = workflow()

        workflow.approve(Identifier("task-1"), Identifier("reviewer-1"), "旧调用方备注")

        assertEquals(Identifier("reviewer-1"), workflow.tasks.single().decisionOperatorId)
        assertEquals(null, workflow.tasks.single().decisionOperatorName)
        assertEquals("旧调用方备注", workflow.tasks.single().comment)
    }

    private fun workflow() = WorkflowInstance(
        Identifier("workflow-1"), Identifier("tenant-1"), Identifier("document-1"), "DOCUMENT_REVIEW",
        tasks = listOf(WorkflowTask(Identifier("task-1"), Identifier("tenant-1"), Identifier("workflow-1"), Identifier("reviewer-1"))),
    )
}
