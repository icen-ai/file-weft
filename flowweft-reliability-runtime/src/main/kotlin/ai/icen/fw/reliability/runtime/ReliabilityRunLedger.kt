package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.ReliabilityBackupCreateRequest
import ai.icen.fw.reliability.api.ReliabilityBackupCreationReceipt
import ai.icen.fw.reliability.api.ReliabilityBackupVerifyRequest
import ai.icen.fw.reliability.api.ReliabilityDrillReport
import ai.icen.fw.reliability.api.ReliabilityDrillRequest
import ai.icen.fw.reliability.api.ReliabilityFailure
import ai.icen.fw.reliability.api.ReliabilityManifestVerificationReceipt
import ai.icen.fw.reliability.api.ReliabilityOperationAttemptReference
import ai.icen.fw.reliability.api.ReliabilityOperationKind
import ai.icen.fw.reliability.api.ReliabilityOutcomeUnknownReference
import ai.icen.fw.reliability.api.ReliabilityReconciliationReceipt
import ai.icen.fw.reliability.api.ReliabilityRestoreReceipt
import ai.icen.fw.reliability.api.ReliabilityRestoreRequest

enum class ReliabilityRunStatus {
    READY,
    PROVIDER_CALL_STARTED,
    RECONCILIATION_REQUIRED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
}

