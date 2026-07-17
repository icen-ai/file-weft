package ai.icen.fw.workflow.domain

/** Immutable execution attempt for one token entering one node. */
class WorkflowNodeExecutionState private constructor(
    executionId: String,
    tokenId: String,
    nodeId: String,
    val status: WorkflowNodeExecutionStatus,
    revision: Long,
    startedAt: Long,
    completedAt: Long?,
    pendingEffectId: String?,
    val pendingEffectCode: WorkflowEffectCode?,
    effectRequestDigest: String?,
) {
    val executionId: String = text(executionId, "node execution")
    val tokenId: String = text(tokenId, "token")
    val nodeId: String = WorkflowDomainSupport.requireCode(nodeId, "Workflow execution node id is invalid.")
    val revision: Long = WorkflowDomainSupport.requireVersion(revision, "Workflow execution revision is invalid.")
    val startedAt: Long = WorkflowDomainSupport.requireTime(startedAt, "Workflow execution start time is invalid.")
    val completedAt: Long? = completedAt?.let { value ->
        WorkflowDomainSupport.requireTime(value, "Workflow execution completion time is invalid.")
    }
    val pendingEffectId: String? = pendingEffectId?.let { value -> text(value, "effect") }
    val effectRequestDigest: String? = effectRequestDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(value, "Workflow execution effect request digest is invalid.")
    }
    val contentDigest: String

    init {
        require(this.completedAt == null || this.completedAt >= this.startedAt) {
            "Workflow execution time window is invalid."
        }
        val pendingCount = listOf(this.pendingEffectId, pendingEffectCode, this.effectRequestDigest).count { it != null }
        require(pendingCount == 0 || pendingCount == 3) { "Workflow pending effect binding is incomplete." }
        when (status) {
            WorkflowNodeExecutionStatus.ACTIVE -> require(this.completedAt == null && pendingCount == 0) {
                "Active workflow executions cannot be completed or waiting."
            }

            WorkflowNodeExecutionStatus.WAITING -> require(this.completedAt == null) {
                "Waiting workflow executions cannot be completed."
            }

            WorkflowNodeExecutionStatus.COMPLETED,
            WorkflowNodeExecutionStatus.INCIDENT -> require(this.completedAt != null && pendingCount == 0) {
                "Terminal workflow executions require completion and no pending effect."
            }

            else -> throw IllegalArgumentException("Unknown workflow execution status is unsupported.")
        }
        contentDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-node-execution-v1")
            .text(this.executionId)
            .text(this.tokenId)
            .text(this.nodeId)
            .text(status.code)
            .longValue(this.revision)
            .longValue(this.startedAt)
            .booleanValue(this.completedAt != null)
            .let { writer -> this.completedAt?.let(writer::longValue) ?: writer }
            .optionalText(this.pendingEffectId)
            .optionalText(pendingEffectCode?.code)
            .optionalText(this.effectRequestDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowNodeExecutionState(<redacted>)"

    companion object {
        @JvmStatic
        fun restore(
            executionId: String,
            tokenId: String,
            nodeId: String,
            status: WorkflowNodeExecutionStatus,
            revision: Long,
            startedAt: Long,
            completedAt: Long?,
            pendingEffectId: String?,
            pendingEffectCode: WorkflowEffectCode?,
            effectRequestDigest: String?,
        ): WorkflowNodeExecutionState = WorkflowNodeExecutionState(
            executionId,
            tokenId,
            nodeId,
            status,
            revision,
            startedAt,
            completedAt,
            pendingEffectId,
            pendingEffectCode,
            effectRequestDigest,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow $label identifier is invalid.",
        )
    }
}
