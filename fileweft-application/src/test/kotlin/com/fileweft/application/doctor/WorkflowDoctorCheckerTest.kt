package com.fileweft.application.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorStatus
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.domain.workflow.WorkflowTask
import com.fileweft.domain.workflow.WorkflowTaskState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkflowDoctorCheckerTest {
    @Test
    fun `skips workflow diagnosis when the document is unavailable`() {
        val result = checker(document = null, workflow = null).check(context())

        assertEquals(DoctorStatus.SKIPPED, result.status)
        assertEquals("document-1", result.evidence["documentId"])
        assertTrue(result.repairSuggestion!!.contains("lifecycle diagnosis"))
    }

    @Test
    fun `warns when a pending document has no local workflow because external approval remains supported`() {
        val result = checker(pendingDocument(), null).check(context())

        assertEquals(DoctorStatus.WARNING, result.status)
        assertEquals(WorkflowDoctorChecker.EXTERNAL_OR_LOCAL_WORKFLOW_MISSING, result.evidence["approvalMode"])
        assertTrue(result.repairSuggestion!!.contains("external approval callback"))
    }

    @Test
    fun `skips when a document outside review has no active local workflow`() {
        val result = checker(draftDocument(), null).check(context())

        assertEquals(DoctorStatus.SKIPPED, result.status)
        assertEquals("DRAFT", result.evidence["lifecycleState"])
    }

    @Test
    fun `reports healthy for a pending document with a coherent local workflow`() {
        val result = checker(pendingDocument(), pendingWorkflow()).check(context())

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals("workflow-1", result.evidence["workflowId"])
        assertEquals("1", result.evidence["pendingTaskCount"])
        assertEquals("0", result.evidence["rejectedTaskCount"])
    }

    @Test
    fun `reports an error for a pending local workflow after the document leaves review`() {
        val result = checker(draftDocument(), pendingWorkflow()).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals("PENDING_REVIEW", result.evidence["expectedLifecycleState"])
        assertTrue(result.reason.contains("not pending review"))
    }

    @Test
    fun `reports an error for corrupted local workflow task invariants`() {
        val workflow = pendingWorkflow()
        replaceTasks(
            workflow,
            listOf(
                WorkflowTask(
                    Identifier("task-1"),
                    Identifier("tenant-2"),
                    workflow.id,
                    Identifier("reviewer-1"),
                ),
            ),
        )

        val result = checker(pendingDocument(), workflow).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals("task-1", result.evidence["invalidTaskId"])
        assertTrue(result.reason.contains("different tenant"))
    }

    @Test
    fun `reports an error when a pending workflow has no pending task`() {
        val workflow = pendingWorkflow()
        replaceTasks(
            workflow,
            listOf(
                WorkflowTask(
                    Identifier("task-1"),
                    Identifier("tenant-1"),
                    workflow.id,
                    Identifier("reviewer-1"),
                    WorkflowTaskState.APPROVED,
                ),
            ),
        )

        val result = checker(pendingDocument(), workflow).check(context())

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals("0", result.evidence["pendingTaskCount"])
        assertTrue(result.reason.contains("no pending review task"))
    }

    @Test
    fun `queries documents and workflows only within the requested tenant`() {
        val documents = RecordingDocumentRepository(pendingDocument())
        val workflows = RecordingWorkflowRepository(pendingWorkflow())
        val checker = WorkflowDoctorChecker(documents, workflows)

        val tenantOneResult = checker.check(context("tenant-1"))
        val tenantTwoResult = checker.check(context("tenant-2"))

        assertEquals(DoctorStatus.HEALTHY, tenantOneResult.status)
        assertEquals(DoctorStatus.SKIPPED, tenantTwoResult.status)
        assertEquals(listOf("tenant-1", "tenant-2"), documents.findTenantIds)
        assertEquals(listOf("tenant-1"), workflows.findTenantIds)
    }

    private fun checker(document: Document?, workflow: WorkflowInstance?): WorkflowDoctorChecker =
        WorkflowDoctorChecker(RecordingDocumentRepository(document), RecordingWorkflowRepository(workflow))

    private fun context(tenantId: String = "tenant-1") = DoctorCheckContext(Identifier(tenantId), Identifier("document-1"))

    private fun pendingDocument(): Document = draftDocument().also { it.transition(LifecycleCommand.SUBMIT) }

    private fun draftDocument(): Document = Document(
        id = Identifier("document-1"),
        tenantId = Identifier("tenant-1"),
        assetId = Identifier("asset-1"),
        documentNumber = "DOC-001",
        title = "Contract",
        versions = listOf(
            DocumentVersion(
                id = Identifier("version-1"),
                tenantId = Identifier("tenant-1"),
                documentId = Identifier("document-1"),
                versionNumber = "1.0",
                fileObjectId = Identifier("file-1"),
            ),
        ),
        currentVersionId = Identifier("version-1"),
    )

    private fun pendingWorkflow(): WorkflowInstance = WorkflowInstance(
        id = Identifier("workflow-1"),
        tenantId = Identifier("tenant-1"),
        documentId = Identifier("document-1"),
        workflowType = "DOCUMENT_REVIEW",
        tasks = listOf(
            WorkflowTask(
                Identifier("task-1"),
                Identifier("tenant-1"),
                Identifier("workflow-1"),
                Identifier("reviewer-1"),
            ),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun replaceTasks(workflow: WorkflowInstance, tasks: List<WorkflowTask>) {
        val field = WorkflowInstance::class.java.getDeclaredField("mutableTasks")
        field.isAccessible = true
        val persistedTasks = field.get(workflow) as MutableList<WorkflowTask>
        persistedTasks.clear()
        persistedTasks.addAll(tasks)
    }

    private class RecordingDocumentRepository(
        private val document: Document?,
    ) : DocumentRepository {
        val findTenantIds = mutableListOf<String>()

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            findTenantIds += tenantId.value
            return document?.takeIf { it.tenantId == tenantId && it.id == documentId }
        }

        override fun save(document: Document) = Unit
    }

    private class RecordingWorkflowRepository(
        private val workflow: WorkflowInstance?,
    ) : WorkflowInstanceRepository {
        val findTenantIds = mutableListOf<String>()

        override fun findById(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? =
            workflow?.takeIf { it.tenantId == tenantId && it.id == workflowId }

        override fun findActiveByDocument(tenantId: Identifier, documentId: Identifier): WorkflowInstance? {
            findTenantIds += tenantId.value
            return workflow?.takeIf { it.tenantId == tenantId && it.documentId == documentId }
        }

        override fun save(workflow: WorkflowInstance) = Unit
    }
}
