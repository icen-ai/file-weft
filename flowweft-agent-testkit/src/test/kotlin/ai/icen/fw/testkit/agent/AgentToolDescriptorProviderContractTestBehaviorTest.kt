package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentToolDescriptorProvider

class AgentToolDescriptorProviderContractTestBehaviorTest : AgentToolDescriptorProviderContractTest() {
    private val fixture = AgentContractFixture()

    override val toolDescriptorProvider: AgentToolDescriptorProvider = fixture.descriptorProvider

    override fun runContext(): AgentRunContext = fixture.runContext()

    override fun requiredCapabilities(): Set<AgentCapabilityId> = setOf(fixture.capability)
}
