package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.ReliabilityAction
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilityVersionFence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReliabilityDurableStateFactoryTest {
    @Test
    fun `canonical intent dispatch and run survive restart while changed digest fails closed`() {
        val fixture = runtimeFixture()
        val trusted = invocation(
            ReliabilityPurpose.CREATE_BACKUP,
            ReliabilityAction.CREATE_BACKUP,
            fixture.source.resource,
            requestedAt = 100_000L,
            rawIdempotencyKey = "durable-create",
        )
        val submitted = requireNotNull(
            fixture.submission.submitCreate(
                ReliabilityCreateCommand.of(
                    trusted,
                    fixture.source,
                    ReliabilityVersionFence.of(fixture.source.resource, 1L, digest('7')),
                    "provider",
                    500_000L,
                ),
            ).run,
        )
        val restoredIntent = rehydrateIntent(submitted.intent)
        assertEquals(submitted.intent.intentDigest, restoredIntent.intentDigest)

        fixture.faults.afterStarted = true
        fixture.clock.now = 110_000L
        assertFailsWith<IllegalStateException> {
            fixture.worker().runOne(
                ReliabilityWorkerCommand.of(
                    invocation(
                        ReliabilityPurpose.CREATE_BACKUP,
                        ReliabilityAction.CREATE_BACKUP,
                        fixture.source.resource,
                        requestedAt = 110_000L,
                        rawIdempotencyKey = "durable-worker",
                    ),
                    submitted.runId,
                    "durable-worker",
                ),
            )
        }
        val started = fixture.repository.latest(submitted.runId)
        val dispatch = requireNotNull(started.dispatch)
        val restoredDispatch = ReliabilityDurableStateFactory.rehydrateDispatch(
            dispatch.kind,
            dispatch.providerId,
            dispatch.providerRevision,
            dispatch.providerDescriptorDigest,
            dispatch.createRequest,
            dispatch.verifyRequest,
            dispatch.restoreRequest,
            dispatch.drillRequest,
            dispatch.originalAttempt,
            dispatch.dispatchDigest,
        )
        val restoredRun = ReliabilityDurableStateFactory.rehydrateRun(
            started.runId,
            restoredIntent,
            started.status,
            started.version,
            started.lease,
            restoredDispatch,
            started.outcomeUnknown,
            started.outcome,
            started.failure,
            started.cancellationRequested,
            started.createdAtEpochMilli,
            started.updatedAtEpochMilli,
            started.stateDigest,
        )

        assertEquals(started.stateDigest, restoredRun.stateDigest)
        assertFailsWith<IllegalArgumentException> {
            ReliabilityDurableStateFactory.rehydrateRun(
                started.runId,
                restoredIntent,
                started.status,
                started.version,
                started.lease,
                restoredDispatch,
                started.outcomeUnknown,
                started.outcome,
                started.failure,
                started.cancellationRequested,
                started.createdAtEpochMilli,
                started.updatedAtEpochMilli,
                digest('0'),
            )
        }
    }

    private fun rehydrateIntent(intent: ReliabilityOperationIntent): ReliabilityOperationIntent =
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
            intent.intentDigest,
        )

    private fun digest(character: Char): String = character.toString().repeat(64)
}
