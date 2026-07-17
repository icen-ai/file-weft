package ai.icen.fw.workflow.document

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommandOptions
import ai.icen.fw.workflow.runtime.WorkflowRuntimeStartRequest
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext

private fun portText(value: String, label: String, maximum: Int = DocumentWorkflowSupport.MAX_ID_BYTES): String =
    DocumentWorkflowSupport.text(value, maximum, "Document workflow $label is invalid.")

private fun portCode(value: String, label: String): String =
    DocumentWorkflowSupport.code(value, "Document workflow $label code is invalid.")

private fun portSha(value: String, label: String): String =
    DocumentWorkflowSupport.sha256(value, "Document workflow $label digest is invalid.")

private fun portVersion(value: Long, label: String): Long =
    DocumentWorkflowSupport.nonNegative(value, "Document workflow $label must not be negative.")

private fun DocumentWorkflowSupport.Digest.portSubject(value: WorkflowSubjectSnapshot): DocumentWorkflowSupport.Digest =
    text(value.ref.type).text(value.ref.id).text(value.revision).text(value.digest)

/** Read-only host application boundary. Implementations perform tenant, ACL and lifecycle checks. */
fun interface DocumentWorkflowSubjectApplicationPort {
    fun resolve(request: DocumentWorkflowSubjectResolveRequest): DocumentWorkflowSubjectRecord?
}

/** Host policy boundary for definition/template selection using authoritative document facts. */
fun interface DocumentWorkflowSelectionApplicationPort {
    fun select(request: DocumentWorkflowSelectionRequest): DocumentWorkflowSelection?
}

class DocumentWorkflowAuthorizationRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val phase: DocumentWorkflowAuthorizationPhase,
    val action: DocumentWorkflowAction,
    instanceId: String,
    val subject: WorkflowSubjectSnapshot,
    val selection: DocumentWorkflowSelection,
    logicalRequestDigest: String,
    purposeDigest: String,
    expectedInstanceVersion: Long,
    expectedBindingRevision: Long,
    cycleNumber: Long,
    evaluatedAtEpochMilli: Long,
) {
    val instanceId: String = portText(instanceId, "authorization instance id")
    val logicalRequestDigest: String = portSha(logicalRequestDigest, "authorization logical request")
    val purposeDigest: String = portSha(purposeDigest, "authorization purpose")
    val expectedInstanceVersion: Long = portVersion(expectedInstanceVersion, "authorization instance version")
    val expectedBindingRevision: Long = portVersion(expectedBindingRevision, "authorization binding revision")
    val cycleNumber: Long = portVersion(cycleNumber, "authorization cycle number")
    val evaluatedAtEpochMilli: Long = portVersion(evaluatedAtEpochMilli, "authorization time")
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-authorization-request-v1")
        .text(callContext.contextDigest)
        .text(phase.code)
        .text(action.code)
        .text(this.instanceId)
        .portSubject(subject)
        .text(selection.selectionDigest)
        .text(this.logicalRequestDigest)
        .text(this.purposeDigest)
        .longValue(this.expectedInstanceVersion)
        .longValue(this.expectedBindingRevision)
        .longValue(this.cycleNumber)
        .longValue(this.evaluatedAtEpochMilli)
        .finish()

    override fun toString(): String = "DocumentWorkflowAuthorizationRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            phase: DocumentWorkflowAuthorizationPhase,
            action: DocumentWorkflowAction,
            instanceId: String,
            subject: WorkflowSubjectSnapshot,
            selection: DocumentWorkflowSelection,
            logicalRequestDigest: String,
            purposeDigest: String,
            expectedInstanceVersion: Long,
            expectedBindingRevision: Long,
            cycleNumber: Long,
            evaluatedAtEpochMilli: Long,
        ): DocumentWorkflowAuthorizationRequest = DocumentWorkflowAuthorizationRequest(
            callContext,
            phase,
            action,
            instanceId,
            subject,
            selection,
            logicalRequestDigest,
            purposeDigest,
            expectedInstanceVersion,
            expectedBindingRevision,
            cycleNumber,
            evaluatedAtEpochMilli,
        )
    }
}