class ReliabilityRunLease private constructor(
    ownerId: String,
    val fencingToken: Long,
    val acquiredAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val ownerId: String = ReliabilityRuntimeSupport.opaque(ownerId, "Reliability lease owner is invalid.")
    val leaseDigest: String

    init {
        require(fencingToken > 0L && acquiredAtEpochMilli >= 0L && expiresAtEpochMilli > acquiredAtEpochMilli) {
            "Reliability lease fence or lifetime is invalid."
        }
        leaseDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-run-lease-v1")
            .text(this.ownerId)
            .longValue(fencingToken)
            .longValue(acquiredAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    fun isCurrent(ownerId: String, atEpochMilli: Long): Boolean =
        this.ownerId == ownerId && atEpochMilli >= acquiredAtEpochMilli && atEpochMilli < expiresAtEpochMilli

    companion object {
        @JvmStatic
        fun of(
            ownerId: String,
            fencingToken: Long,
            acquiredAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilityRunLease = ReliabilityRunLease(
            ownerId, fencingToken, acquiredAtEpochMilli, expiresAtEpochMilli,
        )
    }
}

/** Durable provider-call-started checkpoint with the exact API request and original reference. */
class ReliabilityDispatch private constructor(
    val kind: ReliabilityOperationKind,
    providerId: String,
    providerRevision: String,
    providerDescriptorDigest: String,
    val createRequest: ReliabilityBackupCreateRequest?,
    val verifyRequest: ReliabilityBackupVerifyRequest?,
    val restoreRequest: ReliabilityRestoreRequest?,
    val drillRequest: ReliabilityDrillRequest?,
    val originalAttempt: ReliabilityOperationAttemptReference,
) {
    val providerId: String = ReliabilityRuntimeSupport.code(providerId, "Reliability dispatch provider is invalid.")
    val providerRevision: String = ReliabilityRuntimeSupport.text(
        providerRevision, ReliabilityRuntimeSupport.MAX_REVISION_BYTES, "Reliability dispatch revision is invalid.",
    )
    val providerDescriptorDigest: String = ReliabilityRuntimeSupport.sha256(
        providerDescriptorDigest, "Reliability dispatch descriptor digest is invalid.",
    )
    val requestDigest: String
    val dispatchDigest: String

    init {
        val populated = listOf(createRequest, verifyRequest, restoreRequest, drillRequest).count { it != null }
        require(populated == 1 && originalAttempt.kind == kind &&
            originalAttempt.providerId == this.providerId &&
            originalAttempt.providerRevision == this.providerRevision
        ) { "Reliability dispatch payload or provider binding is inconsistent." }
        requestDigest = when (kind) {
            ReliabilityOperationKind.CREATE_BACKUP -> requireNotNull(createRequest).requestDigest
            ReliabilityOperationKind.VERIFY_BACKUP -> requireNotNull(verifyRequest).requestDigest
            ReliabilityOperationKind.RESTORE -> requireNotNull(restoreRequest).requestDigest
            ReliabilityOperationKind.DRILL -> requireNotNull(drillRequest).requestDigest
        }
        require(originalAttempt.requestDigest == requestDigest) {
            "Reliability dispatch attempt does not bind the exact request."
        }
        dispatchDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-dispatch-v1")
            .text(kind.name)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.providerDescriptorDigest)
            .text(requestDigest)
            .text(originalAttempt.attemptDigest)
            .finish()
    }

    override fun toString(): String = "ReliabilityDispatch(kind=$kind, <redacted>)"

    companion object {
        internal fun of(
            intent: ReliabilityOperationIntent,
            request: Any,
            providerOperationId: String,
        ): ReliabilityDispatch {
            val create = request as? ReliabilityBackupCreateRequest
            val verify = request as? ReliabilityBackupVerifyRequest
            val restore = request as? ReliabilityRestoreRequest
            val drill = request as? ReliabilityDrillRequest
            val attempt = when (intent.kind) {
                ReliabilityOperationKind.CREATE_BACKUP -> ReliabilityOperationAttemptReference.forBackup(
                    requireNotNull(create), intent.providerId, intent.providerRevision, providerOperationId,
                )
                ReliabilityOperationKind.VERIFY_BACKUP -> ReliabilityOperationAttemptReference.forVerification(
                    requireNotNull(verify), intent.providerId, intent.providerRevision, providerOperationId,
                )
                ReliabilityOperationKind.RESTORE -> ReliabilityOperationAttemptReference.forRestore(
                    requireNotNull(restore), intent.providerId, intent.providerRevision, providerOperationId,
                )
                ReliabilityOperationKind.DRILL -> ReliabilityOperationAttemptReference.forDrill(
                    requireNotNull(drill), intent.providerId, intent.providerRevision, providerOperationId,
                )
            }
            return ReliabilityDispatch(
                intent.kind,
                intent.providerId,
                intent.providerRevision,
                intent.providerDescriptorDigest,
                create,
                verify,
                restore,
                drill,
                attempt,
            )
        }
    }
}

enum class ReliabilityRunFailureCode {
    AUTHORIZATION_DENIED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_DRIFT,
    CAPABILITY_UNSUPPORTED,
    TOPOLOGY_DRIFT,
    POLICY_DRIFT,
    EVIDENCE_STALE,
    PROVIDER_FAILURE,
    MALFORMED_PROVIDER_RESULT,
    EXECUTION_DEADLINE_EXCEEDED,
    STORE_OUTCOME_UNKNOWN,
}

class ReliabilityRunFailure private constructor(
    val code: ReliabilityRunFailureCode,
    val providerFailure: ReliabilityFailure?,
) {
    val failureDigest: String = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-run-failure-v1")
        .text(code.name)
        .optionalText(providerFailure?.failureDigest)
        .finish()

    override fun toString(): String = "ReliabilityRunFailure(code=$code)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            code: ReliabilityRunFailureCode,
            providerFailure: ReliabilityFailure? = null,
        ): ReliabilityRunFailure = ReliabilityRunFailure(code, providerFailure)
    }
}

