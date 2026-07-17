package ai.icen.fw.workflow.api

/**
 * Explicit feature switches for one human task.
 *
 * A true switch only enables the corresponding runtime command path. Every command still requires
 * current tenant/user authorization, optimistic state checks, audit and any configured approval.
 */
class WorkflowHumanTaskCapabilities private constructor(
    val addSignEnabled: Boolean,
    val delegationEnabled: Boolean,
    val transferEnabled: Boolean,
    val claimEnabled: Boolean,
) {
    val contentDigest: String = WorkflowContractSupport.digest(
        WorkflowContractSupport.HUMAN_TASK_CAPABILITIES_DIGEST_DOMAIN,
    )
        .booleanValue(addSignEnabled)
        .booleanValue(delegationEnabled)
        .booleanValue(transferEnabled)
        .booleanValue(claimEnabled)
        .finish()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowHumanTaskCapabilities &&
            addSignEnabled == other.addSignEnabled &&
            delegationEnabled == other.delegationEnabled &&
            transferEnabled == other.transferEnabled &&
            claimEnabled == other.claimEnabled

    override fun hashCode(): Int {
        var result = addSignEnabled.hashCode()
        result = 31 * result + delegationEnabled.hashCode()
        result = 31 * result + transferEnabled.hashCode()
        result = 31 * result + claimEnabled.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowHumanTaskCapabilities(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            addSignEnabled: Boolean,
            delegationEnabled: Boolean,
            transferEnabled: Boolean,
            claimEnabled: Boolean,
        ): WorkflowHumanTaskCapabilities = WorkflowHumanTaskCapabilities(
            addSignEnabled,
            delegationEnabled,
            transferEnabled,
            claimEnabled,
        )
    }
}
