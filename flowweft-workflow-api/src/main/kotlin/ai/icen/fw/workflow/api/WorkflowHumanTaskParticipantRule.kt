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
    val membershipStrategy: WorkflowParticipantMembershipStrategy,
) {
    val contentDigest: String = WorkflowContractSupport.digest(
        WorkflowContractSupport.HUMAN_TASK_RULE_DIGEST_DOMAIN,
    )
        .text(selector.digest)
        .text(approvalPolicy.contentDigest)
        // Preserve the already-published/default digest while giving every non-default semantic a
        // distinct definition digest. ACTIVATION_SNAPSHOT is the legacy rule's explicit meaning.
        .also { writer ->
            if (membershipStrategy != WorkflowParticipantMembershipStrategy.ACTIVATION_SNAPSHOT) {
                writer.text(membershipStrategy.code)
            }
        }
        .finish()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowHumanTaskParticipantRule &&
            selector == other.selector &&
            approvalPolicy == other.approvalPolicy &&
            membershipStrategy == other.membershipStrategy

    override fun hashCode(): Int =
        31 * (31 * selector.hashCode() + approvalPolicy.hashCode()) + membershipStrategy.hashCode()

    override fun toString(): String = "WorkflowHumanTaskParticipantRule(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            selector: WorkflowParticipantSelector,
            approvalPolicy: WorkflowApprovalPolicy,
        ): WorkflowHumanTaskParticipantRule = WorkflowHumanTaskParticipantRule(
            selector,
            approvalPolicy,
            WorkflowParticipantMembershipStrategy.ACTIVATION_SNAPSHOT,
        )

        @JvmStatic
        fun of(
            selector: WorkflowParticipantSelector,
            approvalPolicy: WorkflowApprovalPolicy,
            membershipStrategy: WorkflowParticipantMembershipStrategy,
        ): WorkflowHumanTaskParticipantRule = WorkflowHumanTaskParticipantRule(
            selector,
            approvalPolicy,
            membershipStrategy,
        )
    }
}
