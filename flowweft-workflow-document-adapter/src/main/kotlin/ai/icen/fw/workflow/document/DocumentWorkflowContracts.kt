package ai.icen.fw.workflow.document

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommandOptions
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext

private fun workflowCode(value: String, label: String): String = DocumentWorkflowSupport.code(
    value,
    "Document workflow $label code is invalid.",
)

private fun text(value: String, label: String, maximum: Int = DocumentWorkflowSupport.MAX_ID_BYTES): String =
    DocumentWorkflowSupport.text(value, maximum, "Document workflow $label is invalid.")

private fun sha(value: String, label: String): String = DocumentWorkflowSupport.sha256(
    value,
    "Document workflow $label digest is invalid.",
)

private fun nonNegative(value: Long, label: String): Long = DocumentWorkflowSupport.nonNegative(
    value,
    "Document workflow $label must not be negative.",
)

private fun DocumentWorkflowSupport.Digest.subject(value: WorkflowSubjectSnapshot): DocumentWorkflowSupport.Digest =
    text(value.ref.type).text(value.ref.id).text(value.revision).text(value.digest)

private fun DocumentWorkflowSupport.Digest.definition(value: WorkflowDefinitionRef): DocumentWorkflowSupport.Digest =
    text(value.key).text(value.version).text(value.digest)

/** Stable document-adapter action codes; callers may compare by value without enum evolution risk. */
class DocumentWorkflowAction private constructor(value: String) {
    val code: String = workflowCode(value, "action")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowAction && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowAction(<redacted>)"

    companion object {
        @JvmField val SUBMIT = DocumentWorkflowAction("submit")
        @JvmField val REQUEST_SUBJECT_REVISION = DocumentWorkflowAction("request-subject-revision")
        @JvmField val RESUME_SUBJECT_REVISION = DocumentWorkflowAction("resume-subject-revision")

        @JvmStatic fun of(code: String): DocumentWorkflowAction = when (code) {
            SUBMIT.code -> SUBMIT
            REQUEST_SUBJECT_REVISION.code -> REQUEST_SUBJECT_REVISION
            RESUME_SUBJECT_REVISION.code -> RESUME_SUBJECT_REVISION
            else -> DocumentWorkflowAction(code)
        }
    }
}

/** The two return semantics are intentionally non-interchangeable. */
class DocumentWorkflowCorrectionMode private constructor(value: String) {
    val code: String = workflowCode(value, "correction mode")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowCorrectionMode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowCorrectionMode(<redacted>)"

    companion object {
        /** Generic Workflow moves its token; this adapter must not mutate the document. */
        @JvmField val RETURN_WITHOUT_SUBJECT_CHANGE =
            DocumentWorkflowCorrectionMode("return-without-subject-change")

        /** Generic Workflow waits while the host creates an immutable new document revision. */
        @JvmField val REQUEST_SUBJECT_REVISION =
            DocumentWorkflowCorrectionMode("request-subject-revision")

        @JvmStatic fun of(code: String): DocumentWorkflowCorrectionMode = when (code) {
            RETURN_WITHOUT_SUBJECT_CHANGE.code -> RETURN_WITHOUT_SUBJECT_CHANGE
            REQUEST_SUBJECT_REVISION.code -> REQUEST_SUBJECT_REVISION
            else -> DocumentWorkflowCorrectionMode(code)
        }
    }
}

class DocumentWorkflowLifecycle private constructor(value: String) {
    val code: String = workflowCode(value, "lifecycle")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowLifecycle && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowLifecycle(<redacted>)"

    companion object {
        @JvmField val DRAFT = DocumentWorkflowLifecycle("draft")
        @JvmField val PENDING_REVIEW = DocumentWorkflowLifecycle("pending-review")

        @JvmStatic fun of(code: String): DocumentWorkflowLifecycle = when (code) {
            DRAFT.code -> DRAFT
            PENDING_REVIEW.code -> PENDING_REVIEW
            else -> DocumentWorkflowLifecycle(code)
        }
    }
}

class DocumentWorkflowAuthorizationPhase private constructor(value: String) {
    val code: String = workflowCode(value, "authorization phase")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowAuthorizationPhase && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowAuthorizationPhase(<redacted>)"

