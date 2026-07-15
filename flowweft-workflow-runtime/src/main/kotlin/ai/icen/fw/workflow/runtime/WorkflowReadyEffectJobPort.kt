package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.domain.WorkflowEffectCode
import java.util.Arrays

/**
 * Durable worker phase. Extension values fail closed unless a configured worker explicitly
 * supports them.
 */
class WorkflowEffectJobExecutionMode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow effect job mode is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowEffectJobExecutionMode && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowEffectJobExecutionMode(<redacted>)"

    companion object {
        @JvmField val EXECUTE_PROVIDER = WorkflowEffectJobExecutionMode("execute-provider")
        @JvmField val APPLY_SUCCEEDED_RESULT = WorkflowEffectJobExecutionMode("apply-succeeded-result")
        @JvmField val SCHEDULE_RETRY = WorkflowEffectJobExecutionMode("schedule-retry")

        @JvmStatic
        fun of(code: String): WorkflowEffectJobExecutionMode = when (code) {
            EXECUTE_PROVIDER.code -> EXECUTE_PROVIDER
            APPLY_SUCCEEDED_RESULT.code -> APPLY_SUCCEEDED_RESULT
            SCHEDULE_RETRY.code -> SCHEDULE_RETRY
            else -> WorkflowEffectJobExecutionMode(code)
        }
    }
}

/** Tenant-scoped bounded queue claim. Construct it only from a trusted worker context. */
class WorkflowReadyEffectJobClaimRequest private constructor(
    tenantId: String,
    val effectCode: WorkflowEffectCode,
    workerId: String,
    claimId: String,
    now: Long,
    leaseExpiresAt: Long,
    maximumJobs: Int,
) {
    val tenantId: String = jobId(tenantId, "tenant")
    val workerId: String = jobId(workerId, "worker")
    val claimId: String = jobId(claimId, "claim")
    val now: Long = jobTime(now, "claim")
    val leaseExpiresAt: Long = jobTime(leaseExpiresAt, "lease expiry")
    val maximumJobs: Int = WorkflowRuntimeSupport.positive(
        maximumJobs,
        MAXIMUM_CLAIM_SIZE,
        "Workflow effect job claim size is invalid.",
    )
    val requestDigest: String

    init {
        require(this.leaseExpiresAt > this.now) { "Workflow effect job lease must expire after its claim." }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-job-claim-v1")
            .text(this.tenantId)
            .text(effectCode.code)
            .text(this.workerId)
            .text(this.claimId)
            .longValue(this.now)
            .longValue(this.leaseExpiresAt)
            .integer(this.maximumJobs)
            .finish()
    }

    override fun toString(): String = "WorkflowReadyEffectJobClaimRequest(<redacted>)"

    companion object {
        const val MAXIMUM_CLAIM_SIZE: Int = 128

        @JvmStatic
        fun of(
            tenantId: String,
            effectCode: WorkflowEffectCode,
            workerId: String,
            claimId: String,
            now: Long,
            leaseExpiresAt: Long,
            maximumJobs: Int,
        ): WorkflowReadyEffectJobClaimRequest = WorkflowReadyEffectJobClaimRequest(
            tenantId,
            effectCode,
            workerId,
            claimId,
            now,
            leaseExpiresAt,
            maximumJobs,
        )
    }
}

/** Opaque durable handler result. Its bytes never enter a log or exception message. */
class WorkflowEffectJobStoredResult private constructor(
    val outcome: WorkflowEffectObservedOutcome,
    resultType: String,
    resultDigest: String,
    payload: ByteArray,
    retryAt: Long?,
    completedAt: Long,
) {
    val resultType: String = WorkflowRuntimeSupport.code(resultType, "Workflow effect result type is invalid.")
    val resultDigest: String = WorkflowRuntimeSupport.sha256(
        resultDigest,
        "Workflow effect result digest is invalid.",
    )
    private val content: ByteArray = payload.copyOf()
    val size: Int = content.size
    val retryAt: Long? = retryAt?.let { value -> jobTime(value, "retry") }
    val completedAt: Long = jobTime(completedAt, "result completion")

    init {
        require(content.isNotEmpty() && content.size <= MAXIMUM_RESULT_BYTES) {
            "Workflow effect result payload is empty or exceeds the limit."
        }
        require(
            outcome == WorkflowEffectObservedOutcome.SUCCEEDED ||
                outcome == WorkflowEffectObservedOutcome.RETRYABLE_FAILURE ||
                outcome == WorkflowEffectObservedOutcome.TERMINAL_FAILURE ||
                outcome == WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN,
        ) { "Workflow effect result outcome is unsupported." }
        require((outcome == WorkflowEffectObservedOutcome.RETRYABLE_FAILURE) == (this.retryAt != null)) {
            "Only retryable workflow effect results require a retry time."
        }
        require(this.retryAt == null || this.retryAt > this.completedAt) {
            "Workflow effect retry must follow result completion."
        }
    }

    fun bytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowEffectJobStoredResult &&
            outcome == other.outcome && resultType == other.resultType &&
            resultDigest == other.resultDigest && content.contentEquals(other.content) &&
            retryAt == other.retryAt && completedAt == other.completedAt

    override fun hashCode(): Int = Arrays.hashCode(content) + 31 * resultDigest.hashCode()
    override fun toString(): String = "WorkflowEffectJobStoredResult(<redacted>)"

    companion object {
        const val MAXIMUM_RESULT_BYTES: Int = 1024 * 1024

        @JvmStatic
        fun of(
            outcome: WorkflowEffectObservedOutcome,
            resultType: String,
            resultDigest: String,
            payload: ByteArray,
            retryAt: Long?,
            completedAt: Long,
        ): WorkflowEffectJobStoredResult = WorkflowEffectJobStoredResult(
            outcome,
            resultType,
            resultDigest,
            payload,
            retryAt,
            completedAt,
        )
    }
}

