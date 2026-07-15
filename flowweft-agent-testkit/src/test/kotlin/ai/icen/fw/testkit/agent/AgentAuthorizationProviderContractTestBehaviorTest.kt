package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentAuthorizationProvider
import ai.icen.fw.agent.api.AgentAuthorizationRequest

class AgentAuthorizationProviderContractTestBehaviorTest : AgentAuthorizationProviderContractTest() {
    private val fixture = AgentContractFixture()

    override val authorizationProvider: AgentAuthorizationProvider = fixture.authorizationProvider

    override fun authorizationRequest(): AgentAuthorizationRequest = fixture.newAuthorizationRequest()
}
