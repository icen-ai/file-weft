package ai.icen.fw.workflow.runtime

/** Durable phase of the external mention-notification call. */
class WorkflowMentionNotificationCheckpointStatus private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow mention checkpoint status is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowMentionNotificationCheckpointStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowMentionNotificationCheckpointStatus(<redacted>)"

    companion object {
        @JvmField val PROVIDER_CALL_STARTED = WorkflowMentionNotificationCheckpointStatus("provider-call-started")
        @JvmField val OUTCOME_UNKNOWN = WorkflowMentionNotificationCheckpointStatus("outcome-unknown")
        @JvmField val ACCEPTED = WorkflowMentionNotificationCheckpointStatus("accepted")
        @JvmField val NOT_SENT = WorkflowMentionNotificationCheckpointStatus("not-sent")
        @JvmField val TERMINAL_FAILURE = WorkflowMentionNotificationCheckpointStatus("terminal-failure")

        @JvmStatic
        fun of(code: String): WorkflowMentionNotificationCheckpointStatus = BUILT_INS.firstOrNull {
            it.code == code
        } ?: throw IllegalArgumentException("Unsupported workflow mention checkpoint status.")

        private val BUILT_INS = listOf(
            PROVIDER_CALL_STARTED,
            OUTCOME_UNKNOWN,
            ACCEPTED,
            NOT_SENT,
            TERMINAL_FAILURE,
        )
    }
}

class WorkflowMentionNotificationCheckpointRecord private constructor(
    tenantId: String,
    idempotencyKey: String,
    operationRequestDigest: String,
    leaseId: String,
    val fencingToken: Long,
    providerRequestDigest: String,
    val status: WorkflowMentionNotificationCheckpointStatus,
    evidenceDigest: String?,
    val recordVersion: Long,
    val checkpointedAtEpochMilli: Long,
    val updatedAtEpochMilli: Long,
) {
    val tenantId: String = mentionCheckpointId(tenantId, "tenant")
    val idempotencyKey: String = mentionCheckpointId(idempotencyKey, "idempotency key")
    val operationRequestDigest: String = mentionCheckpointDigest(operationRequestDigest, "operation request")
    val leaseId: String = mentionCheckpointId(leaseId, "lease")
    val providerRequestDigest: String = mentionCheckpointDigest(providerRequestDigest, "provider request")
    val evidenceDigest: String? = evidenceDigest?.let {
        mentionCheckpointDigest(it, "outcome evidence")
    }
    val checkpointDigest: String

    init {
        require(fencingToken > 0L && recordVersion > 0L && checkpointedAtEpochMilli >= 0L &&
            updatedAtEpochMilli >= checkpointedAtEpochMilli
        ) { "Workflow mention checkpoint version, fence or time is invalid." }
        require((status == WorkflowMentionNotificationCheckpointStatus.PROVIDER_CALL_STARTED) ==
            (this.evidenceDigest == null)
        ) { "Workflow mention checkpoint evidence does not match its status." }
        checkpointDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-mention-checkpoint-record-v1")
            .text(this.tenantId)
            .text(this.idempotencyKey)
            .text(this.operationRequestDigest)
            .text(this.leaseId)
            .longValue(fencingToken)
            .text(this.providerRequestDigest)
            .text(status.code)
            .bool(this.evidenceDigest != null)
            .also { writer -> this.evidenceDigest?.let { writer.text(it) } }
            .longValue(recordVersion)
            .longValue(checkpointedAtEpochMilli)
            .longValue(updatedAtEpochMilli)
            .finish()
    }

    fun matches(reservation: WorkflowHumanInputReservation, providerRequestDigest: String): Boolean =
        tenantId == reservation.tenantId && idempotencyKey == reservation.idempotencyKey &&
            operationRequestDigest == reservation.requestDigest && leaseId == reservation.leaseId &&
            fencingToken == reservation.fencingToken && this.providerRequestDigest == providerRequestDigest

    override fun toString(): String = "WorkflowMentionNotificationCheckpointRecord(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            idempotencyKey: String,
            operationRequestDigest: String,
            leaseId: String,
            fencingToken: Long,
            providerRequestDigest: String,
            status: WorkflowMentionNotificationCheckpointStatus,
            evidenceDigest: String?,
            recordVersion: Long,
            checkpointedAtEpochMilli: Long,
            updatedAtEpochMilli: Long,
        ): WorkflowMentionNotificationCheckpointRecord = WorkflowMentionNotificationCheckpointRecord(
            tenantId,
            idempotencyKey,
            operationRequestDigest,
            leaseId,
            fencingToken,
            providerRequestDigest,
            status,
            evidenceDigest,
            recordVersion,
            checkpointedAtEpochMilli,
            updatedAtEpochMilli,
        )

        @JvmStatic
        fun restore(
            tenantId: String,
            idempotencyKey: String,
            operationRequestDigest: String,
            leaseId: String,
            fencingToken: Long,
            providerRequestDigest: String,
            status: WorkflowMentionNotificationCheckpointStatus,
            evidenceDigest: String?,
            recordVersion: Long,
            checkpointedAtEpochMilli: Long,
            updatedAtEpochMilli: Long,
            expectedCheckpointDigest: String,
        ): WorkflowMentionNotificationCheckpointRecord {
            val value = of(
                tenantId,
                idempotencyKey,
                operationRequestDigest,
                leaseId,
                fencingToken,
                providerRequestDigest,
                status,
                evidenceDigest,
                recordVersion,
                checkpointedAtEpochMilli,
                updatedAtEpochMilli,
            )
            require(value.checkpointDigest == mentionCheckpointDigest(expectedCheckpointDigest, "record")) {
                "Workflow mention checkpoint digest is inconsistent."
            }
            return value
        }
    }
}

