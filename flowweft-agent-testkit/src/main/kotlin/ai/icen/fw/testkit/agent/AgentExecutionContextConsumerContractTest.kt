package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentExecutionContextConsumer
import ai.icen.fw.agent.api.AgentExecutionContextConsumption
import ai.icen.fw.agent.api.AgentExecutionContextConsumptionStatus
import ai.icen.fw.agent.api.AuthorizedToolInvocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Contract for the durable one-time execution-context claim.
 *
 * The invocation fixture must be produced by the real policy, optional approval and execution-time
 * authorization chain. TestKit deliberately does not manufacture an authorized invocation.
 */
abstract class AgentExecutionContextConsumerContractTest {
    protected abstract val executionContextConsumer: AgentExecutionContextConsumer

    /** A fresh authorized invocation whose execution context has not yet been consumed. */
    protected abstract fun authorizedInvocation(): AuthorizedToolInvocation

    protected open fun consumptionTime(invocation: AuthorizedToolInvocation): Long = invocation.startedAt

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(10)

    /** Number of simultaneous callers used to prove a unique execution-context winner. */
    protected open fun concurrentConsumptionAttempts(): Int = 8

    @Test
    fun `claims one execution context once and returns exact replay evidence`() {
        val consumerId = executionContextConsumer.consumerId()
        assertEquals(consumerId, executionContextConsumer.consumerId(), "Execution consumer identity must be stable.")
        val invocation = authorizedInvocation()
        val consumedAt = consumptionTime(invocation)

        val first = AgentContractAssertions.awaitStage(
            executionContextConsumer.consume(invocation, consumedAt),
            asynchronousTimeout(),
            "Agent execution-context claim",
        )
        val replay = AgentContractAssertions.awaitStage(
            executionContextConsumer.consume(invocation, consumedAt),
            asynchronousTimeout(),
            "Agent execution-context replay",
        )

        assertEquals(AgentExecutionContextConsumptionStatus.CLAIMED, first.status)
        assertEquals(AgentExecutionContextConsumptionStatus.REPLAYED, replay.status)
        assertSameConsumptionEvidence(invocation, consumerId, first, replay)
    }

    @Test
    fun `linearizes concurrent execution context claims to one winner`() {
        val consumerId = executionContextConsumer.consumerId()
        val invocation = authorizedInvocation()
        val consumedAt = consumptionTime(invocation)
        val attempts = concurrentConsumptionAttempts()
        require(attempts >= 2) { "Concurrent execution-context attempts must be at least two." }
        val ready = CountDownLatch(attempts)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(attempts)
        val timeoutMillis = AgentContractAssertions.timeoutMillis(
            asynchronousTimeout(),
            "Agent execution-context concurrency timeout",
        )
        try {
            val futures = (0 until attempts).map {
                executor.submit<AgentExecutionContextConsumption> {
                    ready.countDown()
                    check(start.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        "Timed out waiting to start the Agent execution-context race."
                    }
                    AgentContractAssertions.awaitStage(
                        executionContextConsumer.consume(invocation, consumedAt),
                        asynchronousTimeout(),
                        "Concurrent Agent execution-context claim",
                    )
                }
            }
            assertTrue(
                ready.await(timeoutMillis, TimeUnit.MILLISECONDS),
                "Concurrent Agent execution-context callers did not become ready.",
            )
            start.countDown()
            val results = futures.map { future -> future.get(timeoutMillis, TimeUnit.MILLISECONDS) }
            val winner = results.single { result ->
                result.status == AgentExecutionContextConsumptionStatus.CLAIMED
            }
            assertEquals(
                attempts - 1,
                results.count { result -> result.status == AgentExecutionContextConsumptionStatus.REPLAYED },
                "Every losing execution-context caller must receive replay evidence.",
            )
            results.forEach { result ->
                result.requireMatches(invocation, result.consumedAt)
                assertEquals(consumerId, result.consumerId)
                assertEquals(winner.receiptId, result.receiptId)
                assertEquals(winner.logicalInvocationDigest, result.logicalInvocationDigest)
                assertEquals(winner.idempotencyKeyDigest, result.idempotencyKeyDigest)
                assertEquals(winner.consumerRevision, result.consumerRevision)
                assertEquals(winner.consumedAt, result.consumedAt)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun assertSameConsumptionEvidence(
        invocation: AuthorizedToolInvocation,
        consumerId: ai.icen.fw.agent.api.ProviderId,
        first: AgentExecutionContextConsumption,
        replay: AgentExecutionContextConsumption,
    ) {
        first.requireMatches(invocation, first.consumedAt)
        replay.requireMatches(invocation, replay.consumedAt)
        assertEquals(consumerId, first.consumerId)
        assertEquals(consumerId, replay.consumerId)
        assertEquals(first.receiptId, replay.receiptId, "A replay must reconcile the durable winning receipt.")
        assertEquals(first.logicalInvocationDigest, replay.logicalInvocationDigest)
        assertEquals(first.idempotencyKeyDigest, replay.idempotencyKeyDigest)
        assertEquals(first.consumerRevision, replay.consumerRevision)
        assertEquals(first.consumedAt, replay.consumedAt)
    }
}