/** Exactly one successful operation receipt or one reconciliation receipt is retained. */
class ReliabilityRunOutcome private constructor(
    val backupReceipt: ReliabilityBackupCreationReceipt?,
    val verificationReceipt: ReliabilityManifestVerificationReceipt?,
    val restoreReceipt: ReliabilityRestoreReceipt?,
    val drillReport: ReliabilityDrillReport?,
    val reconciliationReceipt: ReliabilityReconciliationReceipt?,
) {
    val evidenceDigest: String

    init {
        require(listOf(backupReceipt, verificationReceipt, restoreReceipt, drillReport, reconciliationReceipt)
            .count { it != null } == 1
        ) { "Reliability run outcome requires exactly one receipt." }
        evidenceDigest = backupReceipt?.receiptDigest ?: verificationReceipt?.receiptDigest ?:
            restoreReceipt?.receiptDigest ?: drillReport?.reportDigest ?:
            requireNotNull(reconciliationReceipt).receiptDigest
    }

    companion object {
        @JvmStatic fun backup(value: ReliabilityBackupCreationReceipt): ReliabilityRunOutcome =
            ReliabilityRunOutcome(value, null, null, null, null)
        @JvmStatic fun verification(value: ReliabilityManifestVerificationReceipt): ReliabilityRunOutcome =
            ReliabilityRunOutcome(null, value, null, null, null)
        @JvmStatic fun restore(value: ReliabilityRestoreReceipt): ReliabilityRunOutcome =
            ReliabilityRunOutcome(null, null, value, null, null)
        @JvmStatic fun drill(value: ReliabilityDrillReport): ReliabilityRunOutcome =
            ReliabilityRunOutcome(null, null, null, value, null)
        @JvmStatic fun reconciliation(value: ReliabilityReconciliationReceipt): ReliabilityRunOutcome =
            ReliabilityRunOutcome(null, null, null, null, value)
    }
}

