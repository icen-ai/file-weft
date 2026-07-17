package ai.icen.fw.governance.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GovernanceApiContractTest {
    @Test
    fun `binds fresh authorization idempotency resource digest and CAS`() {
        val resource = resource()
        val context = context(GovernancePurpose.PLAN_SECURE_DELETION, resource, "plan-1", 2_000L)
        val same = context(GovernancePurpose.PLAN_SECURE_DELETION, resource, "plan-1", 2_000L)

        assertEquals(context.contextDigest, same.contextDigest)
        assertNotEquals(
            context.contextDigest,
            context(GovernancePurpose.PLAN_SECURE_DELETION, resource, "plan-2", 2_000L).contextDigest,
        )
        assertNotEquals(
            GovernanceVersionFence.of(resource, 7L).fenceDigest,
            GovernanceVersionFence.of(resource, 8L).fenceDigest,
        )
        assertEquals(resource, context.authorization.resource)
        assertTrue(context.toString().contains("<redacted>"))
        assertFalse(context.toString().contains("tenant-a"))

        val wrongPurposeAuthorization = authorization(
            GovernancePurpose.PLAN_SECURE_DELETION, resource, "wrong-purpose", 2_000L,
        )
        assertFailsWith<IllegalArgumentException> {
            GovernanceCallContext.of(
                "request-wrong-purpose",
                TENANT,
                PRINCIPAL,
                GovernancePurpose.EXECUTE_SECURE_DELETION,
                wrongPurposeAuthorization,
                "idem-wrong-purpose",
                2_000L,
                2_100L,
            )
        }
    }

    @Test
    fun `known active hold has priority even when resolution is incomplete`() {
        val resource = resource()
        val clock = clock(1_000L, 50_000L)
        val active = activeHold(clock.observedAtEpochMilli)
        val held = GovernanceLegalHoldResolution.held(
            resource,
            TENANT,
            "hold-registry",
            "registry-r4",
            clock,
            listOf(active),
            false,
            20_000L,
        )
        val request = evaluationRequest(resource, clock, held)
        val assessment = GovernanceRetentionAssessment.evaluate(request)

        assertEquals(GovernanceLegalHoldResolutionStatus.HELD, held.status)
        assertEquals(listOf("hold-1"), held.activeHoldIds)
        assertEquals(900, held.highestActivePriority)
        assertEquals(GovernanceRetentionOutcome.BLOCKED_BY_LEGAL_HOLD, assessment.outcome)
        assertEquals(GovernanceRetentionReason.ACTIVE_LEGAL_HOLD, assessment.reason)
        assertFalse(assessment.isDeletionEligible())

        assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionPlan.of(
                "blocked-plan",
                context(GovernancePurpose.PLAN_SECURE_DELETION, resource, "blocked-plan", 2_000L),
                GovernanceVersionFence.of(resource, 7L),
                assessment,
                steps(),
                false,
                2_050L,
                20_000L,
            )
        }
    }

    @Test
    fun `released holds require immutable release evidence and clear resolution is complete`() {
        val resource = resource()
        val clock = clock(1_000L, 50_000L)
        val release = GovernanceLegalHoldReleaseEvidence.of(
            "release-1",
            "hold-1",
            PRINCIPAL,
            "authorization-r8",
            SHA_B,
            "matter-closed",
            800L,
        )
        val released = GovernanceLegalHoldSnapshot.released(
            "hold-1", TENANT, holdScope(), 900, "hold-r3", SHA_C, 100L, release,
        )
        val clear = GovernanceLegalHoldResolution.clear(
            resource,
            TENANT,
            "hold-registry",
            "registry-r4",
            clock,
            listOf(released),
            20_000L,
        )

        assertEquals(GovernanceLegalHoldResolutionStatus.CLEAR, clear.status)
        assertTrue(clear.complete)
        assertTrue(clear.activeHoldIds.isEmpty())
        assertNull(clear.highestActivePriority)
        assertTrue(released.toString().contains("<redacted>"))

        assertFailsWith<IllegalArgumentException> {
            GovernanceLegalHoldSnapshot.released(
                "different-hold", TENANT, holdScope(), 900, "hold-r3", SHA_C, 100L, release,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceLegalHoldResolution.clear(
                resource,
                TENANT,
                "hold-registry",
                "registry-r4",
                clock,
                listOf(activeHold(clock.observedAtEpochMilli)),
                20_000L,
            )
        }
    }

    @Test
    fun `derives deletion eligibility only from clear current evidence`() {
        val eligible = eligibleAssessment(1_000L)
        assertTrue(eligible.isDeletionEligible())
        assertEquals(GovernanceRetentionReason.RETENTION_EXPIRED, eligible.reason)

        val futureRetention = assessment(
            observedAt = 1_000L,
            effectiveAt = 5_000L,
            retainUntil = 50_000L,
        )
        assertEquals(GovernanceRetentionOutcome.RETAIN, futureRetention.outcome)

        val clock = clock(1_000L, 50_000L)
        val unknownHolds = GovernanceLegalHoldResolution.unknown(
            resource(),
            TENANT,
            "hold-registry",
            "registry-r4",
            clock,
            GovernanceFailure.of(
                GovernanceFailureClass.TEMPORARY_UNAVAILABLE,
                "hold-registry-unavailable",
                true,
                false,
            ),
            20_000L,
        )
        val incomplete = GovernanceRetentionAssessment.evaluate(
            evaluationRequest(resource(), clock, unknownHolds),
        )
        assertEquals(GovernanceRetentionOutcome.INCOMPLETE, incomplete.outcome)
        assertEquals(GovernanceRetentionReason.INCOMPLETE_LEGAL_HOLD, incomplete.reason)
    }

    @Test
    fun `plan covers metadata Outbox index and object in fixed safety order and dry run is not executable`() {
        val assessment = eligibleAssessment(1_000L)
        val plan = plan(assessment, dryRun = false)

        assertEquals(GovernanceDeletionPlan.REQUIRED_STAGE_ORDER, plan.steps.map { it.stage })
        assertEquals(GovernanceDeletionSurface.values().toSet(), plan.steps.map { it.stage.surface }.toSet())
        assertFalse(plan.dryRun)

        val dryRun = plan(assessment, dryRun = true, planId = "dry-run-plan")
        assertNotEquals(plan.planDigest, dryRun.planDigest)
        assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionExecutionRequest.of(
                context(
                    GovernancePurpose.EXECUTE_SECURE_DELETION,
                    dryRun.resource,
                    "execute-dry-run",
                    2_500L,
                ),
                dryRun,
                dryRun.steps.first(),
                eligibleAssessment(2_000L),
                1,
                null,
                emptyList(),
                0L,
            )
        }

        val wrongOrder = steps().toMutableList().also {
            val first = it[0]
            it[0] = it[1]
            it[1] = first
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionPlan.of(
                "wrong-order-plan",
                context(GovernancePurpose.PLAN_SECURE_DELETION, resource(), "wrong-order", 2_000L),
                GovernanceVersionFence.of(resource(), 7L),
                assessment,
                wrongOrder,
                false,
                2_050L,
                100_000L,
            )
        }
    }

    @Test
    fun `requires contiguous successful receipts and verified absence for external surfaces`() {
        val assessment = eligibleAssessment(1_000L)
        val plan = plan(assessment)
        val current = eligibleAssessment(2_000L)
        val firstRequest = executionRequest(plan, current, emptyList(), 0, 3_000L)
        val first = GovernanceDeletionStepReceipt.success(
            firstRequest,
            "metadata-runtime",
            "runtime-r1",
            GovernanceDeletionStepStatus.COMPLETED,
            "receipt-step-1",
            SHA_D,
            3_050L,
        )
        assertTrue(first.isSuccessful())

        val secondRequest = executionRequest(plan, current, listOf(first), 1, 3_200L)
        assertEquals(GovernanceDeletionStage.APPEND_DECISION_AUDIT, secondRequest.step.stage)

        assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionStepReceipt.success(
                executionRequestForIndex(plan, current),
                "index-provider",
                "index-r1",
                GovernanceDeletionStepStatus.COMPLETED,
                "index-receipt",
                SHA_E,
                4_050L,
            )
        }
        val indexRequest = executionRequestForIndex(plan, current)
        val verified = GovernanceDeletionStepReceipt.success(
            indexRequest,
            "index-provider",
            "index-r1",
            GovernanceDeletionStepStatus.VERIFIED_ABSENT,
            "index-receipt",
            SHA_E,
            4_050L,
        )
        assertEquals(GovernanceDeletionStepStatus.VERIFIED_ABSENT, verified.status)
    }

    @Test
    fun `unknown deletion outcome cannot retry and only reconciliation can resolve it`() {
        val plan = plan(eligibleAssessment(1_000L))
        val current = eligibleAssessment(2_000L)
        val previous = successfulReceipts(plan, current, 3)
        val indexRequest = executionRequest(plan, current, previous, 3, 4_000L)
        val unknownFailure = GovernanceFailure.of(
            GovernanceFailureClass.OUTCOME_UNKNOWN,
            "provider-timeout-after-dispatch",
            false,
            true,
        )
        val unknown = GovernanceDeletionStepReceipt.failure(
            indexRequest,
            "index-provider",
            "index-r1",
            GovernanceDeletionStepStatus.OUTCOME_UNKNOWN,
            "index-operation-9",
            SHA_F,
            unknownFailure,
            4_050L,
        )

        assertFalse(unknown.isSuccessful())
        assertTrue(requireNotNull(unknown.failure).reconciliationRequired)
        assertFailsWith<IllegalArgumentException> {
            GovernanceFailure.of(
                GovernanceFailureClass.OUTCOME_UNKNOWN, "unsafe-retry", true, false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionExecutionRequest.of(
                context(
                    GovernancePurpose.EXECUTE_SECURE_DELETION,
                    plan.resource,
                    "blind-retry-index",
                    4_200L,
                ),
                plan,
                plan.steps[3],
                current,
                2,
                unknown,
                previous,
                4L,
            )
        }

        val heldCurrent = heldAssessment(4_500L)
        val reconciliation = GovernanceDeletionReconciliationRequest.of(
            context(
                GovernancePurpose.RECONCILE_SECURE_DELETION,
                plan.resource,
                "reconcile-index",
                4_700L,
            ),
            plan,
            plan.steps[3],
            unknown,
            heldCurrent,
            4L,
        )
        val reconciled = GovernanceDeletionStepReceipt.reconciled(
            reconciliation,
            GovernanceDeletionStepStatus.VERIFIED_ABSENT,
            "index-reconciliation-9",
            SHA_G,
            null,
            4_750L,
        )
        assertTrue(reconciled.isSuccessful())

        assertFailsWith<IllegalArgumentException> {
            GovernanceDeletionExecutionRequest.of(
                context(
                    GovernancePurpose.EXECUTE_SECURE_DELETION,
                    plan.resource,
                    "execute-object-held",
                    4_900L,
                ),
                plan,
                plan.steps[4],
                heldCurrent,
                1,
                null,
                previous + reconciled,
                5L,
            )
        }
    }

    @Test
    fun `capability absence is unsupported and Doctor is value free`() {
        val resource = resource()
        val capabilityRequest = GovernanceCapabilityRequest.of(
            context(GovernancePurpose.DISCOVER_CAPABILITIES, resource, "capabilities", 2_000L),
            listOf(GovernanceCapability.RECONCILIATION),
        )
        val snapshot = GovernanceCapabilitySnapshot.of(
            "governance-runtime",
            "runtime-r1",
            listOf(
                GovernanceCapability.RETENTION_EVALUATION,
                GovernanceCapability.LEGAL_HOLD_RESOLUTION,
                GovernanceCapability.RECONCILIATION,
            ),
            256,
            16,
            2_050L,
            10_000L,
        )
        val result = GovernanceCapabilityResult.available(capabilityRequest, snapshot, 2_060L)
        assertTrue(requireNotNull(result.snapshot).supports(GovernanceCapability.RECONCILIATION))
        assertFalse(requireNotNull(result.snapshot).supports(GovernanceCapability.OBJECT_PURGE))

        val doctorRequest = GovernanceDoctorRequest.of(
            context(GovernancePurpose.INSPECT_DOCTOR, resource, "doctor", 2_000L),
            GovernanceDoctorMode.CONSISTENCY,
        )
        val doctor = GovernanceDoctorResult.of(
            doctorRequest,
            GovernanceDoctorStatus.READY,
            listOf(GovernanceDoctorFinding.of("deletion-ledger-consistent", GovernanceDoctorSeverity.INFO, 1L)),
            2_050L,
            10_000L,
        )
        assertEquals(GovernanceDoctorStatus.READY, doctor.status)
        assertTrue(doctor.toString().contains("<redacted>"))
    }

    private fun plan(
        assessment: GovernanceRetentionAssessment,
        dryRun: Boolean = false,
        planId: String = "deletion-plan-1",
    ): GovernanceDeletionPlan = GovernanceDeletionPlan.of(
        planId,
        context(GovernancePurpose.PLAN_SECURE_DELETION, assessment.resource, "plan-$planId", 2_000L),
        GovernanceVersionFence.of(assessment.resource, 7L),
        assessment,
        steps(),
        dryRun,
        2_050L,
        100_000L,
    )

    private fun steps(): List<GovernanceDeletionStep> = GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.mapIndexed {
            index,
            stage,
        ->
        val sequence = index + 1
        GovernanceDeletionStep.of(
            "step-$sequence",
            sequence,
            stage,
            "target-$sequence",
            "target-r1",
            shaFor(sequence),
            "step-idempotency-$sequence",
        )
    }

    private fun executionRequest(
        plan: GovernanceDeletionPlan,
        assessment: GovernanceRetentionAssessment,
        previous: List<GovernanceDeletionStepReceipt>,
        stepIndex: Int,
        now: Long,
    ): GovernanceDeletionExecutionRequest = GovernanceDeletionExecutionRequest.of(
        context(
            GovernancePurpose.EXECUTE_SECURE_DELETION,
            plan.resource,
            "execute-${stepIndex + 1}-$now",
            now,
        ),
        plan,
        plan.steps[stepIndex],
        assessment,
        1,
        null,
        previous,
        stepIndex.toLong(),
    )

    private fun successfulReceipts(
        plan: GovernanceDeletionPlan,
        assessment: GovernanceRetentionAssessment,
        count: Int,
    ): List<GovernanceDeletionStepReceipt> {
        val receipts = mutableListOf<GovernanceDeletionStepReceipt>()
        repeat(count) { index ->
            val now = 3_000L + index * 200L
            val request = executionRequest(plan, assessment, receipts, index, now)
            val status = when (request.step.stage.surface) {
                GovernanceDeletionSurface.INDEX,
                GovernanceDeletionSurface.OBJECT,
                -> GovernanceDeletionStepStatus.VERIFIED_ABSENT
                GovernanceDeletionSurface.METADATA,
                GovernanceDeletionSurface.OUTBOX,
                -> GovernanceDeletionStepStatus.COMPLETED
            }
            receipts += GovernanceDeletionStepReceipt.success(
                request,
                "provider-${index + 1}",
                "provider-r1",
                status,
                "receipt-${index + 1}",
                shaFor(index + 1),
                now + 50L,
            )
        }
        return receipts
    }

    private fun executionRequestForIndex(
        plan: GovernanceDeletionPlan,
        assessment: GovernanceRetentionAssessment,
    ): GovernanceDeletionExecutionRequest = executionRequest(
        plan, assessment, successfulReceipts(plan, assessment, 3), 3, 4_000L,
    )

    private fun eligibleAssessment(observedAt: Long): GovernanceRetentionAssessment = assessment(
        observedAt = observedAt,
        effectiveAt = 50_000L,
        retainUntil = 40_000L,
    )

    private fun heldAssessment(observedAt: Long): GovernanceRetentionAssessment {
        val resource = resource()
        val clock = clock(observedAt, 50_000L)
        return GovernanceRetentionAssessment.evaluate(
            evaluationRequest(
                resource,
                clock,
                GovernanceLegalHoldResolution.held(
                    resource,
                    TENANT,
                    "hold-registry",
                    "registry-r4",
                    clock,
                    listOf(activeHold(observedAt)),
                    true,
                    observedAt + 30_000L,
                ),
            ),
        )
    }

    private fun assessment(
        observedAt: Long,
        effectiveAt: Long,
        retainUntil: Long,
    ): GovernanceRetentionAssessment {
        val resource = resource()
        val clock = clock(observedAt, effectiveAt)
        return GovernanceRetentionAssessment.evaluate(
            evaluationRequest(resource, clock, clearResolution(resource, clock), retainUntil),
        )
    }

    private fun evaluationRequest(
        resource: GovernanceResourceRef,
        clock: GovernanceEffectiveClock,
        holds: GovernanceLegalHoldResolution,
        retainUntil: Long = 40_000L,
    ): GovernanceRetentionEvaluationRequest = GovernanceRetentionEvaluationRequest.of(
        context(
            GovernancePurpose.EVALUATE_RETENTION,
            resource,
            "evaluate-${clock.observedAtEpochMilli}",
            clock.observedAtEpochMilli - 50L,
        ),
        GovernanceVersionFence.of(resource, 7L),
        policy(resource, clock.observedAtEpochMilli, retainUntil),
        holds,
        clock,
    )

    private fun policy(
        resource: GovernanceResourceRef,
        observedAt: Long,
        retainUntil: Long,
    ): GovernanceRetentionPolicySnapshot = GovernanceRetentionPolicySnapshot.of(
        TENANT,
        resource,
        "records-policy",
        "policy-v4",
        SHA_A,
        GovernanceRetentionPolicyMode.RETAIN_UNTIL,
        0L,
        observedAt - 100L,
        observedAt + 40_000L,
        retainUntil,
    )

    private fun clearResolution(
        resource: GovernanceResourceRef,
        clock: GovernanceEffectiveClock,
    ): GovernanceLegalHoldResolution = GovernanceLegalHoldResolution.clear(
        resource,
        TENANT,
        "hold-registry",
        "registry-r4",
        clock,
        emptyList(),
        clock.observedAtEpochMilli + 30_000L,
    )

    private fun activeHold(observedAt: Long): GovernanceLegalHoldSnapshot = GovernanceLegalHoldSnapshot.active(
        "hold-1", TENANT, holdScope(), 900, "hold-r3", SHA_C, observedAt - 100L,
    )

    private fun holdScope(): GovernanceLegalHoldScope = GovernanceLegalHoldScope.of(
        TENANT, GovernanceLegalHoldScopeType.RESOURCE, "document-9", "scope-r2", SHA_D,
    )

    private fun clock(observedAt: Long, effectiveAt: Long): GovernanceEffectiveClock =
        GovernanceEffectiveClock.of(
            "clock-$observedAt", "governance-clock", "clock-r1",
            observedAt, effectiveAt, observedAt + 50_000L,
        )

    private fun context(
        purpose: GovernancePurpose,
        resource: GovernanceResourceRef,
        id: String,
        now: Long,
    ): GovernanceCallContext = GovernanceCallContext.of(
        "request-$id",
        TENANT,
        PRINCIPAL,
        purpose,
        authorization(purpose, resource, id, now),
        "idempotency-$id",
        now,
        now + 100L,
    )

    private fun authorization(
        purpose: GovernancePurpose,
        resource: GovernanceResourceRef,
        id: String,
        now: Long,
    ): GovernanceAuthorizationSnapshot = GovernanceAuthorizationSnapshot.of(
        "authorization-$id",
        TENANT,
        PRINCIPAL,
        purpose,
        resource,
        "host-authorization",
        "authority-r4",
        "authorization-r8",
        SHA_B,
        now - 50L,
        now + 5_000L,
    )

    private fun resource(): GovernanceResourceRef = GovernanceResourceRef.of(
        "document", "document-9", "revision-12", SHA_A,
    )

    private fun shaFor(value: Int): String = ((value % 6) + 1).toString().repeat(64)

    companion object {
        private const val TENANT = "tenant-a"
        private val PRINCIPAL = GovernancePrincipalRef.of("user", "user-7")
        private val SHA_A = "a".repeat(64)
        private val SHA_B = "b".repeat(64)
        private val SHA_C = "c".repeat(64)
        private val SHA_D = "d".repeat(64)
        private val SHA_E = "e".repeat(64)
        private val SHA_F = "f".repeat(64)
        private val SHA_G = "7".repeat(64)
    }
}
