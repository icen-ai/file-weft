package ai.icen.fw.testkit.governance

import ai.icen.fw.governance.api.GovernanceFailure
import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.api.GovernanceDeletionExecutionRequest
import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.runtime.GovernanceAuthorizedCallFactory
import ai.icen.fw.governance.runtime.GovernanceDeletionDispatch
import ai.icen.fw.governance.runtime.GovernanceDeletionRun
import ai.icen.fw.governance.runtime.GovernanceDeletionRunStatus
import ai.icen.fw.governance.runtime.GovernanceOutboxClaimRequest
import ai.icen.fw.governance.runtime.GovernanceOutboxRecord
import ai.icen.fw.governance.runtime.GovernanceOutboxType
import ai.icen.fw.governance.runtime.GovernancePlanningStatus
import ai.icen.fw.governance.runtime.GovernanceStoreCode
import ai.icen.fw.governance.runtime.GovernanceWorkerStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Tenant, idempotency, CAS, and outbox fencing contract for a governance persistence pair. */
abstract class GovernanceRepositoryContractTest {
    protected abstract fun newRepositories(): GovernanceRepositoryBundle

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `planning is idempotent and every lookup is tenant scoped`() {
        val fixture = harness()
        val first = GovernanceContractAssertions.awaitStage(
            fixture.plan("repository-idempotency", false),
            asynchronousTimeout(),
            "Governance repository initial planning",
        )
        val second = GovernanceContractAssertions.awaitStage(
            fixture.plan("repository-idempotency", false),
            asynchronousTimeout(),
            "Governance repository idempotent replay",
        )
        val run = requireNotNull(first.run)

        assertEquals(GovernancePlanningStatus.CREATED, first.status)
        assertEquals(GovernancePlanningStatus.REPLAYED, second.status)
        assertEquals(run.stateDigest, second.run?.stateDigest)
        assertNull(fixture.repositories.deletion.load("tenant-not-authorized", run.planId))
        assertNull(fixture.repositories.deletion.findByIdempotency("tenant-not-authorized", run.idempotencyKey))
        assertEquals(run.stateDigest, fixture.repositories.deletion.load(fixture.tenantId, run.planId)?.stateDigest)
    }

    @Test
    fun `two tenants may use the same opaque plan and idempotency identifiers`() {
        val repositories = newRepositories()
        val tenantA = GovernanceRuntimeContractHarness.of(
            repositories,
            MutationCountingGovernanceProviderProbe.create(),
            "tenant-contract-a",
        )
        val tenantB = GovernanceRuntimeContractHarness.of(
            repositories,
            MutationCountingGovernanceProviderProbe.create(),
            "tenant-contract-b",
        )
        val runA = createRun(tenantA, "shared-idempotency")
        val runB = createRun(tenantB, "shared-idempotency")

        assertEquals(runA.planId, runB.planId, "The fixture must exercise equal tenant-relative plan IDs.")
        assertEquals(runA.idempotencyKey, runB.idempotencyKey)
        assertEquals(runA.stateDigest,
            repositories.deletion.load(tenantA.tenantId, runA.planId)?.stateDigest)
        assertEquals(runB.stateDigest,
            repositories.deletion.load(tenantB.tenantId, runB.planId)?.stateDigest)
    }

