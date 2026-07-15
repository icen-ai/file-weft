package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.domain.WorkflowEffectIntent

class WorkflowEffectDeliveryStatus private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow effect delivery status is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowEffectDeliveryStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowEffectDeliveryStatus(<redacted>)"

    companion object {
        @JvmField val PENDING = WorkflowEffectDeliveryStatus("pending")
        @JvmField val LEASED = WorkflowEffectDeliveryStatus("leased")
        @JvmField val RETRYABLE_FAILURE = WorkflowEffectDeliveryStatus("retryable-failure")
        @JvmField val RETRY_WAIT = WorkflowEffectDeliveryStatus("retry-wait")
        @JvmField val SUCCEEDED = WorkflowEffectDeliveryStatus("succeeded")
        @JvmField val TERMINAL_FAILURE = WorkflowEffectDeliveryStatus("terminal-failure")
        @JvmField val OUTCOME_UNKNOWN = WorkflowEffectDeliveryStatus("outcome-unknown")
        @JvmField val DOMAIN_APPLIED = WorkflowEffectDeliveryStatus("domain-applied")
        @JvmField val RECONCILIATION_INCIDENT = WorkflowEffectDeliveryStatus("reconciliation-incident")
    }
}

class WorkflowEffectExecutionPhase private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow effect execution phase is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowEffectExecutionPhase && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowEffectExecutionPhase(<redacted>)"

    companion object {
        @JvmField val PREPARED = WorkflowEffectExecutionPhase("prepared")
        @JvmField val PROVIDER_CALL_STARTED = WorkflowEffectExecutionPhase("provider-call-started")
    }
}

class WorkflowEffectObservedOutcome private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow effect outcome is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowEffectObservedOutcome && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowEffectObservedOutcome(<redacted>)"

    companion object {
        @JvmField val SUCCEEDED = WorkflowEffectObservedOutcome("succeeded")
        @JvmField val RETRYABLE_FAILURE = WorkflowEffectObservedOutcome("retryable-failure")
        @JvmField val TERMINAL_FAILURE = WorkflowEffectObservedOutcome("terminal-failure")
        @JvmField val OUTCOME_UNKNOWN = WorkflowEffectObservedOutcome("outcome-unknown")
    }
}

class WorkflowEffectOperationCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow effect operation code is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowEffectOperationCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowEffectOperationCode(<redacted>)"

    companion object {
        @JvmField val APPLIED = WorkflowEffectOperationCode("applied")
        @JvmField val AUTHORIZATION_DENIED = WorkflowEffectOperationCode("authorization-denied")
        @JvmField val NOT_FOUND = WorkflowEffectOperationCode("not-found")
        @JvmField val VERSION_CONFLICT = WorkflowEffectOperationCode("version-conflict")
        @JvmField val NOT_ELIGIBLE = WorkflowEffectOperationCode("not-eligible")
        @JvmField val LEASE_MISMATCH = WorkflowEffectOperationCode("lease-mismatch")
        @JvmField val RECONCILIATION_REQUIRED = WorkflowEffectOperationCode("reconciliation-required")
        @JvmField val STORE_OUTCOME_UNKNOWN = WorkflowEffectOperationCode("store-outcome-unknown")
    }
}

class WorkflowEffectLease private constructor(
    leaseId: String,
    workerId: String,
    fencingToken: Long,
    acquiredAt: Long,
    expiresAt: Long,
) {
    val leaseId: String = effectId(leaseId, "lease")
    val workerId: String = effectId(workerId, "worker")
    val fencingToken: Long = WorkflowRuntimeSupport.nonNegative(
        fencingToken,
        "Workflow effect fencing token is invalid.",
    )
    val acquiredAt: Long = effectTime(acquiredAt, "lease acquisition")
    val expiresAt: Long = effectTime(expiresAt, "lease expiry")

    init {
        require(this.fencingToken > 0L && this.expiresAt > this.acquiredAt) {
            "Workflow effect lease is invalid."
        }
    }

    override fun toString(): String = "WorkflowEffectLease(<redacted>)"

    companion object {
        @JvmStatic fun of(
            leaseId: String,
            workerId: String,
            fencingToken: Long,
            acquiredAt: Long,
            expiresAt: Long,
        ): WorkflowEffectLease = WorkflowEffectLease(
            leaseId,
            workerId,
            fencingToken,
            acquiredAt,
            expiresAt,
        )
    }
}

