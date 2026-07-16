package ai.icen.fw.governance.persistence.jdbc

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot
import ai.icen.fw.governance.api.GovernanceCallContext
import ai.icen.fw.governance.api.GovernanceDeletionExecutionRequest
import ai.icen.fw.governance.api.GovernanceDeletionPlan
import ai.icen.fw.governance.api.GovernanceDeletionReconciliationRequest
import ai.icen.fw.governance.api.GovernanceDeletionStep
import ai.icen.fw.governance.api.GovernanceDeletionStepReceipt
import ai.icen.fw.governance.api.GovernanceDeletionStepStatus
import ai.icen.fw.governance.api.GovernanceEffectiveClock
import ai.icen.fw.governance.api.GovernanceFailure
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
import ai.icen.fw.governance.runtime.GovernanceDeletionTarget
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItem
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemKind
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetManifest
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetRequest

internal class GovernanceTargetLedgerScenario(
    val manifest: GovernanceDeletionTargetManifest,
    val item: GovernanceDeletionTargetItem,
    val secondItem: GovernanceDeletionTargetItem,
    val request: GovernanceDeletionExecutionRequest,
)

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

    fun targetLedgerScenario(): GovernanceTargetLedgerScenario {
        val assessment = assessment()
        val planContext = context(
            GovernancePurpose.PLAN_SECURE_DELETION,
            "plan-target-ledger-document-1",
            1_000L,
            1_100L,
        )
        val targetRequest = GovernanceDeletionTargetRequest.of(planContext, assessment.assessmentDigest)
        val item = GovernanceDeletionTargetItem.of(
            1,
            GovernanceDeletionTargetItemKind.OBJECT_CONTENT,
            "file-object-1",
            "storage-version-1",
            digest('7'),
            "storage-provider",
            "provider-r1",
        )
        val secondItem = GovernanceDeletionTargetItem.of(
            2,
            GovernanceDeletionTargetItemKind.OBJECT_CONTENT,
            "file-object-2",
            "storage-version-2",
            digest('8'),
            "storage-provider",
            "provider-r1",
        )
        val targetRevision = "manifest-r1"
        val target = GovernanceDeletionTarget.of(
            ai.icen.fw.governance.api.GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
            "target-object-content-1",
            targetRevision,
            GovernanceDeletionTargetManifest.calculateTargetDigest(
                ai.icen.fw.governance.api.GovernanceDeletionStage.PURGE_OBJECT_CONTENT,
                targetRevision,
                listOf(item, secondItem),
            ),
        )
        val manifest = GovernanceDeletionTargetManifest.of(targetRequest, target, listOf(item, secondItem))
        val steps = GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.mapIndexed { index, stage ->
            if (stage == target.stage) {
                GovernanceDeletionStep.of(
                    "target-ledger-step-${index + 1}",
                    index + 1,
                    stage,
                    target.targetRef,
                    target.targetRevision,
                    target.targetDigest,
                    "target-ledger-delete-step-${index + 1}",
                )
            } else {
                GovernanceDeletionStep.of(
                    "target-ledger-step-${index + 1}",
                    index + 1,
                    stage,
                    "target-ledger-${index + 1}",
                    "target-r1",
                    ((index % 6) + 1).toString().repeat(64),
                    "target-ledger-delete-step-${index + 1}",
                )
            }
        }
        val plan = GovernanceDeletionPlan.of(
            "target-ledger-plan-1",
            planContext,
            fence,
            assessment,
            steps,
            false,
            1_001L,
            50_000L,
        )
        var run = GovernanceDeletionRun.ready(
            plan,
            digest('8'),
            "target-ledger-delete-document-1",
            1_001L,
        )
        repeat(4) { index ->
            val requestedAt = 1_200L + index * 100L
            val request = executionRequest(run, index, requestedAt)
            val prepared = GovernanceDeletionRun.prepare(
                run,
                GovernanceDeletionDispatch.prepared(
                    request,
                    "prior-stage-provider",
                    "provider-r1",
                    request.context.idempotencyKey,
                    requestedAt + 1L,
                ),
                requestedAt + 1L,
            )
            val started = GovernanceDeletionRun.markProviderCallStarted(prepared, requestedAt + 2L)
            val successfulStatus = if (
                request.step.stage == ai.icen.fw.governance.api.GovernanceDeletionStage.PURGE_INDEX_PROJECTIONS
            ) {
                GovernanceDeletionStepStatus.VERIFIED_ABSENT
            } else {
                GovernanceDeletionStepStatus.COMPLETED
            }
            val receipt = GovernanceDeletionStepReceipt.success(
                request,
                "prior-stage-provider",
                "provider-r1",
                successfulStatus,
                "prior-stage-receipt-${index + 1}",
                digest(('a'.code + index).toChar()),
                requestedAt + 3L,
            )
            run = GovernanceDeletionRun.recordExecution(started, receipt, requestedAt + 4L)
        }
        return GovernanceTargetLedgerScenario(manifest, item, secondItem, executionRequest(run, 4, 2_000L))
    }

    fun executionRequest(
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

    /**
     * Builds the exact read-only reconciliation request for the scenario's target step. Its
     * previous receipt is the outcome-unknown observation of that step, bound to the exact
     * execution request that prepared the target item operation.
     */
    fun reconciliationRequest(
        scenario: GovernanceTargetLedgerScenario,
        unknownFailure: GovernanceFailure,
        reconciliationRequestedAt: Long,
        observedAtEpochMilli: Long,
    ): GovernanceDeletionReconciliationRequest {
        val previousReceipt = GovernanceDeletionStepReceipt.failure(
            scenario.request,
            scenario.item.providerId,
            scenario.item.providerRevision,
            GovernanceDeletionStepStatus.OUTCOME_UNKNOWN,
            "target-ledger-object-operation-1",
            digest('9'),
            unknownFailure,
            observedAtEpochMilli,
        )
        val reconcileContext = context(
            GovernancePurpose.RECONCILE_SECURE_DELETION,
            "reconcile-target-ledger-1",
            reconciliationRequestedAt,
            reconciliationRequestedAt + 100L,
        )
        return GovernanceDeletionReconciliationRequest.of(
            reconcileContext,
            scenario.request.plan,
            scenario.request.step,
            previousReceipt,
            scenario.request.plan.assessment,
            scenario.request.expectedPlanVersion,
        )
    }

    fun plan(): GovernanceDeletionPlan {
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