/** Short-lived decision bound to one exact phase and command; it is not a reusable bearer token. */
class DocumentWorkflowAuthorizationDecision private constructor(
    authorizationId: String,
    tenantId: String,
    val actor: WorkflowPrincipalRef,
    requestDigest: String,
    val status: DocumentWorkflowAuthorizationStatus,
    authorityRevision: String,
    authorityDigest: String,
    evaluatedAtEpochMilli: Long,
    validUntilEpochMilli: Long,
) {
    val authorizationId: String = portText(authorizationId, "authorization id")
    val tenantId: String = portText(tenantId, "authorization tenant")
    val requestDigest: String = portSha(requestDigest, "authorization request")
    val authorityRevision: String = portText(
        authorityRevision,
        "authorization authority revision",
        DocumentWorkflowSupport.MAX_REVISION_BYTES,
    )
    val authorityDigest: String = portSha(authorityDigest, "authorization authority")
    val evaluatedAtEpochMilli: Long = portVersion(evaluatedAtEpochMilli, "authorization evaluation time")
    val validUntilEpochMilli: Long = portVersion(validUntilEpochMilli, "authorization expiry")
    val decisionDigest: String

    init {
        require(status == DocumentWorkflowAuthorizationStatus.AUTHORIZED ||
            status == DocumentWorkflowAuthorizationStatus.DENIED
        ) { "Unknown document workflow authorization status is unsupported." }
        require(this.validUntilEpochMilli >= this.evaluatedAtEpochMilli) {
            "Document workflow authorization window is invalid."
        }
        decisionDigest = DocumentWorkflowSupport.digest("flowweft-workflow-document-authorization-decision-v1")
            .text(this.authorizationId)
            .text(this.tenantId)
            .text(actor.type)
            .text(actor.id)
            .text(this.requestDigest)
            .text(status.code)
            .text(this.authorityRevision)
            .text(this.authorityDigest)
            .longValue(this.evaluatedAtEpochMilli)
            .longValue(this.validUntilEpochMilli)
            .finish()
    }

    fun matches(request: DocumentWorkflowAuthorizationRequest, nowEpochMilli: Long): Boolean =
        tenantId == request.callContext.tenantId &&
            actor == request.callContext.actor &&
            requestDigest == request.requestDigest &&
            evaluatedAtEpochMilli <= nowEpochMilli && nowEpochMilli <= validUntilEpochMilli

    override fun toString(): String = "DocumentWorkflowAuthorizationDecision(<redacted>)"

    companion object {
        @JvmStatic fun of(
            authorizationId: String,
            tenantId: String,
            actor: WorkflowPrincipalRef,
            requestDigest: String,
            status: DocumentWorkflowAuthorizationStatus,
            authorityRevision: String,
            authorityDigest: String,
            evaluatedAtEpochMilli: Long,
            validUntilEpochMilli: Long,
        ): DocumentWorkflowAuthorizationDecision = DocumentWorkflowAuthorizationDecision(
            authorizationId,
            tenantId,
            actor,
            requestDigest,
            status,
            authorityRevision,
            authorityDigest,
            evaluatedAtEpochMilli,
            validUntilEpochMilli,
        )
    }
}

fun interface DocumentWorkflowAuthorizationApplicationPort {
    /** Implementations re-evaluate current authority for every PREPARE and COMMIT request. */
    fun authorize(request: DocumentWorkflowAuthorizationRequest): DocumentWorkflowAuthorizationDecision
}

