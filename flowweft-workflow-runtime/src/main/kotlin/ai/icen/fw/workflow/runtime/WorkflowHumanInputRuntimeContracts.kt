package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowCommentDocument
import ai.icen.fw.workflow.api.WorkflowCommentSnapshot
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessReport
import ai.icen.fw.workflow.api.WorkflowFormFieldPath
import ai.icen.fw.workflow.api.WorkflowFormSubmissionRef
import ai.icen.fw.workflow.api.WorkflowFormVersionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowWorkItemRef
import ai.icen.fw.workflow.spi.WorkflowFormValidationOperation
import ai.icen.fw.workflow.spi.WorkflowMentionSearchPage
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidationReport
import ai.icen.fw.workflow.spi.WorkflowStructuredPayload

class WorkflowHumanInputOperation private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow human-input operation is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowHumanInputOperation && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowHumanInputOperation(<redacted>)"

    companion object {
        @JvmField val FORM_VALIDATE = WorkflowHumanInputOperation("form-validate")
        @JvmField val COMMENT_CREATE = WorkflowHumanInputOperation("comment-create")
        @JvmField val MENTION_SEARCH = WorkflowHumanInputOperation("mention-search")
        @JvmField val MENTION_NOTIFY = WorkflowHumanInputOperation("mention-notify")
    }
}

class WorkflowHumanInputResultCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow human-input result is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowHumanInputResultCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowHumanInputResultCode(<redacted>)"

    companion object {
        @JvmField val SUCCEEDED = WorkflowHumanInputResultCode("succeeded")
        @JvmField val REPLAYED = WorkflowHumanInputResultCode("replayed")
        @JvmField val AUTHORIZATION_DENIED = WorkflowHumanInputResultCode("authorization-denied")
        @JvmField val INVALID = WorkflowHumanInputResultCode("invalid")
        /** Hidden and missing principals intentionally share this single result. */
        @JvmField val MENTION_NOT_VISIBLE = WorkflowHumanInputResultCode("mention-not-visible")
        @JvmField val PROVIDER_UNAVAILABLE = WorkflowHumanInputResultCode("provider-unavailable")
        @JvmField val PROVIDER_REJECTED = WorkflowHumanInputResultCode("provider-rejected")
        @JvmField val RECEIPT_INVALID = WorkflowHumanInputResultCode("receipt-invalid")
        @JvmField val IDEMPOTENCY_CONFLICT = WorkflowHumanInputResultCode("idempotency-conflict")
        @JvmField val OUTCOME_UNKNOWN = WorkflowHumanInputResultCode("outcome-unknown")
    }
}

/** Value-free operational evidence. It never carries provider messages, form data or comment text. */
class WorkflowHumanInputDiagnostic private constructor(
    component: String,
    val operation: WorkflowHumanInputOperation,
    code: String,
    val retryable: Boolean,
) {
    val component: String = WorkflowRuntimeSupport.code(component, "Workflow diagnostic component is invalid.")
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow diagnostic code is invalid.")
    val diagnosticDigest: String = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-human-input-diagnostic-v1")
        .text(this.component)
        .text(operation.code)
        .text(this.code)
        .bool(retryable)
        .finish()

    override fun toString(): String = "WorkflowHumanInputDiagnostic(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            component: String,
            operation: WorkflowHumanInputOperation,
            code: String,
            retryable: Boolean,
        ): WorkflowHumanInputDiagnostic = WorkflowHumanInputDiagnostic(component, operation, code, retryable)
    }
}

