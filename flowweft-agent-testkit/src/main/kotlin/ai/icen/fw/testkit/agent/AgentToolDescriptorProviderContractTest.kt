package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentToolCatalog
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolDescriptorProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reusable side-effect-free discovery contract for one trusted Agent tool provider. */
abstract class AgentToolDescriptorProviderContractTest {
    protected abstract val toolDescriptorProvider: AgentToolDescriptorProvider

    /** Context in which at least one tool is intentionally visible to the current principal. */
    protected abstract fun runContext(): AgentRunContext

    protected open fun descriptorUnderTest(catalog: AgentToolCatalog): AgentToolDescriptor =
        catalog.descriptors.first()

    protected open fun requiredCapabilities(): Set<AgentCapabilityId> = emptySet()

    @Test
    fun `discovers a stable provider-bound tool catalog for the same trusted context`() {
        val providerId = toolDescriptorProvider.providerId()
        assertEquals(providerId, toolDescriptorProvider.providerId(), "Tool provider identity must be stable.")
        val context = runContext()
        val first = toolDescriptorProvider.descriptors(context)
        val replay = toolDescriptorProvider.descriptors(context)

        assertEquals(providerId, first.providerId)
        assertEquals(providerId, replay.providerId)
        assertTrue(first.descriptors.isNotEmpty(), "The contract context must expose at least one tool.")
        assertEquals(
            first.descriptors.associate { it.toolId to it.descriptorDigest },
            replay.descriptors.associate { it.toolId to it.descriptorDigest },
            "Tool discovery must be stable for the same trusted context.",
        )
        val descriptor = descriptorUnderTest(first)
        assertEquals(providerId, descriptor.providerId)
        assertTrue(
            descriptor.capabilities.containsAll(requiredCapabilities()),
            "Tool descriptor is missing a capability required by the contract fixture.",
        )
        assertEquals(descriptor, first.find(descriptor.toolId))
        assertEquals(64, descriptor.descriptorDigest.length)
        assertEquals(64, descriptor.schemaDigest.length)
    }
}
