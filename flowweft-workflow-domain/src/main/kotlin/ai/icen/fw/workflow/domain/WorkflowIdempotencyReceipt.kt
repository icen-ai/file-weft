package ai.icen.fw.workflow.domain

/**
 * Runtime-supplied result of an authoritative idempotency lookup/claim.
 *
 * This caller-constructible DTO is trusted only at the runtime/domain boundary. A fresh receipt
 * must be persisted transactionally with the returned state and events. An applied receipt allows
 * replay only when the exact command code and digest match.
 */
class WorkflowIdempotencyReceipt private constructor(
    tenantId: String,
    instanceId: String,
    idempotencyKey: String,
    val status: WorkflowIdempotencyStatus,
    val commandCode: WorkflowCommandCode?,
    commandDigest: String?,
    resultVersion: Long?,
    checkedAt: Long,
) {
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val idempotencyKey: String = text(idempotencyKey, "idempotency key")
    val commandDigest: String? = commandDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(value, "Workflow idempotency command digest is invalid.")
    }
    val resultVersion: Long? = resultVersion?.let { value ->
        WorkflowDomainSupport.requireVersion(value, "Workflow idempotency result version is invalid.")
    }
    val checkedAt: Long = WorkflowDomainSupport.requireTime(checkedAt, "Workflow idempotency time is invalid.")
    val receiptDigest: String

    init {
        when (status) {
            WorkflowIdempotencyStatus.FRESH -> require(
                commandCode == null && this.commandDigest == null && this.resultVersion == null,
            ) { "Fresh workflow idempotency receipts cannot carry an applied command." }

            WorkflowIdempotencyStatus.APPLIED -> require(
                commandCode != null && this.commandDigest != null && this.resultVersion != null,
            ) { "Applied workflow idempotency receipts require exact command evidence." }

            else -> throw IllegalArgumentException("Unknown workflow idempotency status is unsupported.")
        }
        val writer = WorkflowDomainSupport.digest("flowweft-workflow-domain-idempotency-receipt-v1")
            .text(this.tenantId)
            .text(this.instanceId)
            .text(this.idempotencyKey)
            .text(status.code)
            .optionalText(commandCode?.code)
            .optionalText(this.commandDigest)
            .booleanValue(this.resultVersion != null)
        this.resultVersion?.let(writer::longValue)
        receiptDigest = writer.longValue(this.checkedAt).finish()
    }

    override fun toString(): String = "WorkflowIdempotencyReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun fresh(
            tenantId: String,
            instanceId: String,
            idempotencyKey: String,
            checkedAt: Long,
        ): WorkflowIdempotencyReceipt = WorkflowIdempotencyReceipt(
            tenantId,
            instanceId,
            idempotencyKey,
            WorkflowIdempotencyStatus.FRESH,
            null,
            null,
            null,
            checkedAt,
        )

        @JvmStatic
        fun applied(
            tenantId: String,
            instanceId: String,
            idempotencyKey: String,
            commandCode: WorkflowCommandCode,
            commandDigest: String,
            resultVersion: Long,
            checkedAt: Long,
        ): WorkflowIdempotencyReceipt = WorkflowIdempotencyReceipt(
            tenantId,
            instanceId,
            idempotencyKey,
            WorkflowIdempotencyStatus.APPLIED,
            commandCode,
            commandDigest,
            resultVersion,
            checkedAt,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow idempotency $label is invalid.",
        )
    }
}