/** Provider identity and hard call budgets selected by trusted host configuration. */
class WorkflowHumanInputProviderProfile private constructor(
    providerId: String,
    providerRevision: String,
    val callWindowMillis: Long,
    val maximumInputBytes: Int,
    val maximumOutputBytes: Int,
    val maximumItems: Int,
) {
    val providerId: String = WorkflowRuntimeSupport.code(providerId, "Workflow provider id is invalid.")
    val providerRevision: String = WorkflowRuntimeSupport.text(
        providerRevision,
        MAX_PROVIDER_REVISION_BYTES,
        "Workflow provider revision is invalid.",
    )

    init {
        require(callWindowMillis in 1L..300_000L) { "Workflow provider call window is invalid." }
        require(maximumInputBytes in 1..4 * 1024 * 1024 && maximumOutputBytes in 1..4 * 1024 * 1024) {
            "Workflow provider byte budget is invalid."
        }
        require(maximumItems in 1..256) { "Workflow provider item budget is invalid." }
    }

    override fun toString(): String = "WorkflowHumanInputProviderProfile(<redacted>)"

    companion object {
        private const val MAX_PROVIDER_REVISION_BYTES: Int = 256

        @JvmStatic
        fun of(
            providerId: String,
            providerRevision: String,
            callWindowMillis: Long,
            maximumInputBytes: Int,
            maximumOutputBytes: Int,
            maximumItems: Int,
        ): WorkflowHumanInputProviderProfile = WorkflowHumanInputProviderProfile(
            providerId,
            providerRevision,
            callWindowMillis,
            maximumInputBytes,
            maximumOutputBytes,
            maximumItems,
        )
    }
}

/** Trusted output from one exact secure-form provider invocation. */
class WorkflowRuntimeValidatedForm private constructor(
    val form: WorkflowFormVersionRef,
    val operation: WorkflowFormValidationOperation,
    val normalizedSubmission: WorkflowStructuredPayload,
    val fieldAccess: WorkflowFormFieldAccessReport,
    val providerReceipt: WorkflowProviderReceipt,
    val submission: WorkflowFormSubmissionRef?,
) {
    val evidenceDigest: String

    init {
        require(normalizedSubmission.validated) { "Workflow runtime accepts only validated form payloads." }
        require(normalizedSubmission.schema.providerId == form.dataSchema.registryId &&
            normalizedSubmission.schema.schemaId == form.dataSchema.schemaId &&
            normalizedSubmission.schema.version == form.dataSchema.version &&
            normalizedSubmission.schema.digest == form.dataSchema.digest
        ) { "Workflow runtime form schema binding is invalid." }
        require((operation == WorkflowFormValidationOperation.SUBMIT) == (submission != null)) {
            "Workflow form submission evidence does not match the operation."
        }
        require(submission == null || submission.form == form &&
            submission.canonicalPayloadDigest == normalizedSubmission.canonicalPayloadDigest
        ) { "Workflow form submission reference does not match normalized content." }
        val providerReport = WorkflowSecureFormValidationReport.valid(normalizedSubmission, fieldAccess)
        require(providerReceipt.outcome == WorkflowProviderOutcome.SUCCESS &&
            providerReceipt.resultDigest == providerReport.reportDigest
        ) { "Workflow validated form does not match its provider receipt." }
        evidenceDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-validated-form-v1")
            .text(form.bindingDigest)
            .text(operation.code)
            .text(normalizedSubmission.contentDigest)
            .text(fieldAccess.reportDigest)
            .text(providerReceipt.receiptDigest)
            .optional(submission?.submissionDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowRuntimeValidatedForm(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            form: WorkflowFormVersionRef,
            operation: WorkflowFormValidationOperation,
            normalizedSubmission: WorkflowStructuredPayload,
            fieldAccess: WorkflowFormFieldAccessReport,
            providerReceipt: WorkflowProviderReceipt,
            submission: WorkflowFormSubmissionRef?,
        ): WorkflowRuntimeValidatedForm = WorkflowRuntimeValidatedForm(
            form,
            operation,
            normalizedSubmission,
            fieldAccess,
            providerReceipt,
            submission,
        )
    }
}

class WorkflowRuntimeFormCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    instanceId: String,
    val subject: WorkflowSubjectSnapshot,
    val form: WorkflowFormVersionRef,
    val operation: WorkflowFormValidationOperation,
    requestedFields: Collection<WorkflowFormFieldPath>,
    val submission: WorkflowStructuredPayload,
    submissionId: String?,
    submissionVersion: Long?,
    idempotencyKey: String,
) {
    val instanceId: String = id(instanceId, "form instance")
    val requestedFields: List<WorkflowFormFieldPath> = java.util.Collections.unmodifiableList(
        WorkflowRuntimeSupport.immutable(
            requestedFields,
            256,
            "Workflow form field request exceeds the limit.",
        ).sortedBy { it.value },
    )
    val submissionId: String? = submissionId?.let { id(it, "form submission") }
    val submissionVersion: Long? = submissionVersion?.let {
        WorkflowRuntimeSupport.nonNegative(it, "Workflow form submission version is invalid.")
    }
    val idempotencyKey: String = id(idempotencyKey, "form idempotency key")
    val requestDigest: String

    init {
        require(this.requestedFields.isNotEmpty() && this.requestedFields.toSet().size == this.requestedFields.size) {
            "Workflow form command requires unique requested fields."
        }
        require(operation == WorkflowFormValidationOperation.READ ||
            operation == WorkflowFormValidationOperation.SUBMIT
        ) { "Unknown workflow form operations fail closed." }
        require((operation == WorkflowFormValidationOperation.SUBMIT) ==
            (this.submissionId != null && this.submissionVersion != null)
        ) { "Workflow submit operations require exact immutable submission identity." }
        require(operation != WorkflowFormValidationOperation.READ ||
            this.submissionId == null && this.submissionVersion == null
        ) { "Workflow read operations cannot create submission identity." }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-form-command-v1")
            .text(callContext.contextDigest)
            .text(this.instanceId)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(form.bindingDigest)
            .text(operation.code)
            .integer(this.requestedFields.size)
            .also { writer -> this.requestedFields.forEach { writer.text(it.value) } }
            .text(submission.contentDigest)
            .optional(this.submissionId)
            .longValue(this.submissionVersion ?: -1L)
            .text(this.idempotencyKey)
            .finish()
    }

    override fun toString(): String = "WorkflowRuntimeFormCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            instanceId: String,
            subject: WorkflowSubjectSnapshot,
            form: WorkflowFormVersionRef,
            operation: WorkflowFormValidationOperation,
            requestedFields: Collection<WorkflowFormFieldPath>,
            submission: WorkflowStructuredPayload,
            submissionId: String?,
            submissionVersion: Long?,
            idempotencyKey: String,
        ): WorkflowRuntimeFormCommand = WorkflowRuntimeFormCommand(
            callContext,
            instanceId,
            subject,
            form,
            operation,
            requestedFields,
            submission,
            submissionId,
            submissionVersion,
            idempotencyKey,
        )
    }
}

class WorkflowRuntimeCommentCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    commentId: String,
    val commentVersion: Long,
    val instance: WorkflowInstanceRef,
    val workItem: WorkflowWorkItemRef?,
    val document: WorkflowCommentDocument,
    idempotencyKey: String,
) {
    val commentId: String = id(commentId, "comment")
    val idempotencyKey: String = id(idempotencyKey, "comment idempotency key")
    val requestDigest: String

    init {
        require(commentVersion >= 0L) { "Workflow comment version is invalid." }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-comment-command-v1")
            .text(callContext.contextDigest)
            .text(this.commentId)
            .longValue(commentVersion)
            .text(instance.id)
            .longValue(instance.expectedVersion)
            .optional(workItem?.id)
            .longValue(workItem?.expectedVersion ?: -1L)
            .text(document.documentDigest)
            .text(this.idempotencyKey)
            .finish()
    }

    override fun toString(): String = "WorkflowRuntimeCommentCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            commentId: String,
            commentVersion: Long,
            instance: WorkflowInstanceRef,
            workItem: WorkflowWorkItemRef?,
            document: WorkflowCommentDocument,
            idempotencyKey: String,
        ): WorkflowRuntimeCommentCommand = WorkflowRuntimeCommentCommand(
            callContext,
            commentId,
            commentVersion,
            instance,
            workItem,
            document,
            idempotencyKey,
        )
    }
}

class WorkflowRuntimeMentionSearchCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    instanceId: String,
    val subject: WorkflowSubjectSnapshot?,
    query: String,
    cursor: String?,
    val pageSize: Int,
) {
    val instanceId: String = id(instanceId, "mention-search instance")
    val query: String = WorkflowRuntimeSupport.text(
        query,
        WorkflowRuntimeSupport.MAX_TEXT_BYTES,
        "Workflow mention-search query is invalid.",
    )
    val cursor: String? = cursor?.let { id(it, "mention-search cursor") }
    val requestDigest: String

    init {
        require(pageSize in 1..50) { "Workflow mention-search page size is invalid." }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-mention-search-command-v1")
            .text(callContext.contextDigest)
            .text(this.instanceId)
            .optional(subject?.ref?.type)
            .optional(subject?.ref?.id)
            .optional(subject?.revision)
            .optional(subject?.digest)
            .text(this.query)
            .optional(this.cursor)
            .integer(pageSize)
            .finish()
    }

    override fun toString(): String = "WorkflowRuntimeMentionSearchCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            instanceId: String,
            subject: WorkflowSubjectSnapshot?,
            query: String,
            cursor: String?,
            pageSize: Int,
        ): WorkflowRuntimeMentionSearchCommand = WorkflowRuntimeMentionSearchCommand(
            callContext,
            instanceId,
            subject,
            query,
            cursor,
            pageSize,
        )
    }
}

class WorkflowRuntimeMentionNotificationCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    val comment: WorkflowCommentSnapshot,
    val recipient: WorkflowPrincipalRef,
    idempotencyKey: String,
) {
    val idempotencyKey: String = id(idempotencyKey, "mention-notification idempotency key")
    val requestDigest: String

    init {
        require(comment.document.mentionedPrincipals.contains(recipient)) {
            "Workflow notification recipient is not mentioned by the exact comment."
        }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-mention-notify-command-v1")
            .text(callContext.contextDigest)
            .text(comment.commentId)
            .longValue(comment.version)
            .text(comment.snapshotDigest)
            .text(recipient.type)
            .text(recipient.id)
            .text(this.idempotencyKey)
            .finish()
    }

    override fun toString(): String = "WorkflowRuntimeMentionNotificationCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            comment: WorkflowCommentSnapshot,
            recipient: WorkflowPrincipalRef,
            idempotencyKey: String,
        ): WorkflowRuntimeMentionNotificationCommand = WorkflowRuntimeMentionNotificationCommand(
            callContext,
            comment,
            recipient,
            idempotencyKey,
        )
    }
}