/** Durable effect delivery row reconstructed without exposing a database model. */
class WorkflowEffectRecord private constructor(
    val intent: WorkflowEffectIntent,
    val status: WorkflowEffectDeliveryStatus,
    version: Long,
    val attempt: Int,
    nextAttemptAt: Long?,
    val lease: WorkflowEffectLease?,
    val phase: WorkflowEffectExecutionPhase?,
    val checkpointSequence: Long,
    checkpointDigest: String?,
    outcomeDigest: String?,
    updatedAt: Long,
) {
    val version: Long = WorkflowRuntimeSupport.nonNegative(version, "Workflow effect record version is invalid.")
    val nextAttemptAt: Long? = nextAttemptAt?.let { value -> effectTime(value, "next attempt") }
    val checkpointDigest: String? = checkpointDigest?.let { value -> effectSha(value, "checkpoint") }
    val outcomeDigest: String? = outcomeDigest?.let { value -> effectSha(value, "outcome") }
    val updatedAt: Long = effectTime(updatedAt, "record update")

    init {
        require(attempt >= 0 && checkpointSequence >= 0L && this.updatedAt >= intent.createdAt) {
            "Workflow effect record counters or time are invalid."
        }
        require((checkpointSequence == 0L) == (this.checkpointDigest == null)) {
            "Workflow effect checkpoint sequence and evidence are incomplete."
        }
        when (status) {
            WorkflowEffectDeliveryStatus.PENDING -> require(
                attempt == 0 && lease == null && phase == null && this.nextAttemptAt == null &&
                    checkpointSequence == 0L && this.outcomeDigest == null,
            ) { "Pending workflow effects cannot carry execution state." }
            WorkflowEffectDeliveryStatus.LEASED -> require(
                attempt > 0 && lease != null && phase != null && this.nextAttemptAt == null &&
                    this.outcomeDigest == null,
            ) { "Leased workflow effects require an active lease and phase." }
            WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE -> require(
                attempt > 0 && lease == null && phase == null && this.nextAttemptAt == null &&
                    this.outcomeDigest != null,
            ) { "Retryable workflow failures require a known outcome." }
            WorkflowEffectDeliveryStatus.RETRY_WAIT -> require(
                attempt > 0 && lease == null && phase == null && this.nextAttemptAt != null &&
                    this.nextAttemptAt > this.updatedAt && this.outcomeDigest != null,
            ) { "Workflow retry waits require a scheduled known failure." }
            WorkflowEffectDeliveryStatus.SUCCEEDED,
            WorkflowEffectDeliveryStatus.TERMINAL_FAILURE,
            WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN,
            WorkflowEffectDeliveryStatus.DOMAIN_APPLIED,
            WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT -> require(
                attempt > 0 && lease == null && phase == null && this.nextAttemptAt == null &&
                    this.outcomeDigest != null,
            ) { "Terminal workflow effect states require outcome evidence and no lease." }
            else -> throw IllegalArgumentException("Unknown workflow effect delivery status is unsupported.")
        }
    }

    override fun toString(): String = "WorkflowEffectRecord(<redacted>)"

    companion object {
        @JvmStatic fun restore(
            intent: WorkflowEffectIntent,
            status: WorkflowEffectDeliveryStatus,
            version: Long,
            attempt: Int,
            nextAttemptAt: Long?,
            lease: WorkflowEffectLease?,
            phase: WorkflowEffectExecutionPhase?,
            checkpointSequence: Long,
            checkpointDigest: String?,
            outcomeDigest: String?,
            updatedAt: Long,
        ): WorkflowEffectRecord = WorkflowEffectRecord(
            intent,
            status,
            version,
            attempt,
            nextAttemptAt,
            lease,
            phase,
            checkpointSequence,
            checkpointDigest,
            outcomeDigest,
            updatedAt,
        )

        @JvmStatic fun pending(intent: WorkflowEffectIntent): WorkflowEffectRecord = WorkflowEffectRecord(
            intent,
            WorkflowEffectDeliveryStatus.PENDING,
            0L,
            0,
            null,
            null,
            null,
            0L,
            null,
            null,
            intent.createdAt,
        )
    }
}

