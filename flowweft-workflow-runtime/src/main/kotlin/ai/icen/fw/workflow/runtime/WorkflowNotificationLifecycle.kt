package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowNotificationIntent
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import java.util.ArrayList
import java.util.Collections

private fun notificationId(value: String, label: String): String = WorkflowRuntimeSupport.text(
    value,
    WorkflowRuntimeSupport.MAX_ID_BYTES,
    "Workflow notification $label is invalid.",
)

private fun notificationText(value: String, label: String): String = WorkflowRuntimeSupport.text(
    value,
    WorkflowRuntimeSupport.MAX_TEXT_BYTES,
    "Workflow notification $label is invalid.",
)

private fun notificationSha(value: String, label: String): String = WorkflowRuntimeSupport.sha256(
    value,
    "Workflow notification $label digest is invalid.",
)

private fun notificationTime(value: Long, label: String): Long = WorkflowRuntimeSupport.nonNegative(
    value,
    "Workflow notification $label time is invalid.",
)

class WorkflowNotificationQueueStatus private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow notification queue status is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowNotificationQueueStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowNotificationQueueStatus(<redacted>)"

    companion object {
        @JvmField val QUEUED = WorkflowNotificationQueueStatus("queued")
        @JvmField val LEASED = WorkflowNotificationQueueStatus("leased")
        @JvmField val PROVIDER_CALL_STARTED = WorkflowNotificationQueueStatus("provider-call-started")
        @JvmField val RETRY_WAIT = WorkflowNotificationQueueStatus("retry-wait")
        @JvmField val ACCEPTED = WorkflowNotificationQueueStatus("accepted")
        @JvmField val SUPPRESSED = WorkflowNotificationQueueStatus("suppressed")
        @JvmField val OUTCOME_UNKNOWN = WorkflowNotificationQueueStatus("outcome-unknown")
        @JvmField val TERMINAL_FAILURE = WorkflowNotificationQueueStatus("terminal-failure")
        @JvmField val DELIVERED = WorkflowNotificationQueueStatus("delivered")
        @JvmField val TRANSIENT_BOUNCE = WorkflowNotificationQueueStatus("transient-bounce")
        @JvmField val PERMANENT_BOUNCE = WorkflowNotificationQueueStatus("permanent-bounce")

        @JvmStatic fun of(code: String): WorkflowNotificationQueueStatus = builtIns.firstOrNull { it.code == code }
            ?: WorkflowNotificationQueueStatus(code)

        private val builtIns = listOf(
            QUEUED,
            LEASED,
            PROVIDER_CALL_STARTED,
            RETRY_WAIT,
            ACCEPTED,
            SUPPRESSED,
            OUTCOME_UNKNOWN,
            TERMINAL_FAILURE,
            DELIVERED,
            TRANSIENT_BOUNCE,
            PERMANENT_BOUNCE,
        )
    }
}

/** Exact trusted provider identity and explicit budgets; no process-wide defaults are read. */
class WorkflowNotificationProviderProfile private constructor(
    providerId: String,
    providerRevision: String,
    val callWindowMillis: Long,
    val maximumInputBytes: Int,
    val maximumOutputBytes: Int,
    val maximumRecipients: Int,
    val maximumAttempts: Int,
    val retryDelayMillis: Long,
) {
    val providerId: String = WorkflowRuntimeSupport.code(providerId, "Workflow notification provider id is invalid.")
    val providerRevision: String = notificationText(providerRevision, "provider revision")

    init {
        require(callWindowMillis in 1L..300_000L) { "Workflow notification call window is invalid." }
        require(maximumInputBytes in 1..4 * 1024 * 1024 && maximumOutputBytes in 1..4 * 1024 * 1024) {
            "Workflow notification byte budget is invalid."
        }
        require(maximumRecipients in 1..256 && maximumAttempts in 1..100 && retryDelayMillis in 1L..86_400_000L) {
            "Workflow notification recipient or retry budget is invalid."
        }
    }

    override fun toString(): String = "WorkflowNotificationProviderProfile(<redacted>)"

    companion object {
        @JvmStatic fun of(
            providerId: String,
            providerRevision: String,
            callWindowMillis: Long,
            maximumInputBytes: Int,
            maximumOutputBytes: Int,
            maximumRecipients: Int,
            maximumAttempts: Int,
            retryDelayMillis: Long,
        ): WorkflowNotificationProviderProfile = WorkflowNotificationProviderProfile(
            providerId,
            providerRevision,
            callWindowMillis,
            maximumInputBytes,
            maximumOutputBytes,
            maximumRecipients,
            maximumAttempts,
            retryDelayMillis,
        )
    }
}

