package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentEvaluationCase
import ai.icen.fw.agent.api.AgentEvaluationDiagnostic
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticReason
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticStatus
import ai.icen.fw.agent.api.AgentEvaluationExpectedOutcome
import ai.icen.fw.agent.api.AgentEvaluationLatencyObservation
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DurableAgentEvaluationCoordinatorTest {

    @Test
    fun `fixed cases run outside store transaction and persist safe evidence`() {
        val fixture = RuntimeFixture(caseCount = 1)
        val request = fixture.request("request-1", "same-key")
        val started = fixture.coordinator.start(request)
        val replayed = fixture.coordinator.start(fixture.request("request-2", "same-key"))

        assertEquals(started.evaluationId, replayed.evaluationId)
        assertEquals(1, fixture.store.createCount)

        val completed = fixture.coordinator.execute(started.key(), ProviderId("worker.local"))

        assertEquals(AgentEvaluationRunStatus.COMPLETED, completed.status)
        assertEquals(1, completed.evidence.size)
        assertTrue(completed.evidence.single().passed)
        assertEquals(AgentEvaluationDiagnosticStatus.READY, completed.diagnostic?.status)
        assertFalse(completed.toString().contains("tenant-1"))
        assertFalse(completed.evidence.single().toString().contains(completed.evidence.single().evidenceDigest))
        assertEquals(1, fixture.fixtureCalls.get())
        assertEquals(1, fixture.evaluator.callsByCase["case-1"])
    }

    @Test
    fun `retry resumes durable case evidence with stable operation identity`() {
        val fixture = RuntimeFixture(caseCount = 2)
        fixture.evaluator.failOnceCase = "case-2"
        val started = fixture.coordinator.start(fixture.request("request-1", "retry-key"))

        val retrying = fixture.coordinator.execute(started.key(), ProviderId("worker.local"))
        assertEquals(AgentEvaluationRunStatus.QUEUED, retrying.status)
        assertEquals(1, retrying.attempt)
        assertEquals(listOf("case-1"), retrying.evidence.map { it.caseId.value })
        assertEquals("evaluation.timeout", retrying.diagnostic?.reason?.value)

        val completed = fixture.coordinator.execute(started.key(), ProviderId("worker.local"))
        assertEquals(AgentEvaluationRunStatus.COMPLETED, completed.status)
        assertEquals(2, completed.attempt)
        assertEquals(1, fixture.evaluator.callsByCase["case-1"])
        assertEquals(2, fixture.evaluator.callsByCase["case-2"])
        val operations = requireNotNull(fixture.evaluator.operationsByCase["case-2"])
        assertEquals(2, operations.size)
        assertEquals(operations.first(), operations.last())
    }

    @Test
    fun `provider drift fails closed before fixture or evaluator dispatch`() {
        val fixture = RuntimeFixture(caseCount = 1)
        val started = fixture.coordinator.start(fixture.request("request-1", "drift-key"))
        fixture.evaluator.reportedSnapshot = fixture.providerSnapshot(digestSeed = "changed")

        val failed = fixture.coordinator.execute(started.key(), ProviderId("worker.local"))

        assertEquals(AgentEvaluationRunStatus.FAILED, failed.status)
        assertEquals(AgentEvaluationDiagnosticStatus.DRIFTED, failed.diagnostic?.status)
        assertEquals(AgentEvaluationDiagnosticReason.SNAPSHOT_DRIFT, failed.diagnostic?.reason)
        assertEquals(0, fixture.fixtureCalls.get())
        assertTrue(fixture.evaluator.callsByCase.isEmpty())
    }

    @Test
    fun `heartbeat is fenced and trusted cancellation invalidates worker state`() {
        val fixture = RuntimeFixture(caseCount = 1)
        val started = fixture.coordinator.start(fixture.request("request-1", "cancel-key"))
        val claimed = fixture.store.claim(
            AgentEvaluationLeaseClaim(
                started.key(),
                ProviderId("worker.local"),
                id("lease-1"),
                fixture.clock.currentTimeMillis(),
                100,
            ),
        ).state!!
        fixture.clock.advance(10)
        val heartbeat = fixture.coordinator.heartbeat(started.key(), ProviderId("worker.local"))
        assertTrue(heartbeat.lease!!.expiresAt >= claimed.lease!!.expiresAt)
        assertEquals(claimed.lease!!.fencingToken, heartbeat.lease!!.fencingToken)

        fixture.clock.advance(1)
        val cancelled = fixture.coordinator.cancel(
            AgentEvaluationCancellationRequest(
                started.key(),
                id("principal-1"),
                "USER",
                "authorization-v1",
                "operator.cancelled",
                fixture.clock.currentTimeMillis(),
            ),
        )
        assertEquals(AgentEvaluationRunStatus.CANCELLED, cancelled.status)
        assertEquals("operator.cancelled", cancelled.cancellationReason)
        assertEquals(null, cancelled.lease)
    }

    @Test
    fun `different owner or authorization revision cannot reuse idempotency scope`() {
        val fixture = RuntimeFixture(caseCount = 1)
        val first = fixture.request("request-1", "same-key")
        val other = AgentEvaluationRunRequest(
            id("request-2"),
            id("tenant-1"),
            id("principal-2"),
            "USER",
            "authorization-v2",
            fixture.suite,
            fixture.snapshot,
            "same-key",
            fixture.clock.currentTimeMillis(),
            1_000,
            2,
        )
        assertNotEquals(first.idempotencyScope.scopeDigest, other.idempotencyScope.scopeDigest)
    }

    private class RuntimeFixture(caseCount: Int) {
        val clock = MutableClock(100)
        val store = InMemoryEvaluationStore()
        private val payload = "fixed-evaluation-fixture".toByteArray(StandardCharsets.UTF_8)
        private val payloadDigest = sha256(payload)
        val suite = AgentEvaluationSuite(
            id("suite-1"),
            "Regression",
            "1.0",
            (1..caseCount).map { number -> evaluationCase(number, payloadDigest) },
            90,
        )
        val snapshot = providerSnapshot()
        val fixtureCalls = AtomicInteger()
        val evaluator = FakeEvaluator(snapshot, store)
        private val ids = SequenceIds()
        val coordinator = DurableAgentEvaluationCoordinator(
            store,
            AgentEvaluationFixturePort { request ->
                check(!store.inTransaction()) { "Fixture load ran inside a store transaction." }
                fixtureCalls.incrementAndGet()
                CompletableFuture.completedFuture(
                    AgentEvaluationFixture(request.fixtureId, "text/plain", payload, payloadDigest),
                )
            },
            evaluator,
            AgentEvaluationFailureClassifier {
                AgentEvaluationFailureDecision(
                    AgentEvaluationFailureKind.RETRYABLE,
                    AgentEvaluationDiagnosticReason("evaluator.transient"),
                )
            },
            clock,
            ids,
            AgentEvaluationRuntimeConfiguration(100),
        )

        fun request(requestId: String, key: String): AgentEvaluationRunRequest = AgentEvaluationRunRequest(
            id(requestId),
            id("tenant-1"),
            id("principal-1"),
            "USER",
            "authorization-v1",
            suite,
            snapshot,
            key,
            100,
            1_000,
            2,
        )

        fun providerSnapshot(digestSeed: String = "provider"): AgentEvaluationProviderSnapshot =
            AgentEvaluationProviderSnapshot(
                ProviderId("evaluator.local"),
                "1.0.0",
                listOf(AgentCapabilityId("agent.answer")),
                sha256(digestSeed.toByteArray(StandardCharsets.UTF_8)),
                90,
                1_000,
            )
    }

    private class FakeEvaluator(
        snapshot: AgentEvaluationProviderSnapshot,
        private val store: InMemoryEvaluationStore,
    ) : AgentEvaluationCaseEvaluatorPort {
        var reportedSnapshot: AgentEvaluationProviderSnapshot = snapshot
        var failOnceCase: String? = null
        val callsByCase = linkedMapOf<String, Int>()
        val operationsByCase = linkedMapOf<String, MutableList<String>>()

        override fun snapshot(): AgentEvaluationProviderSnapshot = reportedSnapshot

        override fun evaluate(
            request: AgentEvaluationCaseExecutionRequest,
        ): CompletionStage<AgentEvaluationCaseExecutionResult> {
            check(!store.inTransaction()) { "Evaluator ran inside a store transaction." }
            val caseId = request.case.caseId.value
            callsByCase[caseId] = (callsByCase[caseId] ?: 0) + 1
            operationsByCase.getOrPut(caseId) { arrayListOf() }.add(request.operationDigest)
            if (failOnceCase == caseId) {
                failOnceCase = null
                return CompletableFuture<AgentEvaluationCaseExecutionResult>().also { future ->
                    future.completeExceptionally(TimeoutException("raw provider failure must not persist"))
                }
            }
            val observation = AgentEvaluationLatencyObservation(
                request.context,
                request.requestedAt,
                request.requestedAt,
                500,
            )
            return CompletableFuture.completedFuture(
                AgentEvaluationCaseExecutionResult(
                    request.requestId,
                    listOf(observation),
                    AgentEvaluationDiagnostic(
                        AgentEvaluationDiagnosticStatus.READY,
                        null,
                        reportedSnapshot.providerId,
                        request.case.capabilityId,
                        reportedSnapshot.snapshotDigest,
                        request.requestedAt,
                    ),
                    request.requestedAt,
                ),
            )
        }
    }

    private class InMemoryEvaluationStore : AgentEvaluationDurableStore {
        private val states = linkedMapOf<AgentEvaluationRunKey, AgentEvaluationRunState>()
        private val idempotency = linkedMapOf<AgentEvaluationIdempotencyScope, AgentEvaluationRunKey>()
        private val transaction = ThreadLocal.withInitial { false }
        private var fencingToken = 0L
        var createCount: Int = 0
            private set

        fun inTransaction(): Boolean = transaction.get()

        override fun create(initialState: AgentEvaluationRunState): AgentEvaluationCreateResult = transaction {
            val existingKey = idempotency[initialState.idempotencyScope]
            if (existingKey != null) {
                AgentEvaluationCreateResult(false, requireNotNull(states[existingKey]))
            } else {
                states[initialState.key()] = initialState
                idempotency[initialState.idempotencyScope] = initialState.key()
                createCount++
                AgentEvaluationCreateResult(true, initialState)
            }
        }

        override fun load(key: AgentEvaluationRunKey): AgentEvaluationRunState? = transaction { states[key] }

        override fun findByIdempotency(scope: AgentEvaluationIdempotencyScope): AgentEvaluationRunState? = transaction {
            idempotency[scope]?.let(states::get)
        }

        override fun claim(claim: AgentEvaluationLeaseClaim): AgentEvaluationLeaseClaimResult = transaction {
            val current = states[claim.key]
                ?: return@transaction AgentEvaluationLeaseClaimResult(AgentEvaluationLeaseClaimStatus.MISSING, null)
            if (current.status.isTerminal()) {
                return@transaction AgentEvaluationLeaseClaimResult(AgentEvaluationLeaseClaimStatus.TERMINAL, current)
            }
            if (current.lease?.isCurrent(claim.requestedAt) == true) {
                return@transaction AgentEvaluationLeaseClaimResult(AgentEvaluationLeaseClaimStatus.BUSY, current)
            }
            val lease = AgentEvaluationLease(
                claim.leaseId,
                claim.ownerId,
                ++fencingToken,
                claim.requestedAt,
                claim.requestedAt + claim.leaseDurationMillis,
            )
            val claimed = current.claimed(lease, claim.requestedAt)
            states[claim.key] = claimed
            AgentEvaluationLeaseClaimResult(AgentEvaluationLeaseClaimStatus.ACQUIRED, claimed)
        }

        override fun heartbeat(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult = commit(commit)
        override fun complete(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult = commit(commit)
        override fun fail(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult = commit(commit)
        override fun cancel(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult = commit(commit)

        override fun recoverable(atTime: Long, limit: Int): List<AgentEvaluationRunState> = transaction {
            states.values.filter { state ->
                !state.status.isTerminal() && (state.lease == null || !state.lease.isCurrent(atTime))
            }.take(limit)
        }

        private fun commit(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult = transaction {
            val current = states[commit.key]
                ?: return@transaction AgentEvaluationCommitResult(AgentEvaluationCommitStatus.MISSING, null)
            if (current.stateVersion != commit.expectedStateVersion) {
                return@transaction AgentEvaluationCommitResult(AgentEvaluationCommitStatus.VERSION_CONFLICT, current)
            }
            if (commit.expectedLease != null && current.lease?.matches(commit.expectedLease) != true) {
                return@transaction AgentEvaluationCommitResult(AgentEvaluationCommitStatus.LEASE_LOST, current)
            }
            states[commit.key] = commit.nextState
            AgentEvaluationCommitResult(AgentEvaluationCommitStatus.APPLIED, commit.nextState)
        }

        private fun <T> transaction(block: () -> T): T {
            check(!transaction.get()) { "Nested evaluation store transaction." }
            transaction.set(true)
            return try {
                block()
            } finally {
                transaction.set(false)
            }
        }
    }

    private class MutableClock(private var time: Long) : AgentRuntimeClock {
        override fun currentTimeMillis(): Long = time
        fun advance(millis: Long) {
            time += millis
        }
    }

    private class SequenceIds : AgentRuntimeIdGenerator {
        private val sequence = AtomicInteger()
        override fun nextId(purpose: String): Identifier = id("$purpose-${sequence.incrementAndGet()}")
    }

    companion object {
        private fun evaluationCase(number: Int, payloadDigest: String): AgentEvaluationCase = AgentEvaluationCase(
            id("case-$number"),
            id("fixture-$number"),
            AgentCapabilityId("agent.answer"),
            payloadDigest,
            AgentEvaluationExpectedOutcome(maximumLatencyMillis = 500),
            listOf("latency"),
        )

        private fun id(value: String): Identifier = Identifier(value)

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