    companion object {
        @JvmField val PREPARE = DocumentWorkflowAuthorizationPhase("prepare")
        @JvmField val COMMIT = DocumentWorkflowAuthorizationPhase("commit")
    }
}

class DocumentWorkflowAuthorizationStatus private constructor(value: String) {
    val code: String = workflowCode(value, "authorization status")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowAuthorizationStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowAuthorizationStatus(<redacted>)"

    companion object {
        @JvmField val AUTHORIZED = DocumentWorkflowAuthorizationStatus("authorized")
        @JvmField val DENIED = DocumentWorkflowAuthorizationStatus("denied")
    }
}

class DocumentWorkflowBindingState private constructor(value: String) {
    val code: String = workflowCode(value, "binding state")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowBindingState && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowBindingState(<redacted>)"

    companion object {
        @JvmField val RESERVED = DocumentWorkflowBindingState("reserved")
        @JvmField val ACTIVE = DocumentWorkflowBindingState("active")
        @JvmField val WAITING_SUBJECT_REVISION = DocumentWorkflowBindingState("waiting-subject-revision")
        @JvmField val RECONCILIATION_REQUIRED = DocumentWorkflowBindingState("reconciliation-required")
        @JvmField val TERMINAL = DocumentWorkflowBindingState("terminal")
    }
}

class DocumentWorkflowPortOutcome private constructor(value: String) {
    val code: String = workflowCode(value, "port outcome")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowPortOutcome && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowPortOutcome(<redacted>)"

    companion object {
        @JvmField val APPLIED = DocumentWorkflowPortOutcome("applied")
        @JvmField val REPLAYED = DocumentWorkflowPortOutcome("replayed")
        @JvmField val REJECTED = DocumentWorkflowPortOutcome("rejected")
        @JvmField val OUTCOME_UNKNOWN = DocumentWorkflowPortOutcome("outcome-unknown")
    }
}

class DocumentWorkflowResultCode private constructor(value: String) {
    val code: String = workflowCode(value, "result")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowResultCode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowResultCode(<redacted>)"

    companion object {
        @JvmField val STARTED = DocumentWorkflowResultCode("started")
        @JvmField val REPLAYED = DocumentWorkflowResultCode("replayed")
        @JvmField val WAITING_FOR_SUBJECT_REVISION = DocumentWorkflowResultCode("waiting-for-subject-revision")
        @JvmField val SUBJECT_REVISION_RESUMED = DocumentWorkflowResultCode("subject-revision-resumed")
        @JvmField val WORKFLOW_ONLY_REQUIRED = DocumentWorkflowResultCode("workflow-only-required")
        @JvmField val AUTHORIZATION_DENIED = DocumentWorkflowResultCode("authorization-denied")
        @JvmField val SUBJECT_NOT_FOUND = DocumentWorkflowResultCode("subject-not-found")
        @JvmField val SUBJECT_DRIFT = DocumentWorkflowResultCode("subject-drift")
        @JvmField val SELECTION_DRIFT = DocumentWorkflowResultCode("selection-drift")
        @JvmField val ACTIVE_BINDING_CONFLICT = DocumentWorkflowResultCode("active-binding-conflict")
        @JvmField val IDEMPOTENCY_CONFLICT = DocumentWorkflowResultCode("idempotency-conflict")
        @JvmField val BINDING_CONFLICT = DocumentWorkflowResultCode("binding-conflict")
        @JvmField val DOCUMENT_REJECTED = DocumentWorkflowResultCode("document-rejected")
        @JvmField val WORKFLOW_REJECTED = DocumentWorkflowResultCode("workflow-rejected")
        @JvmField val RECONCILIATION_REQUIRED = DocumentWorkflowResultCode("reconciliation-required")
        @JvmField val PORT_UNAVAILABLE = DocumentWorkflowResultCode("port-unavailable")
    }
}