/** One-recipient durable envelope. Fan-out prevents recipient leakage and permits per-user revocation. */
class WorkflowNotificationEnvelope private constructor(
    envelopeId: String,
    deduplicationKey: String,
    originIntentDigest: String,
    val intent: WorkflowNotificationIntent,
    val recipient: WorkflowPrincipalRef,
    val issuer: WorkflowPrincipalRef,
    val enqueuedAt: Long,
) {
    val envelopeId: String = notificationId(envelopeId, "envelope id")
    val deduplicationKey: String = notificationId(deduplicationKey, "deduplication key")
    val originIntentDigest: String = notificationSha(originIntentDigest, "origin intent")
    val envelopeDigest: String

    init {
        require(intent.recipients == listOf(recipient)) {
            "Workflow notification envelopes must contain exactly their bound recipient."
        }
        require(intent.intentId == this.envelopeId && intent.idempotencyKey == this.deduplicationKey) {
            "Workflow notification envelope identity does not match its delivery intent."
        }
        require(enqueuedAt >= intent.createdAtEpochMilli) { "Workflow notification enqueue predates its intent." }
        envelopeDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-envelope-v1")
            .text(this.envelopeId)
            .text(this.deduplicationKey)
            .text(this.originIntentDigest)
            .text(intent.intentDigest)
            .text(recipient.type)
            .text(recipient.id)
            .text(issuer.type)
            .text(issuer.id)
            .longValue(enqueuedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowNotificationEnvelope(<redacted>)"

    companion object {
        /** Strict persistence factory; constructor invariants and all digest bindings are re-evaluated. */
        @JvmStatic fun restore(
            envelopeId: String,
            deduplicationKey: String,
            originIntentDigest: String,
            intent: WorkflowNotificationIntent,
            recipient: WorkflowPrincipalRef,
            issuer: WorkflowPrincipalRef,
            enqueuedAt: Long,
        ): WorkflowNotificationEnvelope = WorkflowNotificationEnvelope(
            envelopeId,
            deduplicationKey,
            originIntentDigest,
            intent,
            recipient,
            issuer,
            enqueuedAt,
        )

        /** Deterministic, order-preserving fan-out. IDs bind the original idempotency key, not its mutable payload. */
        @JvmStatic fun fanOut(
            origin: WorkflowNotificationIntent,
            issuer: WorkflowPrincipalRef,
            enqueuedAt: Long,
        ): List<WorkflowNotificationEnvelope> {
            val result = ArrayList<WorkflowNotificationEnvelope>(origin.recipients.size)
            origin.recipients.forEach { recipient ->
                val deduplicationKey = WorkflowRuntimeSupport.digest(
                    "flowweft-workflow-runtime-notification-deduplication-v1",
                )
                    .text(origin.idempotencyKey)
                    .text(recipient.type)
                    .text(recipient.id)
                    .finish()
                val envelopeId = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-envelope-id-v1")
                    .text(origin.intentId)
                    .text(recipient.type)
                    .text(recipient.id)
                    .finish()
                val deliveryIntent = WorkflowNotificationIntent.of(
                    envelopeId,
                    deduplicationKey,
                    origin.template,
                    origin.channel,
                    listOf(recipient),
                    origin.subject,
                    origin.safeFields,
                    origin.createdAtEpochMilli,
                )
                result += WorkflowNotificationEnvelope(
                    envelopeId,
                    deduplicationKey,
                    origin.intentDigest,
                    deliveryIntent,
                    recipient,
                    issuer,
                    enqueuedAt,
                )
            }
            return Collections.unmodifiableList(result)
        }
    }
}

class WorkflowNotificationEnqueueBatch private constructor(
    tenantId: String,
    originIdempotencyKey: String,
    originIntentDigest: String,
    envelopes: Collection<WorkflowNotificationEnvelope>,
    authorizationEvidenceDigest: String,
    val enqueuedAt: Long,
) {
    val tenantId: String = notificationId(tenantId, "tenant id")
    val originIdempotencyKey: String = notificationId(originIdempotencyKey, "origin idempotency key")
    val originIntentDigest: String = notificationSha(originIntentDigest, "origin intent")
    val authorizationEvidenceDigest: String = notificationSha(authorizationEvidenceDigest, "enqueue authorization")
    val envelopes: List<WorkflowNotificationEnvelope> = Collections.unmodifiableList(ArrayList(envelopes))
    val batchDigest: String

    init {
        require(this.envelopes.isNotEmpty() && this.envelopes.size <= 256) {
            "Workflow notification enqueue batch size is invalid."
        }
        require(this.envelopes.map { it.deduplicationKey }.toSet().size == this.envelopes.size) {
            "Workflow notification enqueue batch contains duplicate recipients."
        }
        require(this.envelopes.all {
            it.originIntentDigest == this.originIntentDigest && it.enqueuedAt == enqueuedAt
        }) { "Workflow notification enqueue batch binding is inconsistent." }
        batchDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-batch-v1")
            .text(this.tenantId)
            .text(this.originIdempotencyKey)
            .text(this.originIntentDigest)
            .integer(this.envelopes.size)
            .also { writer -> this.envelopes.forEach { writer.text(it.envelopeDigest) } }
            .text(this.authorizationEvidenceDigest)
            .longValue(enqueuedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowNotificationEnqueueBatch(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            originIdempotencyKey: String,
            originIntentDigest: String,
            envelopes: Collection<WorkflowNotificationEnvelope>,
            authorizationEvidenceDigest: String,
            enqueuedAt: Long,
        ): WorkflowNotificationEnqueueBatch = WorkflowNotificationEnqueueBatch(
            tenantId,
            originIdempotencyKey,
            originIntentDigest,
            envelopes,
            authorizationEvidenceDigest,
            enqueuedAt,
        )
    }
}

class WorkflowNotificationAudienceStatus private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow notification audience status is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowNotificationAudienceStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowNotificationAudienceStatus(<redacted>)"

    companion object {
        @JvmField val VISIBLE = WorkflowNotificationAudienceStatus("visible")
        @JvmField val REVOKED = WorkflowNotificationAudienceStatus("revoked")
    }
}

class WorkflowNotificationAudienceRequest private constructor(
    tenantId: String,
    val recipient: WorkflowPrincipalRef,
    val subject: ai.icen.fw.workflow.api.WorkflowSubjectSnapshot?,
    envelopeDigest: String,
    val evaluatedAt: Long,
) {
    val tenantId: String = notificationId(tenantId, "audience tenant id")
    val envelopeDigest: String = notificationSha(envelopeDigest, "audience envelope")
    val requestDigest: String = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-audience-v1")
        .text(this.tenantId)
        .text(recipient.type)
        .text(recipient.id)
        .optional(subject?.ref?.type)
        .optional(subject?.ref?.id)
        .optional(subject?.revision)
        .optional(subject?.digest)
        .text(this.envelopeDigest)
        .longValue(evaluatedAt)
        .finish()

    override fun toString(): String = "WorkflowNotificationAudienceRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            recipient: WorkflowPrincipalRef,
            subject: ai.icen.fw.workflow.api.WorkflowSubjectSnapshot?,
            envelopeDigest: String,
            evaluatedAt: Long,
        ): WorkflowNotificationAudienceRequest = WorkflowNotificationAudienceRequest(
            tenantId,
            recipient,
            subject,
            envelopeDigest,
            evaluatedAt,
        )
    }
}