class WorkflowHumanInputIdempotencyRecord private constructor(
    tenantId: String,
    idempotencyKey: String,
    val operation: WorkflowHumanInputOperation,
    requestDigest: String,
    resultDigest: String,
    val validatedForm: WorkflowRuntimeValidatedForm?,
    val comment: WorkflowCommentSnapshot?,
    val delivery: WorkflowNotificationDelivery?,
    val notificationReceipt: WorkflowProviderReceipt?,
    completedAtEpochMilli: Long,
) {
    val tenantId: String = id(tenantId, "idempotency tenant")
    val idempotencyKey: String = id(idempotencyKey, "idempotency key")
    val requestDigest: String = sha(requestDigest, "idempotency request")
    val resultDigest: String = sha(resultDigest, "idempotency result")
    val completedAtEpochMilli: Long = WorkflowRuntimeSupport.nonNegative(
        completedAtEpochMilli,
        "Workflow human-input completion time is invalid.",
    )

    init {
        val populated = listOf(validatedForm, comment, delivery).count { it != null }
        require(populated == 1) { "Workflow idempotency record requires exactly one typed result." }
        require((operation == WorkflowHumanInputOperation.FORM_VALIDATE) == (validatedForm != null) &&
            (operation == WorkflowHumanInputOperation.COMMENT_CREATE) == (comment != null) &&
            (operation == WorkflowHumanInputOperation.MENTION_NOTIFY) == (delivery != null)
        ) { "Workflow idempotency operation and result do not match." }
        require((delivery == null) == (notificationReceipt == null)) {
            "Workflow notification delivery and provider receipt must be persisted together."
        }
        val notificationEvidence = delivery?.let { delivered ->
            WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-mention-notification-evidence-v1")
                .text(delivered.deliveryDigest)
                .text(notificationReceipt!!.receiptDigest)
                .finish()
        }
        val typedDigest = validatedForm?.evidenceDigest ?: comment?.snapshotDigest ?: notificationEvidence!!
        require(this.resultDigest == typedDigest) { "Workflow idempotency result digest is inconsistent." }
        require(validatedForm == null || validatedForm.providerReceipt.tenantId == this.tenantId) {
            "Workflow form idempotency evidence belongs to another tenant."
        }
        require(notificationReceipt == null ||
            notificationReceipt.tenantId == this.tenantId &&
            notificationReceipt.outcome == WorkflowProviderOutcome.SUCCESS &&
            notificationReceipt.resultDigest == delivery!!.deliveryDigest &&
            notificationReceipt.completedAtEpochMilli <= this.completedAtEpochMilli &&
            this.completedAtEpochMilli <= notificationReceipt.expiresAtEpochMilli
        ) { "Workflow notification idempotency evidence is inconsistent." }
    }

    fun matches(operation: WorkflowHumanInputOperation, requestDigest: String): Boolean =
        this.operation == operation && this.requestDigest == requestDigest

    override fun toString(): String = "WorkflowHumanInputIdempotencyRecord(<redacted>)"

    companion object {
        @JvmStatic
        fun form(
            tenantId: String,
            idempotencyKey: String,
            requestDigest: String,
            result: WorkflowRuntimeValidatedForm,
            completedAtEpochMilli: Long,
        ): WorkflowHumanInputIdempotencyRecord = WorkflowHumanInputIdempotencyRecord(
            tenantId,
            idempotencyKey,
            WorkflowHumanInputOperation.FORM_VALIDATE,
            requestDigest,
            result.evidenceDigest,
            result,
            null,
            null,
            null,
            completedAtEpochMilli,
        )

        @JvmStatic
        fun comment(
            tenantId: String,
            idempotencyKey: String,
            requestDigest: String,
            result: WorkflowCommentSnapshot,
            completedAtEpochMilli: Long,
        ): WorkflowHumanInputIdempotencyRecord = WorkflowHumanInputIdempotencyRecord(
            tenantId,
            idempotencyKey,
            WorkflowHumanInputOperation.COMMENT_CREATE,
            requestDigest,
            result.snapshotDigest,
            null,
            result,
            null,
            null,
            completedAtEpochMilli,
        )

        @JvmStatic
        fun notification(
            tenantId: String,
            idempotencyKey: String,
            requestDigest: String,
            result: WorkflowNotificationDelivery,
            providerReceipt: WorkflowProviderReceipt,
            completedAtEpochMilli: Long,
        ): WorkflowHumanInputIdempotencyRecord = WorkflowHumanInputIdempotencyRecord(
            tenantId,
            idempotencyKey,
            WorkflowHumanInputOperation.MENTION_NOTIFY,
            requestDigest,
            WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-mention-notification-evidence-v1")
                .text(result.deliveryDigest)
                .text(providerReceipt.receiptDigest)
                .finish(),
            null,
            null,
            result,
            providerReceipt,
            completedAtEpochMilli,
        )
    }
}

class WorkflowHumanInputIdempotencyWriteCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow idempotency write code is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowHumanInputIdempotencyWriteCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowHumanInputIdempotencyWriteCode(<redacted>)"

    companion object {
        @JvmField val STORED = WorkflowHumanInputIdempotencyWriteCode("stored")
        @JvmField val REPLAYED = WorkflowHumanInputIdempotencyWriteCode("replayed")
        @JvmField val CONFLICT = WorkflowHumanInputIdempotencyWriteCode("conflict")
        @JvmField val OUTCOME_UNKNOWN = WorkflowHumanInputIdempotencyWriteCode("outcome-unknown")
    }
}

