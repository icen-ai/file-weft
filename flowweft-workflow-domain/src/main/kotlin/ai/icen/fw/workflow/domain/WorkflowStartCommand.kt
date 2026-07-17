package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

/** Explicit start input; tenant, ids, time and deployment evidence are never inferred. */
class WorkflowStartCommand private constructor(
    val context: WorkflowCommandContext,
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    val initiator: WorkflowPrincipalRef,
    val executionReceipt: WorkflowDefinitionExecutionReceipt,
) {
    val code: WorkflowCommandCode = WorkflowCommandCode.START_INSTANCE
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String = text(definitionId, "definition")
    val commandDigest: String = WorkflowDomainSupport.digest("flowweft-workflow-domain-start-command-v1")
        .text(code.code)
        .text(context.inputDigest)
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
        .text(initiator.type)
        .text(initiator.id)
        .text(executionReceipt.receiptDigest)
        .finish()

    override fun toString(): String = "WorkflowStartCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowCommandContext,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            initiator: WorkflowPrincipalRef,
            executionReceipt: WorkflowDefinitionExecutionReceipt,
        ): WorkflowStartCommand = WorkflowStartCommand(
            context,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            initiator,
            executionReceipt,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow start $label identifier is invalid.",
        )
    }
}