/** Durable uniqueness record. A RESERVED or reconciliation record still blocks another active flow. */
class DocumentWorkflowBinding private constructor(
    tenantId: String,
    val document: WorkflowSubjectRef,
    instanceId: String,
    val state: DocumentWorkflowBindingState,
    val subject: WorkflowSubjectSnapshot,
    val selection: DocumentWorkflowSelection,
    startIdempotencyKey: String,
    startRequestDigest: String,
    val lastAction: DocumentWorkflowAction,
    lastOperationIdempotencyKey: String,
    lastOperationRequestDigest: String,
    cycleNumber: Long,
    revision: Long,
) {
    val tenantId: String = portText(tenantId, "binding tenant")
    val instanceId: String = portText(instanceId, "binding instance id")
    val startIdempotencyKey: String = portText(startIdempotencyKey, "binding start idempotency key")
    val startRequestDigest: String = portSha(startRequestDigest, "binding start request")
    val lastOperationIdempotencyKey: String = portText(
        lastOperationIdempotencyKey,
        "binding last operation idempotency key",
    )
    val lastOperationRequestDigest: String = portSha(
        lastOperationRequestDigest,
        "binding last operation request",
    )
    val cycleNumber: Long = portVersion(cycleNumber, "binding cycle number")
    val revision: Long = portVersion(revision, "binding revision")
    val bindingDigest: String

    init {
        require(document.type == DocumentWorkflowSubmissionRequest.DOCUMENT_SUBJECT_TYPE &&
            subject.ref == document && this.revision > 0L
        ) { "Document workflow binding is invalid." }
        bindingDigest = DocumentWorkflowSupport.digest("flowweft-workflow-document-binding-v1")
            .text(this.tenantId)
            .text(document.type)
            .text(document.id)
            .text(this.instanceId)
            .text(state.code)
            .portSubject(subject)
            .text(selection.selectionDigest)
            .text(this.startIdempotencyKey)
            .text(this.startRequestDigest)
            .text(lastAction.code)
            .text(this.lastOperationIdempotencyKey)
            .text(this.lastOperationRequestDigest)
            .longValue(this.cycleNumber)
            .longValue(this.revision)
            .finish()
    }

    fun matches(request: DocumentWorkflowSubmissionRequest): Boolean =
        tenantId == request.callContext.tenantId &&
            document == request.expectedSubject.ref &&
            instanceId == request.instanceId &&
            subject == request.expectedSubject &&
            selection == request.expectedSelection &&
            startIdempotencyKey == request.options.idempotencyKey &&
            startRequestDigest == request.requestDigest &&
            lastAction == request.action &&
            lastOperationIdempotencyKey == request.options.idempotencyKey &&
            lastOperationRequestDigest == request.requestDigest

    override fun toString(): String = "DocumentWorkflowBinding(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            document: WorkflowSubjectRef,
            instanceId: String,
            state: DocumentWorkflowBindingState,
            subject: WorkflowSubjectSnapshot,
            selection: DocumentWorkflowSelection,
            startIdempotencyKey: String,
            startRequestDigest: String,
            lastAction: DocumentWorkflowAction,
            lastOperationIdempotencyKey: String,
            lastOperationRequestDigest: String,
            cycleNumber: Long,
            revision: Long,
        ): DocumentWorkflowBinding = DocumentWorkflowBinding(
            tenantId,
            document,
            instanceId,
            state,
            subject,
            selection,
            startIdempotencyKey,
            startRequestDigest,
            lastAction,
            lastOperationIdempotencyKey,
            lastOperationRequestDigest,
            cycleNumber,
            revision,
        )
    }
}

class DocumentWorkflowBindingReservationCode private constructor(value: String) {
    val code: String = portCode(value, "binding reservation")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowBindingReservationCode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowBindingReservationCode(<redacted>)"

    companion object {
        @JvmField val RESERVED = DocumentWorkflowBindingReservationCode("reserved")
        @JvmField val REPLAYED = DocumentWorkflowBindingReservationCode("replayed")
        @JvmField val ACTIVE_CONFLICT = DocumentWorkflowBindingReservationCode("active-conflict")
        @JvmField val IDEMPOTENCY_CONFLICT = DocumentWorkflowBindingReservationCode("idempotency-conflict")
        @JvmField val OUTCOME_UNKNOWN = DocumentWorkflowBindingReservationCode("outcome-unknown")
    }
}

