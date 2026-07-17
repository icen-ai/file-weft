package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

/**
 * Durable external-work intent. Domain code emits and waits; it never invokes a provider, clock,
 * scheduler, database or network client.
 */
class WorkflowEffectIntent private constructor(
    effectId: String,
    val code: WorkflowEffectCode,
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    tokenId: String?,
    nodeExecutionId: String?,
    workItemId: String?,
    nodeId: String?,
    val ruleIndex: Int?,
    payloadDigest: String,
    createdAt: Long,
) {
    val effectId: String = text(effectId, "effect")
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String = text(definitionId, "definition")
    val tokenId: String? = tokenId?.let { value -> text(value, "token") }
    val nodeExecutionId: String? = nodeExecutionId?.let { value -> text(value, "node execution") }
    val workItemId: String? = workItemId?.let { value -> text(value, "work item") }
    val nodeId: String? = nodeId?.let { value ->
        WorkflowDomainSupport.requireCode(value, "Workflow effect node id is invalid.")
    }
    val payloadDigest: String = WorkflowDomainSupport.requireSha256(
        payloadDigest,
        "Workflow effect payload digest is invalid.",
    )
    val createdAt: Long = WorkflowDomainSupport.requireTime(createdAt, "Workflow effect creation time is invalid.")
    val requestDigest: String

    init {
        when (code) {
            WorkflowEffectCode.CONTINUE_EXECUTION -> require(
                this.tokenId == null && this.nodeExecutionId == null &&
                    this.workItemId == null && this.nodeId == null && ruleIndex == null,
            ) { "Workflow continuation intents are instance-scoped." }

            WorkflowEffectCode.PARTICIPANT_RESOLUTION -> require(
                this.tokenId != null && this.nodeExecutionId != null &&
                    this.workItemId != null && this.nodeId != null && ruleIndex != null && ruleIndex >= 0,
            ) { "Workflow participant intents require exact work-item rule bindings." }

            WorkflowEffectCode.EXCLUSIVE_EVALUATION,
            WorkflowEffectCode.SERVICE_TASK,
            WorkflowEffectCode.DECISION_TASK,
            WorkflowEffectCode.TIMER_WAIT,
            WorkflowEffectCode.SUBPROCESS,
            WorkflowEffectCode.EXTENSION -> require(
                this.tokenId != null && this.nodeExecutionId != null &&
                    this.workItemId == null && this.nodeId != null && ruleIndex == null,
            ) { "Workflow node effects require an exact token execution binding." }

            else -> throw IllegalArgumentException("Unknown workflow effect intent is unsupported.")
        }
        requestDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-effect-intent-v1")
            .text(this.effectId)
            .text(code.code)
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
            .optionalText(this.tokenId)
            .optionalText(this.nodeExecutionId)
            .optionalText(this.workItemId)
            .optionalText(this.nodeId)
            .booleanValue(ruleIndex != null)
            .let { writer -> ruleIndex?.let(writer::integer) ?: writer }
            .text(this.payloadDigest)
            .longValue(this.createdAt)
            .finish()
    }

    override fun toString(): String = "WorkflowEffectIntent(<redacted>)"

    companion object {
        /** Canonical participant-effect payload binding shared by the domain and production worker. */
        @JvmStatic
        fun participantResolutionPayloadDigest(
            workItemDigest: String,
            policyDigest: String,
            ruleIndex: Int,
            ruleDigest: String,
            selectorDigest: String,
            approvalPolicyDigest: String,
        ): String {
            require(ruleIndex >= 0) { "Workflow participant rule index is invalid." }
            val values = listOf(
                WorkflowDomainSupport.requireSha256(workItemDigest, "Workflow work-item digest is invalid."),
                WorkflowDomainSupport.requireSha256(policyDigest, "Workflow human policy digest is invalid."),
                ruleIndex.toString(),
                WorkflowDomainSupport.requireSha256(ruleDigest, "Workflow participant rule digest is invalid."),
                WorkflowDomainSupport.requireSha256(selectorDigest, "Workflow selector digest is invalid."),
                WorkflowDomainSupport.requireSha256(
                    approvalPolicyDigest,
                    "Workflow approval policy digest is invalid.",
                ),
            )
            val writer = WorkflowDomainSupport.digest("flowweft-workflow-domain-participant-request-v1")
                .integer(values.size)
            values.forEach(writer::text)
            return writer.finish()
        }

        @JvmStatic
        fun of(
            effectId: String,
            code: WorkflowEffectCode,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            tokenId: String?,
            nodeExecutionId: String?,
            workItemId: String?,
            nodeId: String?,
            ruleIndex: Int?,
            payloadDigest: String,
            createdAt: Long,
        ): WorkflowEffectIntent = WorkflowEffectIntent(
            effectId,
            code,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            tokenId,
            nodeExecutionId,
            workItemId,
            nodeId,
            ruleIndex,
            payloadDigest,
            createdAt,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow $label identifier is invalid.",
        )
    }
}