class ReliabilityRun private constructor(
    runId: String,
    val intent: ReliabilityOperationIntent,
    val status: ReliabilityRunStatus,
    val version: Long,
    val lease: ReliabilityRunLease?,
    val dispatch: ReliabilityDispatch?,
    val outcomeUnknown: ReliabilityOutcomeUnknownReference?,
    val outcome: ReliabilityRunOutcome?,
    val failure: ReliabilityRunFailure?,
    val cancellationRequested: Boolean,
    val createdAtEpochMilli: Long,
    val updatedAtEpochMilli: Long,
) {
    val runId: String = ReliabilityRuntimeSupport.opaque(runId, "Reliability run id is invalid.")
    val tenantId: String = intent.tenantId
    val stateDigest: String

    init {
        require(version >= 0L && createdAtEpochMilli >= 0L && updatedAtEpochMilli >= createdAtEpochMilli) {
            "Reliability run version or timestamps are invalid."
        }
        when (status) {
            ReliabilityRunStatus.READY -> require(
                dispatch == null && outcomeUnknown == null && outcome == null && failure == null,
            ) { "Ready reliability run has terminal or dispatch state." }
            ReliabilityRunStatus.PROVIDER_CALL_STARTED -> require(
                dispatch != null && outcomeUnknown == null && outcome == null && failure == null,
            ) { "Started reliability run lacks the exact dispatch checkpoint." }
            ReliabilityRunStatus.RECONCILIATION_REQUIRED -> require(
                dispatch != null && outcomeUnknown != null && outcome == null && failure == null,
            ) { "Reliability reconciliation state lacks exact outcome-unknown evidence." }
            ReliabilityRunStatus.SUCCEEDED -> require(outcome != null && failure == null) {
                "Successful reliability run lacks a receipt."
            }
            ReliabilityRunStatus.FAILED -> require(outcome == null && failure != null) {
                "Failed reliability run lacks closed failure evidence."
            }
            ReliabilityRunStatus.CANCELLED,
            ReliabilityRunStatus.TIMED_OUT -> require(outcome == null && failure == null) {
                "Cancelled or timed-out reliability run cannot contain an operation result."
            }
        }
        stateDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-run-state-v1")
            .text(this.runId)
            .text(intent.intentDigest)
            .text(status.name)
            .longValue(version)
            .optionalText(lease?.leaseDigest)
            .optionalText(dispatch?.dispatchDigest)
            .optionalText(outcomeUnknown?.referenceDigest)
            .optionalText(outcome?.evidenceDigest)
            .optionalText(failure?.failureDigest)
            .bool(cancellationRequested)
            .longValue(createdAtEpochMilli)
            .longValue(updatedAtEpochMilli)
            .finish()
    }

    fun isTerminal(): Boolean = status == ReliabilityRunStatus.SUCCEEDED ||
        status == ReliabilityRunStatus.FAILED || status == ReliabilityRunStatus.CANCELLED ||
        status == ReliabilityRunStatus.TIMED_OUT

    fun hasCurrentLease(ownerId: String, nowEpochMilli: Long): Boolean =
        lease?.isCurrent(ownerId, nowEpochMilli) == true

    override fun toString(): String = "ReliabilityRun(status=$status, version=$version, <redacted>)"

    companion object {
        @JvmStatic
        fun ready(
            runId: String,
            intent: ReliabilityOperationIntent,
            createdAtEpochMilli: Long,
        ): ReliabilityRun = ReliabilityRun(
            runId, intent, ReliabilityRunStatus.READY, 0L, null, null, null, null, null,
            false, createdAtEpochMilli, createdAtEpochMilli,
        )

        /** Used by repository implementations when atomically granting a monotonically fenced lease. */
        @JvmStatic
        fun claimed(
            current: ReliabilityRun,
            ownerId: String,
            nowEpochMilli: Long,
            leaseUntilEpochMilli: Long,
            fencingToken: Long,
        ): ReliabilityRun {
            require(!current.isTerminal()) { "A terminal reliability run cannot be claimed." }
            return ReliabilityRun(
                current.runId,
                current.intent,
                current.status,
                current.version + 1L,
                ReliabilityRunLease.of(ownerId, fencingToken, nowEpochMilli, leaseUntilEpochMilli),
                current.dispatch,
                current.outcomeUnknown,
                current.outcome,
                current.failure,
                current.cancellationRequested,
                current.createdAtEpochMilli,
                nowEpochMilli,
            )
        }

        internal fun callStarted(
            current: ReliabilityRun,
            dispatch: ReliabilityDispatch,
            nowEpochMilli: Long,
        ): ReliabilityRun = transition(
            current,
            ReliabilityRunStatus.PROVIDER_CALL_STARTED,
            dispatch,
            null,
            null,
            null,
            current.cancellationRequested,
            nowEpochMilli,
        )

        internal fun reconciliationRequired(
            current: ReliabilityRun,
            outcomeUnknown: ReliabilityOutcomeUnknownReference,
            cancellationRequested: Boolean,
            nowEpochMilli: Long,
        ): ReliabilityRun = transition(
            current,
            ReliabilityRunStatus.RECONCILIATION_REQUIRED,
            requireNotNull(current.dispatch),
            outcomeUnknown,
            null,
            null,
            cancellationRequested,
            nowEpochMilli,
        )

        internal fun succeeded(
            current: ReliabilityRun,
            outcome: ReliabilityRunOutcome,
            nowEpochMilli: Long,
        ): ReliabilityRun = transition(
            current, ReliabilityRunStatus.SUCCEEDED, current.dispatch, null, outcome, null,
            current.cancellationRequested, nowEpochMilli,
        )

        internal fun failed(
            current: ReliabilityRun,
            failure: ReliabilityRunFailure,
            nowEpochMilli: Long,
        ): ReliabilityRun = transition(
            current, ReliabilityRunStatus.FAILED, current.dispatch, null, null, failure,
            current.cancellationRequested, nowEpochMilli,
        )

        internal fun cancelled(current: ReliabilityRun, nowEpochMilli: Long): ReliabilityRun = transition(
            current, ReliabilityRunStatus.CANCELLED, null, null, null, null, true, nowEpochMilli,
        )

        internal fun timedOut(current: ReliabilityRun, nowEpochMilli: Long): ReliabilityRun = transition(
            current, ReliabilityRunStatus.TIMED_OUT, null, null, null, null,
            current.cancellationRequested, nowEpochMilli,
        )

        internal fun cancellationRequested(current: ReliabilityRun, nowEpochMilli: Long): ReliabilityRun = transition(
            current,
            current.status,
            current.dispatch,
            current.outcomeUnknown,
            current.outcome,
            current.failure,
            true,
            nowEpochMilli,
        )

        private fun transition(
            current: ReliabilityRun,
            status: ReliabilityRunStatus,
            dispatch: ReliabilityDispatch?,
            outcomeUnknown: ReliabilityOutcomeUnknownReference?,
            outcome: ReliabilityRunOutcome?,
            failure: ReliabilityRunFailure?,
            cancellationRequested: Boolean,
            nowEpochMilli: Long,
        ): ReliabilityRun {
            require(current.lease != null && nowEpochMilli >= current.updatedAtEpochMilli) {
                "Reliability run transition requires a claimed lease and monotonic time."
            }
            return ReliabilityRun(
                current.runId,
                current.intent,
                status,
                current.version + 1L,
                current.lease,
                dispatch,
                outcomeUnknown,
                outcome,
                failure,
                cancellationRequested,
                current.createdAtEpochMilli,
                nowEpochMilli,
            )
        }
    }
}