class WorkflowEffectClaim private constructor(
    tenantId: String,
    effectId: String,
    expectedRecordVersion: Long,
    requestDigest: String,
    val authorization: WorkflowRuntimeAuthorizationDecision,
    val lease: WorkflowEffectLease,
) {
    val tenantId: String = effectId(tenantId, "tenant")
    val effectId: String = effectId(effectId, "effect")
    val expectedRecordVersion: Long = effectVersion(expectedRecordVersion)
    val requestDigest: String = effectSha(requestDigest, "claim request")

    init {
        require(authorization.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED &&
            authorization.action == WorkflowRuntimeAction.CLAIM_EFFECT &&
            authorization.tenantId == this.tenantId && authorization.requestDigest == this.requestDigest
        ) { "Workflow effect claim authorization is invalid." }
    }

    override fun toString(): String = "WorkflowEffectClaim(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            effectId: String,
            expectedRecordVersion: Long,
            requestDigest: String,
            authorization: WorkflowRuntimeAuthorizationDecision,
            lease: WorkflowEffectLease,
        ): WorkflowEffectClaim = WorkflowEffectClaim(
            tenantId,
            effectId,
            expectedRecordVersion,
            requestDigest,
            authorization,
            lease,
        )
    }
}

class WorkflowEffectCheckpoint private constructor(
    tenantId: String,
    effectId: String,
    expectedRecordVersion: Long,
    requestDigest: String,
    val authorization: WorkflowRuntimeAuthorizationDecision,
    leaseId: String,
    fencingToken: Long,
    sequence: Long,
    val phase: WorkflowEffectExecutionPhase,
    checkpointDigest: String,
    checkpointedAt: Long,
) {
    val tenantId: String = effectId(tenantId, "tenant")
    val effectId: String = effectId(effectId, "effect")
    val expectedRecordVersion: Long = effectVersion(expectedRecordVersion)
    val requestDigest: String = effectSha(requestDigest, "checkpoint request")
    val leaseId: String = effectId(leaseId, "lease")
    val fencingToken: Long = effectFence(fencingToken)
    val sequence: Long = WorkflowRuntimeSupport.nonNegative(sequence, "Workflow checkpoint sequence is invalid.")
    val checkpointDigest: String = effectSha(checkpointDigest, "checkpoint")
    val checkpointedAt: Long = effectTime(checkpointedAt, "checkpoint")

    init {
        require(sequence > 0L &&
            (phase == WorkflowEffectExecutionPhase.PREPARED ||
                phase == WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED) &&
            authorization.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED &&
            authorization.action == WorkflowRuntimeAction.CHECKPOINT_EFFECT &&
            authorization.tenantId == this.tenantId && authorization.requestDigest == this.requestDigest
        ) { "Workflow effect checkpoint is invalid." }
    }

    override fun toString(): String = "WorkflowEffectCheckpoint(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            effectId: String,
            expectedRecordVersion: Long,
            requestDigest: String,
            authorization: WorkflowRuntimeAuthorizationDecision,
            leaseId: String,
            fencingToken: Long,
            sequence: Long,
            phase: WorkflowEffectExecutionPhase,
            checkpointDigest: String,
            checkpointedAt: Long,
        ): WorkflowEffectCheckpoint = WorkflowEffectCheckpoint(
            tenantId,
            effectId,
            expectedRecordVersion,
            requestDigest,
            authorization,
            leaseId,
            fencingToken,
            sequence,
            phase,
            checkpointDigest,
            checkpointedAt,
        )
    }
}

