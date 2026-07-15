package ai.icen.fw.workflow.api

/**
 * Immutable completion policy for one human-task participant rule.
 *
 * [QUORUM] is strict: [requiredApprovals] counts distinct accepted principals after activation
 * resolution, delegation and separation-of-duties filtering. Construction checks only that the
 * quorum is positive because the denominator is runtime evidence. The runtime must fail closed
 * when the requested quorum exceeds that denominator; it must never clamp or silently downgrade.
 * This DTO is not authorization for any principal to act.
 */
class WorkflowApprovalPolicy private constructor(
    val mode: WorkflowApprovalMode,
    val requiredApprovals: Int?,
) {
    val contentDigest: String

    init {
        when (mode) {
            WorkflowApprovalMode.ONE,
            WorkflowApprovalMode.ALL -> require(requiredApprovals == null) {
                "ONE and ALL workflow approval policies do not accept a quorum."
            }

            WorkflowApprovalMode.QUORUM -> require(requiredApprovals != null && requiredApprovals > 0) {
                "Workflow approval quorum must be positive."
            }

            else -> throw IllegalArgumentException("Unknown workflow approval modes require a future typed contract.")
        }

        val writer = WorkflowContractSupport.digest(WorkflowContractSupport.APPROVAL_POLICY_DIGEST_DOMAIN)
            .text(mode.code)
            .booleanValue(requiredApprovals != null)
        requiredApprovals?.let(writer::integer)
        contentDigest = writer.finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowApprovalPolicy &&
            mode == other.mode &&
            requiredApprovals == other.requiredApprovals

    override fun hashCode(): Int = 31 * mode.hashCode() + (requiredApprovals ?: 0)

    override fun toString(): String = "WorkflowApprovalPolicy(<redacted>)"

    companion object {
        @JvmStatic
        fun one(): WorkflowApprovalPolicy = WorkflowApprovalPolicy(WorkflowApprovalMode.ONE, null)

        @JvmStatic
        fun all(): WorkflowApprovalPolicy = WorkflowApprovalPolicy(WorkflowApprovalMode.ALL, null)

        @JvmStatic
        fun quorum(requiredApprovals: Int): WorkflowApprovalPolicy =
            WorkflowApprovalPolicy(WorkflowApprovalMode.QUORUM, requiredApprovals)
    }
}