    @Test
    fun `prepared and started dispatches survive repository reload and restart recovery`() {
        val fixture = harness()
        val initial = createRun(fixture, "repository-dispatch")
        val assessment = GovernanceContractAssertions.awaitStage(
            fixture.evaluateClearRetention("repository-dispatch-assessment"),
            asynchronousTimeout(),
            "Governance dispatch assessment",
        )
        val invocation = fixture.invocation(
            GovernancePurpose.EXECUTE_SECURE_DELETION,
            "repository-dispatch-execution",
        )
        val context = GovernanceAuthorizedCallFactory(fixture.authorization, fixture.identifiers).create(
            invocation,
            GovernancePurpose.EXECUTE_SECURE_DELETION,
            "repository-dispatch-roundtrip",
        )
        val step = requireNotNull(initial.nextStep())
        val descriptor = requireNotNull(fixture.providerProbe.registry().find(step.stage))
        val request = GovernanceDeletionExecutionRequest.of(
            context,
            initial.plan,
            step,
            assessment,
            1,
            null,
            initial.successfulReceipts,
            initial.version,
        )
        val preparedDispatch = GovernanceDeletionDispatch.prepared(
            request,
            descriptor.providerId,
            descriptor.providerRevision,
            context.idempotencyKey,
            fixture.clock.nowEpochMilli(),
        )
        val preparedCandidate = GovernanceDeletionRun.prepare(
            initial,
            preparedDispatch,
            fixture.clock.nowEpochMilli(),
        )
        val preparedStored = fixture.repositories.deletion.compareAndSet(
            fixture.tenantId,
            initial.planId,
            initial.version,
            preparedCandidate,
            outbox(
                preparedCandidate,
                "contract-dispatch-prepared",
                fixture.clock.nowEpochMilli(),
                GovernanceOutboxType.STATE_CHECKPOINTED,
            ),
        )
        assertEquals(GovernanceStoreCode.STORED, preparedStored.code)
        val preparedReloaded = requireNotNull(
            fixture.repositories.deletion.load(fixture.tenantId, initial.planId),
        )
        assertEquals(GovernanceDeletionRunStatus.DISPATCH_PREPARED, preparedReloaded.status)
        assertEquals(preparedDispatch.dispatchDigest, preparedReloaded.dispatch?.dispatchDigest)
        GovernanceDurableStateAssertions.assertRunRoundTrip(preparedReloaded)

        val startedCandidate = GovernanceDeletionRun.markProviderCallStarted(
            preparedReloaded,
            fixture.clock.nowEpochMilli(),
        )
        val startedStored = fixture.repositories.deletion.compareAndSet(
            fixture.tenantId,
            initial.planId,
            preparedReloaded.version,
            startedCandidate,
            outbox(
                startedCandidate,
                "contract-dispatch-started",
                fixture.clock.nowEpochMilli(),
                GovernanceOutboxType.STATE_CHECKPOINTED,
            ),
        )
        assertEquals(GovernanceStoreCode.STORED, startedStored.code)
        val startedReloaded = requireNotNull(
            fixture.repositories.deletion.load(fixture.tenantId, initial.planId),
        )
        assertEquals(GovernanceDeletionRunStatus.DISPATCH_STARTED, startedReloaded.status)
        GovernanceDurableStateAssertions.assertRunRoundTrip(startedReloaded)

        val recovered = GovernanceContractAssertions.awaitStage(
            fixture.execute(startedReloaded, "restart-started-dispatch"),
            asynchronousTimeout(),
            "Governance started-dispatch restart",
        )
        assertEquals(GovernanceWorkerStatus.RECONCILIATION_REQUIRED, recovered.status)
        assertEquals(0L, fixture.providerProbe.executionCount())
        assertEquals(0L, fixture.providerProbe.mutationCount())
        assertNotNull(recovered.run?.pendingReceipt?.receiptReference)
    }

