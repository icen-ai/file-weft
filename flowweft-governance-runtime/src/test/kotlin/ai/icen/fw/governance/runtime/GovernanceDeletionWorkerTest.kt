package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernancePurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GovernanceDeletionWorkerTest {
    @Test
    fun `worker persists checkpoints then calls provider outside transaction and advances one stage`() {
        val fixture = GovernanceRuntimeTestFixture()
        val initial = fixture.createRun()
        fixture.now = 3_000L
        val command = GovernanceWorkerCommand.of(
            fixture.invocation(GovernancePurpose.EXECUTE_SECURE_DELETION, "execute-step-1"),
            initial.planId,
        )

        val result = fixture.worker.process(command).toCompletableFuture().get()
        val run = assertNotNull(result.run)

        assertEquals(GovernanceWorkerStatus.ADVANCED, result.status)
        assertEquals(1, fixture.executorCalls)
        assertFalse(fixture.repository.transactionActive)
        assertEquals(1, run.successfulReceipts.size)
        assertEquals(GovernanceDeletionStage.PERSIST_TOMBSTONE, run.plan.steps.first().stage)
        assertEquals(GovernanceDeletionStage.APPEND_DECISION_AUDIT, run.nextStep()?.stage)
        assertEquals(GovernanceDeletionRunStatus.READY, run.status)
        assertTrue(fixture.repository.outbox.size >= 4)
    }

    @Test
    fun `provider acknowledgement loss becomes exact reconciliation and never blind resubmits`() {
        val fixture = GovernanceRuntimeTestFixture()
        val initial = fixture.createRun()
        fixture.now = 3_000L
        fixture.failNextExecutor = true
        val execute = GovernanceWorkerCommand.of(
            fixture.invocation(GovernancePurpose.EXECUTE_SECURE_DELETION, "execute-unknown"),
            initial.planId,
        )

        val unknownResult = fixture.worker.process(execute).toCompletableFuture().get()
        val unknownRun = assertNotNull(unknownResult.run)
        val unknownReceipt = assertNotNull(unknownRun.pendingReceipt)

        assertEquals(GovernanceWorkerStatus.RECONCILIATION_REQUIRED, unknownResult.status)
        assertEquals(1, fixture.executorCalls)
        assertTrue(unknownReceipt.failure?.reconciliationRequired == true)
        assertNotNull(unknownReceipt.receiptReference)
        assertEquals(ai.icen.fw.governance.api.GovernanceFailureClass.OUTCOME_UNKNOWN,
            unknownReceipt.failure?.classification)

        fixture.now = 3_500L
        val wrongPurpose = GovernanceWorkerCommand.of(
            fixture.invocation(GovernancePurpose.EXECUTE_SECURE_DELETION, "unsafe-repeat"),
            initial.planId,
        )
        val refused = fixture.worker.process(wrongPurpose).toCompletableFuture().get()
        assertEquals(GovernanceWorkerStatus.FAILED, refused.status)
        assertEquals(1, fixture.executorCalls)

        fixture.now = 4_000L
        val reconcile = GovernanceWorkerCommand.of(
            fixture.invocation(GovernancePurpose.RECONCILE_SECURE_DELETION, "reconcile-original-operation"),
            initial.planId,
        )
        val reconciled = fixture.worker.process(reconcile).toCompletableFuture().get()
        val reconciledRun = assertNotNull(reconciled.run)

        assertEquals(GovernanceWorkerStatus.ADVANCED, reconciled.status)
        assertEquals(1, fixture.executorCalls)
        assertEquals(1, fixture.reconcilerCalls)
        assertEquals(1, reconciledRun.successfulReceipts.size)
        assertEquals(GovernanceDeletionRunStatus.READY, reconciledRun.status)
        assertNotNull(reconciledRun.successfulReceipts.single().reconciliationRequestDigest)
    }

    @Test
    fun `new active hold blocks the next destructive step before provider dispatch`() {
        val fixture = GovernanceRuntimeTestFixture()
        val initial = fixture.createRun()
        fixture.activeHold = true
        fixture.now = 3_000L
        val command = GovernanceWorkerCommand.of(
            fixture.invocation(GovernancePurpose.EXECUTE_SECURE_DELETION, "execute-held"),
            initial.planId,
        )

        val result = fixture.worker.process(command).toCompletableFuture().get()

        assertEquals(GovernanceWorkerStatus.BLOCKED, result.status)
        assertEquals(GovernanceDeletionRunStatus.BLOCKED, result.run?.status)
        assertEquals(0, fixture.executorCalls)
        assertTrue(fixture.metrics.any { it.code == GovernanceMetricCode.LEGAL_HOLD_BLOCKED })
    }

    @Test
    fun `recovered prepared dispatch is rebuilt with the current authorization revision`() {
        val fixture = GovernanceRuntimeTestFixture()
        val initial = fixture.createRun()
        fixture.now = 3_000L
        fixture.repository.acknowledgeUnknownAfterNextCommit = true
        fixture.repository.failNextLoadAfterUnknown = true

        val first = fixture.worker.process(
            GovernanceWorkerCommand.of(
                fixture.invocation(GovernancePurpose.EXECUTE_SECURE_DELETION, "prepare-r1"),
                initial.planId,
            ),
        ).toCompletableFuture().get()
        assertEquals(GovernanceWorkerStatus.STORE_OUTCOME_UNKNOWN, first.status)
        assertEquals(0, fixture.executorCalls)
        val prepared = requireNotNull(fixture.repository.load(fixture.tenant, initial.planId))
        val oldContextDigest = requireNotNull(prepared.dispatch).request.context.contextDigest
        assertEquals("authorization-r1", prepared.dispatch?.request?.context?.authorization?.authorizationRevision)

        fixture.authorizationRevision = "authorization-r2"
        fixture.now = 3_050L
        val recovered = fixture.worker.process(
            GovernanceWorkerCommand.of(
                fixture.invocation(GovernancePurpose.EXECUTE_SECURE_DELETION, "recover-r2"),
                initial.planId,
            ),
        ).toCompletableFuture().get()

        assertEquals(GovernanceWorkerStatus.ADVANCED, recovered.status)
        assertEquals(1, fixture.executorCalls)
        assertEquals(listOf("authorization-r2"), fixture.executorAuthorizationRevisions)
        assertTrue(fixture.executorContextDigests.single() != oldContextDigest)
    }

    @Test
    fun `revoked execution authorization prevents a recovered prepared dispatch`() {
        val fixture = GovernanceRuntimeTestFixture()
        val initial = fixture.createRun()
        fixture.now = 3_000L
        fixture.repository.acknowledgeUnknownAfterNextCommit = true
        fixture.repository.failNextLoadAfterUnknown = true
        val command = GovernanceWorkerCommand.of(
            fixture.invocation(GovernancePurpose.EXECUTE_SECURE_DELETION, "prepare-before-revoke"),
            initial.planId,
        )

        val first = fixture.worker.process(command).toCompletableFuture().get()
        assertEquals(GovernanceWorkerStatus.STORE_OUTCOME_UNKNOWN, first.status)
        assertEquals(GovernanceDeletionRunStatus.DISPATCH_PREPARED,
            fixture.repository.load(fixture.tenant, initial.planId)?.status)

        fixture.deniedAuthorizationPurposes += GovernancePurpose.EXECUTE_SECURE_DELETION
        fixture.now = 3_050L
        val revoked = fixture.worker.process(
            GovernanceWorkerCommand.of(
                fixture.invocation(GovernancePurpose.EXECUTE_SECURE_DELETION, "recover-after-revoke"),
                initial.planId,
            ),
        ).toCompletableFuture().get()

        assertEquals(GovernanceWorkerStatus.FAILED, revoked.status)
        assertEquals("execution-authorization-denied", revoked.failureCode)
        assertEquals(0, fixture.executorCalls)
        assertEquals(GovernanceDeletionRunStatus.READY,
            fixture.repository.load(fixture.tenant, initial.planId)?.status)
    }
}
