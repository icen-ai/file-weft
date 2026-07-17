package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.runtime.ReliabilityRunStatus
import ai.icen.fw.reliability.runtime.ReliabilitySubmissionStatus
import ai.icen.fw.reliability.runtime.ReliabilityWorkerStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * Durable recovery contract for an external host/provider/repository composition.
 *
 * Each test calls [newHarness] once and therefore requires a new isolated backing store.
 */
abstract class ReliabilityRuntimeRecoveryContractTest {
    protected abstract fun newHarness(): ReliabilityRuntimeContractHarness

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `one dispatch creates an immutable manifest covering the exact authoritative topology`() {
        val fixture = newHarness()
        val run = submitCreate(fixture, "exact-topology")
        fixture.clock.advance(61_000L)
        val completed = ReliabilityContractAssertions.awaitStage(
            fixture.advance(run.runId, "contract-worker", "exact-topology-worker"),
            asynchronousTimeout(),
            "Reliability topology backup",
        )
        assertEquals(ReliabilityWorkerStatus.COMPLETED, completed.status)
        assertEquals(1, fixture.providerProbe.mutationCount())
        val manifest = requireNotNull(requireNotNull(completed.run).outcome?.backupReceipt?.manifest)
        assertEquals(
            fixture.topology.components.map { it.scopeDigest }.toSet(),
            manifest.content.artifacts.map { it.scope.scopeDigest }.toSet(),
        )
        assertEquals(1, manifest.content.artifacts.map { it.consistentCutDigest }.toSet().size)
        assertTrue(manifest.content.artifacts.all { it.consistentCutDigest == manifest.content.consistentCut.cutDigest })
        ReliabilityDurableStateAssertions.assertRunRoundTrip(requireNotNull(completed.run))
    }

    @Test
    fun `crash after provider mutation reconciles the exact original and mutation count stays one`() {
        val fixture = newHarness()
        val run = submitCreate(fixture, "crash-after-provider")
        fixture.faults.crashAfterProviderReturnedOnce()
        fixture.clock.advance(61_000L)

        ReliabilityContractAssertions.awaitFailure(
            fixture.advance(run.runId, "contract-worker-a", "worker-a"),
            asynchronousTimeout(),
            "Reliability provider-return crash",
        )
        assertEquals(1, fixture.providerProbe.mutationCount())
        assertEquals(
            ReliabilityRunStatus.PROVIDER_CALL_STARTED,
            fixture.repository.load(fixture.topology.tenantId, run.runId)?.status,
        )
        val startedAfterMutation = requireNotNull(
            fixture.repository.load(fixture.topology.tenantId, run.runId),
        )
        ReliabilityDurableStateAssertions.assertRunRoundTrip(startedAfterMutation)
        ReliabilityDurableStateAssertions.assertRunRejectsWrongExpectedDigest(startedAfterMutation)

        fixture.clock.advance(61_000L)
        val recovered = ReliabilityContractAssertions.awaitStage(
            fixture.advance(run.runId, "contract-worker-b", "worker-b"),
            asynchronousTimeout(),
            "Reliability unknown-outcome recovery",
        )
        assertEquals(ReliabilityWorkerStatus.RECONCILIATION_REQUIRED, recovered.status)
        ReliabilityDurableStateAssertions.assertRunRoundTrip(requireNotNull(recovered.run))

        fixture.clock.advance(61_000L)
        val reconciled = ReliabilityContractAssertions.awaitStage(
            fixture.reconcile(run.runId, "contract-reconciler", "reconcile-original"),
            asynchronousTimeout(),
            "Reliability exact reconciliation",
        )
        assertEquals(ReliabilityWorkerStatus.COMPLETED, reconciled.status)
        assertEquals(1, fixture.providerProbe.mutationCount())
        assertEquals(1, fixture.providerProbe.reconciliationCount())
        assertEquals(
            fixture.providerProbe.lastMutationRequestDigest(),
            fixture.providerProbe.lastReconciliationOriginalRequestDigest(),
            "Reconciliation must query the exact original provider request.",
        )
        ReliabilityContractAssertions.assertSha256(
            requireNotNull(fixture.providerProbe.lastReconciliationOriginalAttemptDigest()),
            "Reliability original attempt",
        )
    }

