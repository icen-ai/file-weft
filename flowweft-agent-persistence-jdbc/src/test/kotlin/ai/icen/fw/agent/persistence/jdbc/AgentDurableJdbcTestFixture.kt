package ai.icen.fw.agent.persistence.jdbc

import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentRunRequest
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.AgentRunStatusChangedEvent
import ai.icen.fw.agent.api.AgentTextContentBlock
import ai.icen.fw.agent.api.LanguageModelDescriptor
import ai.icen.fw.agent.api.ModelId
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.runtime.AgentDurableRunState
import ai.icen.fw.agent.runtime.AgentPendingModelOperation
import ai.icen.fw.agent.runtime.AgentPendingModelPhase
import ai.icen.fw.agent.runtime.AgentRunAdmissionDecision
import ai.icen.fw.agent.runtime.AgentRunAdmissionRequest
import ai.icen.fw.agent.runtime.AgentRunCreateCommit
import ai.icen.fw.agent.runtime.AgentRuntimeCheckpoint
import ai.icen.fw.agent.runtime.AgentRuntimeCheckpointEvent
import ai.icen.fw.agent.runtime.AgentRuntimeStep
import ai.icen.fw.agent.runtime.AgentRuntimeStepKind
import ai.icen.fw.agent.runtime.AgentRuntimeStepStatus
import ai.icen.fw.agent.runtime.AgentStoreCommit
import ai.icen.fw.agent.runtime.AgentStoreCommitAuthority
import ai.icen.fw.core.id.Identifier

internal object AgentDurableJdbcTestFixture {
    fun initial(
        runId: String = "run-1",
        tenantId: String = "租户-天津-A",
        idempotencyKey: String = "idempotency-1",
        requestVariant: String = "original",
    ): AgentRunCreateCommit {
        val initiatedAt = 100L
        val tenant = Identifier(tenantId)
        val capability = AgentCapabilityId("document-assistant")
        val request = AgentRunRequest(
            AgentRunContext(
                tenant,
                Identifier("用户-甲"),
                "USER",
                Identifier("request-$requestVariant"),
                initiatedAt,
                "zh-CN",
            ),
            capability,
            listOf(
                AgentMessage(
                    Identifier("message-$requestVariant"),
                    AgentMessageRole.USER,
                    listOf(AgentTextContentBlock(AgentContentOrigin.USER, "请求-$requestVariant")),
                    initiatedAt,
                ),
            ),
            AgentBudget(10_000L, 2_000L, 4, 2, 10_000L, 1_000_000L),
            idempotencyKey,
            1_000L,
            AgentCancellationToken.NONE,
        )
        val admissionRequest = AgentRunAdmissionRequest.create(
            Identifier("admission-request-$requestVariant"),
            request,
            initiatedAt,
        )
        val admission = AgentRunAdmissionDecision.allow(
            Identifier("admission-decision-$requestVariant"),
            ProviderId("authorization-provider"),
            admissionRequest,
            "authorization-v1",
            initiatedAt,
            request.deadlineAt,
        )
        val state = AgentDurableRunState.initial(
            Identifier(runId),
            request,
            admissionRequest,
            admission,
            initiatedAt,
        )
        return AgentRunCreateCommit(
            state,
            AgentRunStatusChangedEvent(
                state.runId,
                tenant,
                1L,
                initiatedAt,
                null,
                AgentRunStatus.QUEUED,
            ),
        )
    }

    fun running(claimed: AgentDurableRunState, atTime: Long = 120L): AgentStoreCommit {
        val next = restore(
            claimed,
            AgentRunStatus.RUNNING,
            claimed.stateVersion + 1L,
            claimed.eventSequence + 1L,
            claimed.checkpointSequence,
            atTime,
            claimed.steps,
            claimed.checkpoints,
            claimed.currentStepId,
            claimed.pendingOperation,
            claimed.lease,
        )
        val event = AgentRunStatusChangedEvent(
            claimed.runId,
            claimed.tenantId,
            claimed.eventSequence + 1L,
            atTime,
            AgentRunStatus.QUEUED,
            AgentRunStatus.RUNNING,
        )
        return AgentStoreCommit(
            claimedKey(claimed),
            claimed.stateVersion,
            claimed.eventSequence,
            AgentStoreCommitAuthority.WORKER,
            requireNotNull(claimed.lease),
            atTime,
            next,
            listOf(event),
        )
    }

