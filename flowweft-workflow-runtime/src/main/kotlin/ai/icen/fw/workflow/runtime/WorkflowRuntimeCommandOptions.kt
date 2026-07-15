package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.domain.WorkflowExecutionIds

/** Explicit deterministic command inputs; no clock, UUID or implicit retry defaults are read. */
class WorkflowRuntimeCommandOptions private constructor(
    commandId: String,
    idempotencyKey: String,
    expectedInstanceVersion: Long,
    now: Long,
    iterationBudget: Int,
    val ids: WorkflowExecutionIds,
) {
    val commandId: String = text(commandId, "command")
    val idempotencyKey: String = text(idempotencyKey, "idempotency")
    val expectedInstanceVersion: Long = WorkflowRuntimeSupport.nonNegative(
        expectedInstanceVersion,
        "Workflow expected instance version is invalid.",
    )
    val now: Long = WorkflowRuntimeSupport.nonNegative(now, "Workflow command time is invalid.")
    val iterationBudget: Int = WorkflowRuntimeSupport.positive(
        iterationBudget,
        1024,
        "Workflow iteration budget is invalid.",
    )

    override fun toString(): String = "WorkflowRuntimeCommandOptions(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            commandId: String,
            idempotencyKey: String,
            expectedInstanceVersion: Long,
            now: Long,
            iterationBudget: Int,
            ids: WorkflowExecutionIds,
        ): WorkflowRuntimeCommandOptions = WorkflowRuntimeCommandOptions(
            commandId,
            idempotencyKey,
            expectedInstanceVersion,
            now,
            iterationBudget,
            ids,
        )

        private fun text(value: String, label: String): String = WorkflowRuntimeSupport.text(
            value,
            WorkflowRuntimeSupport.MAX_ID_BYTES,
            "Workflow $label id is invalid.",
        )
    }
}
