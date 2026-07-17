package ai.icen.fw.workflow.api

/** Bounded delegation expansion requested from the host-owned organization resolver. */
class WorkflowDelegationPolicy private constructor(
    val mode: WorkflowDelegationMode,
    val maximumHops: Int,
) {
    init {
        require(maximumHops in 0..WorkflowContractSupport.MAX_DELEGATION_HOPS) {
            "Workflow delegation hop limit is invalid."
        }
        require(mode != WorkflowDelegationMode.DISABLED || maximumHops == 0) {
            "Disabled workflow delegation must have a zero hop limit."
        }
        require(mode == WorkflowDelegationMode.DISABLED || maximumHops > 0) {
            "Enabled workflow delegation requires a positive hop limit."
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowDelegationPolicy && mode == other.mode && maximumHops == other.maximumHops

    override fun hashCode(): Int = 31 * mode.hashCode() + maximumHops

    override fun toString(): String = "WorkflowDelegationPolicy(<redacted>)"

    companion object {
        @JvmStatic
        fun disabled(): WorkflowDelegationPolicy = WorkflowDelegationPolicy(WorkflowDelegationMode.DISABLED, 0)

        @JvmStatic
        fun includeActiveDelegates(maximumHops: Int): WorkflowDelegationPolicy =
            WorkflowDelegationPolicy(WorkflowDelegationMode.INCLUDE_ACTIVE_DELEGATES, maximumHops)

        @JvmStatic
        fun activeDelegateOrOriginal(maximumHops: Int): WorkflowDelegationPolicy =
            WorkflowDelegationPolicy(WorkflowDelegationMode.ACTIVE_DELEGATE_OR_ORIGINAL, maximumHops)

        /** Extension modes remain bounded and are interpreted only by the configured resolver. */
        @JvmStatic
        fun of(mode: WorkflowDelegationMode, maximumHops: Int): WorkflowDelegationPolicy =
            WorkflowDelegationPolicy(mode, maximumHops)
    }
}
