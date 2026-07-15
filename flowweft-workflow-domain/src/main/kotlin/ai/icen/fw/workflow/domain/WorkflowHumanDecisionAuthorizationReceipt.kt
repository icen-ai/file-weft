package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

/**
 * Exact runtime authorization/re-resolution evidence for one human decision request.
 * The receipt binds actor, decision, activation snapshot and request digest and cannot be reused for
 * another work item or command payload.
 */
class WorkflowHumanDecisionAuthorizationReceipt private constructor(
    receiptId: String,
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    workItemId: String,
    val ruleIndex: Int,
    val actor: WorkflowPrincipalRef,
    val decision: WorkflowHumanDecisionCode,
    activationDigest: String,
    authorizationRequestDigest: String,
    val status: WorkflowAuthorizationStatus,
    authorityRevision: String,
    authorityDigest: String,
    evaluatedAt: Long,
    validUntil: Long,
) {
    val receiptId: String = text(receiptId, "receipt")
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String = text(definitionId, "definition")
    val workItemId: String = text(workItemId, "work item")
    val activationDigest: String = sha(activationDigest, "activation")
    val authorizationRequestDigest: String = sha(authorizationRequestDigest, "authorization request")
    val authorityRevision: String = WorkflowDomainSupport.requireText(
        authorityRevision,
        WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow authorization revision is invalid.",
    )
    val authorityDigest: String = sha(authorityDigest, "authority")
    val evaluatedAt: Long = WorkflowDomainSupport.requireTime(evaluatedAt, "Workflow authorization time is invalid.")
    val validUntil: Long = WorkflowDomainSupport.requireTime(validUntil, "Workflow authorization expiry is invalid.")
    val receiptDigest: String

    init {
        require(ruleIndex >= 0) { "Workflow authorization rule index is invalid." }
        require(decision == WorkflowHumanDecisionCode.APPROVE || decision == WorkflowHumanDecisionCode.REJECT) {
            "Unknown workflow human decisions cannot be authorized."
        }
        require(status == WorkflowAuthorizationStatus.AUTHORIZED || status == WorkflowAuthorizationStatus.DENIED) {
            "Unknown workflow authorization status is unsupported."
        }
        require(this.validUntil >= this.evaluatedAt) { "Workflow authorization receipt window is invalid." }
        receiptDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-decision-authorization-v1")
            .text(this.receiptId)
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
            .text(this.workItemId)
            .integer(ruleIndex)
            .text(actor.type)
            .text(actor.id)
            .text(decision.code)
            .text(this.activationDigest)
            .text(this.authorizationRequestDigest)
            .text(status.code)
            .text(this.authorityRevision)
            .text(this.authorityDigest)
            .longValue(this.evaluatedAt)
            .longValue(this.validUntil)
            .finish()
    }

    override fun toString(): String = "WorkflowHumanDecisionAuthorizationReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            receiptId: String,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            workItemId: String,
            ruleIndex: Int,
            actor: WorkflowPrincipalRef,
            decision: WorkflowHumanDecisionCode,
            activationDigest: String,
            authorizationRequestDigest: String,
            status: WorkflowAuthorizationStatus,
            authorityRevision: String,
            authorityDigest: String,
            evaluatedAt: Long,
            validUntil: Long,
        ): WorkflowHumanDecisionAuthorizationReceipt = WorkflowHumanDecisionAuthorizationReceipt(
            receiptId,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            workItemId,
            ruleIndex,
            actor,
            decision,
            activationDigest,
            authorizationRequestDigest,
            status,
            authorityRevision,
            authorityDigest,
            evaluatedAt,
            validUntil,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow authorization $label identifier is invalid.",
        )

        private fun sha(value: String, label: String): String = WorkflowDomainSupport.requireSha256(
            value,
            "Workflow $label digest is invalid.",
        )
    }
}