class WorkflowNotificationAudienceDecision private constructor(
    tenantId: String,
    val recipient: WorkflowPrincipalRef,
    requestDigest: String,
    val status: WorkflowNotificationAudienceStatus,
    authorityRevision: String,
    authorityEvidenceDigest: String,
    val evaluatedAt: Long,
    val validUntil: Long,
) {
    val tenantId: String = notificationId(tenantId, "audience tenant id")
    val requestDigest: String = notificationSha(requestDigest, "audience request")
    val authorityRevision: String = notificationText(authorityRevision, "audience authority revision")
    val authorityEvidenceDigest: String = notificationSha(authorityEvidenceDigest, "audience authority evidence")
    val decisionDigest: String

    init {
        require(status == WorkflowNotificationAudienceStatus.VISIBLE || status == WorkflowNotificationAudienceStatus.REVOKED) {
            "Unknown workflow notification audience status is unsupported."
        }
        require(validUntil >= evaluatedAt) { "Workflow notification audience window is invalid." }
        decisionDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-audience-decision-v1")
            .text(this.tenantId)
            .text(recipient.type)
            .text(recipient.id)
            .text(this.requestDigest)
            .text(status.code)
            .text(this.authorityRevision)
            .text(this.authorityEvidenceDigest)
            .longValue(evaluatedAt)
            .longValue(validUntil)
            .finish()
    }

    fun matches(request: WorkflowNotificationAudienceRequest, now: Long): Boolean =
        tenantId == request.tenantId && recipient == request.recipient && requestDigest == request.requestDigest &&
            evaluatedAt <= now && now <= validUntil

    override fun toString(): String = "WorkflowNotificationAudienceDecision(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            recipient: WorkflowPrincipalRef,
            requestDigest: String,
            status: WorkflowNotificationAudienceStatus,
            authorityRevision: String,
            authorityEvidenceDigest: String,
            evaluatedAt: Long,
            validUntil: Long,
        ): WorkflowNotificationAudienceDecision = WorkflowNotificationAudienceDecision(
            tenantId,
            recipient,
            requestDigest,
            status,
            authorityRevision,
            authorityEvidenceDigest,
            evaluatedAt,
            validUntil,
        )
    }
}

