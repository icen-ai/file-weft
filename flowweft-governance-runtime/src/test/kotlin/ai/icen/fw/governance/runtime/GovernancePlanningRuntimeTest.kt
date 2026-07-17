package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceDeletionPlan
import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.api.GovernancePurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GovernancePlanningRuntimeTest {
    @Test
    fun `active hold wins before policy target repository or provider work`() {
        val fixture = GovernanceRuntimeTestFixture().also { it.activeHold = true }

        val result = fixture.planning.plan(fixture.planCommand()).toCompletableFuture().get()

        assertEquals(GovernancePlanningStatus.BLOCKED, result.status)
        assertEquals(GovernanceFailureClass.LEGAL_HOLD_ACTIVE, result.failure?.classification)
        assertEquals(0, fixture.policyCalls)
        assertEquals(0, fixture.targetCalls)
        assertEquals(0, fixture.repository.size())
        assertEquals(0, fixture.executorCalls)
        assertEquals(
            listOf(GovernancePurpose.PLAN_SECURE_DELETION, GovernancePurpose.RESOLVE_LEGAL_HOLD),
            fixture.authorizationPurposes,
        )
    }

    @Test
    fun `eligible planning creates exact seven-stage run with atomic outbox and idempotent replay`() {
        val fixture = GovernanceRuntimeTestFixture()
        val command = fixture.planCommand()

        val created = fixture.planning.plan(command).toCompletableFuture().get()
        val run = assertNotNull(created.run)

        assertEquals(GovernancePlanningStatus.CREATED, created.status)
        assertEquals(GovernanceDeletionPlan.REQUIRED_STAGE_ORDER, run.plan.steps.map { it.stage })
        assertEquals(7, run.plan.steps.map { it.idempotencyKey }.distinct().size)
        assertEquals(GovernanceDeletionRunStatus.READY, run.status)
        assertEquals(1L, run.version)
        assertEquals(1, fixture.repository.outbox.size)
        assertEquals(run.stateDigest, fixture.repository.outbox.single().stateDigest)
        assertEquals(1, fixture.signals.size)
        assertTrue(fixture.authorizationPurposes.containsAll(
            listOf(
                GovernancePurpose.PLAN_SECURE_DELETION,
                GovernancePurpose.RESOLVE_LEGAL_HOLD,
                GovernancePurpose.EVALUATE_RETENTION,
            ),
        ))

        val policyCalls = fixture.policyCalls
        val targetCalls = fixture.targetCalls
        fixture.now += 200L
        val replayed = fixture.planning.plan(fixture.planCommand()).toCompletableFuture().get()

        assertEquals(GovernancePlanningStatus.REPLAYED, replayed.status)
        assertEquals(run.stateDigest, replayed.run?.stateDigest)
        assertEquals(policyCalls, fixture.policyCalls)
        assertEquals(targetCalls, fixture.targetCalls)
        assertEquals(1, fixture.repository.size())
        assertTrue(fixture.metrics.any { it.code == GovernanceMetricCode.IDEMPOTENT_REPLAY })
    }

    @Test
    fun `unknown create acknowledgement is closed by exact idempotent reread without duplicate plan`() {
        val fixture = GovernanceRuntimeTestFixture()
        fixture.repository.acknowledgeUnknownAfterNextCommit = true

        val result = fixture.planning.plan(fixture.planCommand()).toCompletableFuture().get()

        assertEquals(GovernancePlanningStatus.CREATED, result.status)
        assertEquals(1, fixture.repository.size())
        assertEquals(1, fixture.repository.outbox.size)
        assertFalse(result.plan?.dryRun ?: true)
    }
}
