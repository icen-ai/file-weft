package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.runtime.ReliabilityDurableStateFactory
import ai.icen.fw.reliability.runtime.ReliabilityDispatch
import ai.icen.fw.reliability.runtime.ReliabilityOperationIntent
import ai.icen.fw.reliability.runtime.ReliabilityRun
import ai.icen.fw.reliability.runtime.ReliabilitySloSchedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows

/** Java-friendly canonical rehydration assertions for persistence adapter contract tests. */
object ReliabilityDurableStateAssertions {
    @JvmStatic
    fun assertRunRoundTrip(run: ReliabilityRun): ReliabilityRun {
        val intent = rehydrateIntent(run.intent, run.intent.intentDigest)
        val dispatch = run.dispatch?.let { rehydrateDispatch(it, it.dispatchDigest) }
        val restored = ReliabilityDurableStateFactory.rehydrateRun(
            run.runId,
            intent,
            run.status,
            run.version,
            run.lease,
            dispatch,
            run.outcomeUnknown,
            run.outcome,
            run.failure,
            run.cancellationRequested,
            run.createdAtEpochMilli,
            run.updatedAtEpochMilli,
            run.stateDigest,
        )
        assertEquals(run.intent.intentDigest, intent.intentDigest)
        assertEquals(run.stateDigest, restored.stateDigest)
        return restored
    }

    @JvmStatic
    fun assertRunRejectsWrongExpectedDigest(run: ReliabilityRun) {
        assertThrows(IllegalArgumentException::class.java) {
            ReliabilityDurableStateFactory.rehydrateRun(
                run.runId,
                run.intent,
                run.status,
                run.version,
                run.lease,
                run.dispatch,
                run.outcomeUnknown,
                run.outcome,
                run.failure,
                run.cancellationRequested,
                run.createdAtEpochMilli,
                run.updatedAtEpochMilli,
                wrongDigest(run.stateDigest),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            rehydrateIntent(run.intent, wrongDigest(run.intent.intentDigest))
        }
        run.dispatch?.let { dispatch ->
            assertThrows(IllegalArgumentException::class.java) {
                rehydrateDispatch(dispatch, wrongDigest(dispatch.dispatchDigest))
            }
        }
    }

    @JvmStatic
    fun assertSloScheduleRoundTrip(schedule: ReliabilitySloSchedule): ReliabilitySloSchedule {
        val restored = ReliabilityDurableStateFactory.rehydrateSloSchedule(
            schedule.scheduleId,
            schedule.tenantId,
            schedule.policyBindingDigest,
            schedule.objectiveResource,
            schedule.cadenceMillis,
            schedule.nextEvaluationAtEpochMilli,
            schedule.version,
            schedule.lease,
            schedule.lastRecord,
            schedule.updatedAtEpochMilli,
            schedule.stateDigest,
        )
        assertEquals(schedule.stateDigest, restored.stateDigest)
        return restored
    }

    @JvmStatic
    fun assertSloScheduleRejectsWrongExpectedDigest(schedule: ReliabilitySloSchedule) {
        assertThrows(IllegalArgumentException::class.java) {
            ReliabilityDurableStateFactory.rehydrateSloSchedule(
                schedule.scheduleId,
                schedule.tenantId,
                schedule.policyBindingDigest,
                schedule.objectiveResource,
                schedule.cadenceMillis,
                schedule.nextEvaluationAtEpochMilli,
                schedule.version,
                schedule.lease,
                schedule.lastRecord,
                schedule.updatedAtEpochMilli,
                wrongDigest(schedule.stateDigest),
            )
        }
    }

    private fun rehydrateIntent(intent: ReliabilityOperationIntent, expectedDigest: String): ReliabilityOperationIntent =
        ReliabilityDurableStateFactory.rehydrateIntent(
            intent.kind,
            intent.tenantId,
            intent.principal,
            intent.purpose,
            intent.action,
            intent.resource,
            intent.idempotencyDigest,
            intent.argumentDigest,
            intent.providerId,
            intent.providerRevision,
            intent.providerDescriptorDigest,
            intent.topologySnapshotDigest,
            intent.objectives,
            intent.manifest,
            intent.verification,
            intent.environment,
            intent.cleanTargetProof,
            intent.versionFence,
            intent.recoveryReferenceEpochMilli,
            intent.drillId,
            intent.submittedAtEpochMilli,
            intent.executionDeadlineEpochMilli,
            expectedDigest,
        )

    private fun rehydrateDispatch(dispatch: ReliabilityDispatch, expectedDigest: String): ReliabilityDispatch =
        ReliabilityDurableStateFactory.rehydrateDispatch(
            dispatch.kind,
            dispatch.providerId,
            dispatch.providerRevision,
            dispatch.providerDescriptorDigest,
            dispatch.createRequest,
            dispatch.verifyRequest,
            dispatch.restoreRequest,
            dispatch.drillRequest,
            dispatch.originalAttempt,
            expectedDigest,
        )

    private fun wrongDigest(value: String): String {
        ReliabilityContractAssertions.assertSha256(value, "Reliability durable state")
        val replacement = if (value[0] == '0') '1' else '0'
        return replacement + value.substring(1)
    }
}
