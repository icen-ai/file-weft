package ai.icen.fw.workflow.domain

/** Explicit correlation frame carried by every branch token of one parallel split. */
class WorkflowParallelFrame private constructor(
    scopeId: String,
    splitNodeId: String,
    joinNodeId: String,
    val branchIndex: Int,
    val branchCount: Int,
) {
    val scopeId: String = text(scopeId, "scope")
    val splitNodeId: String = WorkflowDomainSupport.requireCode(splitNodeId, "Workflow split node id is invalid.")
    val joinNodeId: String = WorkflowDomainSupport.requireCode(joinNodeId, "Workflow join node id is invalid.")
    val contentDigest: String

    init {
        require(branchCount >= 2 && branchIndex in 0 until branchCount) {
            "Workflow parallel branch correlation is invalid."
        }
        require(this.splitNodeId != this.joinNodeId) { "Workflow parallel split and join must differ." }
        contentDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-parallel-frame-v1")
            .text(this.scopeId)
            .text(this.splitNodeId)
            .text(this.joinNodeId)
            .integer(branchIndex)
            .integer(branchCount)
            .finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowParallelFrame &&
            scopeId == other.scopeId &&
            splitNodeId == other.splitNodeId &&
            joinNodeId == other.joinNodeId &&
            branchIndex == other.branchIndex &&
            branchCount == other.branchCount

    override fun hashCode(): Int {
        var result = scopeId.hashCode()
        result = 31 * result + splitNodeId.hashCode()
        result = 31 * result + joinNodeId.hashCode()
        result = 31 * result + branchIndex
        result = 31 * result + branchCount
        return result
    }

    override fun toString(): String = "WorkflowParallelFrame(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            scopeId: String,
            splitNodeId: String,
            joinNodeId: String,
            branchIndex: Int,
            branchCount: Int,
        ): WorkflowParallelFrame = WorkflowParallelFrame(
            scopeId,
            splitNodeId,
            joinNodeId,
            branchIndex,
            branchCount,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow parallel $label identifier is invalid.",
        )
    }
}
