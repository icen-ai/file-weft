package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentPolicyProposal
import ai.icen.fw.agent.api.AgentPolicyProvider

class AgentPolicyProviderContractTestBehaviorTest : AgentPolicyProviderContractTest() {
    private val fixture = AgentContractFixture()

    override val policyProvider: AgentPolicyProvider = fixture.policyProvider

    override fun policyProposal(): AgentPolicyProposal = fixture.newPolicyProposal()
}