class WorkflowEffectOutcome private constructor(
    tenantId: String,
    effectId: String,
    expectedRecordVersion: Long,
    requestDigest: String,
    val authorization: WorkflowRuntimeAuthorizationDecision,
    leaseId: String,
    fencingToken: Long,
    val outcome: WorkflowEffectObservedOutcome,
    outcomeDigest: String,
    completedAt: Long,
) {
    val tenantId: String = effectId(tenantId, "tenant")
    val effectId: String = effectId(effectId, "effect")
    val expectedRecordVersion: Long = effectVersion(expectedRecordVersion)
    val requestDigest: String = effectSha(requestDigest, "outcome request")
    val leaseId: String = effectId(leaseId, "lease")
    val fencingToken: Long = effectFence(fencingToken)
    val outcomeDigest: String = effectSha(outcomeDigest, "outcome")
    val completedAt: Long = effectTime(completedAt, "outcome")

    init {
        require(outcome == WorkflowEffectObservedOutcome.SUCCEEDED ||
            outcome == WorkflowEffectObservedOutcome.RETRYABLE_FAILURE ||
            outcome == WorkflowEffectObservedOutcome.TERMINAL_FAILURE ||
            outcome == WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN
        ) { "Unknown workflow effect outcome is unsupported." }
        require(authorization.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED &&
            authorization.action == WorkflowRuntimeAction.RECORD_EFFECT_OUTCOME &&
            authorization.tenantId == this.tenantId && authorization.requestDigest == this.requestDigest
        ) { "Workflow effect outcome authorization is invalid." }
    }

    override fun toString(): String = "WorkflowEffectOutcome(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            effectId: String,
            expectedRecordVersion: Long,
            requestDigest: String,
            authorization: WorkflowRuntimeAuthorizationDecision,
            leaseId: String,
            fencingToken: Long,
            outcome: WorkflowEffectObservedOutcome,
            outcomeDigest: String,
            completedAt: Long,
        ): WorkflowEffectOutcome = WorkflowEffectOutcome(
            tenantId,
            effectId,
            expectedRecordVersion,
            requestDigest,
            authorization,
            leaseId,
            fencingToken,
            outcome,
            outcomeDigest,
            completedAt,
        )
    }
}

class WorkflowEffectRetry private constructor(
    tenantId: String,
    effectId: String,
    expectedRecordVersion: Long,
    requestDigest: String,
    val authorization: WorkflowRuntimeAuthorizationDecision,
    leaseId: String?,
    fencingToken: Long?,
    nextAttemptAt: Long,
    retryReasonDigest: String,
    scheduledAt: Long,
) {
    val tenantId: String = effectId(tenantId, "tenant")
    val effectId: String = effectId(effectId, "effect")
    val expectedRecordVersion: Long = effectVersion(expectedRecordVersion)
    val requestDigest: String = effectSha(requestDigest, "retry request")
    val leaseId: String? = leaseId?.let { value -> effectId(value, "job lease") }
    val fencingToken: Long? = fencingToken?.let(::effectFence)
    val nextAttemptAt: Long = effectTime(nextAttemptAt, "next attempt")
    val retryReasonDigest: String = effectSha(retryReasonDigest, "retry reason")
    val scheduledAt: Long = effectTime(scheduledAt, "retry schedule")

    init {
        require((this.leaseId == null) == (this.fencingToken == null) &&
            this.nextAttemptAt > this.scheduledAt &&
            authorization.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED &&
            authorization.action == WorkflowRuntimeAction.RETRY_EFFECT &&
            authorization.tenantId == this.tenantId && authorization.requestDigest == this.requestDigest
        ) { "Workflow effect retry is invalid." }
    }

    override fun toString(): String = "WorkflowEffectRetry(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            effectId: String,
            expectedRecordVersion: Long,
            requestDigest: String,
            authorization: WorkflowRuntimeAuthorizationDecision,
            nextAttemptAt: Long,
            retryReasonDigest: String,
            scheduledAt: Long,
        ): WorkflowEffectRetry = WorkflowEffectRetry(
            tenantId,
            effectId,
            expectedRecordVersion,
            requestDigest,
            authorization,
            null,
            null,
            nextAttemptAt,
            retryReasonDigest,
            scheduledAt,
        )

        /** Fenced worker form. The legacy [of] factory remains for source/binary compatibility. */
        @JvmStatic fun fenced(
            tenantId: String,
            effectId: String,
            expectedRecordVersion: Long,
            requestDigest: String,
            authorization: WorkflowRuntimeAuthorizationDecision,
            leaseId: String,
            fencingToken: Long,
            nextAttemptAt: Long,
            retryReasonDigest: String,
            scheduledAt: Long,
        ): WorkflowEffectRetry = WorkflowEffectRetry(
            tenantId,
            effectId,
            expectedRecordVersion,
            requestDigest,
            authorization,
            leaseId,
            fencingToken,
            nextAttemptAt,
            retryReasonDigest,
            scheduledAt,
        )
    }
}