class WorkflowMentionNotificationProviderCheckpoint private constructor(
    val reservation: WorkflowHumanInputReservation,
    providerRequestDigest: String,
    val checkpointedAtEpochMilli: Long,
) {
    val providerRequestDigest: String = mentionCheckpointDigest(providerRequestDigest, "provider request")
    val requestDigest: String

    init {
        require(reservation.operation == WorkflowHumanInputOperation.MENTION_NOTIFY) {
            "Workflow mention checkpoint requires a mention-notify reservation."
        }
        require(checkpointedAtEpochMilli >= 0L && checkpointedAtEpochMilli <= reservation.expiresAtEpochMilli) {
            "Workflow mention checkpoint time is outside its reservation."
        }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-mention-checkpoint-request-v1")
            .text(reservation.tenantId)
            .text(reservation.idempotencyKey)
            .text(reservation.requestDigest)
            .text(reservation.leaseId)
            .longValue(reservation.fencingToken)
            .text(this.providerRequestDigest)
            .longValue(checkpointedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowMentionNotificationProviderCheckpoint(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            reservation: WorkflowHumanInputReservation,
            providerRequestDigest: String,
            checkpointedAtEpochMilli: Long,
        ): WorkflowMentionNotificationProviderCheckpoint = WorkflowMentionNotificationProviderCheckpoint(
            reservation,
            providerRequestDigest,
            checkpointedAtEpochMilli,
        )
    }
}

class WorkflowMentionNotificationOutcomeUnknown private constructor(
    val checkpoint: WorkflowMentionNotificationCheckpointRecord,
    evidenceDigest: String,
    val observedAtEpochMilli: Long,
) {
    val evidenceDigest: String = mentionCheckpointDigest(evidenceDigest, "unknown outcome evidence")
    val requestDigest: String

    init {
        require(checkpoint.status == WorkflowMentionNotificationCheckpointStatus.PROVIDER_CALL_STARTED &&
            observedAtEpochMilli >= checkpoint.updatedAtEpochMilli
        ) { "Workflow mention unknown-outcome request is not eligible." }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-mention-outcome-unknown-v1")
            .text(checkpoint.checkpointDigest)
            .text(this.evidenceDigest)
            .longValue(observedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowMentionNotificationOutcomeUnknown(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            checkpoint: WorkflowMentionNotificationCheckpointRecord,
            evidenceDigest: String,
            observedAtEpochMilli: Long,
        ): WorkflowMentionNotificationOutcomeUnknown = WorkflowMentionNotificationOutcomeUnknown(
            checkpoint,
            evidenceDigest,
            observedAtEpochMilli,
        )
    }
}

class WorkflowMentionNotificationReconciliationResolution private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow mention reconciliation resolution is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowMentionNotificationReconciliationResolution && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowMentionNotificationReconciliationResolution(<redacted>)"

    companion object {
        @JvmField val ACCEPTED = WorkflowMentionNotificationReconciliationResolution("accepted")
        @JvmField val NOT_SENT = WorkflowMentionNotificationReconciliationResolution("not-sent")
        @JvmField val TERMINAL_FAILURE = WorkflowMentionNotificationReconciliationResolution("terminal-failure")
    }
}

class WorkflowMentionNotificationReconciliation private constructor(
    val checkpoint: WorkflowMentionNotificationCheckpointRecord,
    val resolution: WorkflowMentionNotificationReconciliationResolution,
    val record: WorkflowHumanInputIdempotencyRecord?,
    evidenceDigest: String,
    val reconciledAtEpochMilli: Long,
) {
    val evidenceDigest: String = mentionCheckpointDigest(evidenceDigest, "reconciliation evidence")
    val requestDigest: String

    init {
        require(checkpoint.status == WorkflowMentionNotificationCheckpointStatus.PROVIDER_CALL_STARTED ||
            checkpoint.status == WorkflowMentionNotificationCheckpointStatus.OUTCOME_UNKNOWN
        ) { "Workflow mention checkpoint is not reconcilable." }
        require(reconciledAtEpochMilli >= checkpoint.updatedAtEpochMilli) {
            "Workflow mention reconciliation precedes its checkpoint."
        }
        require((resolution == WorkflowMentionNotificationReconciliationResolution.ACCEPTED) == (record != null)) {
            "Workflow mention reconciliation result binding is inconsistent."
        }
        if (record != null) {
            val receipt = record.notificationReceipt
            require(record.operation == WorkflowHumanInputOperation.MENTION_NOTIFY &&
                record.tenantId == checkpoint.tenantId &&
                record.idempotencyKey == checkpoint.idempotencyKey &&
                record.requestDigest == checkpoint.operationRequestDigest &&
                receipt != null && record.delivery != null &&
                receipt.requestDigest == checkpoint.providerRequestDigest &&
                receipt.resultDigest == record.delivery.deliveryDigest &&
                record.completedAtEpochMilli <= reconciledAtEpochMilli
            ) { "Workflow mention accepted reconciliation evidence is inconsistent." }
        }
        requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-mention-reconciliation-v1")
            .text(checkpoint.checkpointDigest)
            .text(resolution.code)
            .bool(record != null)
            .also { writer -> record?.resultDigest?.let { writer.text(it) } }
            .text(this.evidenceDigest)
            .longValue(reconciledAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowMentionNotificationReconciliation(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            checkpoint: WorkflowMentionNotificationCheckpointRecord,
            resolution: WorkflowMentionNotificationReconciliationResolution,
            record: WorkflowHumanInputIdempotencyRecord?,
            evidenceDigest: String,
            reconciledAtEpochMilli: Long,
        ): WorkflowMentionNotificationReconciliation = WorkflowMentionNotificationReconciliation(
            checkpoint,
            resolution,
            record,
            evidenceDigest,
            reconciledAtEpochMilli,
        )
    }
}

class WorkflowMentionNotificationCheckpointCode private constructor(code: String) {
    val code: String = WorkflowRuntimeSupport.code(code, "Workflow mention checkpoint result is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowMentionNotificationCheckpointCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowMentionNotificationCheckpointCode(<redacted>)"

    companion object {
        @JvmField val APPLIED = WorkflowMentionNotificationCheckpointCode("applied")
        @JvmField val REPLAYED = WorkflowMentionNotificationCheckpointCode("replayed")
        @JvmField val CONFLICT = WorkflowMentionNotificationCheckpointCode("conflict")
        @JvmField val OUTCOME_UNKNOWN = WorkflowMentionNotificationCheckpointCode("outcome-unknown")
    }
}

class WorkflowMentionNotificationCheckpointResult private constructor(
    val code: WorkflowMentionNotificationCheckpointCode,
    val checkpoint: WorkflowMentionNotificationCheckpointRecord?,
) {
    init {
        require((code == WorkflowMentionNotificationCheckpointCode.APPLIED ||
            code == WorkflowMentionNotificationCheckpointCode.REPLAYED) == (checkpoint != null)
        ) { "Workflow mention checkpoint result content is inconsistent." }
    }

    override fun toString(): String = "WorkflowMentionNotificationCheckpointResult(<redacted>)"

    companion object {
        @JvmStatic fun applied(value: WorkflowMentionNotificationCheckpointRecord) =
            WorkflowMentionNotificationCheckpointResult(WorkflowMentionNotificationCheckpointCode.APPLIED, value)
        @JvmStatic fun replayed(value: WorkflowMentionNotificationCheckpointRecord) =
            WorkflowMentionNotificationCheckpointResult(WorkflowMentionNotificationCheckpointCode.REPLAYED, value)
        @JvmStatic fun failed(code: WorkflowMentionNotificationCheckpointCode): WorkflowMentionNotificationCheckpointResult {
            require(code == WorkflowMentionNotificationCheckpointCode.CONFLICT ||
                code == WorkflowMentionNotificationCheckpointCode.OUTCOME_UNKNOWN
            ) { "Workflow mention checkpoint failure code is invalid." }
            return WorkflowMentionNotificationCheckpointResult(code, null)
        }
    }
}

class WorkflowMentionNotificationReconciliationResult private constructor(
    val code: WorkflowMentionNotificationCheckpointCode,
    val checkpoint: WorkflowMentionNotificationCheckpointRecord?,
    val record: WorkflowHumanInputIdempotencyRecord?,
) {
    init {
        val successful = code == WorkflowMentionNotificationCheckpointCode.APPLIED ||
            code == WorkflowMentionNotificationCheckpointCode.REPLAYED
        require(successful == (checkpoint != null) &&
            (record != null) == (checkpoint?.status == WorkflowMentionNotificationCheckpointStatus.ACCEPTED)
        ) { "Workflow mention reconciliation result content is inconsistent." }
    }

    override fun toString(): String = "WorkflowMentionNotificationReconciliationResult(<redacted>)"

    companion object {
        @JvmStatic
        fun applied(
            checkpoint: WorkflowMentionNotificationCheckpointRecord,
            record: WorkflowHumanInputIdempotencyRecord?,
        ) = WorkflowMentionNotificationReconciliationResult(
            WorkflowMentionNotificationCheckpointCode.APPLIED,
            checkpoint,
            record,
        )

        @JvmStatic
        fun replayed(
            checkpoint: WorkflowMentionNotificationCheckpointRecord,
            record: WorkflowHumanInputIdempotencyRecord?,
        ) = WorkflowMentionNotificationReconciliationResult(
            WorkflowMentionNotificationCheckpointCode.REPLAYED,
            checkpoint,
            record,
        )

        @JvmStatic
        fun failed(code: WorkflowMentionNotificationCheckpointCode): WorkflowMentionNotificationReconciliationResult {
            require(code == WorkflowMentionNotificationCheckpointCode.CONFLICT ||
                code == WorkflowMentionNotificationCheckpointCode.OUTCOME_UNKNOWN
            ) { "Workflow mention reconciliation failure code is invalid." }
            return WorkflowMentionNotificationReconciliationResult(code, null, null)
        }
    }
}

/** Additive capability. Legacy human-input ports remain binary compatible but cannot send mentions safely. */
interface WorkflowMentionNotificationCheckpointPort {
    fun loadProviderCheckpoint(
        tenantId: String,
        idempotencyKey: String,
        readAtEpochMilli: Long,
    ): WorkflowMentionNotificationCheckpointRecord?

    fun checkpointProviderCall(
        request: WorkflowMentionNotificationProviderCheckpoint,
    ): WorkflowMentionNotificationCheckpointResult

    fun markProviderOutcomeUnknown(
        request: WorkflowMentionNotificationOutcomeUnknown,
    ): WorkflowMentionNotificationCheckpointResult

    fun reconcileProviderCall(
        request: WorkflowMentionNotificationReconciliation,
    ): WorkflowMentionNotificationReconciliationResult
}

private fun mentionCheckpointId(value: String, label: String): String = WorkflowRuntimeSupport.text(
    value,
    512,
    "Workflow mention checkpoint $label is invalid.",
)

private fun mentionCheckpointDigest(value: String, label: String): String = WorkflowRuntimeSupport.sha256(
    value,
    "Workflow mention checkpoint $label digest is invalid.",
)
