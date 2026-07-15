package ai.icen.fw.workflow.api

/**
 * Stable outcome or completion trigger selecting one outgoing workflow transition.
 *
 * Known values are canonical singletons. Extension values remain representable for codecs, but a
 * runtime must fail closed unless it explicitly supports their source-node semantics.
 */
class WorkflowTransitionTrigger private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow transition trigger is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowTransitionTrigger && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowTransitionTrigger(<redacted>)"

    companion object {
        /** Normal completion of start, gateway, automatic, wait, subprocess or extension nodes. */
        @JvmField
        val COMPLETED = WorkflowTransitionTrigger("completed")

        /** Successful approval outcome of a human task. */
        @JvmField
        val APPROVED = WorkflowTransitionTrigger("approved")

        /** Explicit rejection outcome of a human task. */
        @JvmField
        val REJECTED = WorkflowTransitionTrigger("rejected")

        @JvmStatic
        fun of(code: String): WorkflowTransitionTrigger = when (code) {
            COMPLETED.code -> COMPLETED
            APPROVED.code -> APPROVED
            REJECTED.code -> REJECTED
            else -> WorkflowTransitionTrigger(code)
        }
    }
}
