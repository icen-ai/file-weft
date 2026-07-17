package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowPrincipalRef

/** Optimistic, receipt-bound human decision command. */
class WorkflowHumanDecisionCommand private constructor(
    val context: WorkflowCommandContext,
    workItemId: String,
    val actor: WorkflowPrincipalRef,
    val decision: WorkflowHumanDecisionCode,
    expectedWorkItemVersion: Long,
    val authorizationReceipt: WorkflowHumanDecisionAuthorizationReceipt,
) {
    val code: WorkflowCommandCode = WorkflowCommandCode.DECIDE_HUMAN_TASK
    val workItemId: String = WorkflowDomainSupport.requireText(
        workItemId,
        WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
        "Workflow decision work-item id is invalid.",
    )
    val expectedWorkItemVersion: Long = WorkflowDomainSupport.requireVersion(
        expectedWorkItemVersion,
        "Workflow expected work-item version is invalid.",
    )
    val authorizationRequestDigest: String = WorkflowDomainSupport.digest(
        "flowweft-workflow-domain-decision-authorization-request-v1",
    )
        .text(this.workItemId)
        .text(actor.type)
        .text(actor.id)
        .text(decision.code)
        .longValue(this.expectedWorkItemVersion)
        .finish()
    val commandDigest: String

    init {
        require(decision == WorkflowHumanDecisionCode.APPROVE || decision == WorkflowHumanDecisionCode.REJECT) {
            "Unknown workflow human decisions are unsupported."
        }
        require(authorizationReceipt.workItemId == this.workItemId &&
            authorizationReceipt.actor == actor &&
            authorizationReceipt.decision == decision &&
            authorizationReceipt.authorizationRequestDigest == authorizationRequestDigest
        ) { "Workflow decision authorization receipt does not bind the command." }
        commandDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-decision-command-v1")
            .text(code.code)
            .text(context.inputDigest)
            .text(this.workItemId)
            .text(actor.type)
            .text(actor.id)
            .text(decision.code)
            .longValue(this.expectedWorkItemVersion)
            .text(authorizationReceipt.receiptDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowHumanDecisionCommand(<redacted>)"

    companion object {
        /**
         * Computes the receipt-bound request digest before an authorization provider is called.
         * This pure helper is safe to use while constructing [WorkflowHumanDecisionAuthorizationReceipt].
         */
        @JvmStatic
        fun authorizationRequestDigest(
            workItemId: String,
            actor: WorkflowPrincipalRef,
            decision: WorkflowHumanDecisionCode,
            expectedWorkItemVersion: Long,
        ): String = WorkflowDomainSupport.digest(
            "flowweft-workflow-domain-decision-authorization-request-v1",
        )
            .text(
                WorkflowDomainSupport.requireText(
                    workItemId,
                    WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
                    "Workflow decision work-item id is invalid.",
                ),
            )
            .text(actor.type)
            .text(actor.id)
            .text(decision.code)
            .longValue(
                WorkflowDomainSupport.requireVersion(
                    expectedWorkItemVersion,
                    "Workflow expected work-item version is invalid.",
                ),
            )
            .finish()

        @JvmStatic
        fun of(
            context: WorkflowCommandContext,
            workItemId: String,
            actor: WorkflowPrincipalRef,
            decision: WorkflowHumanDecisionCode,
            expectedWorkItemVersion: Long,
            authorizationReceipt: WorkflowHumanDecisionAuthorizationReceipt,
        ): WorkflowHumanDecisionCommand = WorkflowHumanDecisionCommand(
            context,
            workItemId,
            actor,
            decision,
            expectedWorkItemVersion,
            authorizationReceipt,
        )
    }
}
