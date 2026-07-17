package ai.icen.fw.workflow.api

/**
 * Stable code for the completion rule of one ordered human-task participant tier.
 * Custom codes are reserved for a future typed policy contract and are rejected by schema v1.
 */
class WorkflowApprovalMode private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow approval mode is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowApprovalMode && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowApprovalMode(<redacted>)"

    companion object {
        @JvmField
        val ONE = WorkflowApprovalMode("one")

        @JvmField
        val ALL = WorkflowApprovalMode("all")

        @JvmField
        val QUORUM = WorkflowApprovalMode("quorum")

        @JvmStatic
        fun of(code: String): WorkflowApprovalMode = when (code) {
            ONE.code -> ONE
            ALL.code -> ALL
            QUORUM.code -> QUORUM
            else -> WorkflowApprovalMode(code)
        }
    }
}