fun interface WorkflowNotificationAudiencePort {
    /** Must evaluate current recipient access. Missing/hidden/deleted recipients return REVOKED uniformly. */
    fun evaluate(request: WorkflowNotificationAudienceRequest): WorkflowNotificationAudienceDecision
}

class WorkflowNotificationLease private constructor(
    leaseId: String,
    workerId: String,
    val fencingToken: Long,
    val acquiredAt: Long,
    val expiresAt: Long,
) {
    val leaseId: String = notificationId(leaseId, "lease id")
    val workerId: String = notificationId(workerId, "worker id")

    init {
        require(fencingToken > 0L && acquiredAt >= 0L && expiresAt > acquiredAt) {
            "Workflow notification lease is invalid."
        }
    }

    override fun toString(): String = "WorkflowNotificationLease(<redacted>)"

    companion object {
        @JvmStatic fun of(
            leaseId: String,
            workerId: String,
            fencingToken: Long,
            acquiredAt: Long,
            expiresAt: Long,
        ): WorkflowNotificationLease = WorkflowNotificationLease(
            leaseId,
            workerId,
            fencingToken,
            acquiredAt,
            expiresAt,
        )
    }
}

/** Durable projection. Provider payload remains the validated intent; failures store only stable evidence digests. */
class WorkflowNotificationRecord private constructor(
    tenantId: String,
    val envelope: WorkflowNotificationEnvelope,
    val status: WorkflowNotificationQueueStatus,
    val version: Long,
    val attempt: Int,
    val lease: WorkflowNotificationLease?,
    val nextAttemptAt: Long?,
    providerRequestDigest: String?,
    val providerReceipt: WorkflowProviderReceipt?,
    val delivery: WorkflowNotificationDelivery?,
    outcomeEvidenceDigest: String?,
    val updatedAt: Long,
) {
    val tenantId: String = notificationId(tenantId, "record tenant id")
    val providerRequestDigest: String? = providerRequestDigest?.let { notificationSha(it, "provider request") }
    val outcomeEvidenceDigest: String? = outcomeEvidenceDigest?.let { notificationSha(it, "outcome evidence") }

    init {
        require(version >= 0L && attempt >= 0 && updatedAt >= envelope.enqueuedAt) {
            "Workflow notification record counters or time are invalid."
        }
        require((providerReceipt == null) == (delivery == null)) {
            "Workflow notification provider receipt and delivery must be stored together."
        }
        if (providerReceipt != null) {
            require(providerReceipt.tenantId == this.tenantId && providerReceipt.resultDigest == delivery!!.deliveryDigest) {
                "Workflow notification provider evidence is inconsistent."
            }
        }
        when (status) {
            WorkflowNotificationQueueStatus.QUEUED -> require(
                attempt == 0 && lease == null && nextAttemptAt == null && this.providerRequestDigest == null &&
                    providerReceipt == null && this.outcomeEvidenceDigest == null,
            ) { "Queued workflow notification carries execution state." }
            WorkflowNotificationQueueStatus.LEASED -> require(
                attempt > 0 && lease != null && nextAttemptAt == null && this.providerRequestDigest == null &&
                    providerReceipt == null,
            ) { "Leased workflow notification is incomplete." }
            WorkflowNotificationQueueStatus.PROVIDER_CALL_STARTED -> require(
                attempt > 0 && lease != null && nextAttemptAt == null && this.providerRequestDigest != null &&
                    providerReceipt == null,
            ) { "Started workflow notification call is incomplete." }
            WorkflowNotificationQueueStatus.RETRY_WAIT -> require(
                attempt > 0 && lease == null && nextAttemptAt != null && nextAttemptAt > updatedAt &&
                    providerReceipt == null && this.outcomeEvidenceDigest != null,
            ) { "Workflow notification retry state is incomplete." }
            WorkflowNotificationQueueStatus.ACCEPTED -> require(
                attempt > 0 && lease == null && nextAttemptAt == null && providerReceipt != null &&
                    delivery != null && this.outcomeEvidenceDigest != null,
            ) { "Accepted workflow notification is incomplete." }
            WorkflowNotificationQueueStatus.SUPPRESSED -> require(
                lease == null && nextAttemptAt == null && this.outcomeEvidenceDigest != null,
            ) { "Suppressed workflow notification is incomplete." }
            WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN,
            WorkflowNotificationQueueStatus.TERMINAL_FAILURE -> require(
                attempt > 0 && lease == null && nextAttemptAt == null && providerReceipt == null &&
                    this.outcomeEvidenceDigest != null,
            ) { "Failed workflow notification state is incomplete." }
            WorkflowNotificationQueueStatus.DELIVERED,
            WorkflowNotificationQueueStatus.TRANSIENT_BOUNCE,
            WorkflowNotificationQueueStatus.PERMANENT_BOUNCE -> require(
                attempt > 0 && lease == null && nextAttemptAt == null && providerReceipt != null &&
                    delivery != null && this.outcomeEvidenceDigest != null,
            ) { "Workflow notification delivery report state is incomplete." }
            else -> throw IllegalArgumentException("Unknown workflow notification queue status is unsupported.")
        }
    }

    override fun toString(): String = "WorkflowNotificationRecord(<redacted>)"

    companion object {
        @JvmStatic fun queued(tenantId: String, envelope: WorkflowNotificationEnvelope): WorkflowNotificationRecord =
            WorkflowNotificationRecord(
                tenantId,
                envelope,
                WorkflowNotificationQueueStatus.QUEUED,
                0L,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                envelope.enqueuedAt,
            )

        @JvmStatic fun restore(
            tenantId: String,
            envelope: WorkflowNotificationEnvelope,
            status: WorkflowNotificationQueueStatus,
            version: Long,
            attempt: Int,
            lease: WorkflowNotificationLease?,
            nextAttemptAt: Long?,
            providerRequestDigest: String?,
            providerReceipt: WorkflowProviderReceipt?,
            delivery: WorkflowNotificationDelivery?,
            outcomeEvidenceDigest: String?,
            updatedAt: Long,
        ): WorkflowNotificationRecord = WorkflowNotificationRecord(
            tenantId,
            envelope,
            status,
            version,
            attempt,
            lease,
            nextAttemptAt,
            providerRequestDigest,
            providerReceipt,
            delivery,
            outcomeEvidenceDigest,
            updatedAt,
        )
    }
}

class WorkflowNotificationStoreCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow notification store result is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowNotificationStoreCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowNotificationStoreCode(<redacted>)"

    companion object {
        @JvmField val APPLIED = WorkflowNotificationStoreCode("applied")
        @JvmField val REPLAYED = WorkflowNotificationStoreCode("replayed")
        @JvmField val CONFLICT = WorkflowNotificationStoreCode("conflict")
        @JvmField val NOT_ELIGIBLE = WorkflowNotificationStoreCode("not-eligible")
        @JvmField val LEASE_MISMATCH = WorkflowNotificationStoreCode("lease-mismatch")
    }
}

class WorkflowNotificationStoreResult private constructor(
    val code: WorkflowNotificationStoreCode,
    val record: WorkflowNotificationRecord?,
) {
    init {
        require((code == WorkflowNotificationStoreCode.APPLIED || code == WorkflowNotificationStoreCode.REPLAYED) ==
            (record != null)
        ) { "Workflow notification store result content is inconsistent." }
    }

    override fun toString(): String = "WorkflowNotificationStoreResult(<redacted>)"

    companion object {
        @JvmStatic fun applied(record: WorkflowNotificationRecord): WorkflowNotificationStoreResult =
            WorkflowNotificationStoreResult(WorkflowNotificationStoreCode.APPLIED, record)

        @JvmStatic fun replayed(record: WorkflowNotificationRecord): WorkflowNotificationStoreResult =
            WorkflowNotificationStoreResult(WorkflowNotificationStoreCode.REPLAYED, record)

        @JvmStatic fun failed(code: WorkflowNotificationStoreCode): WorkflowNotificationStoreResult {
            require(code != WorkflowNotificationStoreCode.APPLIED && code != WorkflowNotificationStoreCode.REPLAYED)
            return WorkflowNotificationStoreResult(code, null)
        }
    }
}

class WorkflowNotificationClaim private constructor(
    tenantId: String,
    envelopeId: String,
    val expectedVersion: Long,
    val lease: WorkflowNotificationLease,
    authorizationEvidenceDigest: String,
) {
    val tenantId: String = notificationId(tenantId, "claim tenant id")
    val envelopeId: String = notificationId(envelopeId, "claim envelope id")
    val authorizationEvidenceDigest: String = notificationSha(authorizationEvidenceDigest, "claim authorization")

    init {
        require(expectedVersion >= 0L) { "Workflow notification claim version is invalid." }
    }

    override fun toString(): String = "WorkflowNotificationClaim(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            envelopeId: String,
            expectedVersion: Long,
            lease: WorkflowNotificationLease,
            authorizationEvidenceDigest: String,
        ): WorkflowNotificationClaim = WorkflowNotificationClaim(
            tenantId,
            envelopeId,
            expectedVersion,
            lease,
            authorizationEvidenceDigest,
        )
    }
}

