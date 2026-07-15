package ai.icen.fw.workflow.api

/** Extensible, case-sensitive outcome code; unknown codes must be handled fail-closed. */
class WorkflowParticipantResolutionStatus private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow participant resolution status is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowParticipantResolutionStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowParticipantResolutionStatus(<redacted>)"

    companion object {
        @JvmField
        val RESOLVED = WorkflowParticipantResolutionStatus("resolved")

        @JvmField
        val EMPTY = WorkflowParticipantResolutionStatus("empty")

        @JvmField
        val DENIED = WorkflowParticipantResolutionStatus("denied")

        @JvmField
        val ERROR = WorkflowParticipantResolutionStatus("error")

        @JvmStatic
        fun of(code: String): WorkflowParticipantResolutionStatus = when (code) {
            RESOLVED.code -> RESOLVED
            EMPTY.code -> EMPTY
            DENIED.code -> DENIED
            ERROR.code -> ERROR
            else -> WorkflowParticipantResolutionStatus(code)
        }
    }
}
