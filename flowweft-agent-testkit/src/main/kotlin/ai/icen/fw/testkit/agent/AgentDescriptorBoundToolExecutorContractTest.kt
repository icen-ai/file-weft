package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentDescriptorBoundToolExecutor
import ai.icen.fw.agent.api.AgentExecutableToolInvocation
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolObserver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Contract for the production tool-executor boundary.
 *
 * Implementations must supply an invocation produced by the real authorization, one-time execution
 * context and dispatch-fence chain. The TestKit intentionally has no shortcut that forges or skips
 * those gates.
 */
abstract class AgentDescriptorBoundToolExecutorContractTest {
    protected abstract val toolExecutor: AgentDescriptorBoundToolExecutor

    protected abstract fun toolDescriptor(): AgentToolDescriptor

    protected abstract fun executableInvocation(): AgentExecutableToolInvocation

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    protected open fun cancellation(invocation: AgentExecutableToolInvocation): AgentCancellation =
        AgentCancellation("testkit-contract-cancelled", invocation.preparedAt)

    @Test
    fun `executes only its exact descriptor-bound invocation and returns a bounded result`() {
        val descriptor = toolDescriptor()
        val replayedDescriptor = toolDescriptor()
        assertEquals(descriptor.descriptorDigest, replayedDescriptor.descriptorDigest)
        assertEquals(descriptor.providerId, toolExecutor.providerId())
        assertEquals(descriptor.toolId, toolExecutor.toolId())
        assertEquals(descriptor.descriptorDigest, toolExecutor.descriptorDigest())
        val executable = executableInvocation()
        executable.invocation.proposal.requireMatches(descriptor)
        executable.invocation.let { invocation ->
            assertEquals(descriptor.providerId, invocation.providerId)
            assertEquals(descriptor.toolId, invocation.toolId)
            assertEquals(descriptor.descriptorDigest, invocation.descriptorDigest)
        }

        val call = toolExecutor.start(executable, AgentToolObserver.NOOP)
        assertNotNull(call, "Agent tool executor must return a non-null call handle.")
        assertEquals(executable.invocation.invocationId, requireNotNull(call).invocationId())
        val result = AgentContractAssertions.awaitStage(
            call.completion(),
            asynchronousTimeout(),
            "Agent tool completion",
        )

        result.requireBindingIntact()
        assertEquals(executable.invocation.invocationId, result.invocationId)
        assertTrue(
            result.completedAt in executable.preparedAt..executable.invocation.deadlineAt,
            "Agent tool result must complete inside the authorized execution window.",
        )
        assertTrue(
            result.canonicalPayloadSizeBytes <= descriptor.maximumResultBytes.toLong(),
            "Agent tool result exceeds the descriptor's hard byte limit.",
        )
        assertTrue(
            result.usage.costMicros <= executable.maximumCostMicros,
            "Agent tool usage exceeds its conservative cost reservation.",
        )
        assertTrue(
            result.usage.durationMillis <= executable.maximumDurationMillis,
            "Agent tool usage exceeds its conservative duration reservation.",
        )
        AgentContractAssertions.awaitStage(
            call.cancel(cancellation(executable)),
            asynchronousTimeout(),
            "Agent tool cancellation",
        )
    }
}
