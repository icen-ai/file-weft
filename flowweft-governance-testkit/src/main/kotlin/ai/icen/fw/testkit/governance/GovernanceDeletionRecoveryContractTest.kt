package ai.icen.fw.testkit.governance

import ai.icen.fw.governance.api.GovernanceDeletionStepStatus
import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.runtime.GovernanceDeletionRun
import ai.icen.fw.governance.runtime.GovernanceDeletionRunStatus
import ai.icen.fw.governance.runtime.GovernancePlanningStatus
import ai.icen.fw.governance.runtime.GovernanceWorkerStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Crash-safe provider/runtime contract for secure deletion and exact reconciliation. */
abstract class GovernanceDeletionRecoveryContractTest {
    protected abstract fun newHarness(): GovernanceRuntimeContractHarness

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `complete secure deletion mutates each ordered stage exactly once`() {
        val fixture = newHarness()
        var run = createRun(fixture, "complete-plan")

        run.plan.steps.forEachIndexed { index, _ ->
            val result = GovernanceContractAssertions.awaitStage(
                fixture.execute(run, "complete-step-${index + 1}"),
                asynchronousTimeout(),
                "Governance deletion stage ${index + 1}",
            )
            run = requireNotNull(result.run)
        }

        assertEquals(GovernanceDeletionRunStatus.COMPLETED, run.status)
        assertEquals(run.plan.steps.size.toLong(), fixture.providerProbe.executionCount())
        assertEquals(run.plan.steps.size.toLong(), fixture.providerProbe.mutationCount())
        assertEquals(run.plan.steps.size, run.successfulReceipts.size)
        assertTrue(run.successfulReceipts.all { it.isSuccessful() })
        GovernanceDurableStateAssertions.assertRunRoundTrip(run)
        GovernanceDurableStateAssertions.assertRunRejectsWrongExpectedDigest(run)
    }

    @Test
    fun `crash after mutation reconciles exact original and never duplicates external deletion`() {
        val fixture = newHarness()
        val initial = createRun(fixture, "unknown-outcome")
        fixture.providerProbe.crashAfterNextMutation()

        val unknownResult = GovernanceContractAssertions.awaitStage(
            fixture.execute(initial, "execute-unknown"),
            asynchronousTimeout(),
            "Governance deletion acknowledgement loss",
        )
        val unknownRun = requireNotNull(unknownResult.run)
        val unknownReceipt = requireNotNull(unknownRun.pendingReceipt)
        val operationReference = requireNotNull(unknownReceipt.receiptReference)

        assertEquals(GovernanceWorkerStatus.RECONCILIATION_REQUIRED, unknownResult.status)
        assertEquals(GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED, unknownRun.status)
        assertEquals(GovernanceDeletionStepStatus.OUTCOME_UNKNOWN, unknownReceipt.status)
        assertEquals(GovernanceFailureClass.OUTCOME_UNKNOWN, unknownReceipt.failure?.classification)
        assertEquals(1L, fixture.providerProbe.executionCount())
        assertEquals(1L, fixture.providerProbe.mutationCount())
        assertEquals(1L, fixture.providerProbe.mutationCount(operationReference))
        GovernanceDurableStateAssertions.assertReceiptRoundTrip(unknownReceipt)
        GovernanceDurableStateAssertions.assertReceiptRejectsWrongExpectedDigest(unknownReceipt)
        GovernanceDurableStateAssertions.assertRunRoundTrip(unknownRun)

        val refusedRepeat = GovernanceContractAssertions.awaitStage(
            fixture.execute(unknownRun, "unsafe-blind-repeat"),
            asynchronousTimeout(),
            "Governance blind repeat refusal",
        )
        assertEquals(GovernanceWorkerStatus.FAILED, refusedRepeat.status)
        assertEquals(1L, fixture.providerProbe.executionCount())
        assertEquals(1L, fixture.providerProbe.mutationCount())

        val reconciledResult = GovernanceContractAssertions.awaitStage(
            fixture.reconcile(unknownRun, "reconcile-original"),
            asynchronousTimeout(),
            "Governance exact reconciliation",
        )
        val reconciledRun = requireNotNull(reconciledResult.run)

        assertEquals(GovernanceWorkerStatus.ADVANCED, reconciledResult.status)
        assertEquals(GovernanceDeletionRunStatus.READY, reconciledRun.status)
        assertEquals(1L, fixture.providerProbe.executionCount())
        assertEquals(1L, fixture.providerProbe.mutationCount())
        assertEquals(1L, fixture.providerProbe.reconciliationCount())
        assertEquals(operationReference, fixture.providerProbe.lastOriginalOperationReference())
        assertEquals(operationReference, fixture.providerProbe.lastReconciledOperationReference())
        assertEquals(1, reconciledRun.successfulReceipts.size)
        assertNotNull(reconciledRun.successfulReceipts.single().reconciliationRequestDigest)
        GovernanceDurableStateAssertions.assertRunRoundTrip(reconciledRun)
    }

    @Test
    fun `new active hold blocks the next step without invoking provider`() {
        val fixture = newHarness()
        val initial = createRun(fixture, "hold-before-execute")
        fixture.legalHolds.setState(GovernanceLegalHoldFixtureState.ACTIVE)

        val result = GovernanceContractAssertions.awaitStage(
            fixture.execute(initial, "execute-held"),
            asynchronousTimeout(),
            "Governance held deletion",
        )

        assertEquals(GovernanceWorkerStatus.BLOCKED, result.status)
        assertEquals(GovernanceDeletionRunStatus.BLOCKED, result.run?.status)
        assertEquals(0L, fixture.providerProbe.executionCount())
        assertEquals(0L, fixture.providerProbe.mutationCount())
    }

    @Test
    fun `new incomplete hold evidence blocks the next step without invoking provider`() {
        val fixture = newHarness()
        val initial = createRun(fixture, "incomplete-before-execute")
        fixture.legalHolds.setState(GovernanceLegalHoldFixtureState.INCOMPLETE)

        val result = GovernanceContractAssertions.awaitStage(
            fixture.execute(initial, "execute-with-incomplete-holds"),
            asynchronousTimeout(),
            "Governance incomplete-evidence deletion",
        )

        assertEquals(GovernanceWorkerStatus.BLOCKED, result.status)
        assertEquals(GovernanceDeletionRunStatus.BLOCKED, result.run?.status)
        assertEquals(0L, fixture.providerProbe.executionCount())
        assertEquals(0L, fixture.providerProbe.mutationCount())
    }

    private fun createRun(fixture: GovernanceRuntimeContractHarness, key: String): GovernanceDeletionRun {
        val result = GovernanceContractAssertions.awaitStage(
            fixture.plan(key, false),
            asynchronousTimeout(),
            "Governance executable planning",
        )
        assertEquals(GovernancePlanningStatus.CREATED, result.status)
        return requireNotNull(result.run)
    }
}
