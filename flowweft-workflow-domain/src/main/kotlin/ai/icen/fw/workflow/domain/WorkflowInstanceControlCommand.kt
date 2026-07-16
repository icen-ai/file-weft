package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowPrincipalRef

/** Time-bounded authorization evidence for one exact lifecycle mutation. */
class WorkflowInstanceControlAuthorizationReceipt private constructor(
    receiptId: String,
    tenantId: String,
    instanceId: String,
    val actor: WorkflowPrincipalRef,
    val action: WorkflowInstanceControlAction,
    stateDigest: String,
    expectedInstanceVersion: Long,
    runtimeRequestDigest: String,
    reasonDigest: String,
    val status: WorkflowAuthorizationStatus,
    authorityRevision: String,
    authorityDigest: String,
    evaluatedAt: Long,
    validUntil: Long,
) {
    val receiptId: String = text(receiptId, "receipt")
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val stateDigest: String = sha(stateDigest, "state")
    val expectedInstanceVersion: Long = WorkflowDomainSupport.requireVersion(
        expectedInstanceVersion,
        "Workflow control expected instance version is invalid.",
    )
    val runtimeRequestDigest: String = sha(runtimeRequestDigest, "runtime request")
    val reasonDigest: String = sha(reasonDigest, "reason")
    val authorityRevision: String = WorkflowDomainSupport.requireText(
        authorityRevision,
        WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow control authority revision is invalid.",
    )
    val authorityDigest: String = sha(authorityDigest, "authority")
    val evaluatedAt: Long = WorkflowDomainSupport.requireTime(evaluatedAt, "Workflow control evaluation time is invalid.")
    val validUntil: Long = WorkflowDomainSupport.requireTime(validUntil, "Workflow control expiry is invalid.")
    val receiptDigest: String

    init {
        require(action.isBuiltin) { "Unknown workflow instance control action is unsupported." }
        require(status == WorkflowAuthorizationStatus.AUTHORIZED || status == WorkflowAuthorizationStatus.DENIED) {
            "Unknown workflow control authorization status is unsupported."
        }
        require(this.validUntil >= this.evaluatedAt) { "Workflow control authorization window is invalid." }
        receiptDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-instance-control-authorization-v1")
            .text(this.receiptId)
            .text(this.tenantId)
            .text(this.instanceId)
            .text(actor.type)
            .text(actor.id)
            .text(action.code)
            .text(this.stateDigest)
            .longValue(this.expectedInstanceVersion)
            .text(this.runtimeRequestDigest)
            .text(this.reasonDigest)
            .text(status.code)
            .text(this.authorityRevision)
            .text(this.authorityDigest)
            .longValue(this.evaluatedAt)
            .longValue(this.validUntil)
            .finish()
    }

    override fun toString(): String = "WorkflowInstanceControlAuthorizationReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            receiptId: String,
            tenantId: String,
            instanceId: String,
            actor: WorkflowPrincipalRef,
            action: WorkflowInstanceControlAction,
            stateDigest: String,
            expectedInstanceVersion: Long,
            runtimeRequestDigest: String,
            reasonDigest: String,
            status: WorkflowAuthorizationStatus,
            authorityRevision: String,
            authorityDigest: String,
            evaluatedAt: Long,
            validUntil: Long,
        ): WorkflowInstanceControlAuthorizationReceipt = WorkflowInstanceControlAuthorizationReceipt(
            receiptId,
            tenantId,
            instanceId,
            actor,
            action,
            stateDigest,
            expectedInstanceVersion,
            runtimeRequestDigest,
            reasonDigest,
            status,
            authorityRevision,
            authorityDigest,
            evaluatedAt,
            validUntil,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow control $label identifier is invalid.",
        )

        private fun sha(value: String, label: String): String = WorkflowDomainSupport.requireSha256(
            value,
            "Workflow control $label digest is invalid.",
        )
    }
}

/** Optimistic, idempotent and authorization-bound lifecycle command. */
class WorkflowInstanceControlCommand private constructor(
    val context: WorkflowCommandContext,
    val actor: WorkflowPrincipalRef,
    val action: WorkflowInstanceControlAction,
    reasonDigest: String,
    runtimeRequestDigest: String,
    val authorizationReceipt: WorkflowInstanceControlAuthorizationReceipt,
) {
    val code: WorkflowCommandCode = WorkflowCommandCode.CONTROL_INSTANCE
    val reasonDigest: String = WorkflowDomainSupport.requireSha256(
        reasonDigest,
        "Workflow control reason digest is invalid.",
    )
    val runtimeRequestDigest: String = WorkflowDomainSupport.requireSha256(
        runtimeRequestDigest,
        "Workflow control runtime request digest is invalid.",
    )
    val commandDigest: String

    init {
        require(action.isBuiltin) { "Unknown workflow instance control action is unsupported." }
        require(authorizationReceipt.actor == actor && authorizationReceipt.action == action &&
            authorizationReceipt.expectedInstanceVersion == context.expectedInstanceVersion &&
            authorizationReceipt.reasonDigest == this.reasonDigest &&
            authorizationReceipt.runtimeRequestDigest == this.runtimeRequestDigest
        ) { "Workflow control authorization receipt does not bind the command." }
        commandDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-instance-control-command-v1")
            .text(code.code)
            .text(context.inputDigest)
            .text(actor.type)
            .text(actor.id)
            .text(action.code)
            .text(this.reasonDigest)
            .text(this.runtimeRequestDigest)
            .text(authorizationReceipt.receiptDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowInstanceControlCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowCommandContext,
            actor: WorkflowPrincipalRef,
            action: WorkflowInstanceControlAction,
            reasonDigest: String,
            runtimeRequestDigest: String,
            authorizationReceipt: WorkflowInstanceControlAuthorizationReceipt,
        ): WorkflowInstanceControlCommand = WorkflowInstanceControlCommand(
            context,
            actor,
            action,
            reasonDigest,
            runtimeRequestDigest,
            authorizationReceipt,
        )
    }
}