class WorkflowEffectReconciliationIncident private constructor(
    tenantId: String,
    effectId: String,
    expectedRecordVersion: Long,
    requestDigest: String,
    val authorization: WorkflowRuntimeAuthorizationDecision,
    incidentId: String,
    evidenceDigest: String,
    raisedAt: Long,
) {
    val tenantId: String = effectId(tenantId, "tenant")
    val effectId: String = effectId(effectId, "effect")
    val expectedRecordVersion: Long = effectVersion(expectedRecordVersion)
    val requestDigest: String = effectSha(requestDigest, "reconciliation request")
    val incidentId: String = effectId(incidentId, "incident")
    val evidenceDigest: String = effectSha(evidenceDigest, "reconciliation evidence")
    val raisedAt: Long = effectTime(raisedAt, "reconciliation incident")

    init {
        require(authorization.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED &&
            authorization.action == WorkflowRuntimeAction.RECONCILE_EFFECT &&
            authorization.tenantId == this.tenantId && authorization.requestDigest == this.requestDigest
        ) { "Workflow effect reconciliation authorization is invalid." }
    }

    override fun toString(): String = "WorkflowEffectReconciliationIncident(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            effectId: String,
            expectedRecordVersion: Long,
            requestDigest: String,
            authorization: WorkflowRuntimeAuthorizationDecision,
            incidentId: String,
            evidenceDigest: String,
            raisedAt: Long,
        ): WorkflowEffectReconciliationIncident = WorkflowEffectReconciliationIncident(
            tenantId,
            effectId,
            expectedRecordVersion,
            requestDigest,
            authorization,
            incidentId,
            evidenceDigest,
            raisedAt,
        )
    }
}

class WorkflowEffectOperationResult private constructor(
    val code: WorkflowEffectOperationCode,
    val record: WorkflowEffectRecord?,
) {
    init {
        require((code == WorkflowEffectOperationCode.APPLIED) == (record != null)) {
            "Workflow effect operation result binding is invalid."
        }
    }

    override fun toString(): String = "WorkflowEffectOperationResult(<redacted>)"

    companion object {
        @JvmStatic fun applied(record: WorkflowEffectRecord): WorkflowEffectOperationResult =
            WorkflowEffectOperationResult(WorkflowEffectOperationCode.APPLIED, record)

        @JvmStatic fun failed(code: WorkflowEffectOperationCode): WorkflowEffectOperationResult {
            require(code != WorkflowEffectOperationCode.APPLIED) { "Applied is not a failure code." }
            return WorkflowEffectOperationResult(code, null)
        }
    }
}

private fun effectId(value: String, label: String): String = WorkflowRuntimeSupport.text(
    value,
    WorkflowRuntimeSupport.MAX_ID_BYTES,
    "Workflow effect $label id is invalid.",
)

private fun effectSha(value: String, label: String): String = WorkflowRuntimeSupport.sha256(
    value,
    "Workflow effect $label digest is invalid.",
)

private fun effectTime(value: Long, label: String): Long = WorkflowRuntimeSupport.nonNegative(
    value,
    "Workflow effect $label time is invalid.",
)

private fun effectVersion(value: Long): Long = WorkflowRuntimeSupport.nonNegative(
    value,
    "Workflow expected effect record version is invalid.",
)

private fun effectFence(value: Long): Long = WorkflowRuntimeSupport.nonNegative(
    value,
    "Workflow effect fencing token is invalid.",
).also { token -> require(token > 0L) { "Workflow effect fencing token must be positive." } }