class DocumentWorkflowBindingReserveRequest private constructor(
    val submission: DocumentWorkflowSubmissionRequest,
    authorizationDecisionDigest: String,
) {
    val authorizationDecisionDigest: String = portSha(
        authorizationDecisionDigest,
        "binding reservation authorization decision",
    )
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-binding-reserve-v1")
        .text(submission.requestDigest)
        .text(this.authorizationDecisionDigest)
        .finish()

    override fun toString(): String = "DocumentWorkflowBindingReserveRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            submission: DocumentWorkflowSubmissionRequest,
            authorizationDecisionDigest: String,
        ): DocumentWorkflowBindingReserveRequest = DocumentWorkflowBindingReserveRequest(
            submission,
            authorizationDecisionDigest,
        )
    }
}

class DocumentWorkflowBindingReservation private constructor(
    val code: DocumentWorkflowBindingReservationCode,
    val binding: DocumentWorkflowBinding?,
) {
    init {
        require((code == DocumentWorkflowBindingReservationCode.RESERVED ||
            code == DocumentWorkflowBindingReservationCode.REPLAYED) == (binding != null)
        ) { "Document workflow binding reservation result is invalid." }
    }

    override fun toString(): String = "DocumentWorkflowBindingReservation(<redacted>)"

    companion object {
        @JvmStatic fun accepted(
            code: DocumentWorkflowBindingReservationCode,
            binding: DocumentWorkflowBinding,
        ): DocumentWorkflowBindingReservation = DocumentWorkflowBindingReservation(code, binding)

        @JvmStatic fun rejected(
            code: DocumentWorkflowBindingReservationCode,
        ): DocumentWorkflowBindingReservation = DocumentWorkflowBindingReservation(code, null)
    }
}

class DocumentWorkflowBindingLookupRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val document: WorkflowSubjectRef,
    instanceId: String,
    evaluatedAtEpochMilli: Long,
) {
    val instanceId: String = portText(instanceId, "binding lookup instance id")
    val evaluatedAtEpochMilli: Long = portVersion(evaluatedAtEpochMilli, "binding lookup time")
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-binding-lookup-v1")
        .text(callContext.contextDigest)
        .text(document.type)
        .text(document.id)
        .text(this.instanceId)
        .longValue(this.evaluatedAtEpochMilli)
        .finish()

    init {
        require(document.type == DocumentWorkflowSubmissionRequest.DOCUMENT_SUBJECT_TYPE) {
            "Document workflow binding lookup requires the document subject type."
        }
    }

    override fun toString(): String = "DocumentWorkflowBindingLookupRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            document: WorkflowSubjectRef,
            instanceId: String,
            evaluatedAtEpochMilli: Long,
        ): DocumentWorkflowBindingLookupRequest = DocumentWorkflowBindingLookupRequest(
            callContext,
            document,
            instanceId,
            evaluatedAtEpochMilli,
        )
    }
}

class DocumentWorkflowBindingTransitionRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val action: DocumentWorkflowAction,
    val document: WorkflowSubjectRef,
    instanceId: String,
    expectedRevision: Long,
    val expectedState: DocumentWorkflowBindingState,
    val targetState: DocumentWorkflowBindingState,
    val expectedSubject: WorkflowSubjectSnapshot,
    val targetSubject: WorkflowSubjectSnapshot,
    expectedCycleNumber: Long,
    targetCycleNumber: Long,
    idempotencyKey: String,
    logicalRequestDigest: String,
    evidenceDigest: String,
    transitionedAtEpochMilli: Long,
) {
    val instanceId: String = portText(instanceId, "binding transition instance id")
    val expectedRevision: Long = portVersion(expectedRevision, "binding transition expected revision")
    val expectedCycleNumber: Long = portVersion(expectedCycleNumber, "binding transition expected cycle")
    val targetCycleNumber: Long = portVersion(targetCycleNumber, "binding transition target cycle")
    val idempotencyKey: String = portText(idempotencyKey, "binding transition idempotency key")
    val logicalRequestDigest: String = portSha(logicalRequestDigest, "binding transition logical request")
    val evidenceDigest: String = portSha(evidenceDigest, "binding transition evidence")
    val transitionedAtEpochMilli: Long = portVersion(transitionedAtEpochMilli, "binding transition time")
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-binding-transition-v1")
        .text(callContext.contextDigest)
        .text(action.code)
        .text(document.type)
        .text(document.id)
        .text(this.instanceId)
        .longValue(this.expectedRevision)
        .text(expectedState.code)
        .text(targetState.code)
        .portSubject(expectedSubject)
        .portSubject(targetSubject)
        .longValue(this.expectedCycleNumber)
        .longValue(this.targetCycleNumber)
        .text(this.idempotencyKey)
        .text(this.logicalRequestDigest)
        .text(this.evidenceDigest)
        .longValue(this.transitionedAtEpochMilli)
        .finish()

    init {
        require(this.expectedRevision > 0L && document == expectedSubject.ref && document == targetSubject.ref) {
            "Document workflow binding transition is invalid."
        }
        val valid = when (expectedState) {
            DocumentWorkflowBindingState.RESERVED ->
                targetState == DocumentWorkflowBindingState.ACTIVE ||
                    targetState == DocumentWorkflowBindingState.TERMINAL ||
                    targetState == DocumentWorkflowBindingState.RECONCILIATION_REQUIRED
            DocumentWorkflowBindingState.ACTIVE ->
                targetState == DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION ||
                    targetState == DocumentWorkflowBindingState.RECONCILIATION_REQUIRED
            DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION ->
                targetState == DocumentWorkflowBindingState.ACTIVE ||
                    targetState == DocumentWorkflowBindingState.RECONCILIATION_REQUIRED
            DocumentWorkflowBindingState.RECONCILIATION_REQUIRED ->
                targetState == DocumentWorkflowBindingState.RECONCILIATION_REQUIRED
            else -> false
        }
        require(valid) { "Document workflow binding state transition is unsupported." }
        if (targetState == DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION) {
            require(this.targetCycleNumber == this.expectedCycleNumber + 1L && targetSubject == expectedSubject) {
                "Opening a document revision cycle must preserve the old immutable subject evidence."
            }
        }
        if (expectedState == DocumentWorkflowBindingState.WAITING_SUBJECT_REVISION &&
            targetState == DocumentWorkflowBindingState.ACTIVE
        ) {
            require(this.targetCycleNumber == this.expectedCycleNumber &&
                (targetSubject.revision != expectedSubject.revision || targetSubject.digest != expectedSubject.digest)
            ) { "Resuming a document revision cycle requires an immutable replacement subject." }
        }
    }

    override fun toString(): String = "DocumentWorkflowBindingTransitionRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            action: DocumentWorkflowAction,
            document: WorkflowSubjectRef,
            instanceId: String,
            expectedRevision: Long,
            expectedState: DocumentWorkflowBindingState,
            targetState: DocumentWorkflowBindingState,
            expectedSubject: WorkflowSubjectSnapshot,
            targetSubject: WorkflowSubjectSnapshot,
            expectedCycleNumber: Long,
            targetCycleNumber: Long,
            idempotencyKey: String,
            logicalRequestDigest: String,
            evidenceDigest: String,
            transitionedAtEpochMilli: Long,
        ): DocumentWorkflowBindingTransitionRequest = DocumentWorkflowBindingTransitionRequest(
            callContext,
            action,
            document,
            instanceId,
            expectedRevision,
            expectedState,
            targetState,
            expectedSubject,
            targetSubject,
            expectedCycleNumber,
            targetCycleNumber,
            idempotencyKey,
            logicalRequestDigest,
            evidenceDigest,
            transitionedAtEpochMilli,
        )
    }
}

class DocumentWorkflowBindingTransitionResult private constructor(
    val outcome: DocumentWorkflowPortOutcome,
    val binding: DocumentWorkflowBinding?,
    failureCode: String?,
) {
    val failureCode: String? = failureCode?.let { portCode(it, "binding transition failure") }

    init {
        require((outcome == DocumentWorkflowPortOutcome.APPLIED ||
            outcome == DocumentWorkflowPortOutcome.REPLAYED) == (binding != null)
        ) { "Document workflow binding transition result is invalid." }
    }

    override fun toString(): String = "DocumentWorkflowBindingTransitionResult(<redacted>)"

    companion object {
        @JvmStatic fun success(
            outcome: DocumentWorkflowPortOutcome,
            binding: DocumentWorkflowBinding,
        ): DocumentWorkflowBindingTransitionResult = DocumentWorkflowBindingTransitionResult(
            outcome,
            binding,
            null,
        )

        @JvmStatic fun failure(
            outcome: DocumentWorkflowPortOutcome,
            failureCode: String,
        ): DocumentWorkflowBindingTransitionResult = DocumentWorkflowBindingTransitionResult(
            outcome,
            null,
            failureCode,
        )
    }
}

