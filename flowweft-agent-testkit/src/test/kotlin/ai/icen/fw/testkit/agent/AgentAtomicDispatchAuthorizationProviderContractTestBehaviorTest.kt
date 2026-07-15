package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentAtomicDispatchAuthorizationProvider
import ai.icen.fw.agent.api.AgentAuthorizationRequest
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceRequest

class AgentAtomicDispatchAuthorizationProviderContractTestBehaviorTest :
    AgentAtomicDispatchAuthorizationProviderContractTest() {
    private val fixture = AgentContractFixture()

    override val authorizationProvider: AgentAtomicDispatchAuthorizationProvider = fixture.authorizationProvider

    override fun authorizationRequest(): AgentAuthorizationRequest = fixture.newAuthorizationRequest()

    override fun dispatchFenceRequest(): AgentDispatchAuthorizationFenceRequest = fixture.newDispatchFenceRequest()
}
