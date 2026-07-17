package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowPrincipalRef

/** Immutable accepted decision by one distinct principal in one activated rule. */
class WorkflowHumanDecision private constructor(
    decisionId: String,
    val ruleIndex: Int,
    val actor: WorkflowPrincipalRef,
    val decision: WorkflowHumanDecisionCode,
    authorizationReceiptDigest: String,
    decidedAt: Long,
) {
    val decisionId: String = WorkflowDomainSupport.requireText(
        decisionId,
        WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
        "Workflow human decision id is invalid.",
    )
    val authorizationReceiptDigest: String = WorkflowDomainSupport.requireSha256(
        authorizationReceiptDigest,
        "Workflow decision authorization receipt digest is invalid.",
    )
    val decidedAt: Long = WorkflowDomainSupport.requireTime(decidedAt, "Workflow human decision time is invalid.")
    val contentDigest: String

    init {
        require(ruleIndex >= 0) { "Workflow human decision rule index is invalid." }
        require(decision == WorkflowHumanDecisionCode.APPROVE || decision == WorkflowHumanDecisionCode.REJECT) {
            "Unknown workflow human decisions are unsupported."
        }
        contentDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-human-decision-v1")
            .text(this.decisionId)
            .integer(ruleIndex)
            .text(actor.type)
            .text(actor.id)
            .text(decision.code)
            .text(this.authorizationReceiptDigest)
            .longValue(this.decidedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowHumanDecision(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            decisionId: String,
            ruleIndex: Int,
            actor: WorkflowPrincipalRef,
            decision: WorkflowHumanDecisionCode,
            authorizationReceiptDigest: String,
            decidedAt: Long,
        ): WorkflowHumanDecision = WorkflowHumanDecision(
            decisionId,
            ruleIndex,
            actor,
            decision,
            authorizationReceiptDigest,
            decidedAt,
        )
    }
}