class WorkflowNotificationProviderCheckpoint private constructor(
    tenantId: String,
    envelopeId: String,
    val expectedVersion: Long,
    leaseId: String,
    val fencingToken: Long,
    providerRequestDigest: String,
    authorizationEvidenceDigest: String,
    val checkpointedAt: Long,
) {
    val tenantId: String = notificationId(tenantId, "checkpoint tenant id")
    val envelopeId: String = notificationId(envelopeId, "checkpoint envelope id")
    val leaseId: String = notificationId(leaseId, "checkpoint lease id")
    val providerRequestDigest: String = notificationSha(providerRequestDigest, "provider request")
    val authorizationEvidenceDigest: String = notificationSha(authorizationEvidenceDigest, "delivery authorization")

    init {
        require(expectedVersion >= 0L && fencingToken > 0L && checkpointedAt >= 0L) {
            "Workflow notification provider checkpoint is invalid."
        }
    }

    override fun toString(): String = "WorkflowNotificationProviderCheckpoint(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            envelopeId: String,
            expectedVersion: Long,
            leaseId: String,
            fencingToken: Long,
            providerRequestDigest: String,
            authorizationEvidenceDigest: String,
            checkpointedAt: Long,
        ): WorkflowNotificationProviderCheckpoint = WorkflowNotificationProviderCheckpoint(
            tenantId,
            envelopeId,
            expectedVersion,
            leaseId,
            fencingToken,
            providerRequestDigest,
            authorizationEvidenceDigest,
            checkpointedAt,
        )
    }
}

class WorkflowNotificationCompletion private constructor(
    tenantId: String,
    envelopeId: String,
    val expectedVersion: Long,
    leaseId: String,
    val fencingToken: Long,
    val targetStatus: WorkflowNotificationQueueStatus,
    val providerReceipt: WorkflowProviderReceipt?,
    val delivery: WorkflowNotificationDelivery?,
    outcomeEvidenceDigest: String,
    val nextAttemptAt: Long?,
    val completedAt: Long,
) {
    val tenantId: String = notificationId(tenantId, "completion tenant id")
    val envelopeId: String = notificationId(envelopeId, "completion envelope id")
    val leaseId: String = notificationId(leaseId, "completion lease id")
    val outcomeEvidenceDigest: String = notificationSha(outcomeEvidenceDigest, "completion outcome")

    init {
        require(expectedVersion >= 0L && fencingToken > 0L && completedAt >= 0L) {
            "Workflow notification completion is invalid."
        }
        require((providerReceipt == null) == (delivery == null)) {
            "Workflow notification completion provider evidence is incomplete."
        }
        require(targetStatus == WorkflowNotificationQueueStatus.RETRY_WAIT ||
            targetStatus == WorkflowNotificationQueueStatus.ACCEPTED ||
            targetStatus == WorkflowNotificationQueueStatus.SUPPRESSED ||
            targetStatus == WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN ||
            targetStatus == WorkflowNotificationQueueStatus.TERMINAL_FAILURE
        ) { "Workflow notification completion target is invalid." }
        require((targetStatus == WorkflowNotificationQueueStatus.RETRY_WAIT) == (nextAttemptAt != null)) {
            "Workflow notification completion retry schedule is inconsistent."
        }
        require(nextAttemptAt == null || nextAttemptAt > completedAt) {
            "Workflow notification retry must follow completion."
        }
        require(providerReceipt == null || providerReceipt.resultDigest == delivery!!.deliveryDigest) {
            "Workflow notification completion provider receipt is inconsistent."
        }
        require(targetStatus != WorkflowNotificationQueueStatus.ACCEPTED || providerReceipt != null) {
            "Accepted workflow notification requires provider evidence."
        }
    }

    override fun toString(): String = "WorkflowNotificationCompletion(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            envelopeId: String,
            expectedVersion: Long,
            leaseId: String,
            fencingToken: Long,
            targetStatus: WorkflowNotificationQueueStatus,
            providerReceipt: WorkflowProviderReceipt?,
            delivery: WorkflowNotificationDelivery?,
            outcomeEvidenceDigest: String,
            nextAttemptAt: Long?,
            completedAt: Long,
        ): WorkflowNotificationCompletion = WorkflowNotificationCompletion(
            tenantId,
            envelopeId,
            expectedVersion,
            leaseId,
            fencingToken,
            targetStatus,
            providerReceipt,
            delivery,
            outcomeEvidenceDigest,
            nextAttemptAt,
            completedAt,
        )
    }
}

