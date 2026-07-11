package com.fileweft.application.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorStatus
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTaskState
import com.fileweft.spi.doctor.DoctorChecker

/**
 * Verifies the local review-workflow projection for one document tenant.
 *
 * This checker reads only FileWeft repositories. The starter wraps it in a
 * short transaction; it deliberately does not resolve a host workflow or any
 * other external SPI. A pending document without a local workflow is a warning
 * rather than corruption because an integrator can use the external approval
 * compatibility path.
 */
class WorkflowDoctorChecker(
    private val documents: DocumentRepository,
    private val workflows: WorkflowInstanceRepository,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val requestedDocumentId = context.documentId ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.SKIPPED,
            "Workflow diagnosis needs a document identifier.",
            repairSuggestion = "Run this checker as part of document diagnosis.",
        )
        val document = documents.findById(context.tenantId, requestedDocumentId) ?: return DoctorCheckResult(
            NAME,
            DoctorStatus.SKIPPED,
            "Workflow diagnosis was skipped because the document is unavailable.",
            evidence = mapOf("documentId" to requestedDocumentId.value),
            repairSuggestion = "Resolve the lifecycle diagnosis before checking the document review workflow.",
        )
        val documentEvidence = documentEvidence(requestedDocumentId.value, document)
        if (document.tenantId != context.tenantId) {
            return inconsistent(
                "Document repository returned a document from a different tenant.",
                documentEvidence + ("expectedTenantId" to context.tenantId.value),
            )
        }
        if (document.id != requestedDocumentId) {
            return inconsistent(
                "Document repository returned a document with a different identifier.",
                documentEvidence + ("requestedDocumentId" to requestedDocumentId.value),
            )
        }

        val workflow = workflows.findActiveByDocument(context.tenantId, document.id)
        if (workflow == null) {
            return if (document.lifecycleState == LifecycleState.PENDING_REVIEW) {
                DoctorCheckResult(
                    NAME,
                    DoctorStatus.WARNING,
                    "Document is pending review but has no active local workflow.",
                    documentEvidence + ("approvalMode" to EXTERNAL_OR_LOCAL_WORKFLOW_MISSING),
                    "Verify whether this document is awaiting an external approval callback. If local review is required, create or restore its workflow through a controlled recovery operation.",
                )
            } else {
                DoctorCheckResult(
                    NAME,
                    DoctorStatus.SKIPPED,
                    "Document is not pending review and has no active local workflow.",
                    documentEvidence,
                    "No local workflow repair is needed unless the document should enter review.",
                )
            }
        }

        val workflowEvidence = workflowEvidence(documentEvidence, workflow)
        validateWorkflow(context, document, workflow, workflowEvidence)?.let { return it }
        if (document.lifecycleState != LifecycleState.PENDING_REVIEW) {
            return inconsistent(
                "An active local workflow remains although the document is not pending review.",
                workflowEvidence + ("expectedLifecycleState" to LifecycleState.PENDING_REVIEW.name),
                "Complete or recover the residual local workflow, or restore the document lifecycle through a controlled operation.",
            )
        }
        return DoctorCheckResult(
            NAME,
            DoctorStatus.HEALTHY,
            "Pending document review has a coherent active local workflow.",
            workflowEvidence,
        )
    }

    private fun validateWorkflow(
        context: DoctorCheckContext,
        document: Document,
        workflow: WorkflowInstance,
        evidence: Map<String, String>,
    ): DoctorCheckResult? {
        if (workflow.tenantId != context.tenantId) {
            return inconsistent(
                "Active workflow belongs to a different tenant.",
                evidence + ("expectedTenantId" to context.tenantId.value),
            )
        }
        if (workflow.documentId != document.id) {
            return inconsistent(
                "Active workflow belongs to a different document.",
                evidence + ("expectedDocumentId" to document.id.value),
            )
        }
        if (workflow.state != WorkflowState.PENDING) {
            return inconsistent(
                "Active workflow repository returned a workflow that is not pending.",
                evidence + ("expectedWorkflowState" to WorkflowState.PENDING.name),
            )
        }
        if (workflow.tasks.isEmpty()) {
            return inconsistent(
                "Pending workflow has no review tasks.",
                evidence,
            )
        }
        if (workflow.tasks.map { it.id }.distinct().size != workflow.tasks.size) {
            return inconsistent(
                "Pending workflow contains duplicate task identifiers.",
                evidence,
            )
        }
        val taskFromDifferentTenant = workflow.tasks.firstOrNull { it.tenantId != workflow.tenantId }
        if (taskFromDifferentTenant != null) {
            return inconsistent(
                "Workflow task belongs to a different tenant than its workflow.",
                evidence + ("invalidTaskId" to taskFromDifferentTenant.id.value),
            )
        }
        val taskFromDifferentWorkflow = workflow.tasks.firstOrNull { it.workflowId != workflow.id }
        if (taskFromDifferentWorkflow != null) {
            return inconsistent(
                "Workflow task belongs to a different workflow instance.",
                evidence + ("invalidTaskId" to taskFromDifferentWorkflow.id.value),
            )
        }
        if (workflow.tasks.any { it.state == WorkflowTaskState.REJECTED }) {
            return inconsistent(
                "Pending workflow contains a rejected task.",
                evidence,
            )
        }
        if (workflow.tasks.none { it.state == WorkflowTaskState.PENDING }) {
            return inconsistent(
                "Pending workflow has no pending review task.",
                evidence,
            )
        }
        return null
    }

    private fun inconsistent(
        reason: String,
        evidence: Map<String, String>,
        repairSuggestion: String = "Repair the local workflow records through a controlled data migration, then run diagnosis again.",
    ): DoctorCheckResult = DoctorCheckResult(NAME, DoctorStatus.ERROR, reason, evidence, repairSuggestion)

    private fun documentEvidence(requestedDocumentId: String, document: Document): Map<String, String> = linkedMapOf(
        "requestedDocumentId" to requestedDocumentId,
        "documentId" to document.id.value,
        "tenantId" to document.tenantId.value,
        "lifecycleState" to document.lifecycleState.name,
    )

    private fun workflowEvidence(documentEvidence: Map<String, String>, workflow: WorkflowInstance): Map<String, String> =
        LinkedHashMap(documentEvidence).also { evidence ->
            evidence["workflowId"] = workflow.id.value
            evidence["workflowType"] = workflow.workflowType
            evidence["workflowTenantId"] = workflow.tenantId.value
            evidence["workflowDocumentId"] = workflow.documentId.value
            evidence["workflowState"] = workflow.state.name
            evidence["taskCount"] = workflow.tasks.size.toString()
            evidence["pendingTaskCount"] = workflow.tasks.count { it.state == WorkflowTaskState.PENDING }.toString()
            evidence["approvedTaskCount"] = workflow.tasks.count { it.state == WorkflowTaskState.APPROVED }.toString()
            evidence["rejectedTaskCount"] = workflow.tasks.count { it.state == WorkflowTaskState.REJECTED }.toString()
        }

    companion object {
        const val NAME = "workflow"
        const val EXTERNAL_OR_LOCAL_WORKFLOW_MISSING = "EXTERNAL_OR_LOCAL_WORKFLOW_MISSING"
    }
}
