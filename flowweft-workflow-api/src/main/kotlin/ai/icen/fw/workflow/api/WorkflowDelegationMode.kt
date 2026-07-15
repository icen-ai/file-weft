package ai.icen.fw.workflow.api

/** Extensible, non-executable machine code for delegation expansion semantics. */
class WorkflowDelegationMode private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow delegation mode is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowDelegationMode && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowDelegationMode(<redacted>)"

    companion object {
        @JvmField
        val DISABLED = WorkflowDelegationMode("disabled")

        @JvmField
        val INCLUDE_ACTIVE_DELEGATES = WorkflowDelegationMode("include-active-delegates")

        @JvmField
        val ACTIVE_DELEGATE_OR_ORIGINAL = WorkflowDelegationMode("active-delegate-or-original")

        @JvmStatic
        fun of(code: String): WorkflowDelegationMode = when (code) {
            DISABLED.code -> DISABLED
            INCLUDE_ACTIVE_DELEGATES.code -> INCLUDE_ACTIVE_DELEGATES
            ACTIVE_DELEGATE_OR_ORIGINAL.code -> ACTIVE_DELEGATE_OR_ORIGINAL
            else -> WorkflowDelegationMode(code)
        }
    }
}
