package ai.icen.fw.workflow.api

/** Value-free, extensible diagnostic code. It must never contain vendor exception text. */
class WorkflowParticipantResolutionReason private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow participant resolution reason is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowParticipantResolutionReason && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowParticipantResolutionReason(<redacted>)"

    companion object {
        @JvmField
        val NO_MATCH = WorkflowParticipantResolutionReason("no-match")

        @JvmField
        val DIRECTORY_DENIED = WorkflowParticipantResolutionReason("directory-denied")

        @JvmField
        val PROVIDER_ERROR = WorkflowParticipantResolutionReason("provider-error")

        @JvmField
        val UNSUPPORTED_SELECTOR = WorkflowParticipantResolutionReason("unsupported-selector")

        @JvmField
        val SNAPSHOT_UNAVAILABLE = WorkflowParticipantResolutionReason("snapshot-unavailable")

        @JvmField
        val RESULT_LIMIT_EXCEEDED = WorkflowParticipantResolutionReason("result-limit-exceeded")

        @JvmField
        val DELEGATION_CYCLE = WorkflowParticipantResolutionReason("delegation-cycle")

        @JvmStatic
        fun of(code: String): WorkflowParticipantResolutionReason = when (code) {
            NO_MATCH.code -> NO_MATCH
            DIRECTORY_DENIED.code -> DIRECTORY_DENIED
            PROVIDER_ERROR.code -> PROVIDER_ERROR
            UNSUPPORTED_SELECTOR.code -> UNSUPPORTED_SELECTOR
            SNAPSHOT_UNAVAILABLE.code -> SNAPSHOT_UNAVAILABLE
            RESULT_LIMIT_EXCEEDED.code -> RESULT_LIMIT_EXCEEDED
            DELEGATION_CYCLE.code -> DELEGATION_CYCLE
            else -> WorkflowParticipantResolutionReason(code)
        }
    }
}