/** One fenced queue lease. [expectedEffectVersion] is the CAS version used by the coordinator. */
class WorkflowClaimedEffectJob private constructor(
    jobId: String,
    tenantId: String,
    instanceId: String,
    effectId: String,
    val effectCode: WorkflowEffectCode,
    val mode: WorkflowEffectJobExecutionMode,
    jobVersion: Long,
    expectedEffectVersion: Long,
    claimRequestDigest: String,
    val lease: WorkflowEffectLease,
    val storedResult: WorkflowEffectJobStoredResult?,
    claimedAt: Long,
) {
    val jobId: String = jobId(jobId, "job")
    val tenantId: String = jobId(tenantId, "tenant")
    val instanceId: String = jobId(instanceId, "instance")
    val effectId: String = jobId(effectId, "effect")
    val jobVersion: Long = jobVersion(jobVersion)
    val expectedEffectVersion: Long = jobVersion(expectedEffectVersion)
    val claimRequestDigest: String = WorkflowRuntimeSupport.sha256(
        claimRequestDigest,
        "Workflow job claim request digest is invalid.",
    )
    val claimedAt: Long = jobTime(claimedAt, "claim")
    val claimReceiptDigest: String

    init {
        require(this.jobVersion > 0L && lease.acquiredAt == this.claimedAt) {
            "Workflow claimed job version or time is invalid."
        }
        require(
            mode == WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER ||
                mode == WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT &&
                storedResult?.outcome == WorkflowEffectObservedOutcome.SUCCEEDED ||
                mode == WorkflowEffectJobExecutionMode.SCHEDULE_RETRY &&
                storedResult?.outcome == WorkflowEffectObservedOutcome.RETRYABLE_FAILURE,
        ) { "Workflow claimed job mode and stored result do not match." }
        claimReceiptDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-job-receipt-v1")
            .text(this.jobId)
            .text(this.tenantId)
            .text(this.instanceId)
            .text(this.effectId)
            .text(effectCode.code)
            .text(mode.code)
            .longValue(this.jobVersion)
            .longValue(this.expectedEffectVersion)
            .text(this.claimRequestDigest)
            .text(lease.leaseId)
            .text(lease.workerId)
            .longValue(lease.fencingToken)
            .longValue(lease.acquiredAt)
            .longValue(lease.expiresAt)
            .finish()
    }

    fun sameLease(other: WorkflowClaimedEffectJob): Boolean =
        tenantId == other.tenantId && jobId == other.jobId && effectId == other.effectId &&
            lease.leaseId == other.lease.leaseId && lease.fencingToken == other.lease.fencingToken &&
            claimReceiptDigest == other.claimReceiptDigest

    override fun toString(): String = "WorkflowClaimedEffectJob(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            jobId: String,
            tenantId: String,
            instanceId: String,
            effectId: String,
            effectCode: WorkflowEffectCode,
            mode: WorkflowEffectJobExecutionMode,
            jobVersion: Long,
            expectedEffectVersion: Long,
            claimRequestDigest: String,
            lease: WorkflowEffectLease,
            storedResult: WorkflowEffectJobStoredResult?,
            claimedAt: Long,
        ): WorkflowClaimedEffectJob = WorkflowClaimedEffectJob(
            jobId,
            tenantId,
            instanceId,
            effectId,
            effectCode,
            mode,
            jobVersion,
            expectedEffectVersion,
            claimRequestDigest,
            lease,
            storedResult,
            claimedAt,
        )
    }
}