    @Test
    fun `crash after call-started never blindly dispatches a provider mutation`() {
        val fixture = newHarness()
        val run = submitCreate(fixture, "crash-after-started")
        fixture.faults.crashAfterCallStartedOnce()
        fixture.clock.advance(61_000L)

        assertThrows<IllegalStateException> {
            fixture.advance(run.runId, "contract-worker-a", "worker-a")
        }
        assertEquals(0, fixture.providerProbe.mutationCount())
        assertEquals(
            ReliabilityRunStatus.PROVIDER_CALL_STARTED,
            fixture.repository.load(fixture.topology.tenantId, run.runId)?.status,
        )

        fixture.clock.advance(61_000L)
        val recovered = ReliabilityContractAssertions.awaitStage(
            fixture.advance(run.runId, "contract-worker-b", "worker-b"),
            asynchronousTimeout(),
            "Reliability call-started recovery",
        )
        assertEquals(ReliabilityWorkerStatus.RECONCILIATION_REQUIRED, recovered.status)

        fixture.clock.advance(61_000L)
        val reconciled = ReliabilityContractAssertions.awaitStage(
            fixture.reconcile(run.runId, "contract-reconciler", "reconcile-not-dispatched"),
            asynchronousTimeout(),
            "Reliability call-started reconciliation",
        )
        assertTrue(
            reconciled.status == ReliabilityWorkerStatus.COMPLETED ||
                reconciled.status == ReliabilityWorkerStatus.FAILED,
            "Exact reconciliation must close the never-redispatched original attempt.",
        )
        assertEquals(0, fixture.providerProbe.mutationCount())
        assertEquals(1, fixture.providerProbe.reconciliationCount())
    }

    @Test
    fun `revocation and cross-tenant lookup fail before provider mutation`() {
        val revoked = newHarness()
        val protectedRun = submitCreate(revoked, "revoked")
        revoked.authorization.revoke()
        revoked.clock.advance(10_000L)
        val denied = ReliabilityContractAssertions.awaitStage(
            revoked.advance(protectedRun.runId, "contract-worker", "revoked-worker"),
            asynchronousTimeout(),
            "Reliability revoked dispatch",
        )
        assertEquals(ReliabilityWorkerStatus.FAILED, denied.status)
        assertEquals(0, revoked.providerProbe.mutationCount())

        val isolated = newHarness()
        val isolatedRun = submitCreate(isolated, "cross-tenant")
        isolated.clock.advance(10_000L)
        val hiddenInvocation = isolated.operationInvocation(
            "cross-tenant-worker",
            isolated.clock.nowEpochMilli(),
            "tenant-not-authorized",
            isolated.authorization.operationPrincipal,
        )
        val hidden = ReliabilityContractAssertions.awaitStage(
            isolated.worker().runOne(
                ai.icen.fw.reliability.runtime.ReliabilityWorkerCommand.of(
                    hiddenInvocation,
                    isolatedRun.runId,
                    "contract-worker",
                ),
            ),
            asynchronousTimeout(),
            "Reliability cross-tenant lookup",
        )
        assertEquals(ReliabilityWorkerStatus.NOT_FOUND, hidden.status)
        assertEquals(0, isolated.providerProbe.mutationCount())
        assertTrue(isolated.repository.load("tenant-not-authorized", isolatedRun.runId) == null)
    }

    private fun submitCreate(
        fixture: ReliabilityRuntimeContractHarness,
        key: String,
    ): ai.icen.fw.reliability.runtime.ReliabilityRun {
        val result = fixture.submitCreate(key)
        assertEquals(ReliabilitySubmissionStatus.CREATED, result.status)
        assertNotNull(result.run, "Reliability submission must persist a durable run.")
        return requireNotNull(result.run)
    }
}