class WorkflowNotificationDeliveryReport private constructor(
    reportId: String,
    tenantId: String,
    envelopeId: String,
    providerId: String,
    providerRevision: String,
    providerMessageRef: String,
    val status: WorkflowNotificationQueueStatus,
    evidenceDigest: String,
    val observedAt: Long,
) {
    val reportId: String = notificationId(reportId, "delivery report id")
    val tenantId: String = notificationId(tenantId, "delivery report tenant id")
    val envelopeId: String = notificationId(envelopeId, "delivery report envelope id")
    val providerId: String = WorkflowRuntimeSupport.code(providerId, "Workflow notification report provider is invalid.")
    val providerRevision: String = notificationText(providerRevision, "delivery report provider revision")
    val providerMessageRef: String = notificationText(providerMessageRef, "delivery report provider reference")
    val evidenceDigest: String = notificationSha(evidenceDigest, "delivery report evidence")
    val reportDigest: String

    init {
        require(status == WorkflowNotificationQueueStatus.DELIVERED ||
            status == WorkflowNotificationQueueStatus.TRANSIENT_BOUNCE ||
            status == WorkflowNotificationQueueStatus.PERMANENT_BOUNCE
        ) { "Workflow notification delivery report status is invalid." }
        require(observedAt >= 0L) { "Workflow notification delivery report time is invalid." }
        reportDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-report-v1")
            .text(this.reportId)
            .text(this.tenantId)
            .text(this.envelopeId)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.providerMessageRef)
            .text(status.code)
            .text(this.evidenceDigest)
            .longValue(observedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowNotificationDeliveryReport(<redacted>)"

    companion object {
        @JvmStatic fun of(
            reportId: String,
            tenantId: String,
            envelopeId: String,
            providerId: String,
            providerRevision: String,
            providerMessageRef: String,
            status: WorkflowNotificationQueueStatus,
            evidenceDigest: String,
            observedAt: Long,
        ): WorkflowNotificationDeliveryReport = WorkflowNotificationDeliveryReport(
            reportId,
            tenantId,
            envelopeId,
            providerId,
            providerRevision,
            providerMessageRef,
            status,
            evidenceDigest,
            observedAt,
        )
    }
}

class WorkflowNotificationReportMutation private constructor(
    val report: WorkflowNotificationDeliveryReport,
    val expectedVersion: Long,
    authorizationEvidenceDigest: String,
) {
    val authorizationEvidenceDigest: String = notificationSha(authorizationEvidenceDigest, "report authorization")

    init {
        require(expectedVersion >= 0L) { "Workflow notification report version is invalid." }
    }

    override fun toString(): String = "WorkflowNotificationReportMutation(<redacted>)"

    companion object {
        @JvmStatic fun of(
            report: WorkflowNotificationDeliveryReport,
            expectedVersion: Long,
            authorizationEvidenceDigest: String,
        ): WorkflowNotificationReportMutation = WorkflowNotificationReportMutation(
            report,
            expectedVersion,
            authorizationEvidenceDigest,
        )
    }
}

class WorkflowNotificationReconciliationResolution private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow notification reconciliation resolution is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowNotificationReconciliationResolution && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowNotificationReconciliationResolution(<redacted>)"

    companion object {
        @JvmField val ACCEPTED = WorkflowNotificationReconciliationResolution("accepted")
        @JvmField val NOT_SENT = WorkflowNotificationReconciliationResolution("not-sent")
        @JvmField val TERMINAL_FAILURE = WorkflowNotificationReconciliationResolution("terminal-failure")
    }
}

class WorkflowNotificationReconciliation private constructor(
    tenantId: String,
    envelopeId: String,
    val expectedVersion: Long,
    val resolution: WorkflowNotificationReconciliationResolution,
    val providerReceipt: WorkflowProviderReceipt?,
    val delivery: WorkflowNotificationDelivery?,
    evidenceDigest: String,
    authorizationEvidenceDigest: String,
    val nextAttemptAt: Long?,
    val reconciledAt: Long,
) {
    val tenantId: String = notificationId(tenantId, "reconciliation tenant id")
    val envelopeId: String = notificationId(envelopeId, "reconciliation envelope id")
    val evidenceDigest: String = notificationSha(evidenceDigest, "reconciliation evidence")
    val authorizationEvidenceDigest: String = notificationSha(
        authorizationEvidenceDigest,
        "reconciliation authorization",
    )

    init {
        require(expectedVersion >= 0L && reconciledAt >= 0L) { "Workflow notification reconciliation is invalid." }
        require((resolution == WorkflowNotificationReconciliationResolution.ACCEPTED) ==
            (providerReceipt != null && delivery != null)
        ) { "Workflow notification reconciliation provider evidence is inconsistent." }
        require((providerReceipt == null) == (delivery == null) &&
            (providerReceipt == null || providerReceipt.resultDigest == delivery!!.deliveryDigest)
        ) {
            "Workflow notification reconciliation receipt is inconsistent."
        }
        require((resolution == WorkflowNotificationReconciliationResolution.NOT_SENT) == (nextAttemptAt != null)) {
            "Workflow notification reconciliation retry schedule is inconsistent."
        }
        require(nextAttemptAt == null || nextAttemptAt > reconciledAt) {
            "Workflow notification reconciliation retry must follow reconciliation."
        }
    }

    override fun toString(): String = "WorkflowNotificationReconciliation(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            envelopeId: String,
            expectedVersion: Long,
            resolution: WorkflowNotificationReconciliationResolution,
            providerReceipt: WorkflowProviderReceipt?,
            delivery: WorkflowNotificationDelivery?,
            evidenceDigest: String,
            authorizationEvidenceDigest: String,
            nextAttemptAt: Long?,
            reconciledAt: Long,
        ): WorkflowNotificationReconciliation = WorkflowNotificationReconciliation(
            tenantId,
            envelopeId,
            expectedVersion,
            resolution,
            providerReceipt,
            delivery,
            evidenceDigest,
            authorizationEvidenceDigest,
            nextAttemptAt,
            reconciledAt,
        )
    }
}

