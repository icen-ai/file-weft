package ai.icen.fw.workflow.api

/**
 * Versioned participant-membership semantics for one human-task selector.
 *
 * [ACTIVATION_SNAPSHOT] freezes the resolved candidate set when the rule is activated. Later
 * organization changes do not add or remove candidates from that running rule, although every
 * operation still requires fresh business authorization.
 *
 * [CURRENT_MEMBERSHIP] keeps the activation result as immutable audit/quorum evidence, but requires
 * a fresh, authorization-bound resolution for task discovery, claim/collaboration and decision.
 * Runtime code must fail closed when the directory, authorization revision or exact resolution
 * evidence is unavailable. Unknown values are representable for codecs, but are not executable by
 * the built-in runtime.
 */
class WorkflowParticipantMembershipStrategy private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow participant membership strategy is invalid.",
    )

    val isBuiltin: Boolean
        get() = this == ACTIVATION_SNAPSHOT || this == CURRENT_MEMBERSHIP

    /** Whether the built-in strategy requires a fresh resolution at this operation boundary. */
    fun requiresFreshResolution(stage: WorkflowParticipantResolutionStage): Boolean = when (this) {
        ACTIVATION_SNAPSHOT -> stage == WorkflowParticipantResolutionStage.ACTIVATION
        CURRENT_MEMBERSHIP -> stage == WorkflowParticipantResolutionStage.ACTIVATION ||
            stage == WorkflowParticipantResolutionStage.QUERY ||
            stage == WorkflowParticipantResolutionStage.CLAIM ||
            stage == WorkflowParticipantResolutionStage.DECISION
        else -> true
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowParticipantMembershipStrategy && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowParticipantMembershipStrategy(<redacted>)"

    companion object {
        @JvmField
        val ACTIVATION_SNAPSHOT = WorkflowParticipantMembershipStrategy("activation-snapshot")

        @JvmField
        val CURRENT_MEMBERSHIP = WorkflowParticipantMembershipStrategy("current-membership")

        @JvmStatic
        fun of(code: String): WorkflowParticipantMembershipStrategy = when (code) {
            ACTIVATION_SNAPSHOT.code -> ACTIVATION_SNAPSHOT
            CURRENT_MEMBERSHIP.code -> CURRENT_MEMBERSHIP
            else -> WorkflowParticipantMembershipStrategy(code)
        }
    }
}