/** Exact revision of a reusable template, never a live pointer such as `latest`. */
class DocumentWorkflowTemplateRef private constructor(
    key: String,
    revision: String,
    digest: String,
) {
    val key: String = text(key, "template key", 256)
    val revision: String = text(revision, "template revision", DocumentWorkflowSupport.MAX_REVISION_BYTES)
    val digest: String = sha(digest, "template")

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DocumentWorkflowTemplateRef &&
            key == other.key && revision == other.revision && digest == other.digest

    override fun hashCode(): Int = (31 * key.hashCode() + revision.hashCode()) * 31 + digest.hashCode()
    override fun toString(): String = "DocumentWorkflowTemplateRef(<redacted>)"

    companion object {
        @JvmStatic fun of(key: String, revision: String, digest: String): DocumentWorkflowTemplateRef =
            DocumentWorkflowTemplateRef(key, revision, digest)
    }
}

/** Pinned policy for a subject revision cycle, including the explicit resume node. */
class DocumentWorkflowRevisionPolicyRef private constructor(
    revision: String,
    digest: String,
    resumeNodeId: String,
) {
    val revision: String = text(revision, "revision policy revision", DocumentWorkflowSupport.MAX_REVISION_BYTES)
    val digest: String = sha(digest, "revision policy")
    val resumeNodeId: String = text(resumeNodeId, "revision resume node")

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DocumentWorkflowRevisionPolicyRef &&
            revision == other.revision && digest == other.digest && resumeNodeId == other.resumeNodeId

    override fun hashCode(): Int =
        ((31 * revision.hashCode()) + digest.hashCode()) * 31 + resumeNodeId.hashCode()

    override fun toString(): String = "DocumentWorkflowRevisionPolicyRef(<redacted>)"

    companion object {
        @JvmStatic fun of(
            revision: String,
            digest: String,
            resumeNodeId: String,
        ): DocumentWorkflowRevisionPolicyRef = DocumentWorkflowRevisionPolicyRef(
            revision,
            digest,
            resumeNodeId,
        )
    }
}

/** Host-selected, fully pinned Workflow definition and template decision. */
class DocumentWorkflowSelection private constructor(
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val templateRef: DocumentWorkflowTemplateRef,
    val revisionPolicy: DocumentWorkflowRevisionPolicyRef,
    authorityRevision: String,
) {
    val definitionId: String = text(definitionId, "definition id")
    val authorityRevision: String = text(
        authorityRevision,
        "selection authority revision",
        DocumentWorkflowSupport.MAX_REVISION_BYTES,
    )
    val selectionDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-selection-v1")
        .text(this.definitionId)
        .definition(definitionRef)
        .text(templateRef.key)
        .text(templateRef.revision)
        .text(templateRef.digest)
        .text(revisionPolicy.revision)
        .text(revisionPolicy.digest)
        .text(revisionPolicy.resumeNodeId)
        .text(this.authorityRevision)
        .finish()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DocumentWorkflowSelection &&
            definitionId == other.definitionId &&
            definitionRef == other.definitionRef &&
            templateRef == other.templateRef &&
            revisionPolicy == other.revisionPolicy &&
            authorityRevision == other.authorityRevision

    override fun hashCode(): Int = selectionDigest.hashCode()
    override fun toString(): String = "DocumentWorkflowSelection(<redacted>)"

    companion object {
        @JvmStatic fun of(
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            templateRef: DocumentWorkflowTemplateRef,
            revisionPolicy: DocumentWorkflowRevisionPolicyRef,
            authorityRevision: String,
        ): DocumentWorkflowSelection = DocumentWorkflowSelection(
            definitionId,
            definitionRef,
            templateRef,
            revisionPolicy,
            authorityRevision,
        )
    }
}

