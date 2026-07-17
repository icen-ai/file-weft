package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

/** Runtime acknowledgement of one persisted continuation intent after an iteration budget stop. */
class WorkflowContinuationReceipt private constructor(
    receiptId: String,
    effectId: String,
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    requestDigest: String,
    completedAt: Long,
) {
    val receiptId: String = text(receiptId, "receipt")
    val effectId: String = text(effectId, "effect")
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String = text(definitionId, "definition")
    val requestDigest: String = WorkflowDomainSupport.requireSha256(
        requestDigest,
        "Workflow continuation request digest is invalid.",
    )
    val completedAt: Long = WorkflowDomainSupport.requireTime(completedAt, "Workflow continuation time is invalid.")
    val receiptDigest: String = WorkflowDomainSupport.digest("flowweft-workflow-domain-continuation-receipt-v1")
        .text(this.receiptId)
        .text(this.effectId)
        .text(this.tenantId)
        .text(this.instanceId)
        .text(this.definitionId)
        .text(definitionRef.key)
        .text(definitionRef.version)
        .text(definitionRef.digest)
        .text(subject.ref.type)
        .text(subject.ref.id)
        .text(subject.revision)
        .text(subject.digest)
        .text(this.requestDigest)
        .longValue(this.completedAt)
        .finish()

    override fun toString(): String = "WorkflowContinuationReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            receiptId: String,
            effectId: String,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            requestDigest: String,
            completedAt: Long,
        ): WorkflowContinuationReceipt = WorkflowContinuationReceipt(
            receiptId,
            effectId,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            requestDigest,
            completedAt,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow continuation $label identifier is invalid.",
        )
    }
}
