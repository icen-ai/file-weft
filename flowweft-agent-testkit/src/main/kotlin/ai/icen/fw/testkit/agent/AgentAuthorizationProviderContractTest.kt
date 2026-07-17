package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentAtomicDispatchAuthorizationProvider
import ai.icen.fw.agent.api.AgentAuthorizationProvider
import ai.icen.fw.agent.api.AgentAuthorizationRequest
import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceConsumption
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceRequest
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reusable contract for a current, trusted Agent authorization provider.
 *
 * The fixture request must be created from trusted runtime coordinates. This suite never derives
 * tenant, principal, resource, arguments, or purpose from model output and never manufactures an
 * executable tool invocation around the authorization gate.
 */
abstract class AgentAuthorizationProviderContractTest {
    protected abstract val authorizationProvider: AgentAuthorizationProvider

    protected abstract fun authorizationRequest(): AgentAuthorizationRequest

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(10)

    protected open fun cancellation(request: AgentAuthorizationRequest): AgentCancellation =
        AgentCancellation("testkit-contract-cancelled", request.requestedAt)

    @Test
    fun `returns an exact decision through a non-null cancellable handle`() {
        val providerId = authorizationProvider.providerId()
        assertEquals(providerId, authorizationProvider.providerId(), "Authorization provider identity must be stable.")
        val request = authorizationRequest()
        assertEquals(
            request.authorizationProviderId,
            providerId,
            "The trusted authorization request must target the provider under test.",
        )

        val call = authorizationProvider.start(request)
        assertNotNull(call, "Authorization provider must return a non-null call handle.")
        val decision = AgentContractAssertions.awaitStage(
            requireNotNull(call).completion(),
            asynchronousTimeout(),
            "Agent authorization completion",
        )

        decision.requireValidFor(request, decision.decidedAt)
        assertEquals(providerId, decision.providerId)
        AgentContractAssertions.awaitStage(
            call.cancel(cancellation(request)),
            asynchronousTimeout(),
            "Agent authorization cancellation",
        )
    }
}

/** Adds the mandatory linearizable dispatch fence contract used immediately before tool side effects. */
abstract class AgentAtomicDispatchAuthorizationProviderContractTest :
    AgentAuthorizationProviderContractTest() {
    protected abstract override val authorizationProvider: AgentAtomicDispatchAuthorizationProvider

    /** A fresh, unconsumed, fully authorized dispatch fence for this test invocation. */
    protected abstract fun dispatchFenceRequest(): AgentDispatchAuthorizationFenceRequest

    /** Number of simultaneous callers used to prove a unique dispatch-fence winner. */
    protected open fun concurrentDispatchAttempts(): Int = 8

    @Test
    fun `consumes one dispatch fence once and returns bound replay evidence`() {
        val request = dispatchFenceRequest()
        val first = AgentContractAssertions.awaitStage(
            authorizationProvider.consumeDispatchFence(request),
            asynchronousTimeout(),
            "Agent dispatch-fence consumption",
        )
        val replay = AgentContractAssertions.awaitStage(
            authorizationProvider.consumeDispatchFence(request),
            asynchronousTimeout(),
            "Agent dispatch-fence replay",
        )

        assertEquals(AgentDispatchAuthorizationFenceStatus.CONSUMED, first.status)
        assertEquals(AgentDispatchAuthorizationFenceStatus.REPLAYED, replay.status)
        assertSameDispatchEvidence(request, first, replay)
    }

    @Test
    fun `linearizes concurrent dispatch fence consumption to one winner`() {
        val request = dispatchFenceRequest()
        val attempts = concurrentDispatchAttempts()
        require(attempts >= 2) { "Concurrent dispatch attempts must be at least two." }
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(attempts)
        val timeoutMillis = AgentContractAssertions.timeoutMillis(
            asynchronousTimeout(),
            "Agent dispatch-fence concurrency timeout",
        )
        try {
            val futures = (0 until attempts).map {
                executor.submit<AgentDispatchAuthorizationFenceConsumption> {
                    ready.countDown()
                    check(start.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        "Timed out waiting to start the Agent dispatch-fence race."
                    }
                    AgentContractAssertions.awaitStage(
                        authorizationProvider.consumeDispatchFence(request),
                        asynchronousTimeout(),
                        "Concurrent Agent dispatch-fence consumption",
                    )
                }
            }
            assertTrue(
                ready.await(timeoutMillis, TimeUnit.MILLISECONDS),
                "Concurrent Agent dispatch-fence callers did not become ready.",
            )
            start.countDown()
            val results = futures.map { future -> future.get(timeoutMillis, TimeUnit.MILLISECONDS) }
            val winner = results.single { result ->
                result.status == AgentDispatchAuthorizationFenceStatus.CONSUMED
            }
            assertEquals(
                attempts - 1,
                results.count { result -> result.status == AgentDispatchAuthorizationFenceStatus.REPLAYED },
                "Every losing dispatch-fence caller must receive replay evidence.",
            )
            results.forEach { result ->
                result.requireMatches(request, result.consumedAt)
                assertEquals(winner.receiptId, result.receiptId)
                assertEquals(winner.requestBindingDigest, result.requestBindingDigest)
                assertEquals(winner.providerRevision, result.providerRevision)
                assertEquals(winner.consumedAt, result.consumedAt)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertSameDispatchEvidence(
        request: AgentDispatchAuthorizationFenceRequest,
        first: AgentDispatchAuthorizationFenceConsumption,
        replay: AgentDispatchAuthorizationFenceConsumption,
    ) {
        first.requireMatches(request, first.consumedAt)
        replay.requireMatches(request, replay.consumedAt)
        assertEquals(first.receiptId, replay.receiptId, "A replay must reconcile the durable winning receipt.")
        assertEquals(first.requestBindingDigest, replay.requestBindingDigest)
        assertEquals(first.providerRevision, replay.providerRevision)
        assertEquals(first.consumedAt, replay.consumedAt)
    }
}
