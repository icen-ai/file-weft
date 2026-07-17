package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.*
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ReliabilityRuntimeTest {
    @Test
    fun `exact idempotency replays and changed arguments conflict`() {
        val fixture = runtimeFixture()
        val invocation = createInvocation(fixture, 100_000L, "same-key")
        val first = fixture.submission.submitCreate(createCommand(fixture, invocation, 500_000L))
        val replay = fixture.submission.submitCreate(createCommand(fixture, invocation, 500_000L))
        val laterWindowReplay = fixture.submission.submitCreate(
            createCommand(fixture, createInvocation(fixture, 101_000L, "same-key"), 500_000L),
        )
        val conflict = fixture.submission.submitCreate(createCommand(fixture, invocation, 600_000L))

        assertEquals(ReliabilitySubmissionStatus.CREATED, first.status)
        assertEquals(ReliabilitySubmissionStatus.REPLAY, replay.status)
        assertEquals(first.run?.runId, replay.run?.runId)
        assertEquals(ReliabilitySubmissionStatus.REPLAY, laterWindowReplay.status)
        assertEquals(first.run?.runId, laterWindowReplay.run?.runId)
        assertEquals(ReliabilitySubmissionStatus.CONFLICT, conflict.status)
        assertEquals(0, fixture.provider.mutationCount)
    }

    @Test
    fun `crash after intent storage remains dispatchable`() {
        val fixture = runtimeFixture()
        fixture.faults.afterIntent = true
        val invocation = createInvocation(fixture, 100_000L, "crash-before")

        val result = fixture.submission.submitCreate(createCommand(fixture, invocation, 500_000L))
        val stored = requireNotNull(
            fixture.repository.findByIdempotency(TENANT, invocation.idempotencyDigest),
        )
        fixture.clock.now = 110_000L
        val completed = fixture.worker().runOne(
            ReliabilityWorkerCommand.of(createInvocation(fixture, 110_000L, "worker"), stored.runId, "worker-1"),
        ).toCompletableFuture().join()

        assertEquals(ReliabilitySubmissionStatus.FAILED, result.status)
        assertEquals(ReliabilityWorkerStatus.COMPLETED, completed.status)
        assertEquals(1, fixture.provider.mutationCount)
    }

    @Test
    fun `crash after call-started never blindly dispatches mutation`() {
        val fixture = runtimeFixture()
        val run = submitCreate(fixture)
        fixture.faults.afterStarted = true
        fixture.clock.now = 110_000L

        assertFailsWith<IllegalStateException> {
            fixture.worker().runOne(
                ReliabilityWorkerCommand.of(
                    createInvocation(fixture, 110_000L, "worker-a"), run.runId, "worker-a",
                ),
            )
        }
        assertEquals(ReliabilityRunStatus.PROVIDER_CALL_STARTED, fixture.repository.latest(run.runId).status)
        assertEquals(0, fixture.provider.mutationCount)

        fixture.clock.now = 120_000L
        val recovered = fixture.worker().runOne(
            ReliabilityWorkerCommand.of(
                createInvocation(fixture, 120_000L, "worker-b"), run.runId, "worker-b",
            ),
        ).toCompletableFuture().join()
        assertEquals(ReliabilityWorkerStatus.RECONCILIATION_REQUIRED, recovered.status)

        fixture.clock.now = 130_000L
        val reconciled = fixture.worker().runOne(
            ReliabilityWorkerCommand.of(
                reconcileInvocation(fixture, 130_000L), run.runId, "reconciler",
            ),
        ).toCompletableFuture().join()
        assertEquals(ReliabilityWorkerStatus.COMPLETED, reconciled.status)
        assertEquals(0, fixture.provider.mutationCount)
        assertEquals(1, fixture.provider.reconciliationCount)
    }

    @Test
    fun `crash after provider mutation reconciles exact original and mutation count stays one`() {
        val fixture = runtimeFixture()
        val run = submitCreate(fixture)
        fixture.faults.afterReturned = true
        fixture.clock.now = 110_000L

        assertFailsWith<CompletionException> {
            fixture.worker().runOne(
                ReliabilityWorkerCommand.of(
                    createInvocation(fixture, 110_000L, "worker-a"), run.runId, "worker-a",
                ),
            ).toCompletableFuture().join()
        }
        assertEquals(1, fixture.provider.mutationCount)
        assertEquals(ReliabilityRunStatus.PROVIDER_CALL_STARTED, fixture.repository.latest(run.runId).status)

        fixture.clock.now = 120_000L
        fixture.worker().runOne(
            ReliabilityWorkerCommand.of(
                createInvocation(fixture, 120_000L, "worker-b"), run.runId, "worker-b",
            ),
        ).toCompletableFuture().join()
        fixture.clock.now = 130_000L
        val reconciled = fixture.worker().runOne(
            ReliabilityWorkerCommand.of(
                reconcileInvocation(fixture, 130_000L), run.runId, "reconciler",
            ),
        ).toCompletableFuture().join()

        assertEquals(ReliabilityWorkerStatus.COMPLETED, reconciled.status)
        assertEquals(1, fixture.provider.mutationCount)
        assertEquals(1, fixture.provider.reconciliationCount)
        assertTrue(requireNotNull(reconciled.run).outcome?.reconciliationReceipt != null)
    }

    @Test
    fun `revocation provider drift cross tenant and CAS race fail before mutation`() {
        val revoked = runtimeFixture()
        val revokedRun = submitCreate(revoked)
        revoked.authorization.revoked = true
        revoked.clock.now = 110_000L
        val denied = revoked.worker().runOne(
            ReliabilityWorkerCommand.of(
                createInvocation(revoked, 110_000L, "worker"), revokedRun.runId, "worker",
            ),
        ).toCompletableFuture().join()
        assertEquals(ReliabilityRunFailureCode.AUTHORIZATION_DENIED, denied.failureCode)
        assertEquals(0, revoked.provider.mutationCount)

        val drift = runtimeFixture()
        val driftRun = submitCreate(drift)
        val changed = ReliabilityProviderDescriptor.of(
            "provider", "2", "1", digest('c'), ReliabilityCapability.values().toList(),
            listOf(ReliabilityComponentKind.DATABASE), 8, 0L, 2_000_000L,
        )
        drift.provider.descriptorValue = changed
        drift.registry.provider = ReliabilityRegisteredProvider.of(changed, drift.provider)
        drift.clock.now = 110_000L
        val drifted = drift.worker().runOne(
            ReliabilityWorkerCommand.of(
                createInvocation(drift, 110_000L, "worker"), driftRun.runId, "worker",
            ),
        ).toCompletableFuture().join()
        assertEquals(ReliabilityRunFailureCode.PROVIDER_DRIFT, drifted.failureCode)
        assertEquals(0, drift.provider.mutationCount)

        val crossTenant = runtimeFixture()
        val crossRun = submitCreate(crossTenant)
        crossTenant.clock.now = 110_000L
        val hidden = crossTenant.worker().runOne(
            ReliabilityWorkerCommand.of(
                createInvocation(crossTenant, 110_000L, "worker", tenant = "tenant-b"),
                crossRun.runId,
                "worker",
            ),
        ).toCompletableFuture().join()
        assertEquals(ReliabilityWorkerStatus.NOT_FOUND, hidden.status)
        assertEquals(0, crossTenant.provider.mutationCount)

        val wrongPrincipal = runtimeFixture()
        val protectedRun = submitCreate(wrongPrincipal)
        wrongPrincipal.clock.now = 110_000L
        val unauthorized = wrongPrincipal.worker().runOne(
            ReliabilityWorkerCommand.of(
                invocation(
                    ReliabilityPurpose.CREATE_BACKUP,
                    ReliabilityAction.CREATE_BACKUP,
                    wrongPrincipal.source.resource,
                    principal = ReliabilityPrincipalRef.of("user", "intruder"),
                    rawIdempotencyKey = "intruder",
                    requestedAt = 110_000L,
                ),
                protectedRun.runId,
                "intruder",
            ),
        ).toCompletableFuture().join()
        assertEquals(ReliabilityWorkerStatus.AUTHORIZATION_DENIED, unauthorized.status)
        assertEquals(0L, wrongPrincipal.repository.latest(protectedRun.runId).version)
        assertEquals(0, wrongPrincipal.provider.mutationCount)

        val race = runtimeFixture()
        val raceRun = submitCreate(race)
        race.repository.conflictNextCompare = true
        race.clock.now = 110_000L
        val conflict = race.worker().runOne(
            ReliabilityWorkerCommand.of(
                createInvocation(race, 110_000L, "worker"), raceRun.runId, "worker",
            ),
        ).toCompletableFuture().join()
        assertEquals(ReliabilityWorkerStatus.CONFLICT, conflict.status)
        assertEquals(0, race.provider.mutationCount)
    }

    @Test
    fun `long execution outlives short authorization and old backup RPO remains red`() {
        val long = runtimeFixture()
        long.provider.completionOffsetMillis = 600_000L
        val longRun = submitCreate(long, executionDeadline = 800_000L)
        long.clock.now = 110_000L
        val completed = long.worker().runOne(
            ReliabilityWorkerCommand.of(
                createInvocation(long, 110_000L, "worker"), longRun.runId, "worker",
            ),
        ).toCompletableFuture().join()
        assertEquals(ReliabilityWorkerStatus.COMPLETED, completed.status)
        assertTrue(
            requireNotNull(completed.run).outcome?.backupReceipt?.completedAtEpochMilli!! >
                111_000L,
        )

        val restore = runtimeFixture()
        val restoreInvocation = invocation(
            ReliabilityPurpose.RESTORE,
            ReliabilityAction.RESTORE_CLEAN_TARGET,
            restore.target.resource,
            rawIdempotencyKey = "restore-key",
            requestedAt = 100_000L,
        )
        val submitted = restore.submission.submitRestore(
            ReliabilityRestoreCommand.of(
                restoreInvocation,
                restore.manifest,
                restore.verification,
                restore.target,
                restore.proof,
                ReliabilityVersionFence.of(restore.target.resource, 0L, digest('6')),
                100_000L,
                "provider",
                500_000L,
            ),
        )
        val restoreRun = requireNotNull(submitted.run)
        restore.clock.now = 110_000L
        val restored = restore.worker().runOne(
            ReliabilityWorkerCommand.of(
                invocation(
                    ReliabilityPurpose.RESTORE,
                    ReliabilityAction.RESTORE_CLEAN_TARGET,
                    restore.target.resource,
                    rawIdempotencyKey = "restore-worker",
                    requestedAt = 110_000L,
                ),
                restoreRun.runId,
                "restore-worker",
            ),
        ).toCompletableFuture().join()
        val assessment = requireNotNull(requireNotNull(restored.run).outcome?.restoreReceipt).assessment
        assertFalse(assessment.rpoMet)
        assertEquals(20_500L, assessment.evaluations.single().dataLossMillis)
    }

    @Test
    fun `queued verification receives a new short dispatch authorization window`() {
        val fixture = runtimeFixture()
        val submitted = fixture.submission.submitVerify(
            ReliabilityVerifyCommand.of(
                invocation(
                    ReliabilityPurpose.VERIFY_BACKUP,
                    ReliabilityAction.VERIFY_BACKUP,
                    fixture.manifest.resource,
                    rawIdempotencyKey = "verify-key",
                    requestedAt = 100_000L,
                ),
                fixture.manifest,
                ReliabilityVersionFence.of(fixture.manifest.resource, 1L, digest('8')),
                "provider",
                500_000L,
            ),
        )
        val run = requireNotNull(submitted.run)

        fixture.clock.now = 110_000L
        val completed = fixture.worker().runOne(
            ReliabilityWorkerCommand.of(
                invocation(
                    ReliabilityPurpose.VERIFY_BACKUP,
                    ReliabilityAction.VERIFY_BACKUP,
                    fixture.manifest.resource,
                    rawIdempotencyKey = "verify-worker",
                    requestedAt = 110_000L,
                ),
                run.runId,
                "verify-worker",
            ),
        ).toCompletableFuture().join()

        assertEquals(ReliabilityWorkerStatus.COMPLETED, completed.status)
        assertEquals(1, fixture.provider.verificationCount)
        assertNotNull(requireNotNull(completed.run).outcome?.verificationReceipt)
    }

    @Test
    fun `cancel before dispatch is terminal and never calls provider`() {
        val fixture = runtimeFixture()
        val run = submitCreate(fixture)
        fixture.clock.now = 110_000L
        val cancelled = fixture.worker().runOne(
            ReliabilityWorkerCommand.of(
                createInvocation(fixture, 110_000L, "cancel"),
                run.runId,
                "worker",
                ReliabilityWorkerMode.CANCEL,
            ),
        ).toCompletableFuture().join()

        assertEquals(ReliabilityWorkerStatus.CANCELLED, cancelled.status)
        assertEquals(ReliabilityRunStatus.CANCELLED, cancelled.run?.status)
        assertEquals(0, fixture.provider.mutationCount)
    }

    private fun submitCreate(
        fixture: RuntimeFixture,
        executionDeadline: Long = 500_000L,
    ): ReliabilityRun {
        val inv = createInvocation(fixture, 100_000L, "create-key")
        val result = fixture.submission.submitCreate(createCommand(fixture, inv, executionDeadline))
        assertEquals(ReliabilitySubmissionStatus.CREATED, result.status)
        return requireNotNull(result.run)
    }

    private fun createCommand(
        fixture: RuntimeFixture,
        invocation: ReliabilityTrustedInvocation,
        executionDeadline: Long,
    ): ReliabilityCreateCommand = ReliabilityCreateCommand.of(
        invocation,
        fixture.source,
        ReliabilityVersionFence.of(fixture.source.resource, 1L, digest('7')),
        "provider",
        executionDeadline,
    )

    private fun createInvocation(
        fixture: RuntimeFixture,
        requestedAt: Long,
        key: String,
        tenant: String = TENANT,
    ): ReliabilityTrustedInvocation = invocation(
        ReliabilityPurpose.CREATE_BACKUP,
        ReliabilityAction.CREATE_BACKUP,
        fixture.source.resource,
        tenant = tenant,
        rawIdempotencyKey = key,
        requestedAt = requestedAt,
    )

    private fun reconcileInvocation(
        fixture: RuntimeFixture,
        requestedAt: Long,
    ): ReliabilityTrustedInvocation = invocation(
        ReliabilityPurpose.RECONCILE,
        ReliabilityAction.RECONCILE_OPERATION,
        fixture.source.resource,
        principal = ReliabilityPrincipalRef.of("service", "reconciler"),
        rawIdempotencyKey = "reconcile-$requestedAt",
        requestedAt = requestedAt,
    )
}