/** Application-level uniqueness and saga-state boundary; this is not a repository API. */
interface DocumentWorkflowBindingApplicationPort {
    fun reserve(request: DocumentWorkflowBindingReserveRequest): DocumentWorkflowBindingReservation
    fun find(request: DocumentWorkflowBindingLookupRequest): DocumentWorkflowBinding?
    fun transition(request: DocumentWorkflowBindingTransitionRequest): DocumentWorkflowBindingTransitionResult
}

class DocumentWorkflowDocumentMutationAction private constructor(value: String) {
    val code: String = portCode(value, "document mutation action")

    override fun equals(other: Any?): Boolean =
        this === other || other is DocumentWorkflowDocumentMutationAction && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "DocumentWorkflowDocumentMutationAction(<redacted>)"

    companion object {
        @JvmField val SUBMIT_FOR_REVIEW = DocumentWorkflowDocumentMutationAction("submit-for-review")
        @JvmField val OPEN_REVISION_DRAFT = DocumentWorkflowDocumentMutationAction("open-revision-draft")
        @JvmField val SUBMIT_REVISION_FOR_REVIEW =
            DocumentWorkflowDocumentMutationAction("submit-revision-for-review")
    }
}

class DocumentWorkflowDocumentMutationRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val action: DocumentWorkflowDocumentMutationAction,
    instanceId: String,
    val subject: WorkflowSubjectSnapshot,
    val selection: DocumentWorkflowSelection,
    cycleNumber: Long,
    logicalRequestDigest: String,
    authorizationDecisionDigest: String,
    reasonDigest: String,
    executedAtEpochMilli: Long,
) {
    val instanceId: String = portText(instanceId, "document mutation instance id")
    val cycleNumber: Long = portVersion(cycleNumber, "document mutation cycle")
    val logicalRequestDigest: String = portSha(logicalRequestDigest, "document mutation logical request")
    val authorizationDecisionDigest: String = portSha(
        authorizationDecisionDigest,
        "document mutation authorization decision",
    )
    val reasonDigest: String = portSha(reasonDigest, "document mutation reason")
    val executedAtEpochMilli: Long = portVersion(executedAtEpochMilli, "document mutation time")
    val idempotencyKey: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-mutation-key-v1")
        .text(this.logicalRequestDigest)
        .text(action.code)
        .longValue(this.cycleNumber)
        .finish()
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-mutation-request-v1")
        .text(callContext.contextDigest)
        .text(action.code)
        .text(this.instanceId)
        .portSubject(subject)
        .text(selection.selectionDigest)
        .longValue(this.cycleNumber)
        .text(this.logicalRequestDigest)
        .text(this.authorizationDecisionDigest)
        .text(this.reasonDigest)
        .longValue(this.executedAtEpochMilli)
        .finish()

    override fun toString(): String = "DocumentWorkflowDocumentMutationRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            action: DocumentWorkflowDocumentMutationAction,
            instanceId: String,
            subject: WorkflowSubjectSnapshot,
            selection: DocumentWorkflowSelection,
            cycleNumber: Long,
            logicalRequestDigest: String,
            authorizationDecisionDigest: String,
            reasonDigest: String,
            executedAtEpochMilli: Long,
        ): DocumentWorkflowDocumentMutationRequest = DocumentWorkflowDocumentMutationRequest(
            callContext,
            action,
            instanceId,
            subject,
            selection,
            cycleNumber,
            logicalRequestDigest,
            authorizationDecisionDigest,
            reasonDigest,
            executedAtEpochMilli,
        )
    }
}

