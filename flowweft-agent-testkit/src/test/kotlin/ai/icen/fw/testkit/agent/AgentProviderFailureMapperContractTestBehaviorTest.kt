package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentProviderFailureMapper
import ai.icen.fw.agent.api.ProviderId

class AgentProviderFailureMapperContractTestBehaviorTest : AgentProviderFailureMapperContractTest() {
    private val fixture = AgentContractFixture()

    override val failureMapper: AgentProviderFailureMapper = fixture.failureMapper

    override fun providerId(): ProviderId = AgentContractFixture.TOOL_PROVIDER_ID
}
