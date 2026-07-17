package ai.icen.fw.workflow.domain

/** Immutable human work-item state with sequential rule snapshots and optimistic revision. */
class WorkflowHumanWorkItemState private constructor(
    workItemId: String,
    nodeExecutionId: String,
    tokenId: String,
    nodeId: String,
    policyDigest: String,
    val status: WorkflowHumanWorkItemStatus,
    val activeRuleIndex: Int,
    ruleSnapshots: Collection<WorkflowHumanRuleSnapshot>,
    decisions: Collection<WorkflowHumanDecision>,
    val collaboration: WorkflowHumanTaskCollaborationState,
    revision: Long,
    createdAt: Long,
    updatedAt: Long,
) {
    val workItemId: String = text(workItemId, "work item")
    val nodeExecutionId: String = text(nodeExecutionId, "node execution")
    val tokenId: String = text(tokenId, "token")
    val nodeId: String = WorkflowDomainSupport.requireCode(nodeId, "Workflow work-item node id is invalid.")
    val policyDigest: String = WorkflowDomainSupport.requireSha256(
        policyDigest,
        "Workflow human-task policy digest is invalid.",
    )
    val ruleSnapshots: List<WorkflowHumanRuleSnapshot> = WorkflowDomainSupport.immutableList(
        ruleSnapshots,
        WorkflowDomainSupport.MAX_STATE_ITEMS,
        "Workflow human rule snapshots are invalid or exceed the limit.",
    )
    val decisions: List<WorkflowHumanDecision> = WorkflowDomainSupport.immutableList(
        decisions,
        WorkflowDomainSupport.MAX_STATE_ITEMS,
        "Workflow human decisions are invalid or exceed the limit.",
    )
    val revision: Long = WorkflowDomainSupport.requireVersion(revision, "Workflow work-item revision is invalid.")
    val createdAt: Long = WorkflowDomainSupport.requireTime(createdAt, "Workflow work-item creation time is invalid.")
    val updatedAt: Long = WorkflowDomainSupport.requireTime(updatedAt, "Workflow work-item update time is invalid.")
    val contentDigest: String

    init {
        require(activeRuleIndex >= 0 && this.updatedAt >= this.createdAt) {
            "Workflow human work-item state is invalid."
        }
        this.ruleSnapshots.forEachIndexed { index, snapshot ->
            require(snapshot.ruleIndex == index) { "Workflow human-rule snapshots must be contiguous and ordered." }
        }
        require(this.decisions.map { decision -> decision.decisionId }.toSet().size == this.decisions.size) {
            "Workflow human decision ids must be unique."
        }
        require(this.decisions.groupBy { decision -> decision.ruleIndex }.values.all { ruleDecisions ->
            ruleDecisions.map { decision -> decision.actor }.toSet().size == ruleDecisions.size
        }) { "A principal can decide only once in each human-task rule." }
        require(this.decisions.all { decision -> decision.ruleIndex < this.ruleSnapshots.size }) {
            "Workflow human decisions require an activated rule snapshot."
        }
        when (status) {
            WorkflowHumanWorkItemStatus.WAITING_PARTICIPANTS -> require(
                this.ruleSnapshots.size == activeRuleIndex,
            ) { "Waiting human work items require the next unactivated rule." }

            WorkflowHumanWorkItemStatus.ACTIVE -> require(
                this.ruleSnapshots.size == activeRuleIndex + 1,
            ) { "Active human work items require the current activation snapshot." }

            WorkflowHumanWorkItemStatus.APPROVED,
            WorkflowHumanWorkItemStatus.REJECTED -> require(
                this.ruleSnapshots.isNotEmpty() && activeRuleIndex == this.ruleSnapshots.lastIndex,
            ) { "Completed human work items require their final active snapshot." }

            WorkflowHumanWorkItemStatus.INCIDENT -> Unit
            else -> throw IllegalArgumentException("Unknown workflow human work-item status is unsupported.")
        }
        val digestDomain = if (collaboration.isPristine) {
            "flowweft-workflow-domain-human-work-item-v1"
        } else {
            "flowweft-workflow-domain-human-work-item-v2"
        }
        val writer = WorkflowDomainSupport.digest(digestDomain)
            .text(this.workItemId)
            .text(this.nodeExecutionId)
            .text(this.tokenId)
            .text(this.nodeId)
            .text(this.policyDigest)
            .text(status.code)
            .integer(activeRuleIndex)
            .integer(this.ruleSnapshots.size)
        this.ruleSnapshots.forEach { snapshot -> writer.text(snapshot.activationDigest) }
        writer.integer(this.decisions.size)
        this.decisions.forEach { decision -> writer.text(decision.contentDigest) }
        if (!collaboration.isPristine) writer.text(collaboration.contentDigest)
        contentDigest = writer.longValue(this.revision)
            .longValue(this.createdAt)
            .longValue(this.updatedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowHumanWorkItemState(<redacted>)"

    companion object {
        @JvmStatic
        fun restore(
            workItemId: String,
            nodeExecutionId: String,
            tokenId: String,
            nodeId: String,
            policyDigest: String,
            status: WorkflowHumanWorkItemStatus,
            activeRuleIndex: Int,
            ruleSnapshots: Collection<WorkflowHumanRuleSnapshot>,
            decisions: Collection<WorkflowHumanDecision>,
            revision: Long,
            createdAt: Long,
            updatedAt: Long,
        ): WorkflowHumanWorkItemState = WorkflowHumanWorkItemState(
            workItemId,
            nodeExecutionId,
            tokenId,
            nodeId,
            policyDigest,
            status,
            activeRuleIndex,
            ruleSnapshots,
            decisions,
            EMPTY_COLLABORATION,
            revision,
            createdAt,
            updatedAt,
        )

        @JvmStatic
        fun restore(
            workItemId: String,
            nodeExecutionId: String,
            tokenId: String,
            nodeId: String,
            policyDigest: String,
            status: WorkflowHumanWorkItemStatus,
            activeRuleIndex: Int,
            ruleSnapshots: Collection<WorkflowHumanRuleSnapshot>,
            decisions: Collection<WorkflowHumanDecision>,
            collaboration: WorkflowHumanTaskCollaborationState,
            revision: Long,
            createdAt: Long,
            updatedAt: Long,
        ): WorkflowHumanWorkItemState = WorkflowHumanWorkItemState(
            workItemId,
            nodeExecutionId,
            tokenId,
            nodeId,
            policyDigest,
            status,
            activeRuleIndex,
            ruleSnapshots,
            decisions,
            collaboration,
            revision,
            createdAt,
            updatedAt,
        )

        private val EMPTY_COLLABORATION = WorkflowHumanTaskCollaborationState.unclaimed()

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow $label identifier is invalid.",
        )
    }
}
