package ai.icen.fw.workflow.api

/**
 * One ordered participant tier and its strict completion policy.
 *
 * The selector is resolved from trusted organization snapshots at the stages configured by the
 * containing policy. The result is candidate evidence only, never a permission grant.
 */
class WorkflowHumanTaskParticipantRule private constructor(
    val selector: WorkflowParticipantSelector,
    val approvalPolicy: WorkflowApprovalPolicy,
) {
    val contentDigest: String = WorkflowContractSupport.digest(
        WorkflowContractSupport.HUMAN_TASK_RULE_DIGEST_DOMAIN,
    )
        .text(selector.digest)
        .text(approvalPolicy.contentDigest)
        .finish()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowHumanTaskParticipantRule &&
            selector == other.selector &&
            approvalPolicy == other.approvalPolicy

    override fun hashCode(): Int = 31 * selector.hashCode() + approvalPolicy.hashCode()

    override fun toString(): String = "WorkflowHumanTaskParticipantRule(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            selector: WorkflowParticipantSelector,
            approvalPolicy: WorkflowApprovalPolicy,
        ): WorkflowHumanTaskParticipantRule = WorkflowHumanTaskParticipantRule(selector, approvalPolicy)
    }
}
