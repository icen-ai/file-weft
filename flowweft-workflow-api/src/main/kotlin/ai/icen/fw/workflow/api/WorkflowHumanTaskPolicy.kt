package ai.icen.fw.workflow.api

/**
 * Immutable, bounded human-task assignment and completion contract.
 *
 * [participantRules] are ordered tiers. [resolutionStages] must begin with [WorkflowParticipantResolutionStage.ACTIVATION];
 * later entries request fail-closed re-resolution before that operation. Re-resolution never
 * replaces current authorization, state-version checks or an auditable command. Extension stages
 * are representable but require an explicit runtime provider.
 */
class WorkflowHumanTaskPolicy private constructor(
    participantRules: Collection<WorkflowHumanTaskParticipantRule>,
    val capabilities: WorkflowHumanTaskCapabilities,
    val separationOfDuties: WorkflowSeparationOfDutiesPolicy,
    resolutionStages: Collection<WorkflowParticipantResolutionStage>,
    val evidenceBinding: WorkflowHumanTaskEvidenceBinding,
) {
    val participantRules: List<WorkflowHumanTaskParticipantRule> = WorkflowContractSupport.immutableList(
        participantRules,
        WorkflowContractSupport.MAX_HUMAN_TASK_RULES,
        "Workflow human-task participant rules are invalid or exceed the limit.",
    )
    val resolutionStages: List<WorkflowParticipantResolutionStage> = WorkflowContractSupport.immutableList(
        resolutionStages,
        WorkflowContractSupport.MAX_RESOLUTION_STAGES,
        "Workflow human-task resolution stages are invalid or exceed the limit.",
    )
    val contentDigest: String

    init {
        require(this.participantRules.isNotEmpty()) {
            "Workflow human tasks require at least one participant rule."
        }
        require(this.participantRules.map { rule -> rule.selector.digest }.toSet().size == this.participantRules.size) {
            "Workflow human-task participant selectors must be unique."
        }
        require(this.resolutionStages.isNotEmpty() &&
            this.resolutionStages.first() == WorkflowParticipantResolutionStage.ACTIVATION
        ) {
            "Workflow human-task resolution stages must begin with activation."
        }
        require(this.resolutionStages.toSet().size == this.resolutionStages.size) {
            "Workflow human-task resolution stages must be unique."
        }

        val writer = WorkflowContractSupport.digest(WorkflowContractSupport.HUMAN_TASK_POLICY_DIGEST_DOMAIN)
            .integer(this.participantRules.size)
        this.participantRules.forEach { rule -> writer.text(rule.contentDigest) }
        writer.text(capabilities.contentDigest)
            .text(separationOfDuties.contentDigest)
            .integer(this.resolutionStages.size)
        this.resolutionStages.forEach { stage -> writer.text(stage.code) }
        if (!evidenceBinding.isBuiltinNone) writer.text(evidenceBinding.contentDigest)
        contentDigest = writer.finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowHumanTaskPolicy &&
            participantRules == other.participantRules &&
            capabilities == other.capabilities &&
            separationOfDuties == other.separationOfDuties &&
            evidenceBinding == other.evidenceBinding &&
            resolutionStages == other.resolutionStages

    override fun hashCode(): Int {
        var result = participantRules.hashCode()
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + separationOfDuties.hashCode()
        result = 31 * result + evidenceBinding.hashCode()
        result = 31 * result + resolutionStages.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowHumanTaskPolicy(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            participantRules: Collection<WorkflowHumanTaskParticipantRule>,
            capabilities: WorkflowHumanTaskCapabilities,
            separationOfDuties: WorkflowSeparationOfDutiesPolicy,
            resolutionStages: Collection<WorkflowParticipantResolutionStage>,
        ): WorkflowHumanTaskPolicy = WorkflowHumanTaskPolicy(
            participantRules,
            capabilities,
            separationOfDuties,
            resolutionStages,
            WorkflowHumanTaskEvidenceBinding.none(),
        )

        @JvmStatic
        fun of(
            participantRules: Collection<WorkflowHumanTaskParticipantRule>,
            capabilities: WorkflowHumanTaskCapabilities,
            separationOfDuties: WorkflowSeparationOfDutiesPolicy,
            resolutionStages: Collection<WorkflowParticipantResolutionStage>,
            evidenceBinding: WorkflowHumanTaskEvidenceBinding,
        ): WorkflowHumanTaskPolicy = WorkflowHumanTaskPolicy(
            participantRules,
            capabilities,
            separationOfDuties,
            resolutionStages,
            evidenceBinding,
        )
    }
}