class WorkflowEffectJobResultCheckpoint private constructor(
    val claim: WorkflowClaimedEffectJob,
    expectedEffectVersion: Long,
    val result: WorkflowEffectJobStoredResult,
    storedAt: Long,
) {
    val expectedEffectVersion: Long = jobVersion(expectedEffectVersion)
    val storedAt: Long = jobTime(storedAt, "result store")

    init {
        require(claim.mode == WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER &&
            this.expectedEffectVersion >= claim.expectedEffectVersion &&
            result.completedAt >= claim.claimedAt && this.storedAt >= result.completedAt &&
            this.storedAt < claim.lease.expiresAt
        ) { "Workflow effect result checkpoint is outside its fenced execution lease." }
    }

    override fun toString(): String = "WorkflowEffectJobResultCheckpoint(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            claim: WorkflowClaimedEffectJob,
            expectedEffectVersion: Long,
            result: WorkflowEffectJobStoredResult,
            storedAt: Long,
        ): WorkflowEffectJobResultCheckpoint = WorkflowEffectJobResultCheckpoint(
            claim,
            expectedEffectVersion,
            result,
            storedAt,
        )
    }
}

class WorkflowEffectJobStoreCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow effect result store code is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowEffectJobStoreCode && code == other.code
    override fun hashCode(): Int = code.hashCode()

    companion object {
        @JvmField val STORED = WorkflowEffectJobStoreCode("stored")
        @JvmField val REPLAYED = WorkflowEffectJobStoreCode("replayed")
        @JvmField val LEASE_MISMATCH = WorkflowEffectJobStoreCode("lease-mismatch")
        @JvmField val CONFLICT = WorkflowEffectJobStoreCode("conflict")
    }
}

class WorkflowEffectJobStoreResult private constructor(
    val code: WorkflowEffectJobStoreCode,
    val storedResult: WorkflowEffectJobStoredResult?,
) {
    init {
        require((code == WorkflowEffectJobStoreCode.STORED || code == WorkflowEffectJobStoreCode.REPLAYED) ==
            (storedResult != null)
        ) { "Workflow effect result store response is inconsistent." }
    }

    override fun toString(): String = "WorkflowEffectJobStoreResult(<redacted>)"

    companion object {
        @JvmStatic fun stored(result: WorkflowEffectJobStoredResult): WorkflowEffectJobStoreResult =
            WorkflowEffectJobStoreResult(WorkflowEffectJobStoreCode.STORED, result)

        @JvmStatic fun replayed(result: WorkflowEffectJobStoredResult): WorkflowEffectJobStoreResult =
            WorkflowEffectJobStoreResult(WorkflowEffectJobStoreCode.REPLAYED, result)

        @JvmStatic fun failed(code: WorkflowEffectJobStoreCode): WorkflowEffectJobStoreResult {
            require(code != WorkflowEffectJobStoreCode.STORED && code != WorkflowEffectJobStoreCode.REPLAYED)
            return WorkflowEffectJobStoreResult(code, null)
        }
    }
}

/**
 * Narrow durable queue/result port. Implementations use short local transactions only and must
 * never invoke a resolver, provider, authorization callback or dispatcher.
 */
interface WorkflowReadyEffectJobPort {
    fun claimReady(request: WorkflowReadyEffectJobClaimRequest): List<WorkflowClaimedEffectJob>

    fun storeResult(checkpoint: WorkflowEffectJobResultCheckpoint): WorkflowEffectJobStoreResult

    /** Re-reads a batch whose queue-claim commit returned an unknown outcome. */
    fun loadClaims(request: WorkflowReadyEffectJobClaimRequest, readAt: Long): List<WorkflowClaimedEffectJob>

    /**
     * Re-reads exact durable claim/result evidence after an unknown commit outcome. A different
     * lease/fence or digest is a conflict, never permission to repeat the provider call.
     */
    fun loadClaim(tenantId: String, jobId: String, readAt: Long): WorkflowClaimedEffectJob?
}

private fun jobId(value: String, label: String): String = WorkflowRuntimeSupport.text(
    value,
    WorkflowRuntimeSupport.MAX_ID_BYTES,
    "Workflow $label identifier is invalid.",
)

private fun jobTime(value: Long, label: String): Long = WorkflowRuntimeSupport.nonNegative(
    value,
    "Workflow $label time is invalid.",
)

private fun jobVersion(value: Long): Long = WorkflowRuntimeSupport.nonNegative(
    value,
    "Workflow job version is invalid.",
)