class WorkflowHumanInputReservation private constructor(
    tenantId: String,
    idempotencyKey: String,
    val operation: WorkflowHumanInputOperation,
    requestDigest: String,
    leaseId: String,
    val fencingToken: Long,
    val expiresAtEpochMilli: Long,
) {
    val tenantId: String = id(tenantId, "reservation tenant")
    val idempotencyKey: String = id(idempotencyKey, "reservation idempotency key")
    val requestDigest: String = sha(requestDigest, "reservation request")
    val leaseId: String = id(leaseId, "reservation lease")

    init {
        require(fencingToken > 0L && expiresAtEpochMilli >= 0L) {
            "Workflow human-input reservation fencing or expiry is invalid."
        }
    }

    override fun toString(): String = "WorkflowHumanInputReservation(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            idempotencyKey: String,
            operation: WorkflowHumanInputOperation,
            requestDigest: String,
            leaseId: String,
            fencingToken: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowHumanInputReservation = WorkflowHumanInputReservation(
            tenantId,
            idempotencyKey,
            operation,
            requestDigest,
            leaseId,
            fencingToken,
            expiresAtEpochMilli,
        )
    }
}

class WorkflowHumanInputReservationRequest private constructor(
    tenantId: String,
    idempotencyKey: String,
    val operation: WorkflowHumanInputOperation,
    requestDigest: String,
    val requestedAtEpochMilli: Long,
    val leaseUntilEpochMilli: Long,
) {
    val tenantId: String = id(tenantId, "reservation tenant")
    val idempotencyKey: String = id(idempotencyKey, "reservation idempotency key")
    val requestDigest: String = sha(requestDigest, "reservation request")

    init {
        require(requestedAtEpochMilli >= 0L && leaseUntilEpochMilli > requestedAtEpochMilli &&
            leaseUntilEpochMilli - requestedAtEpochMilli <= MAXIMUM_LEASE_MILLIS
        ) { "Workflow human-input reservation window is invalid." }
    }

    override fun toString(): String = "WorkflowHumanInputReservationRequest(<redacted>)"

    companion object {
        const val MAXIMUM_LEASE_MILLIS: Long = 600_000L

        @JvmStatic
        fun of(
            tenantId: String,
            idempotencyKey: String,
            operation: WorkflowHumanInputOperation,
            requestDigest: String,
            requestedAtEpochMilli: Long,
            leaseUntilEpochMilli: Long,
        ): WorkflowHumanInputReservationRequest = WorkflowHumanInputReservationRequest(
            tenantId,
            idempotencyKey,
            operation,
            requestDigest,
            requestedAtEpochMilli,
            leaseUntilEpochMilli,
        )
    }
}

class WorkflowHumanInputReservationCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow reservation result code is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowHumanInputReservationCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowHumanInputReservationCode(<redacted>)"

    companion object {
        @JvmField val RESERVED = WorkflowHumanInputReservationCode("reserved")
        @JvmField val REPLAYED = WorkflowHumanInputReservationCode("replayed")
        @JvmField val CONFLICT = WorkflowHumanInputReservationCode("conflict")
        @JvmField val IN_PROGRESS = WorkflowHumanInputReservationCode("in-progress")
        @JvmField val OUTCOME_UNKNOWN = WorkflowHumanInputReservationCode("outcome-unknown")
    }
}