    fun checkpointModel(running: AgentDurableRunState, atTime: Long = 130L): AgentStoreCommit {
        val stepId = Identifier("step-model-1")
        val operationId = Identifier("operation-model-1")
        val checkpointId = Identifier("checkpoint-model-1")
        val descriptor = LanguageModelDescriptor(
            ProviderId("model-provider"),
            ModelId("model-1"),
            "测试模型",
            listOf(running.capabilityId),
            10_000L,
            2_000L,
            false,
            false,
            100_000L,
            1_000L,
        )
        val pending = AgentPendingModelOperation(
            operationId,
            stepId,
            Identifier("model-request-1"),
            descriptor,
            emptyList(),
            1_000L,
            500L,
            100_000L,
            1_000L,
            900L,
            1,
            AgentPendingModelPhase.CHECKPOINTED,
            checkpointId,
            null,
            atTime,
            atTime,
        )
        val step = AgentRuntimeStep(
            stepId,
            AgentRuntimeStepKind.MODEL,
            AgentRuntimeStepStatus.CHECKPOINTED,
            operationId,
            1,
            atTime,
            atTime,
        )
        val checkpoint = AgentRuntimeCheckpoint(
            checkpointId,
            running.runId,
            running.tenantId,
            stepId,
            operationId,
            "model.checkpointed",
            pending.operationDigest,
            1L,
            atTime,
        )
        val next = restore(
            running,
            AgentRunStatus.RUNNING,
            running.stateVersion + 1L,
            running.eventSequence + 1L,
            1L,
            atTime,
            listOf(step),
            listOf(checkpoint),
            stepId,
            pending,
            running.lease,
        )
        return AgentStoreCommit(
            claimedKey(running),
            running.stateVersion,
            running.eventSequence,
            AgentStoreCommitAuthority.WORKER,
            requireNotNull(running.lease),
            atTime,
            next,
            listOf(
                AgentRuntimeCheckpointEvent(
                    running.runId,
                    running.tenantId,
                    running.eventSequence + 1L,
                    atTime,
                    checkpoint,
                ),
            ),
        )
    }

    fun completeModel(withPending: AgentDurableRunState, atTime: Long = 140L): AgentStoreCommit {
        val before = requireNotNull(withPending.steps.singleOrNull())
        val completed = AgentRuntimeStep(
            before.stepId,
            before.kind,
            AgentRuntimeStepStatus.COMPLETED,
            before.operationId,
            before.attempt,
            before.createdAt,
            atTime,
        )
        val next = restore(
            withPending,
            AgentRunStatus.COMPLETED,
            withPending.stateVersion + 1L,
            withPending.eventSequence + 1L,
            withPending.checkpointSequence,
            atTime,
            listOf(completed),
            withPending.checkpoints,
            null,
            null,
            null,
        )
        return AgentStoreCommit(
            claimedKey(withPending),
            withPending.stateVersion,
            withPending.eventSequence,
            AgentStoreCommitAuthority.WORKER,
            requireNotNull(withPending.lease),
            atTime,
            next,
            listOf(
                AgentRunStatusChangedEvent(
                    withPending.runId,
                    withPending.tenantId,
                    withPending.eventSequence + 1L,
                    atTime,
                    AgentRunStatus.RUNNING,
                    AgentRunStatus.COMPLETED,
                ),
            ),
        )
    }

    private fun restore(
        source: AgentDurableRunState,
        status: AgentRunStatus,
        stateVersion: Long,
        eventSequence: Long,
        checkpointSequence: Long,
        updatedAt: Long,
        steps: Collection<AgentRuntimeStep>,
        checkpoints: Collection<AgentRuntimeCheckpoint>,
        currentStepId: Identifier?,
        pending: ai.icen.fw.agent.runtime.AgentPendingOperation?,
        lease: ai.icen.fw.agent.runtime.AgentRunLease?,
    ): AgentDurableRunState = AgentDurableRunState.restore(
        source.runId,
        source.context,
        source.capabilityId,
        source.messages,
        source.budget,
        source.usage,
        status,
        stateVersion,
        eventSequence,
        checkpointSequence,
        source.createdAt,
        updatedAt,
        source.deadlineAt,
        source.idempotencyScope,
        source.admission,
        steps,
        checkpoints,
        currentStepId,
        pending,
        lease,
        source.cancellation,
        source.failure,
        source.incidents,
        source.idempotencyReplayDigest,
    )

    private fun claimedKey(state: AgentDurableRunState) =
        ai.icen.fw.agent.runtime.AgentRunKey(state.tenantId, state.runId)
}