    @Test
    fun `same expected version has one CAS winner and tenant mismatch is rejected`() {
        val fixture = harness()
        val initial = createRun(fixture, "repository-cas")
        val changedAt = fixture.clock.advance(1L)
        val candidateA = GovernanceDeletionRun.blocked(
            initial,
            GovernanceFailure.of(
                GovernanceFailureClass.LEGAL_HOLD_ACTIVE,
                "contract-hold-a",
                false,
                false,
            ),
            changedAt,
        )
        val candidateB = GovernanceDeletionRun.blocked(
            initial,
            GovernanceFailure.of(
                GovernanceFailureClass.LEGAL_HOLD_ACTIVE,
                "contract-hold-b",
                false,
                false,
            ),
            changedAt,
        )
        val outboxA = outbox(candidateA, "contract-cas-a", changedAt)
        val outboxB = outbox(candidateB, "contract-cas-b", changedAt)

        val crossTenant = fixture.repositories.deletion.compareAndSet(
            "tenant-not-authorized",
            initial.planId,
            initial.version,
            candidateA,
            outboxA,
        )
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val first = executor.submit(Callable {
            ready.countDown()
            release.await()
            fixture.repositories.deletion.compareAndSet(
                fixture.tenantId,
                initial.planId,
                initial.version,
                candidateA,
                outboxA,
            )
        })
        val second = executor.submit(Callable {
            ready.countDown()
            release.await()
            fixture.repositories.deletion.compareAndSet(
                fixture.tenantId,
                initial.planId,
                initial.version,
                candidateB,
                outboxB,
            )
        })
        val results = try {
            assertTrue(ready.await(10L, TimeUnit.SECONDS), "CAS contenders did not become ready.")
            release.countDown()
            listOf(first.get(10L, TimeUnit.SECONDS), second.get(10L, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }

        assertEquals(GovernanceStoreCode.CONFLICT, crossTenant.code)
        assertEquals(1, results.count { it.code == GovernanceStoreCode.STORED })
        assertEquals(1, results.count { it.code == GovernanceStoreCode.CONFLICT })
        val storedDigest = fixture.repositories.deletion.load(fixture.tenantId, initial.planId)?.stateDigest
        assertTrue(storedDigest == candidateA.stateDigest || storedDigest == candidateB.stateDigest)
    }

    @Test
    fun `outbox lease fencing rejects stale acknowledgements and cross tenant claims`() {
        val fixture = harness()
        val run = createRun(fixture, "repository-outbox")
        val now = run.updatedAtEpochMilli
        val wrongTenant = fixture.repositories.outbox.claimReady(
            GovernanceOutboxClaimRequest.of(
                "tenant-not-authorized",
                "worker-wrong",
                "claim-wrong",
                now,
                now + 100L,
                16,
            ),
        )
        assertTrue(wrongTenant.isEmpty())

        val firstClaims = fixture.repositories.outbox.claimReady(
            GovernanceOutboxClaimRequest.of(
                fixture.tenantId,
                "worker-a",
                "claim-a",
                now,
                now + 100L,
                1,
            ),
        )
        val first = firstClaims.single()
        assertFalse(
            fixture.repositories.outbox.acknowledge(first, now + 100L),
            "A claim is expired at its exclusive lease boundary.",
        )
        val second = fixture.repositories.outbox.claimReady(
            GovernanceOutboxClaimRequest.of(
                fixture.tenantId,
                "worker-b",
                "claim-b",
                now + 100L,
                now + 200L,
                1,
            ),
        ).single()

        assertTrue(second.fencingToken > first.fencingToken)
        assertFalse(fixture.repositories.outbox.acknowledge(first, now + 100L))
        assertTrue(fixture.repositories.outbox.acknowledge(second, now + 101L))
        assertNotNull(second.record)
    }

    private fun harness(): GovernanceRuntimeContractHarness = GovernanceRuntimeContractHarness.of(
        newRepositories(),
        MutationCountingGovernanceProviderProbe.create(),
    )

    private fun createRun(fixture: GovernanceRuntimeContractHarness, key: String): GovernanceDeletionRun {
        val result = GovernanceContractAssertions.awaitStage(
            fixture.plan(key, false),
            asynchronousTimeout(),
            "Governance repository planning",
        )
        assertEquals(GovernancePlanningStatus.CREATED, result.status)
        return requireNotNull(result.run)
    }

    private fun outbox(
        run: GovernanceDeletionRun,
        id: String,
        now: Long,
        type: GovernanceOutboxType = GovernanceOutboxType.RUN_BLOCKED,
    ): GovernanceOutboxRecord = GovernanceOutboxRecord.of(id, type, run, now)
}
