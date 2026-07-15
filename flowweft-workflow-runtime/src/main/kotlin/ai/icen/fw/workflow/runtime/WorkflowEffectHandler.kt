package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.domain.WorkflowEffectCode
import ai.icen.fw.workflow.domain.WorkflowInstanceState

/** Clock boundary used by workers after external calls; tests can supply deterministic time. */
fun interface WorkflowWorkerClock {
    fun currentTimeMillis(): Long
}

class WorkflowEffectHandlerRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val claim: WorkflowClaimedEffectJob,
    val effect: WorkflowEffectRecord,
    val state: WorkflowInstanceState,
    val definition: WorkflowRuntimeDefinitionRecord,
    now: Long,
    deadline: Long,
) {
    val now: Long = WorkflowRuntimeSupport.nonNegative(now, "Workflow handler request time is invalid.")
    val deadline: Long = WorkflowRuntimeSupport.nonNegative(deadline, "Workflow handler deadline is invalid.")

    init {
        require(this.deadline > this.now && this.deadline <= claim.lease.expiresAt) {
            "Workflow handler deadline is outside its queue lease."
        }
        require(callContext.tenantId == claim.tenantId && effect.intent.tenantId == claim.tenantId &&
            effect.intent.instanceId == claim.instanceId && effect.intent.effectId == claim.effectId &&
            effect.intent.code == claim.effectCode && state.tenantId == claim.tenantId &&
            state.instanceId == claim.instanceId && state.definitionId == effect.intent.definitionId &&
            state.definitionRef == effect.intent.definitionRef && state.subject == effect.intent.subject &&
            definition.index.definition.definitionId == state.definitionId &&
            definition.index.definition.ref == state.definitionRef
        ) { "Workflow handler request bindings are inconsistent." }
    }

    override fun toString(): String = "WorkflowEffectHandlerRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            claim: WorkflowClaimedEffectJob,
            effect: WorkflowEffectRecord,
            state: WorkflowInstanceState,
            definition: WorkflowRuntimeDefinitionRecord,
            now: Long,
            deadline: Long,
        ): WorkflowEffectHandlerRequest = WorkflowEffectHandlerRequest(
            callContext,
            claim,
            effect,
            state,
            definition,
            now,
            deadline,
        )
    }
}

class WorkflowEffectApplyRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val claim: WorkflowClaimedEffectJob,
    val effect: WorkflowEffectRecord,
    val state: WorkflowInstanceState,
    val definition: WorkflowRuntimeDefinitionRecord,
    val result: WorkflowEffectJobStoredResult,
    now: Long,
) {
    val now: Long = WorkflowRuntimeSupport.nonNegative(now, "Workflow effect apply time is invalid.")

    init {
        require(result.outcome == WorkflowEffectObservedOutcome.SUCCEEDED && result.completedAt <= now &&
            (claim.mode != WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER ||
                result.completedAt >= claim.claimedAt) && now < claim.lease.expiresAt
        ) {
            "Workflow effect apply requires a successful result and a live queue lease."
        }
        require(
            (claim.mode == WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT &&
                effect.version == claim.expectedEffectVersion) ||
                (claim.mode == WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER &&
                    effect.version > claim.expectedEffectVersion)
        ) { "Workflow effect apply record version is not bound to its queue claim." }
        require(callContext.tenantId == claim.tenantId && effect.intent.tenantId == claim.tenantId &&
            effect.intent.instanceId == claim.instanceId && effect.intent.effectId == claim.effectId &&
            effect.intent.code == claim.effectCode && effect.status == WorkflowEffectDeliveryStatus.SUCCEEDED &&
            effect.outcomeDigest == result.resultDigest && state.tenantId == claim.tenantId &&
            state.instanceId == claim.instanceId && state.definitionId == effect.intent.definitionId &&
            state.definitionRef == effect.intent.definitionRef && state.subject == effect.intent.subject &&
            definition.index.definition.definitionId == state.definitionId &&
            definition.index.definition.ref == state.definitionRef
        ) { "Workflow effect apply bindings are inconsistent." }
    }

    override fun toString(): String = "WorkflowEffectApplyRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            claim: WorkflowClaimedEffectJob,
            effect: WorkflowEffectRecord,
            state: WorkflowInstanceState,
            definition: WorkflowRuntimeDefinitionRecord,
            result: WorkflowEffectJobStoredResult,
            now: Long,
        ): WorkflowEffectApplyRequest = WorkflowEffectApplyRequest(
            callContext,
            claim,
            effect,
            state,
            definition,
            result,
            now,
        )
    }
}

/**
 * Effect-specific provider boundary used by the durable worker. [execute] runs only after the
 * provider-call checkpoint has committed and outside every database transaction. [apply] may
 * call local application use cases but must not repeat the provider call.
 */
interface WorkflowEffectHandler {
    fun effectCode(): WorkflowEffectCode
    fun execute(request: WorkflowEffectHandlerRequest): WorkflowEffectJobStoredResult
    fun apply(request: WorkflowEffectApplyRequest): WorkflowRuntimeResult
}