class DocumentWorkflowDocumentMutationResult private constructor(
    val outcome: DocumentWorkflowPortOutcome,
    val subject: WorkflowSubjectSnapshot?,
    receiptDigest: String?,
    failureCode: String?,
) {
    val receiptDigest: String? = receiptDigest?.let { portSha(it, "document mutation receipt") }
    val failureCode: String? = failureCode?.let { portCode(it, "document mutation failure") }

    init {
        val success = outcome == DocumentWorkflowPortOutcome.APPLIED ||
            outcome == DocumentWorkflowPortOutcome.REPLAYED
        require(success == (subject != null && this.receiptDigest != null)) {
            "Document workflow document mutation result is invalid."
        }
        require(success || this.failureCode != null) {
            "Document workflow document mutation failure requires a stable code."
        }
    }

    override fun toString(): String = "DocumentWorkflowDocumentMutationResult(<redacted>)"

    companion object {
        @JvmStatic fun success(
            outcome: DocumentWorkflowPortOutcome,
            subject: WorkflowSubjectSnapshot,
            receiptDigest: String,
        ): DocumentWorkflowDocumentMutationResult = DocumentWorkflowDocumentMutationResult(
            outcome,
            subject,
            receiptDigest,
            null,
        )

        @JvmStatic fun failure(
            outcome: DocumentWorkflowPortOutcome,
            failureCode: String,
        ): DocumentWorkflowDocumentMutationResult = DocumentWorkflowDocumentMutationResult(
            outcome,
            null,
            null,
            failureCode,
        )
    }
}

/**
 * Narrow host application use cases. Implementations recheck current authorization and exact
 * document revision/lifecycle; they may call existing FileWeft application services but never
 * expose repositories, object stores, or catalog CRUD to this adapter.
 *
 * `OPEN_REVISION_DRAFT` must retain the old version/hash as immutable approval evidence while a
 * controlled host use case makes the document editable. It must not overwrite old content.
 * `SUBMIT_REVISION_FOR_REVIEW` must verify that [DocumentWorkflowDocumentMutationRequest.subject]
 * is the newly created immutable version/hash before returning success. These actions do not
 * alter the legacy DocumentReview ABI, tables, routes, or HTTP behavior.
 */
fun interface DocumentWorkflowDocumentApplicationPort {
    fun mutate(request: DocumentWorkflowDocumentMutationRequest): DocumentWorkflowDocumentMutationResult
}

class DocumentWorkflowSubjectRevisionCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    val action: DocumentWorkflowAction,
    instanceId: String,
    val selection: DocumentWorkflowSelection,
    val previousSubject: WorkflowSubjectSnapshot,
    val replacementSubject: WorkflowSubjectSnapshot,
    cycleNumber: Long,
    authorizationDecisionDigest: String,
    reasonDigest: String,
) {
    val instanceId: String = portText(instanceId, "subject revision command instance id")
    val cycleNumber: Long = portVersion(cycleNumber, "subject revision command cycle")
    val authorizationDecisionDigest: String = portSha(
        authorizationDecisionDigest,
        "subject revision command authorization decision",
    )
    val reasonDigest: String = portSha(reasonDigest, "subject revision command reason")
    val requestDigest: String = DocumentWorkflowSupport.digest("flowweft-workflow-document-revision-command-v1")
        .text(callContext.contextDigest)
        .text(options.commandId)
        .text(options.idempotencyKey)
        .longValue(options.expectedInstanceVersion)
        .longValue(options.now)
        .text(action.code)
        .text(this.instanceId)
        .text(selection.selectionDigest)
        .portSubject(previousSubject)
        .portSubject(replacementSubject)
        .longValue(this.cycleNumber)
        .text(this.authorizationDecisionDigest)
        .text(this.reasonDigest)
        .finish()

    init {
        require(action == DocumentWorkflowAction.REQUEST_SUBJECT_REVISION ||
            action == DocumentWorkflowAction.RESUME_SUBJECT_REVISION
        ) { "Document workflow subject revision command action is unsupported." }
        require(previousSubject.ref == replacementSubject.ref) {
            "Document workflow subject revision command targets another subject."
        }
        if (action == DocumentWorkflowAction.REQUEST_SUBJECT_REVISION) {
            require(previousSubject == replacementSubject && this.cycleNumber > 0L) {
                "Opening a subject revision cycle must preserve old immutable subject evidence."
            }
        } else {
            require(this.cycleNumber > 0L &&
                (previousSubject.revision != replacementSubject.revision ||
                    previousSubject.digest != replacementSubject.digest)
            ) { "Resuming a subject revision cycle requires an immutable replacement subject." }
        }
    }

    override fun toString(): String = "DocumentWorkflowSubjectRevisionCommand(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            action: DocumentWorkflowAction,
            instanceId: String,
            selection: DocumentWorkflowSelection,
            previousSubject: WorkflowSubjectSnapshot,
            replacementSubject: WorkflowSubjectSnapshot,
            cycleNumber: Long,
            authorizationDecisionDigest: String,
            reasonDigest: String,
        ): DocumentWorkflowSubjectRevisionCommand = DocumentWorkflowSubjectRevisionCommand(
            callContext,
            options,
            action,
            instanceId,
            selection,
            previousSubject,
            replacementSubject,
            cycleNumber,
            authorizationDecisionDigest,
            reasonDigest,
        )
    }
}

