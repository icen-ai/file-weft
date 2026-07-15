package ai.icen.fw.workflow.domain

/** Applies one exact external effect completion receipt without performing the effect. */
class WorkflowCompleteEffectCommand private constructor(
    val context: WorkflowCommandContext,
    val receipt: WorkflowEffectCompletionReceipt,
) {
    val code: WorkflowCommandCode = WorkflowCommandCode.COMPLETE_EFFECT
    val commandDigest: String = WorkflowDomainSupport.digest("flowweft-workflow-domain-complete-command-v1")
        .text(code.code)
        .text(context.inputDigest)
        .text(receipt.receiptDigest)
        .finish()

    override fun toString(): String = "WorkflowCompleteEffectCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowCommandContext,
            receipt: WorkflowEffectCompletionReceipt,
        ): WorkflowCompleteEffectCommand = WorkflowCompleteEffectCommand(context, receipt)
    }
}
