package ai.icen.fw.testkit.governance

import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.api.GovernanceRetentionOutcome
import ai.icen.fw.governance.api.GovernanceRetentionReason
import ai.icen.fw.governance.runtime.GovernancePlanningStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Retention and legal-hold evidence must fail closed before any destructive provider dispatch. */
abstract class GovernanceRetentionLegalHoldContractTest {
    protected abstract fun newHarness(): GovernanceRuntimeContractHarness

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `complete clear hold evidence and expired retention produce canonical eligibility`() {
        val fixture = newHarness()
        fixture.legalHolds.setState(GovernanceLegalHoldFixtureState.CLEAR)

        val result = GovernanceContractAssertions.awaitStage(
            fixture.plan("clear-expired", true),
            asynchronousTimeout(),
            "Governance clear retention planning",
        )

        assertEquals(GovernancePlanningStatus.DRY_RUN, result.status)
        val assessment = requireNotNull(result.plan).assessment
        assertEquals(GovernanceRetentionOutcome.ELIGIBLE_FOR_DELETION, assessment.outcome)
        assertEquals(GovernanceRetentionReason.RETENTION_EXPIRED, assessment.reason)
        assertTrue(assessment.isDeletionEligible())
        GovernanceDurableStateAssertions.assertAssessmentRoundTrip(assessment)
        GovernanceDurableStateAssertions.assertAssessmentRejectsWrongExpectedDigest(assessment)
    }

    @Test
    fun `active legal hold blocks before policy lookup target resolution and provider mutation`() {
        val fixture = newHarness()
        fixture.legalHolds.setState(GovernanceLegalHoldFixtureState.ACTIVE)

        val result = GovernanceContractAssertions.awaitStage(
            fixture.plan("active-hold", false),
            asynchronousTimeout(),
            "Governance active-hold planning",
        )

        assertEquals(GovernancePlanningStatus.BLOCKED, result.status)
        assertEquals(GovernanceFailureClass.LEGAL_HOLD_ACTIVE, requireNotNull(result.failure).classification)
        assertEquals(0L, fixture.retentionPolicy.loadCount())
        assertEquals(0L, fixture.targetResolutionCount())
        assertEquals(0L, fixture.providerProbe.mutationCount())
        assertTrue(fixture.signals.isEmpty())
    }

    @Test
    fun `incomplete legal hold evidence is never treated as clear`() {
        val fixture = newHarness()
        fixture.legalHolds.setState(GovernanceLegalHoldFixtureState.INCOMPLETE)

        val result = GovernanceContractAssertions.awaitStage(
            fixture.plan("incomplete-hold", true),
            asynchronousTimeout(),
            "Governance incomplete-hold planning",
        )

        assertEquals(GovernancePlanningStatus.BLOCKED, result.status)
        assertEquals(GovernanceFailureClass.STALE_EVIDENCE, requireNotNull(result.failure).classification)
        assertEquals(0L, fixture.retentionPolicy.loadCount())
        assertEquals(0L, fixture.targetResolutionCount())
        assertEquals(0L, fixture.providerProbe.mutationCount())
        assertNotNull(result.failure)
    }
}
