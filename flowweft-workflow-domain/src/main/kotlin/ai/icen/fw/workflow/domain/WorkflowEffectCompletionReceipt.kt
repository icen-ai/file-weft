package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

/** Exact runtime completion evidence for one pending external effect intent. */
class WorkflowEffectCompletionReceipt private constructor(
    receiptId: String,
    effectId: String,
    val effectCode: WorkflowEffectCode,
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    tokenId: String,
    nodeExecutionId: String,
    nodeId: String,
    effectRequestDigest: String,
    val outcome: WorkflowEffectOutcomeCode,
    selectedTransitionId: String?,
    failureCode: String?,
    outputDigest: String,
    completedAt: Long,
) {
    val receiptId: String = text(receiptId, "receipt")
    val effectId: String = text(effectId, "effect")
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String = text(definitionId, "definition")
    val tokenId: String = text(tokenId, "token")
    val nodeExecutionId: String = text(nodeExecutionId, "node execution")
    val nodeId: String = WorkflowDomainSupport.requireCode(nodeId, "Workflow completion node id is invalid.")
    val effectRequestDigest: String = sha(effectRequestDigest, "effect request")
    val selectedTransitionId: String? = selectedTransitionId?.let { value ->
        WorkflowDomainSupport.requireCode(value, "Workflow selected transition id is invalid.")
    }
    val failureCode: String? = failureCode?.let { value ->
        WorkflowDomainSupport.requireCode(value, "Workflow effect failure code is invalid.")
    }
    val outputDigest: String = sha(outputDigest, "effect output")
    val completedAt: Long = WorkflowDomainSupport.requireTime(completedAt, "Workflow effect completion time is invalid.")
    val receiptDigest: String

    init {
        when (outcome) {
            WorkflowEffectOutcomeCode.SUCCESS -> require(this.failureCode == null) {
                "Successful workflow effects cannot carry a failure code."
            }

            WorkflowEffectOutcomeCode.FAILURE -> require(
                this.failureCode != null && this.selectedTransitionId == null,
            ) { "Failed workflow effects require a failure code and no selected transition." }

            else -> throw IllegalArgumentException("Unknown workflow effect outcomes are unsupported.")
        }
        receiptDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-effect-completion-v1")
            .text(this.receiptId)
            .text(this.effectId)
            .text(effectCode.code)
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
            .text(this.tokenId)
            .text(this.nodeExecutionId)
            .text(this.nodeId)
            .text(this.effectRequestDigest)
            .text(outcome.code)
            .optionalText(this.selectedTransitionId)
            .optionalText(this.failureCode)
            .text(this.outputDigest)
            .longValue(this.completedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowEffectCompletionReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            receiptId: String,
            effectId: String,
            effectCode: WorkflowEffectCode,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            tokenId: String,
            nodeExecutionId: String,
            nodeId: String,
            effectRequestDigest: String,
            selectedTransitionId: String?,
            outputDigest: String,
            completedAt: Long,
        ): WorkflowEffectCompletionReceipt = WorkflowEffectCompletionReceipt(
            receiptId,
            effectId,
            effectCode,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            tokenId,
            nodeExecutionId,
            nodeId,
            effectRequestDigest,
            WorkflowEffectOutcomeCode.SUCCESS,
            selectedTransitionId,
            null,
            outputDigest,
            completedAt,
        )

        @JvmStatic
        fun failure(
            receiptId: String,
            effectId: String,
            effectCode: WorkflowEffectCode,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            tokenId: String,
            nodeExecutionId: String,
            nodeId: String,
            effectRequestDigest: String,
            failureCode: String,
            outputDigest: String,
            completedAt: Long,
        ): WorkflowEffectCompletionReceipt = WorkflowEffectCompletionReceipt(
            receiptId,
            effectId,
            effectCode,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            tokenId,
            nodeExecutionId,
            nodeId,
            effectRequestDigest,
            WorkflowEffectOutcomeCode.FAILURE,
            null,
            failureCode,
            outputDigest,
            completedAt,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow effect $label identifier is invalid.",
        )

        private fun sha(value: String, label: String): String = WorkflowDomainSupport.requireSha256(
            value,
            "Workflow $label digest is invalid.",
        )
    }
}
