package ai.icen.fw.workflow.domain

/** Explicit deterministic envelope shared by every domain command. */
class WorkflowCommandContext private constructor(
    commandId: String,
    idempotencyKey: String,
    expectedInstanceVersion: Long,
    now: Long,
    iterationBudget: Int,
    val ids: WorkflowExecutionIds,
    val idempotencyReceipt: WorkflowIdempotencyReceipt,
) {
    val commandId: String = text(commandId, "command")
    val idempotencyKey: String = text(idempotencyKey, "idempotency key")
    val expectedInstanceVersion: Long = WorkflowDomainSupport.requireVersion(
        expectedInstanceVersion,
        "Workflow expected instance version is invalid.",
    )
    val now: Long = WorkflowDomainSupport.requireTime(now, "Workflow command time is invalid.")
    val iterationBudget: Int = iterationBudget.also { value ->
        require(value in 1..WorkflowDomainSupport.MAX_ITERATION_BUDGET) {
            "Workflow iteration budget is invalid."
        }
    }
    val inputDigest: String

    init {
        require(idempotencyReceipt.idempotencyKey == this.idempotencyKey) {
            "Workflow command and idempotency receipt keys differ."
        }
        require(idempotencyReceipt.checkedAt <= this.now) {
            "Workflow idempotency receipt cannot be checked in the future."
        }
        inputDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-command-context-v1")
            .text(this.commandId)
            .text(this.idempotencyKey)
            .longValue(this.expectedInstanceVersion)
            .longValue(this.now)
            .integer(this.iterationBudget)
            .text(ids.contentDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowCommandContext(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            commandId: String,
            idempotencyKey: String,
            expectedInstanceVersion: Long,
            now: Long,
            iterationBudget: Int,
            ids: WorkflowExecutionIds,
            idempotencyReceipt: WorkflowIdempotencyReceipt,
        ): WorkflowCommandContext = WorkflowCommandContext(
            commandId,
            idempotencyKey,
            expectedInstanceVersion,
            now,
            iterationBudget,
            ids,
            idempotencyReceipt,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow $label identifier is invalid.",
        )
    }
}
