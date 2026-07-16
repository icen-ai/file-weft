package ai.icen.fw.governance.persistence.jdbc

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot
import ai.icen.fw.governance.api.GovernanceCallContext
import ai.icen.fw.governance.api.GovernanceDeletionExecutionRequest
import ai.icen.fw.governance.api.GovernanceDeletionPlan
import ai.icen.fw.governance.api.GovernanceDeletionStep
import ai.icen.fw.governance.api.GovernanceDeletionStepReceipt
import ai.icen.fw.governance.api.GovernanceDeletionStepStatus
import ai.icen.fw.governance.api.GovernanceEffectiveClock
import ai.icen.fw.governance.api.GovernanceLegalHoldResolution
import ai.icen.fw.governance.api.GovernancePrincipalRef
import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.api.GovernanceResourceRef
import ai.icen.fw.governance.api.GovernanceRetentionAssessment
import ai.icen.fw.governance.api.GovernanceRetentionEvaluationRequest
import ai.icen.fw.governance.api.GovernanceRetentionPolicyMode
import ai.icen.fw.governance.api.GovernanceRetentionPolicySnapshot
import ai.icen.fw.governance.api.GovernanceVersionFence
import ai.icen.fw.governance.runtime.GovernanceDeletionDispatch
import ai.icen.fw.governance.runtime.GovernanceDeletionRun

internal object GovernanceJdbcTestFixture {
    const val TENANT = "tenant-1"
    val principal: GovernancePrincipalRef = GovernancePrincipalRef.of("user", "user-1")
    val resource: GovernanceResourceRef = GovernanceResourceRef.of(
        "document", "document-1", "revision-1", digest('a'),
    )
    val fence: GovernanceVersionFence = GovernanceVersionFence.of(resource, 7L)

    fun readyRun(): GovernanceDeletionRun {
        val plan = plan()
        return GovernanceDeletionRun.ready(plan, digest('b'), "delete-document-1", plan.createdAtEpochMilli)
    }

    fun preparedSecondStepRun(): GovernanceDeletionRun {
        val initial = readyRun()
        val firstRequest = executionRequest(initial, 0, 2_000L)
        val firstPrepared = GovernanceDeletionRun.prepare(
            initial,
            GovernanceDeletionDispatch.prepared(
                firstRequest, "metadata-provider", "provider-r1", firstRequest.context.idempotencyKey, 2_001L,
            ),
            2_001L,
        )
        val started = GovernanceDeletionRun.markProviderCallStarted(firstPrepared, 2_002L)
        val receipt = GovernanceDeletionStepReceipt.success(
            firstRequest,
            "metadata-provider",
            "provider-r1",
            GovernanceDeletionStepStatus.COMPLETED,
            "receipt-1",
            digest('c'),
            2_003L,
        )
        val afterFirst = GovernanceDeletionRun.recordExecution(started, receipt, 2_004L)
        val secondRequest = executionRequest(afterFirst, 1, 2_100L)
        return GovernanceDeletionRun.prepare(
            afterFirst,
            GovernanceDeletionDispatch.prepared(
                secondRequest, "audit-provider", "provider-r1", secondRequest.context.idempotencyKey, 2_101L,
            ),
            2_101L,
        )
    }

    private fun executionRequest(
        run: GovernanceDeletionRun,
        stepIndex: Int,
        requestedAt: Long,
    ): GovernanceDeletionExecutionRequest {
        val context = context(
            GovernancePurpose.EXECUTE_SECURE_DELETION,
            "execute-${stepIndex + 1}",
            requestedAt,
            requestedAt + 100L,
        )
        return GovernanceDeletionExecutionRequest.of(
            context,
            run.plan,
            run.plan.steps[stepIndex],
            run.plan.assessment,
            1,
            null,
            run.successfulReceipts,
            run.version,
        )
    }

    private fun plan(): GovernanceDeletionPlan {
        val assessment = assessment()
        val planContext = context(
            GovernancePurpose.PLAN_SECURE_DELETION,
            "plan-delete-document-1",
            1_000L,
            1_100L,
        )
        val steps = GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.mapIndexed { index, stage ->
            GovernanceDeletionStep.of(
                "step-${index + 1}",
                index + 1,
                stage,
                "target-${index + 1}",
                "target-r1",
                ((index % 6) + 1).toString().repeat(64),
                "delete-step-${index + 1}",
            )
        }
        return GovernanceDeletionPlan.of(
            "plan-1", planContext, fence, assessment, steps, false, 1_001L, 50_000L,
        )
    }

    private fun assessment(): GovernanceRetentionAssessment {
        val clock = GovernanceEffectiveClock.of(
            "clock-1", "trusted-clock", "clock-r1", 1_000L, 2_000L, 100_000L,
        )
        val holds = GovernanceLegalHoldResolution.clear(
            resource, TENANT, "hold-registry", "registry-r1", clock, emptyList(), 80_000L,
        )
        val policy = GovernanceRetentionPolicySnapshot.of(
            TENANT,
            resource,
            "records-policy",
            "policy-r1",
            digest('d'),
            GovernanceRetentionPolicyMode.RETAIN_UNTIL,
            0L,
            900L,
            90_000L,
            1_500L,
        )
        val evaluationContext = context(
            GovernancePurpose.EVALUATE_RETENTION,
            "evaluate-document-1",
            990L,
            1_100L,
        )
        return GovernanceRetentionAssessment.evaluate(
            GovernanceRetentionEvaluationRequest.of(evaluationContext, fence, policy, holds, clock),
        )
    }

    private fun context(
        purpose: GovernancePurpose,
        idempotencyKey: String,
        requestedAt: Long,
        deadline: Long,
    ): GovernanceCallContext {
        val authorization = GovernanceAuthorizationSnapshot.of(
            "authorization-$idempotencyKey",
            TENANT,
            principal,
            purpose,
            resource,
            "host-authorization",
            "authority-r1",
            "authorization-r1",
            digest('e'),
            requestedAt - 100L,
            deadline + 100L,
        )
        return GovernanceCallContext.of(
            "request-$idempotencyKey",
            TENANT,
            principal,
            purpose,
            authorization,
            idempotencyKey,
            requestedAt,
            deadline,
        )
    }

    fun digest(character: Char): String = character.toString().repeat(64)
}
