package ai.icen.fw.workflow.domain

/** Resumes deterministic advancement after a persisted continuation intent. */
class WorkflowContinueCommand private constructor(
    val context: WorkflowCommandContext,
    val receipt: WorkflowContinuationReceipt,
) {
    val code: WorkflowCommandCode = WorkflowCommandCode.CONTINUE_EXECUTION
    val commandDigest: String = WorkflowDomainSupport.digest("flowweft-workflow-domain-continue-command-v1")
        .text(code.code)
        .text(context.inputDigest)
        .text(receipt.receiptDigest)
        .finish()

    override fun toString(): String = "WorkflowContinueCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowCommandContext,
            receipt: WorkflowContinuationReceipt,
        ): WorkflowContinueCommand = WorkflowContinueCommand(context, receipt)
    }
}
