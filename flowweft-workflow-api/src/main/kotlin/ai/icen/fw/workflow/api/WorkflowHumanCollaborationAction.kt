package ai.icen.fw.workflow.api

/**
 * Stable human-task collaboration operation.
 *
 * Unknown extension codes remain representable for storage and transport, but schema v1 runtimes
 * execute only the six built-in operations below.
 */
class WorkflowHumanCollaborationAction private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow human collaboration action is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowHumanCollaborationAction && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowHumanCollaborationAction(<redacted>)"

    companion object {
        @JvmField val CLAIM = WorkflowHumanCollaborationAction("claim")
        @JvmField val UNCLAIM = WorkflowHumanCollaborationAction("unclaim")
        @JvmField val DELEGATE = WorkflowHumanCollaborationAction("delegate")
        @JvmField val TRANSFER = WorkflowHumanCollaborationAction("transfer")
        @JvmField val ADD_SIGN = WorkflowHumanCollaborationAction("add-sign")
        @JvmField val RETURN = WorkflowHumanCollaborationAction("return")

        @JvmStatic
        fun of(code: String): WorkflowHumanCollaborationAction = when (code) {
            CLAIM.code -> CLAIM
            UNCLAIM.code -> UNCLAIM
            DELEGATE.code -> DELEGATE
            TRANSFER.code -> TRANSFER
            ADD_SIGN.code -> ADD_SIGN
            RETURN.code -> RETURN
            else -> WorkflowHumanCollaborationAction(code)
        }
    }
}
