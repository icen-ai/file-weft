package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

/**
 * Immutable aggregate state for one exact tenant, definition revision and subject revision.
 * No field is inferred from a thread-local context. Persistence restoration must retain every
 * binding and [stateDigest] and the engine revalidates it against the compiled definition.
 */
class WorkflowInstanceState private constructor(
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    val initiator: WorkflowPrincipalRef,
    val status: WorkflowInstanceStatus,
    version: Long,
    createdAt: Long,
    updatedAt: Long,
    tokens: Collection<WorkflowTokenState>,
    nodeExecutions: Collection<WorkflowNodeExecutionState>,
    humanWorkItems: Collection<WorkflowHumanWorkItemState>,
    pendingContinuationEffectId: String?,
    pendingContinuationRequestDigest: String?,
    val suspendedFromStatus: WorkflowInstanceStatus?,
) {
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String = text(definitionId, "definition")
    val version: Long = WorkflowDomainSupport.requireVersion(version, "Workflow instance version is invalid.")
    val createdAt: Long = WorkflowDomainSupport.requireTime(createdAt, "Workflow instance creation time is invalid.")
    val updatedAt: Long = WorkflowDomainSupport.requireTime(updatedAt, "Workflow instance update time is invalid.")
    val tokens: List<WorkflowTokenState> = WorkflowDomainSupport.immutableList(
        tokens,
        WorkflowDomainSupport.MAX_STATE_ITEMS,
        "Workflow instance tokens are invalid or exceed the limit.",
    )
    val nodeExecutions: List<WorkflowNodeExecutionState> = WorkflowDomainSupport.immutableList(
        nodeExecutions,
        WorkflowDomainSupport.MAX_STATE_ITEMS,
        "Workflow node executions are invalid or exceed the limit.",
    )
    val humanWorkItems: List<WorkflowHumanWorkItemState> = WorkflowDomainSupport.immutableList(
        humanWorkItems,
        WorkflowDomainSupport.MAX_STATE_ITEMS,
        "Workflow human work items are invalid or exceed the limit.",
    )
    val pendingContinuationEffectId: String? = pendingContinuationEffectId?.let { value ->
        text(value, "continuation effect")
    }
    val pendingContinuationRequestDigest: String? = pendingContinuationRequestDigest?.let { value ->
        WorkflowDomainSupport.requireSha256(value, "Workflow continuation request digest is invalid.")
    }
    val stateDigest: String

    init {
        require(this.version > 0L && this.updatedAt >= this.createdAt && this.tokens.isNotEmpty()) {
            "Workflow instance state is invalid."
        }
        require((this.pendingContinuationEffectId == null) == (this.pendingContinuationRequestDigest == null)) {
            "Workflow continuation binding is incomplete."
        }
        require(this.tokens.map { token -> token.tokenId }.toSet().size == this.tokens.size) {
            "Workflow token ids must be unique."
        }
        require(this.nodeExecutions.map { execution -> execution.executionId }.toSet().size == this.nodeExecutions.size) {
            "Workflow node execution ids must be unique."
        }
        require(this.humanWorkItems.map { workItem -> workItem.workItemId }.toSet().size == this.humanWorkItems.size) {
            "Workflow human work-item ids must be unique."
        }
        val tokensById = this.tokens.associateBy { token -> token.tokenId }
        val executionsById = this.nodeExecutions.associateBy { execution -> execution.executionId }
        require(this.nodeExecutions.all { execution -> tokensById.containsKey(execution.tokenId) }) {
            "Workflow node executions must reference aggregate tokens."
        }
        require(this.tokens.all { token ->
            token.waitingExecutionId == null || executionsById[token.waitingExecutionId]?.tokenId == token.tokenId
        }) { "Workflow waiting tokens must reference their own node execution." }
        require(this.humanWorkItems.all { workItem ->
            tokensById[workItem.tokenId] != null &&
                executionsById[workItem.nodeExecutionId]?.tokenId == workItem.tokenId
        }) { "Workflow human work items must reference aggregate token executions." }
        when (status) {
            WorkflowInstanceStatus.COMPLETED -> require(
                suspendedFromStatus == null && this.pendingContinuationEffectId == null &&
                    this.tokens.all { token ->
                        token.status == WorkflowTokenStatus.COMPLETED || token.status == WorkflowTokenStatus.CONSUMED
                    },
            ) { "Completed workflow instances cannot retain live tokens or continuations." }

            WorkflowInstanceStatus.INCIDENT -> require(
                suspendedFromStatus == null && this.pendingContinuationEffectId == null,
            ) {
                "Incident workflow instances cannot retain a continuation."
            }

            WorkflowInstanceStatus.SUSPENDED -> require(
                suspendedFromStatus == WorkflowInstanceStatus.RUNNING ||
                    suspendedFromStatus == WorkflowInstanceStatus.WAITING,
            ) { "Suspended workflow instances require their exact prior operational status." }

            WorkflowInstanceStatus.CANCELLED,
            WorkflowInstanceStatus.TERMINATED -> require(
                this.pendingContinuationEffectId == null && suspendedFromStatus == null,
            ) { "Terminally controlled workflow instances cannot retain continuation state." }

            WorkflowInstanceStatus.RUNNING,
            WorkflowInstanceStatus.WAITING -> require(suspendedFromStatus == null) {
                "Active workflow instances cannot retain a suspended origin."
            }
            else -> throw IllegalArgumentException("Unknown workflow instance status is unsupported.")
        }
        val writer = WorkflowDomainSupport.digest("flowweft-workflow-domain-instance-state-v1")
            .text(this.tenantId)
            .text(this.instanceId)
            .text(this.definitionId)
            .text(definitionRef.key)
            .text(definitionRef.version)
            .text(definitionRef.digest)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(initiator.type)
            .text(initiator.id)
            .text(status.code)
            .longValue(this.version)
            .longValue(this.createdAt)
            .longValue(this.updatedAt)
            .integer(this.tokens.size)
        this.tokens.forEach { token -> writer.text(token.contentDigest) }
        writer.integer(this.nodeExecutions.size)
        this.nodeExecutions.forEach { execution -> writer.text(execution.contentDigest) }
        writer.integer(this.humanWorkItems.size)
        this.humanWorkItems.forEach { workItem -> writer.text(workItem.contentDigest) }
        writer.optionalText(this.pendingContinuationEffectId)
            .optionalText(this.pendingContinuationRequestDigest)
        if (suspendedFromStatus != null) writer.text(suspendedFromStatus.code)
        stateDigest = writer.finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowInstanceState &&
            stateDigest == other.stateDigest &&
            version == other.version

    override fun hashCode(): Int = 31 * stateDigest.hashCode() + version.hashCode()

    override fun toString(): String = "WorkflowInstanceState(<redacted>)"

    companion object {
        @JvmStatic
        fun restore(
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            initiator: WorkflowPrincipalRef,
            status: WorkflowInstanceStatus,
            version: Long,
            createdAt: Long,
            updatedAt: Long,
            tokens: Collection<WorkflowTokenState>,
            nodeExecutions: Collection<WorkflowNodeExecutionState>,
            humanWorkItems: Collection<WorkflowHumanWorkItemState>,
            pendingContinuationEffectId: String?,
            pendingContinuationRequestDigest: String?,
        ): WorkflowInstanceState = restore(
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            initiator,
            status,
            version,
            createdAt,
            updatedAt,
            tokens,
            nodeExecutions,
            humanWorkItems,
            pendingContinuationEffectId,
            pendingContinuationRequestDigest,
            null,
        )

        @JvmStatic
        fun restore(
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            initiator: WorkflowPrincipalRef,
            status: WorkflowInstanceStatus,
            version: Long,
            createdAt: Long,
            updatedAt: Long,
            tokens: Collection<WorkflowTokenState>,
            nodeExecutions: Collection<WorkflowNodeExecutionState>,
            humanWorkItems: Collection<WorkflowHumanWorkItemState>,
            pendingContinuationEffectId: String?,
            pendingContinuationRequestDigest: String?,
            suspendedFromStatus: WorkflowInstanceStatus?,
        ): WorkflowInstanceState = WorkflowInstanceState(
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            initiator,
            status,
            version,
            createdAt,
            updatedAt,
            tokens,
            nodeExecutions,
            humanWorkItems,
            pendingContinuationEffectId,
            pendingContinuationRequestDigest,
            suspendedFromStatus,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow $label identifier is invalid.",
        )
    }
}
