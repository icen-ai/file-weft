package ai.icen.fw.testkit.governance

import ai.icen.fw.governance.api.GovernanceDeletionStepReceipt
import ai.icen.fw.governance.api.GovernanceRetentionAssessment
import ai.icen.fw.governance.runtime.GovernanceDeletionRun
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

object GovernanceDurableStateAssertions {
    @JvmStatic
    fun assertAssessmentRoundTrip(assessment: GovernanceRetentionAssessment): GovernanceRetentionAssessment {
        val restored = GovernanceRetentionAssessment.rehydrate(
            assessment.tenantId,
            assessment.resource,
            assessment.fence,
            assessment.policy,
            assessment.legalHolds,
            assessment.clock,
            assessment.requestDigest,
            assessment.outcome,
            assessment.reason,
            assessment.assessmentDigest,
        )
        assertEquals(assessment.assessmentDigest, restored.assessmentDigest)
        assertEquals(assessment.outcome, restored.outcome)
        assertEquals(assessment.reason, restored.reason)
        return restored
    }

    @JvmStatic
    fun assertAssessmentRejectsWrongExpectedDigest(assessment: GovernanceRetentionAssessment) {
        assertThrows(IllegalArgumentException::class.java) {
            GovernanceRetentionAssessment.rehydrate(
                assessment.tenantId,
                assessment.resource,
                assessment.fence,
                assessment.policy,
                assessment.legalHolds,
                assessment.clock,
                assessment.requestDigest,
                assessment.outcome,
                assessment.reason,
                wrongDigest(assessment.assessmentDigest),
            )
        }
    }

    @JvmStatic
    fun assertReceiptRoundTrip(receipt: GovernanceDeletionStepReceipt): GovernanceDeletionStepReceipt {
        val restored = GovernanceDeletionStepReceipt.rehydrate(
            receipt.planDigest,
            receipt.stepDigest,
            receipt.executionRequestDigest,
            receipt.attempt,
            receipt.providerId,
            receipt.providerRevision,
            receipt.status,
            receipt.receiptReference,
            receipt.resultDigest,
            receipt.failure,
            receipt.observedAtEpochMilli,
            receipt.reconciliationRequestDigest,
            receipt.receiptDigest,
        )
        assertEquals(receipt.receiptDigest, restored.receiptDigest)
        assertEquals(receipt.status, restored.status)
        return restored
    }

    @JvmStatic
    fun assertReceiptRejectsWrongExpectedDigest(receipt: GovernanceDeletionStepReceipt) {
        assertThrows(IllegalArgumentException::class.java) {
            GovernanceDeletionStepReceipt.rehydrate(
                receipt.planDigest,
                receipt.stepDigest,
                receipt.executionRequestDigest,
                receipt.attempt,
                receipt.providerId,
                receipt.providerRevision,
                receipt.status,
                receipt.receiptReference,
                receipt.resultDigest,
                receipt.failure,
                receipt.observedAtEpochMilli,
                receipt.reconciliationRequestDigest,
                wrongDigest(receipt.receiptDigest),
            )
        }
    }

    @JvmStatic
    fun assertRunRoundTrip(run: GovernanceDeletionRun): GovernanceDeletionRun {
        val restored = GovernanceDeletionRun.rehydrate(
            run.plan,
            run.commandDigest,
            run.idempotencyKey,
            run.status,
            run.successfulReceipts,
            run.pendingReceipt,
            run.dispatch,
            run.failure,
            run.nextActionAtEpochMilli,
            run.version,
            run.updatedAtEpochMilli,
            run.stateDigest,
        )
        assertEquals(run.stateDigest, restored.stateDigest)
        assertEquals(run.status, restored.status)
        assertEquals(run.version, restored.version)
        return restored
    }

    @JvmStatic
    fun assertRunRejectsWrongExpectedDigest(run: GovernanceDeletionRun) {
        assertThrows(IllegalArgumentException::class.java) {
            GovernanceDeletionRun.rehydrate(
                run.plan,
                run.commandDigest,
                run.idempotencyKey,
                run.status,
                run.successfulReceipts,
                run.pendingReceipt,
                run.dispatch,
                run.failure,
                run.nextActionAtEpochMilli,
                run.version,
                run.updatedAtEpochMilli,
                wrongDigest(run.stateDigest),
            )
        }
    }

    private fun wrongDigest(original: String): String =
        if (original.firstOrNull() == '0') GovernanceContractAssertions.digest('1')
        else GovernanceContractAssertions.digest('0')
}
