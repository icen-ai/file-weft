package ai.icen.fw.workflow.api

/**
 * Extensible point at which a human task requests a fresh participant resolution.
 *
 * Resolution never substitutes for current authorization. Unknown stages require an explicit
 * runtime extension and otherwise make the definition non-executable.
 */
class WorkflowParticipantResolutionStage private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow participant resolution stage is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowParticipantResolutionStage && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowParticipantResolutionStage(<redacted>)"

    companion object {
        @JvmField
        val ACTIVATION = WorkflowParticipantResolutionStage("activation")

        @JvmField
        val CLAIM = WorkflowParticipantResolutionStage("claim")

        @JvmField
        val DECISION = WorkflowParticipantResolutionStage("decision")

        @JvmStatic
        fun of(code: String): WorkflowParticipantResolutionStage = when (code) {
            ACTIVATION.code -> ACTIVATION
            CLAIM.code -> CLAIM
            DECISION.code -> DECISION
            else -> WorkflowParticipantResolutionStage(code)
        }
    }
}