class WorkflowHumanInputReservationResult private constructor(
    val code: WorkflowHumanInputReservationCode,
    val reservation: WorkflowHumanInputReservation?,
    val record: WorkflowHumanInputIdempotencyRecord?,
) {
    init {
        require((code == WorkflowHumanInputReservationCode.RESERVED) == (reservation != null) &&
            (code == WorkflowHumanInputReservationCode.REPLAYED) == (record != null)
        ) { "Workflow human-input reservation result is inconsistent." }
    }

    override fun toString(): String = "WorkflowHumanInputReservationResult(<redacted>)"

    companion object {
        @JvmStatic
        fun reserved(reservation: WorkflowHumanInputReservation): WorkflowHumanInputReservationResult =
            WorkflowHumanInputReservationResult(WorkflowHumanInputReservationCode.RESERVED, reservation, null)

        @JvmStatic
        fun replayed(record: WorkflowHumanInputIdempotencyRecord): WorkflowHumanInputReservationResult =
            WorkflowHumanInputReservationResult(WorkflowHumanInputReservationCode.REPLAYED, null, record)

        @JvmStatic
        fun failed(code: WorkflowHumanInputReservationCode): WorkflowHumanInputReservationResult {
            require(code == WorkflowHumanInputReservationCode.CONFLICT ||
                code == WorkflowHumanInputReservationCode.IN_PROGRESS ||
                code == WorkflowHumanInputReservationCode.OUTCOME_UNKNOWN
            ) { "Workflow human-input reservation failure code is invalid." }
            return WorkflowHumanInputReservationResult(code, null, null)
        }
    }
}

class WorkflowHumanInputIdempotencyWriteResult private constructor(
    val code: WorkflowHumanInputIdempotencyWriteCode,
    val record: WorkflowHumanInputIdempotencyRecord?,
) {
    init {
        require((code == WorkflowHumanInputIdempotencyWriteCode.STORED ||
            code == WorkflowHumanInputIdempotencyWriteCode.REPLAYED) == (record != null)
        ) { "Workflow idempotency write result is inconsistent." }
    }

    override fun toString(): String = "WorkflowHumanInputIdempotencyWriteResult(<redacted>)"

    companion object {
        @JvmStatic
        fun stored(record: WorkflowHumanInputIdempotencyRecord): WorkflowHumanInputIdempotencyWriteResult =
            WorkflowHumanInputIdempotencyWriteResult(WorkflowHumanInputIdempotencyWriteCode.STORED, record)

        @JvmStatic
        fun replayed(record: WorkflowHumanInputIdempotencyRecord): WorkflowHumanInputIdempotencyWriteResult =
            WorkflowHumanInputIdempotencyWriteResult(WorkflowHumanInputIdempotencyWriteCode.REPLAYED, record)

        @JvmStatic
        fun failed(code: WorkflowHumanInputIdempotencyWriteCode): WorkflowHumanInputIdempotencyWriteResult {
            require(code == WorkflowHumanInputIdempotencyWriteCode.CONFLICT ||
                code == WorkflowHumanInputIdempotencyWriteCode.OUTCOME_UNKNOWN
            ) { "Workflow idempotency failure code is invalid." }
            return WorkflowHumanInputIdempotencyWriteResult(code, null)
        }
    }
}

/** Calls are short independent transactions and never enclose provider invocation. */
interface WorkflowHumanInputIdempotencyPort {
    fun reserve(request: WorkflowHumanInputReservationRequest): WorkflowHumanInputReservationResult

    fun complete(
        reservation: WorkflowHumanInputReservation,
        record: WorkflowHumanInputIdempotencyRecord,
    ): WorkflowHumanInputIdempotencyWriteResult
}

class WorkflowRuntimeFormResult private constructor(
    val code: WorkflowHumanInputResultCode,
    val value: WorkflowRuntimeValidatedForm?,
    val diagnostic: WorkflowHumanInputDiagnostic?,
) {
    init {
        require((code == WorkflowHumanInputResultCode.SUCCEEDED || code == WorkflowHumanInputResultCode.REPLAYED) ==
            (value != null)
        ) { "Workflow form runtime result is inconsistent." }
        require((value == null) == (diagnostic != null)) { "Workflow form diagnostic binding is inconsistent." }
    }

    override fun toString(): String = "WorkflowRuntimeFormResult(<redacted>)"

    companion object {
        @JvmStatic fun success(code: WorkflowHumanInputResultCode, value: WorkflowRuntimeValidatedForm) =
            WorkflowRuntimeFormResult(code, value, null)
        @JvmStatic fun failed(code: WorkflowHumanInputResultCode, diagnostic: WorkflowHumanInputDiagnostic) =
            WorkflowRuntimeFormResult(code, null, diagnostic)
    }
}