enum class ReliabilityOutboxType {
    INTENT_READY,
    CALL_STARTED,
    RECONCILIATION_REQUIRED,
    RUN_SUCCEEDED,
    RUN_FAILED,
    RUN_CANCELLED,
    RUN_TIMED_OUT,
    SLO_EVALUATED,
    SLO_ALERTED,
}

class ReliabilityOutboxRecord private constructor(
    outboxId: String,
    val type: ReliabilityOutboxType,
    tenantId: String,
    aggregateId: String,
    aggregateStateDigest: String,
    val aggregateVersion: Long,
    val createdAtEpochMilli: Long,
) {
    val outboxId: String = ReliabilityRuntimeSupport.opaque(outboxId, "Reliability outbox id is invalid.")
    val tenantId: String = ReliabilityRuntimeSupport.text(
        tenantId, ReliabilityRuntimeSupport.MAX_ID_BYTES, "Reliability outbox tenant is invalid.",
    )
    val aggregateId: String = ReliabilityRuntimeSupport.opaque(
        aggregateId, "Reliability outbox aggregate id is invalid.",
    )
    val aggregateStateDigest: String = ReliabilityRuntimeSupport.sha256(
        aggregateStateDigest, "Reliability outbox state digest is invalid.",
    )
    val recordDigest: String

    init {
        require(aggregateVersion >= 0L && createdAtEpochMilli >= 0L) {
            "Reliability outbox version or time is invalid."
        }
        recordDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-outbox-v1")
            .text(this.outboxId)
            .text(type.name)
            .text(this.tenantId)
            .text(this.aggregateId)
            .text(this.aggregateStateDigest)
            .longValue(aggregateVersion)
            .longValue(createdAtEpochMilli)
            .finish()
    }

    companion object {
        @JvmStatic
        fun forRun(
            outboxId: String,
            type: ReliabilityOutboxType,
            run: ReliabilityRun,
            createdAtEpochMilli: Long,
        ): ReliabilityOutboxRecord = ReliabilityOutboxRecord(
            outboxId,
            type,
            run.tenantId,
            run.runId,
            run.stateDigest,
            run.version,
            createdAtEpochMilli,
        )

        @JvmStatic
        fun forAggregate(
            outboxId: String,
            type: ReliabilityOutboxType,
            tenantId: String,
            aggregateId: String,
            aggregateStateDigest: String,
            aggregateVersion: Long,
            createdAtEpochMilli: Long,
        ): ReliabilityOutboxRecord = ReliabilityOutboxRecord(
            outboxId,
            type,
            tenantId,
            aggregateId,
            aggregateStateDigest,
            aggregateVersion,
            createdAtEpochMilli,
        )
    }
}
