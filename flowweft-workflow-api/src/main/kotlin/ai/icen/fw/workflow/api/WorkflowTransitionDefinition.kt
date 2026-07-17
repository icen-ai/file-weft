package ai.icen.fw.workflow.api

/**
 * Immutable directed transition in definition order.
 *
 * [trigger] explicitly binds the source-node completion or outcome that selects this edge. A
 * condition is only an exact [WorkflowPredicateRef]; executable expressions are never embedded.
 * Structural lint restricts conditional transitions to exclusive split gateways and human outcomes
 * to explicit trigger-only edges. The caller-built value is not proof that a predicate provider
 * exists or authorizes access to its inputs.
 */
class WorkflowTransitionDefinition private constructor(
    transitionId: String,
    fromNodeId: String,
    toNodeId: String,
    trigger: WorkflowTransitionTrigger?,
    val predicate: WorkflowPredicateRef?,
) {
    val transitionId: String = WorkflowContractSupport.requireMachineCode(
        transitionId,
        "Workflow transition identifier is invalid.",
    )
    val fromNodeId: String = WorkflowContractSupport.requireMachineCode(
        fromNodeId,
        "Workflow transition source node identifier is invalid.",
    )
    val toNodeId: String = WorkflowContractSupport.requireMachineCode(
        toNodeId,
        "Workflow transition target node identifier is invalid.",
    )
    val trigger: WorkflowTransitionTrigger = requireNotNull(trigger) {
        "Workflow transition trigger is required."
    }
    val contentDigest: String = WorkflowContractSupport.digest(
        WorkflowContractSupport.TRANSITION_DIGEST_DOMAIN,
    )
        .text(this.transitionId)
        .text(this.fromNodeId)
        .text(this.toNodeId)
        .text(this.trigger.code)
        .optionalText(predicate?.bindingDigest)
        .finish()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowTransitionDefinition &&
            transitionId == other.transitionId &&
            fromNodeId == other.fromNodeId &&
            toNodeId == other.toNodeId &&
            trigger == other.trigger &&
            predicate == other.predicate

    override fun hashCode(): Int {
        var result = transitionId.hashCode()
        result = 31 * result + fromNodeId.hashCode()
        result = 31 * result + toNodeId.hashCode()
        result = 31 * result + trigger.hashCode()
        result = 31 * result + (predicate?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "WorkflowTransitionDefinition(<redacted>)"

    companion object {
        @JvmStatic
        fun unconditional(
            transitionId: String,
            fromNodeId: String,
            toNodeId: String,
        ): WorkflowTransitionDefinition = WorkflowTransitionDefinition(
            transitionId,
            fromNodeId,
            toNodeId,
            WorkflowTransitionTrigger.COMPLETED,
            null,
        )

        /** Explicit trigger route without an additional predicate. */
        @JvmStatic
        fun unconditional(
            transitionId: String,
            fromNodeId: String,
            toNodeId: String,
            trigger: WorkflowTransitionTrigger,
        ): WorkflowTransitionDefinition = WorkflowTransitionDefinition(
            transitionId,
            fromNodeId,
            toNodeId,
            trigger,
            null,
        )

        @JvmStatic
        fun conditional(
            transitionId: String,
            fromNodeId: String,
            toNodeId: String,
            predicate: WorkflowPredicateRef,
        ): WorkflowTransitionDefinition = WorkflowTransitionDefinition(
            transitionId,
            fromNodeId,
            toNodeId,
            WorkflowTransitionTrigger.COMPLETED,
            predicate,
        )

        /** Explicit trigger route with an exact provider predicate binding. */
        @JvmStatic
        fun conditional(
            transitionId: String,
            fromNodeId: String,
            toNodeId: String,
            trigger: WorkflowTransitionTrigger,
            predicate: WorkflowPredicateRef,
        ): WorkflowTransitionDefinition = WorkflowTransitionDefinition(
            transitionId,
            fromNodeId,
            toNodeId,
            trigger,
            predicate,
        )
    }
}
