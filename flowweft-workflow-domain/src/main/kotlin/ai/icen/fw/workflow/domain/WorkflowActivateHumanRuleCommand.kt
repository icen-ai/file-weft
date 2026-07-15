package ai.icen.fw.workflow.domain

/** Applies one exact participant-resolution receipt to the waiting ordered human-task rule. */
class WorkflowActivateHumanRuleCommand private constructor(
    val context: WorkflowCommandContext,
    workItemId: String,
    val receipt: WorkflowParticipantActivationReceipt,
) {
    val code: WorkflowCommandCode = WorkflowCommandCode.ACTIVATE_HUMAN_RULE
    val workItemId: String = WorkflowDomainSupport.requireText(
        workItemId,
        WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
        "Workflow activation work-item id is invalid.",
    )
    val commandDigest: String

    init {
        require(receipt.workItemId == this.workItemId) { "Workflow activation receipt targets another work item." }
        commandDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-activate-command-v1")
            .text(code.code)
            .text(context.inputDigest)
            .text(this.workItemId)
            .text(receipt.receiptDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowActivateHumanRuleCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowCommandContext,
            workItemId: String,
            receipt: WorkflowParticipantActivationReceipt,
        ): WorkflowActivateHumanRuleCommand = WorkflowActivateHumanRuleCommand(context, workItemId, receipt)
    }
}