class WorkflowRuntimeCommentResult private constructor(
    val code: WorkflowHumanInputResultCode,
    val comment: WorkflowCommentSnapshot?,
    val diagnostic: WorkflowHumanInputDiagnostic?,
) {
    init {
        require((code == WorkflowHumanInputResultCode.SUCCEEDED || code == WorkflowHumanInputResultCode.REPLAYED) ==
            (comment != null)
        ) { "Workflow comment runtime result is inconsistent." }
        require((comment == null) == (diagnostic != null)) { "Workflow comment diagnostic binding is inconsistent." }
    }

    override fun toString(): String = "WorkflowRuntimeCommentResult(<redacted>)"

    companion object {
        @JvmStatic fun success(code: WorkflowHumanInputResultCode, comment: WorkflowCommentSnapshot) =
            WorkflowRuntimeCommentResult(code, comment, null)
        @JvmStatic fun failed(code: WorkflowHumanInputResultCode, diagnostic: WorkflowHumanInputDiagnostic) =
            WorkflowRuntimeCommentResult(code, null, diagnostic)
    }
}

class WorkflowRuntimeMentionSearchResult private constructor(
    val code: WorkflowHumanInputResultCode,
    val page: WorkflowMentionSearchPage?,
    val diagnostic: WorkflowHumanInputDiagnostic?,
) {
    init {
        require((code == WorkflowHumanInputResultCode.SUCCEEDED) == (page != null)) {
            "Workflow mention-search runtime result is inconsistent."
        }
        require((page == null) == (diagnostic != null)) {
            "Workflow mention-search diagnostic binding is inconsistent."
        }
    }

    override fun toString(): String = "WorkflowRuntimeMentionSearchResult(<redacted>)"

    companion object {
        @JvmStatic fun success(page: WorkflowMentionSearchPage) =
            WorkflowRuntimeMentionSearchResult(WorkflowHumanInputResultCode.SUCCEEDED, page, null)
        @JvmStatic fun failed(code: WorkflowHumanInputResultCode, diagnostic: WorkflowHumanInputDiagnostic) =
            WorkflowRuntimeMentionSearchResult(code, null, diagnostic)
    }
}

class WorkflowRuntimeMentionNotificationResult private constructor(
    val code: WorkflowHumanInputResultCode,
    val delivery: WorkflowNotificationDelivery?,
    val diagnostic: WorkflowHumanInputDiagnostic?,
) {
    init {
        require((code == WorkflowHumanInputResultCode.SUCCEEDED || code == WorkflowHumanInputResultCode.REPLAYED) ==
            (delivery != null)
        ) { "Workflow mention-notification runtime result is inconsistent." }
        require((delivery == null) == (diagnostic != null)) {
            "Workflow mention-notification diagnostic binding is inconsistent."
        }
    }

    override fun toString(): String = "WorkflowRuntimeMentionNotificationResult(<redacted>)"

    companion object {
        @JvmStatic fun success(code: WorkflowHumanInputResultCode, delivery: WorkflowNotificationDelivery) =
            WorkflowRuntimeMentionNotificationResult(code, delivery, null)
        @JvmStatic fun failed(code: WorkflowHumanInputResultCode, diagnostic: WorkflowHumanInputDiagnostic) =
            WorkflowRuntimeMentionNotificationResult(code, null, diagnostic)
    }
}

private fun id(value: String, label: String): String = WorkflowRuntimeSupport.text(
    value,
    WorkflowRuntimeSupport.MAX_ID_BYTES,
    "Workflow $label is invalid.",
)

private fun sha(value: String, label: String): String = WorkflowRuntimeSupport.sha256(
    value,
    "Workflow $label digest is invalid.",
)