/** Exact durable receipt from the generic Workflow application boundary. */
class DocumentWorkflowGenericCommandResult private constructor(
    val outcome: DocumentWorkflowPortOutcome,
    tenantId: String?,
    instanceId: String?,
    definitionId: String?,
    val definitionRef: WorkflowDefinitionRef?,
    val subject: WorkflowSubjectSnapshot?,
    resultInstanceVersion: Long?,
    receiptDigest: String?,
    failureCode: String?,
) {
    val tenantId: String? = tenantId?.let { portText(it, "generic result tenant") }
    val instanceId: String? = instanceId?.let { portText(it, "generic result instance id") }
    val definitionId: String? = definitionId?.let { portText(it, "generic result definition id") }
    val resultInstanceVersion: Long? = resultInstanceVersion?.let {
        portVersion(it, "generic result instance version")
    }
    val receiptDigest: String? = receiptDigest?.let { portSha(it, "generic result receipt") }
    val failureCode: String? = failureCode?.let { portCode(it, "generic result failure") }

    init {
        val success = outcome == DocumentWorkflowPortOutcome.APPLIED ||
            outcome == DocumentWorkflowPortOutcome.REPLAYED
        val complete = this.tenantId != null && this.instanceId != null && this.definitionId != null &&
            definitionRef != null && subject != null && this.resultInstanceVersion != null &&
            this.receiptDigest != null
        require(success == complete && (!success || this.resultInstanceVersion!! > 0L)) {
            "Document workflow generic command result is invalid."
        }
        require(success || this.failureCode != null) {
            "Document workflow generic command failure requires a stable code."
        }
    }

    override fun toString(): String = "DocumentWorkflowGenericCommandResult(<redacted>)"

    companion object {
        @JvmStatic fun success(
            outcome: DocumentWorkflowPortOutcome,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            resultInstanceVersion: Long,
            receiptDigest: String,
        ): DocumentWorkflowGenericCommandResult = DocumentWorkflowGenericCommandResult(
            outcome,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            resultInstanceVersion,
            receiptDigest,
            null,
        )

        @JvmStatic fun failure(
            outcome: DocumentWorkflowPortOutcome,
            failureCode: String,
        ): DocumentWorkflowGenericCommandResult = DocumentWorkflowGenericCommandResult(
            outcome,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            failureCode,
        )
    }
}

/**
 * Generic Workflow application boundary. [start] consumes the existing public runtime request;
 * subject-revision methods are explicit until the generic runtime exposes that lifecycle itself.
 */
interface DocumentWorkflowGenericApplicationPort {
    fun start(request: WorkflowRuntimeStartRequest): DocumentWorkflowGenericCommandResult
    fun transitionSubjectRevision(
        request: DocumentWorkflowSubjectRevisionCommand,
    ): DocumentWorkflowGenericCommandResult
}