/** Every mutation is one short CAS transaction. Provider and authorization calls happen outside it. */
interface WorkflowNotificationStore {
    fun enqueue(batch: WorkflowNotificationEnqueueBatch): WorkflowNotificationStoreResult
    fun load(tenantId: String, envelopeId: String, readAt: Long): WorkflowNotificationRecord?
    fun claim(request: WorkflowNotificationClaim): WorkflowNotificationStoreResult
    fun checkpointProviderCall(request: WorkflowNotificationProviderCheckpoint): WorkflowNotificationStoreResult
    fun complete(request: WorkflowNotificationCompletion): WorkflowNotificationStoreResult
    fun recordDeliveryReport(request: WorkflowNotificationReportMutation): WorkflowNotificationStoreResult
    fun reconcile(request: WorkflowNotificationReconciliation): WorkflowNotificationStoreResult
}

class WorkflowNotificationRuntimeCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow notification runtime result is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowNotificationRuntimeCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowNotificationRuntimeCode(<redacted>)"

    companion object {
        @JvmField val COMMITTED = WorkflowNotificationRuntimeCode("committed")
        @JvmField val REPLAYED = WorkflowNotificationRuntimeCode("replayed")
        @JvmField val AUTHORIZATION_DENIED = WorkflowNotificationRuntimeCode("authorization-denied")
        @JvmField val NOT_FOUND = WorkflowNotificationRuntimeCode("not-found")
        @JvmField val NOT_ELIGIBLE = WorkflowNotificationRuntimeCode("not-eligible")
        @JvmField val IDEMPOTENCY_CONFLICT = WorkflowNotificationRuntimeCode("idempotency-conflict")
        @JvmField val PROVIDER_UNAVAILABLE = WorkflowNotificationRuntimeCode("provider-unavailable")
        @JvmField val RECEIPT_INVALID = WorkflowNotificationRuntimeCode("receipt-invalid")
        @JvmField val OUTCOME_UNKNOWN = WorkflowNotificationRuntimeCode("outcome-unknown")
        @JvmField val RECONCILIATION_REQUIRED = WorkflowNotificationRuntimeCode("reconciliation-required")
        @JvmField val STORE_OUTCOME_UNKNOWN = WorkflowNotificationRuntimeCode("store-outcome-unknown")
    }
}

class WorkflowNotificationRuntimeResult private constructor(
    val code: WorkflowNotificationRuntimeCode,
    val record: WorkflowNotificationRecord?,
    diagnosticCode: String?,
) {
    val diagnosticCode: String? = diagnosticCode?.let {
        WorkflowRuntimeSupport.code(it, "Workflow notification diagnostic is invalid.")
    }

    init {
        require((code == WorkflowNotificationRuntimeCode.COMMITTED || code == WorkflowNotificationRuntimeCode.REPLAYED) ==
            (record != null)
        ) { "Workflow notification runtime result content is inconsistent." }
    }

    override fun toString(): String = "WorkflowNotificationRuntimeResult(<redacted>)"

    companion object {
        @JvmStatic fun success(
            code: WorkflowNotificationRuntimeCode,
            record: WorkflowNotificationRecord,
        ): WorkflowNotificationRuntimeResult {
            require(code == WorkflowNotificationRuntimeCode.COMMITTED || code == WorkflowNotificationRuntimeCode.REPLAYED)
            return WorkflowNotificationRuntimeResult(code, record, null)
        }

        @JvmStatic fun failed(
            code: WorkflowNotificationRuntimeCode,
            diagnosticCode: String,
        ): WorkflowNotificationRuntimeResult {
            require(code != WorkflowNotificationRuntimeCode.COMMITTED && code != WorkflowNotificationRuntimeCode.REPLAYED)
            return WorkflowNotificationRuntimeResult(code, null, diagnosticCode)
        }
    }
}
