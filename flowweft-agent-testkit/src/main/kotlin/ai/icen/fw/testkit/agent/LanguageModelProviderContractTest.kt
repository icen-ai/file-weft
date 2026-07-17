package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.LanguageModelDescriptor
import ai.icen.fw.agent.api.LanguageModelObserver
import ai.icen.fw.agent.api.LanguageModelProvider
import ai.icen.fw.agent.api.LanguageModelRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** Reusable provider-neutral model contract with exact descriptor and response binding checks. */
abstract class LanguageModelProviderContractTest {
    protected abstract val languageModelProvider: LanguageModelProvider

    /** A successful request for the exact descriptor passed to this hook. */
    protected abstract fun modelRequest(descriptor: LanguageModelDescriptor): LanguageModelRequest

    /** Capabilities this configured provider/model is expected to expose to selection. */
    protected open fun requiredCapabilities(): Set<AgentCapabilityId> = emptySet()

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    protected open fun cancellation(request: LanguageModelRequest): AgentCancellation =
        AgentCancellation("testkit-contract-cancelled", request.requestedAt)

    @Test
    fun `keeps its descriptor stable and returns an exactly bound response`() {
        val descriptor = languageModelProvider.descriptor()
        val replayedDescriptor = languageModelProvider.descriptor()
        assertStableDescriptor(descriptor, replayedDescriptor)
        assertTrue(
            descriptor.capabilities.containsAll(requiredCapabilities()),
            "Language model descriptor is missing a capability required by the contract fixture.",
        )
        val request = modelRequest(descriptor)
        request.requireSupportedBy(descriptor)

        val call = languageModelProvider.start(request, LanguageModelObserver.NOOP)
        assertNotNull(call, "Language model provider must return a non-null call handle.")
        val response = AgentContractAssertions.awaitStage(
            requireNotNull(call).completion(),
            asynchronousTimeout(),
            "Language model completion",
        )

        response.requireValidFor(request, descriptor)
        assertEquals(64, response.bindingDigest.length, "Language model response must retain a SHA-256 binding.")
        AgentContractAssertions.awaitStage(
            call.cancel(cancellation(request)),
            asynchronousTimeout(),
            "Language model cancellation",
        )
    }

    private fun assertStableDescriptor(first: LanguageModelDescriptor, second: LanguageModelDescriptor) {
        assertEquals(first.providerId, second.providerId)
        assertEquals(first.modelId, second.modelId)
        assertEquals(first.descriptorDigest, second.descriptorDigest, "Model descriptor digest must be stable.")
        assertEquals(first.capabilities, second.capabilities)
        assertEquals(first.maximumInputTokens, second.maximumInputTokens)
        assertEquals(first.maximumOutputTokens, second.maximumOutputTokens)
        assertEquals(first.maximumCostMicros, second.maximumCostMicros)
        assertEquals(first.maximumDurationMillis, second.maximumDurationMillis)
    }
}
