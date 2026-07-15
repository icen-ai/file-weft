package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentEvaluationObserver
import ai.icen.fw.agent.api.AgentEvaluationRequest
import ai.icen.fw.agent.api.AgentEvaluator
import ai.icen.fw.agent.api.AgentEvaluatorDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Duration

/** Reusable diagnostic evaluator contract; evaluator output never becomes authorization evidence. */
abstract class AgentEvaluatorContractTest {
    protected abstract val agentEvaluator: AgentEvaluator

    protected abstract fun evaluationRequest(descriptor: AgentEvaluatorDescriptor): AgentEvaluationRequest

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    protected open fun cancellation(request: AgentEvaluationRequest): AgentCancellation =
        AgentCancellation("testkit-contract-cancelled", request.requestedAt)

    @Test
    fun `keeps criteria stable and returns an exactly bound diagnostic result`() {
        val descriptor = agentEvaluator.descriptor()
        val replayedDescriptor = agentEvaluator.descriptor()
        assertEquals(descriptor.providerId, replayedDescriptor.providerId)
        assertEquals(descriptor.criteria, replayedDescriptor.criteria, "Evaluator criteria must be stable.")
        assertEquals(descriptor.maximumCitations, replayedDescriptor.maximumCitations)
        val request = evaluationRequest(descriptor)
        request.requireSupportedBy(descriptor)

        val call = agentEvaluator.start(request, AgentEvaluationObserver.NOOP)
        assertNotNull(call, "Agent evaluator must return a non-null call handle.")
        assertEquals(request.requestId, requireNotNull(call).requestId())
        val result = AgentContractAssertions.awaitStage(
            call.completion(),
            asynchronousTimeout(),
            "Agent evaluation completion",
        )

        result.requireValidFor(request, descriptor)
        AgentContractAssertions.awaitStage(
            call.cancel(cancellation(request)),
            asynchronousTimeout(),
            "Agent evaluation cancellation",
        )
    }
}
