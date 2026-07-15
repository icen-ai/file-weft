package ai.icen.fw.workflow.domain

/** Immutable token position with an explicit nested parallel-correlation stack. */
class WorkflowTokenState private constructor(
    tokenId: String,
    nodeId: String,
    val status: WorkflowTokenStatus,
    parallelFrames: Collection<WorkflowParallelFrame>,
    waitingExecutionId: String?,
    revision: Long,
) {
    val tokenId: String = text(tokenId, "token")
    val nodeId: String = WorkflowDomainSupport.requireCode(nodeId, "Workflow token node id is invalid.")
    val parallelFrames: List<WorkflowParallelFrame> = WorkflowDomainSupport.immutableList(
        parallelFrames,
        WorkflowDomainSupport.MAX_PARALLEL_DEPTH,
        "Workflow token parallel frames are invalid or exceed the limit.",
    )
    val waitingExecutionId: String? = waitingExecutionId?.let { value -> text(value, "waiting execution") }
    val revision: Long = WorkflowDomainSupport.requireVersion(revision, "Workflow token revision is invalid.")
    val contentDigest: String

    init {
        require(this.parallelFrames.map { frame -> frame.scopeId }.toSet().size == this.parallelFrames.size) {
            "Workflow token parallel scope ids must be unique."
        }
        when (status) {
            WorkflowTokenStatus.ACTIVE,
            WorkflowTokenStatus.COMPLETED,
            WorkflowTokenStatus.CONSUMED -> require(this.waitingExecutionId == null) {
                "Non-waiting workflow tokens cannot reference a waiting execution."
            }

            WorkflowTokenStatus.WAITING_EFFECT,
            WorkflowTokenStatus.WAITING_HUMAN,
            WorkflowTokenStatus.WAITING_JOIN -> require(this.waitingExecutionId != null) {
                "Waiting workflow tokens require a node execution."
            }

            else -> throw IllegalArgumentException("Unknown workflow token status is unsupported.")
        }
        val writer = WorkflowDomainSupport.digest("flowweft-workflow-domain-token-state-v1")
            .text(this.tokenId)
            .text(this.nodeId)
            .text(status.code)
            .integer(this.parallelFrames.size)
        this.parallelFrames.forEach { frame -> writer.text(frame.contentDigest) }
        contentDigest = writer.optionalText(this.waitingExecutionId).longValue(this.revision).finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowTokenState &&
            tokenId == other.tokenId &&
            nodeId == other.nodeId &&
            status == other.status &&
            parallelFrames == other.parallelFrames &&
            waitingExecutionId == other.waitingExecutionId &&
            revision == other.revision

    override fun hashCode(): Int {
        var result = tokenId.hashCode()
        result = 31 * result + nodeId.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + parallelFrames.hashCode()
        result = 31 * result + (waitingExecutionId?.hashCode() ?: 0)
        result = 31 * result + revision.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowTokenState(<redacted>)"

    companion object {
        @JvmStatic
        fun restore(
            tokenId: String,
            nodeId: String,
            status: WorkflowTokenStatus,
            parallelFrames: Collection<WorkflowParallelFrame>,
            waitingExecutionId: String?,
            revision: Long,
        ): WorkflowTokenState = WorkflowTokenState(
            tokenId,
            nodeId,
            status,
            parallelFrames,
            waitingExecutionId,
            revision,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow $label identifier is invalid.",
        )
    }
}
