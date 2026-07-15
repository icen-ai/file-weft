package ai.icen.fw.workflow.api

/**
 * Candidate exclusions for human-task participant resolution.
 *
 * Principals and approval history must come from trusted runtime state. This value neither proves
 * an exclusion was applied nor authorizes the remaining candidates.
 */
class WorkflowSeparationOfDutiesPolicy private constructor(
    val initiatorExcluded: Boolean,
    val priorApproversExcluded: Boolean,
) {
    val contentDigest: String = WorkflowContractSupport.digest(
        WorkflowContractSupport.SEPARATION_OF_DUTIES_DIGEST_DOMAIN,
    )
        .booleanValue(initiatorExcluded)
        .booleanValue(priorApproversExcluded)
        .finish()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowSeparationOfDutiesPolicy &&
            initiatorExcluded == other.initiatorExcluded &&
            priorApproversExcluded == other.priorApproversExcluded

    override fun hashCode(): Int = 31 * initiatorExcluded.hashCode() + priorApproversExcluded.hashCode()

    override fun toString(): String = "WorkflowSeparationOfDutiesPolicy(<redacted>)"

    companion object {
        @JvmStatic
        fun none(): WorkflowSeparationOfDutiesPolicy = WorkflowSeparationOfDutiesPolicy(false, false)

        @JvmStatic
        fun of(
            initiatorExcluded: Boolean,
            priorApproversExcluded: Boolean,
        ): WorkflowSeparationOfDutiesPolicy = WorkflowSeparationOfDutiesPolicy(
            initiatorExcluded,
            priorApproversExcluded,
        )
    }
}
