package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef

/**
 * Trusted runtime acknowledgement that one exact definition passed publish and capability gates.
 * Definition status alone is never sufficient. This public DTO must not be accepted from HTTP or
 * an Agent; the runtime constructs it from authoritative deployment evidence.
 */
class WorkflowDefinitionExecutionReceipt private constructor(
    receiptId: String,
    tenantId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val schemaVersion: Int,
    capabilityDigest: String,
    acceptedAt: Long,
    validUntil: Long,
) {
    val receiptId: String = text(receiptId, "receipt")
    val tenantId: String = text(tenantId, "tenant")
    val definitionId: String = text(definitionId, "definition")
    val capabilityDigest: String = WorkflowDomainSupport.requireSha256(
        capabilityDigest,
        "Workflow definition capability digest is invalid.",
    )
    val acceptedAt: Long = WorkflowDomainSupport.requireTime(acceptedAt, "Workflow acceptance time is invalid.")
    val validUntil: Long = WorkflowDomainSupport.requireTime(validUntil, "Workflow acceptance expiry is invalid.")
    val receiptDigest: String

    init {
        require(schemaVersion > 0) { "Workflow execution receipt schema version is invalid." }
        require(this.validUntil >= this.acceptedAt) { "Workflow execution receipt window is invalid." }
        receiptDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-definition-receipt-v1")
            .text(this.receiptId)
            .text(this.tenantId)
            .text(this.definitionId)
            .text(definitionRef.key)
            .text(definitionRef.version)
            .text(definitionRef.digest)
            .integer(schemaVersion)
            .text(this.capabilityDigest)
            .longValue(this.acceptedAt)
            .longValue(this.validUntil)
            .finish()
    }

    override fun toString(): String = "WorkflowDefinitionExecutionReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            receiptId: String,
            tenantId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            schemaVersion: Int,
            capabilityDigest: String,
            acceptedAt: Long,
            validUntil: Long,
        ): WorkflowDefinitionExecutionReceipt = WorkflowDefinitionExecutionReceipt(
            receiptId,
            tenantId,
            definitionId,
            definitionRef,
            schemaVersion,
            capabilityDigest,
            acceptedAt,
            validUntil,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow definition $label identifier is invalid.",
        )
    }
}
