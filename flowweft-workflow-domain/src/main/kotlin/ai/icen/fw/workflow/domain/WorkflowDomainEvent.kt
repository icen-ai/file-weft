package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

/** Ordered, immutable domain fact whose sensitive payload is represented only by a digest. */
class WorkflowDomainEvent private constructor(
    eventId: String,
    val code: WorkflowEventCode,
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    tokenId: String?,
    nodeExecutionId: String?,
    workItemId: String?,
    nodeId: String?,
    payloadDigest: String,
    occurredAt: Long,
    instanceVersion: Long,
) {
    val eventId: String = text(eventId, "event")
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String = text(definitionId, "definition")
    val tokenId: String? = tokenId?.let { value -> text(value, "token") }
    val nodeExecutionId: String? = nodeExecutionId?.let { value -> text(value, "node execution") }
    val workItemId: String? = workItemId?.let { value -> text(value, "work item") }
    val nodeId: String? = nodeId?.let { value ->
        WorkflowDomainSupport.requireCode(value, "Workflow event node id is invalid.")
    }
    val payloadDigest: String = WorkflowDomainSupport.requireSha256(
        payloadDigest,
        "Workflow event payload digest is invalid.",
    )
    val occurredAt: Long = WorkflowDomainSupport.requireTime(occurredAt, "Workflow event time is invalid.")
    val instanceVersion: Long = WorkflowDomainSupport.requireVersion(
        instanceVersion,
        "Workflow event instance version is invalid.",
    )
    val eventDigest: String = WorkflowDomainSupport.digest("flowweft-workflow-domain-event-v1")
        .text(this.eventId)
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
        .text(this.payloadDigest)
        .longValue(this.occurredAt)
        .longValue(this.instanceVersion)
        .finish()

    override fun toString(): String = "WorkflowDomainEvent(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            eventId: String,
            code: WorkflowEventCode,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            tokenId: String?,
            nodeExecutionId: String?,
            workItemId: String?,
            nodeId: String?,
            payloadDigest: String,
            occurredAt: Long,
            instanceVersion: Long,
        ): WorkflowDomainEvent = WorkflowDomainEvent(
            eventId,
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
            payloadDigest,
            occurredAt,
            instanceVersion,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow $label identifier is invalid.",
        )
    }
}