/** Host-authoritative, authorization-filtered document view; it exposes no catalog mutation API. */
class DocumentWorkflowSubjectRecord private constructor(
    tenantId: String,
    val resolvedForActor: WorkflowPrincipalRef,
    val snapshot: WorkflowSubjectSnapshot,
    val lifecycle: DocumentWorkflowLifecycle,
    authorityRevision: String,
    validUntilEpochMilli: Long,
) {
    val tenantId: String = text(tenantId, "subject tenant")
    val authorityRevision: String = text(
        authorityRevision,
        "subject authority revision",
        DocumentWorkflowSupport.MAX_REVISION_BYTES,
    )
    val validUntilEpochMilli: Long = nonNegative(validUntilEpochMilli, "subject expiry")
    val resolutionDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-subject-record-v1")
        .text(this.tenantId)
        .text(resolvedForActor.type)
        .text(resolvedForActor.id)
        .subject(snapshot)
        .text(lifecycle.code)
        .text(this.authorityRevision)
        .longValue(this.validUntilEpochMilli)
        .finish()

    fun matches(request: DocumentWorkflowSubjectResolveRequest): Boolean =
        tenantId == request.callContext.tenantId &&
            resolvedForActor == request.callContext.actor &&
            snapshot.ref == request.subject &&
            (request.expectedSnapshot == null || snapshot == request.expectedSnapshot) &&
            validUntilEpochMilli >= request.evaluatedAtEpochMilli

    override fun toString(): String = "DocumentWorkflowSubjectRecord(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            resolvedForActor: WorkflowPrincipalRef,
            snapshot: WorkflowSubjectSnapshot,
            lifecycle: DocumentWorkflowLifecycle,
            authorityRevision: String,
            validUntilEpochMilli: Long,
        ): DocumentWorkflowSubjectRecord = DocumentWorkflowSubjectRecord(
            tenantId,
            resolvedForActor,
            snapshot,
            lifecycle,
            authorityRevision,
            validUntilEpochMilli,
        )
    }
}

class DocumentWorkflowSubjectResolveRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val action: DocumentWorkflowAction,
    val subject: WorkflowSubjectRef,
    val expectedSnapshot: WorkflowSubjectSnapshot?,
    purposeDigest: String,
    evaluatedAtEpochMilli: Long,
) {
    val purposeDigest: String = sha(purposeDigest, "subject resolution purpose")
    val evaluatedAtEpochMilli: Long = nonNegative(evaluatedAtEpochMilli, "subject resolution time")
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-subject-request-v1")
        .text(callContext.contextDigest)
        .text(action.code)
        .text(subject.type)
        .text(subject.id)
        .optional(expectedSnapshot?.revision)
        .optional(expectedSnapshot?.digest)
        .text(this.purposeDigest)
        .longValue(this.evaluatedAtEpochMilli)
        .finish()

    init {
        require(expectedSnapshot == null || expectedSnapshot.ref == subject) {
            "Document workflow expected snapshot targets another subject."
        }
    }

    override fun toString(): String = "DocumentWorkflowSubjectResolveRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            action: DocumentWorkflowAction,
            subject: WorkflowSubjectRef,
            expectedSnapshot: WorkflowSubjectSnapshot?,
            purposeDigest: String,
            evaluatedAtEpochMilli: Long,
        ): DocumentWorkflowSubjectResolveRequest = DocumentWorkflowSubjectResolveRequest(
            callContext,
            action,
            subject,
            expectedSnapshot,
            purposeDigest,
            evaluatedAtEpochMilli,
        )
    }
}

class DocumentWorkflowSelectionRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val action: DocumentWorkflowAction,
    val subjectRecord: DocumentWorkflowSubjectRecord,
    val expectedSelection: DocumentWorkflowSelection,
    purposeDigest: String,
    evaluatedAtEpochMilli: Long,
) {
    val purposeDigest: String = sha(purposeDigest, "selection purpose")
    val evaluatedAtEpochMilli: Long = nonNegative(evaluatedAtEpochMilli, "selection time")
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-selection-request-v1")
        .text(callContext.contextDigest)
        .text(action.code)
        .text(subjectRecord.resolutionDigest)
        .text(expectedSelection.selectionDigest)
        .text(this.purposeDigest)
        .longValue(this.evaluatedAtEpochMilli)
        .finish()

    init {
        require(subjectRecord.tenantId == callContext.tenantId &&
            subjectRecord.resolvedForActor == callContext.actor &&
            subjectRecord.validUntilEpochMilli >= this.evaluatedAtEpochMilli
        ) { "Document workflow selection requires a current subject record for the caller." }
    }

    override fun toString(): String = "DocumentWorkflowSelectionRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            action: DocumentWorkflowAction,
            subjectRecord: DocumentWorkflowSubjectRecord,
            expectedSelection: DocumentWorkflowSelection,
            purposeDigest: String,
            evaluatedAtEpochMilli: Long,
        ): DocumentWorkflowSelectionRequest = DocumentWorkflowSelectionRequest(
            callContext,
            action,
            subjectRecord,
            expectedSelection,
            purposeDigest,
            evaluatedAtEpochMilli,
        )
    }
}

