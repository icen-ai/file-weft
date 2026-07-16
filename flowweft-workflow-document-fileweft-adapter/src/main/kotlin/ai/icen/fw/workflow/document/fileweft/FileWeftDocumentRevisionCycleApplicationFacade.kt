package ai.icen.fw.workflow.document.fileweft

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.document.DocumentWorkflowPortOutcome

/**
 * The smallest missing FileWeft application use case required by a generic-workflow correction.
 *
 * Implementations are application facades, not repositories. They must derive the same current
 * tenant and user from the host security context, re-authorize `document:revise`, bind the exact
 * idempotency key and command digest durably, and change `PENDING_REVIEW` to `DRAFT` while retaining
 * the old immutable version/digest as evidence. They must not create, cancel, or mutate a legacy
 * `DocumentReviewWorkflow`, and must never overwrite the retained version.
 *
 * FileWeft's current public application API does not provide this atomic operation. The built-in
 * bridge therefore reports [UNSUPPORTED_FAILURE_CODE] until a host supplies this explicit facade;
 * it never composes `reject` plus `revise` and never accesses a repository directly.
 */
fun interface FileWeftDocumentRevisionCycleApplicationFacade {
    fun openRevisionDraft(
        command: FileWeftOpenDocumentRevisionDraftCommand,
    ): FileWeftDocumentRevisionCycleResult

    companion object {
        const val UNSUPPORTED_FAILURE_CODE: String = "revision-cycle-unsupported"

        @JvmStatic
        fun unsupported(): FileWeftDocumentRevisionCycleApplicationFacade =
            FileWeftDocumentRevisionCycleApplicationFacade {
                FileWeftDocumentRevisionCycleResult.failure(
                    DocumentWorkflowPortOutcome.REJECTED,
                    UNSUPPORTED_FAILURE_CODE,
                )
            }
    }
}

/** Exact trusted command passed to a host application facade. */
class FileWeftOpenDocumentRevisionDraftCommand private constructor(
    val tenantId: Identifier,
    val actorId: Identifier,
    val documentId: Identifier,
    val retainedSubject: WorkflowSubjectSnapshot,
    workflowInstanceId: String,
    cycleNumber: Long,
    idempotencyKey: String,
    logicalRequestDigest: String,
    authorizationDecisionDigest: String,
    reasonDigest: String,
    selectionDigest: String,
    requestedAtEpochMilli: Long,
) {
    val workflowInstanceId: String = FileWeftDocumentWorkflowSupport.text(
        workflowInstanceId,
        "Workflow instance id",
    )
    val cycleNumber: Long = cycleNumber.also {
        require(it > 0L) { "Document revision cycle number must be positive." }
    }
    val idempotencyKey: String = FileWeftDocumentWorkflowSupport.text(
        idempotencyKey,
        "Document revision idempotency key",
        128,
    )
    val logicalRequestDigest: String = FileWeftDocumentWorkflowSupport.digest(
        logicalRequestDigest,
        "Document revision logical request digest",
    )
    val authorizationDecisionDigest: String = FileWeftDocumentWorkflowSupport.digest(
        authorizationDecisionDigest,
        "Document revision authorization decision digest",
    )
    val reasonDigest: String = FileWeftDocumentWorkflowSupport.digest(
        reasonDigest,
        "Document revision reason digest",
    )
    val selectionDigest: String = FileWeftDocumentWorkflowSupport.digest(
        selectionDigest,
        "Document revision selection digest",
    )
    val requestedAtEpochMilli: Long = requestedAtEpochMilli.also {
        require(it >= 0L) { "Document revision request time must not be negative." }
    }
    val commandDigest: String = FileWeftDocumentWorkflowSupport.sha256(
        "flowweft-document-fileweft-revision-command-v1",
        tenantId.value,
        actorId.value,
        documentId.value,
        retainedSubject.ref.type,
        retainedSubject.ref.id,
        retainedSubject.revision,
        retainedSubject.digest,
        this.workflowInstanceId,
        this.cycleNumber.toString(),
        this.idempotencyKey,
        this.logicalRequestDigest,
        this.authorizationDecisionDigest,
        this.reasonDigest,
        this.selectionDigest,
        this.requestedAtEpochMilli.toString(),
    )

    init {
        require(retainedSubject.ref.type == "document" && retainedSubject.ref.id == documentId.value) {
            "Retained subject does not identify the requested FileWeft document."
        }
    }

    override fun toString(): String = "FileWeftOpenDocumentRevisionDraftCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: Identifier,
            actorId: Identifier,
            documentId: Identifier,
            retainedSubject: WorkflowSubjectSnapshot,
            workflowInstanceId: String,
            cycleNumber: Long,
            idempotencyKey: String,
            logicalRequestDigest: String,
            authorizationDecisionDigest: String,
            reasonDigest: String,
            selectionDigest: String,
            requestedAtEpochMilli: Long,
        ): FileWeftOpenDocumentRevisionDraftCommand = FileWeftOpenDocumentRevisionDraftCommand(
            tenantId,
            actorId,
            documentId,
            retainedSubject,
            workflowInstanceId,
            cycleNumber,
            idempotencyKey,
            logicalRequestDigest,
            authorizationDecisionDigest,
            reasonDigest,
            selectionDigest,
            requestedAtEpochMilli,
        )
    }
}

/** A bounded receipt; successful facades must echo the exact retained immutable subject. */
class FileWeftDocumentRevisionCycleResult private constructor(
    val outcome: DocumentWorkflowPortOutcome,
    val retainedSubject: WorkflowSubjectSnapshot?,
    receiptDigest: String?,
    failureCode: String?,
) {
    val receiptDigest: String? = receiptDigest?.let {
        FileWeftDocumentWorkflowSupport.digest(it, "Document revision receipt digest")
    }
    val failureCode: String? = failureCode?.let {
        FileWeftDocumentWorkflowSupport.code(it, "Document revision failure code")
    }

    init {
        val success = outcome == DocumentWorkflowPortOutcome.APPLIED ||
            outcome == DocumentWorkflowPortOutcome.REPLAYED
        require(success == (retainedSubject != null && this.receiptDigest != null)) {
            "Document revision receipt shape is invalid."
        }
        require(success || this.failureCode != null) {
            "Document revision failure requires a stable code."
        }
    }

    override fun toString(): String = "FileWeftDocumentRevisionCycleResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            outcome: DocumentWorkflowPortOutcome,
            retainedSubject: WorkflowSubjectSnapshot,
            receiptDigest: String,
        ): FileWeftDocumentRevisionCycleResult = FileWeftDocumentRevisionCycleResult(
            outcome,
            retainedSubject,
            receiptDigest,
            null,
        )

        @JvmStatic
        fun failure(
            outcome: DocumentWorkflowPortOutcome,
            failureCode: String,
        ): FileWeftDocumentRevisionCycleResult = FileWeftDocumentRevisionCycleResult(
            outcome,
            null,
            null,
            failureCode,
        )
    }
}