/** Exact, caller-expected start request. Tenant and actor come only from [callContext]. */
class DocumentWorkflowSubmissionRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    instanceId: String,
    val expectedSubject: WorkflowSubjectSnapshot,
    val expectedSelection: DocumentWorkflowSelection,
    purposeDigest: String,
) {
    val action: DocumentWorkflowAction = DocumentWorkflowAction.SUBMIT
    val instanceId: String = text(instanceId, "submission instance id")
    val purposeDigest: String = sha(purposeDigest, "submission purpose")
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-submit-request-v1")
        .text(callContext.contextDigest)
        .text(this.instanceId)
        .text(options.commandId)
        .text(options.idempotencyKey)
        .longValue(options.expectedInstanceVersion)
        .longValue(options.now)
        .integer(options.iterationBudget)
        .text(options.ids.contentDigest)
        .subject(expectedSubject)
        .text(expectedSelection.selectionDigest)
        .text(this.purposeDigest)
        .finish()

    init {
        require(options.expectedInstanceVersion == 0L) { "Document workflow submission starts at version zero." }
        require(expectedSubject.ref.type == DOCUMENT_SUBJECT_TYPE) {
            "Document workflow submission requires the document subject type."
        }
    }

    override fun toString(): String = "DocumentWorkflowSubmissionRequest(<redacted>)"

    companion object {
        const val DOCUMENT_SUBJECT_TYPE: String = "document"

        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            expectedSubject: WorkflowSubjectSnapshot,
            expectedSelection: DocumentWorkflowSelection,
            purposeDigest: String,
        ): DocumentWorkflowSubmissionRequest = DocumentWorkflowSubmissionRequest(
            callContext,
            options,
            instanceId,
            expectedSubject,
            expectedSelection,
            purposeDigest,
        )
    }
}

class DocumentWorkflowCorrectionRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    instanceId: String,
    expectedBindingRevision: Long,
    expectedCycleNumber: Long,
    val expectedSubject: WorkflowSubjectSnapshot,
    val expectedSelection: DocumentWorkflowSelection,
    val mode: DocumentWorkflowCorrectionMode,
    reasonDigest: String,
    purposeDigest: String,
) {
    val action: DocumentWorkflowAction = DocumentWorkflowAction.REQUEST_SUBJECT_REVISION
    val instanceId: String = text(instanceId, "correction instance id")
    val expectedBindingRevision: Long = nonNegative(expectedBindingRevision, "expected binding revision")
    val expectedCycleNumber: Long = nonNegative(expectedCycleNumber, "expected cycle number")
    val reasonDigest: String = sha(reasonDigest, "correction reason")
    val purposeDigest: String = sha(purposeDigest, "correction purpose")
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-correction-request-v1")
        .text(callContext.contextDigest)
        .text(options.commandId)
        .text(options.idempotencyKey)
        .longValue(options.expectedInstanceVersion)
        .longValue(options.now)
        .text(this.instanceId)
        .longValue(this.expectedBindingRevision)
        .longValue(this.expectedCycleNumber)
        .subject(expectedSubject)
        .text(expectedSelection.selectionDigest)
        .text(mode.code)
        .text(this.reasonDigest)
        .text(this.purposeDigest)
        .finish()

    init {
        require(options.expectedInstanceVersion > 0L && this.expectedBindingRevision > 0L) {
            "Document workflow correction requires observed workflow and binding versions."
        }
        require(expectedSubject.ref.type == DocumentWorkflowSubmissionRequest.DOCUMENT_SUBJECT_TYPE) {
            "Document workflow correction requires the document subject type."
        }
    }

    override fun toString(): String = "DocumentWorkflowCorrectionRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            expectedBindingRevision: Long,
            expectedCycleNumber: Long,
            expectedSubject: WorkflowSubjectSnapshot,
            expectedSelection: DocumentWorkflowSelection,
            mode: DocumentWorkflowCorrectionMode,
            reasonDigest: String,
            purposeDigest: String,
        ): DocumentWorkflowCorrectionRequest = DocumentWorkflowCorrectionRequest(
            callContext,
            options,
            instanceId,
            expectedBindingRevision,
            expectedCycleNumber,
            expectedSubject,
            expectedSelection,
            mode,
            reasonDigest,
            purposeDigest,
        )
    }
}

class DocumentWorkflowResumeRevisionRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    instanceId: String,
    expectedBindingRevision: Long,
    cycleNumber: Long,
    val previousSubject: WorkflowSubjectSnapshot,
    val replacementSubject: WorkflowSubjectSnapshot,
    val expectedSelection: DocumentWorkflowSelection,
    reasonDigest: String,
    purposeDigest: String,
) {
    val action: DocumentWorkflowAction = DocumentWorkflowAction.RESUME_SUBJECT_REVISION
    val instanceId: String = text(instanceId, "revision resume instance id")
    val expectedBindingRevision: Long = nonNegative(expectedBindingRevision, "expected binding revision")
    val cycleNumber: Long = nonNegative(cycleNumber, "revision cycle number")
    val reasonDigest: String = sha(reasonDigest, "revision resume reason")
    val purposeDigest: String = sha(purposeDigest, "revision resume purpose")
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-resume-request-v1")
        .text(callContext.contextDigest)
        .text(options.commandId)
        .text(options.idempotencyKey)
        .longValue(options.expectedInstanceVersion)
        .longValue(options.now)
        .text(this.instanceId)
        .longValue(this.expectedBindingRevision)
        .longValue(this.cycleNumber)
        .subject(previousSubject)
        .subject(replacementSubject)
        .text(expectedSelection.selectionDigest)
        .text(this.reasonDigest)
        .text(this.purposeDigest)
        .finish()

    init {
        require(options.expectedInstanceVersion > 0L && this.expectedBindingRevision > 0L && this.cycleNumber > 0L) {
            "Document workflow revision resume requires observed versions and a positive cycle number."
        }
        require(previousSubject.ref.type == DocumentWorkflowSubmissionRequest.DOCUMENT_SUBJECT_TYPE &&
            replacementSubject.ref == previousSubject.ref
        ) { "Document workflow revision replacement targets another subject." }
        require(previousSubject.revision != replacementSubject.revision ||
            previousSubject.digest != replacementSubject.digest
        ) { "Document workflow revision must bind an immutable new subject revision or digest." }
    }

    override fun toString(): String = "DocumentWorkflowResumeRevisionRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            expectedBindingRevision: Long,
            cycleNumber: Long,
            previousSubject: WorkflowSubjectSnapshot,
            replacementSubject: WorkflowSubjectSnapshot,
            expectedSelection: DocumentWorkflowSelection,
            reasonDigest: String,
            purposeDigest: String,
        ): DocumentWorkflowResumeRevisionRequest = DocumentWorkflowResumeRevisionRequest(
            callContext,
            options,
            instanceId,
            expectedBindingRevision,
            cycleNumber,
            previousSubject,
            replacementSubject,
            expectedSelection,
            reasonDigest,
            purposeDigest,
        )
    }
}

class DocumentWorkflowResult private constructor(
    val code: DocumentWorkflowResultCode,
    instanceId: String,
    val subject: WorkflowSubjectSnapshot?,
    cycleNumber: Long?,
    bindingRevision: Long?,
    failureCode: String?,
) {
    val instanceId: String = text(instanceId, "result instance id")
    val cycleNumber: Long? = cycleNumber?.let { nonNegative(it, "result cycle number") }
    val bindingRevision: Long? = bindingRevision?.let { nonNegative(it, "result binding revision") }
    val failureCode: String? = failureCode?.let { workflowCode(it, "failure") }

    override fun toString(): String = "DocumentWorkflowResult(<redacted>)"

    companion object {
        @JvmStatic fun of(
            code: DocumentWorkflowResultCode,
            instanceId: String,
            subject: WorkflowSubjectSnapshot?,
            cycleNumber: Long?,
            bindingRevision: Long?,
            failureCode: String?,
        ): DocumentWorkflowResult = DocumentWorkflowResult(
            code,
            instanceId,
            subject,
            cycleNumber,
            bindingRevision,
            failureCode,
        )
    }
}
