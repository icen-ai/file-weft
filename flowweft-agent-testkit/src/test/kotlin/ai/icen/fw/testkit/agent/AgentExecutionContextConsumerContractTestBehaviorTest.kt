package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentExecutionContextConsumer
import ai.icen.fw.agent.api.AuthorizedToolInvocation

class AgentExecutionContextConsumerContractTestBehaviorTest : AgentExecutionContextConsumerContractTest() {
    private val fixture = AgentContractFixture()

    override val executionContextConsumer: AgentExecutionContextConsumer = fixture.executionContextConsumer

    override fun authorizedInvocation(): AuthorizedToolInvocation = fixture.newAuthorizedInvocation()
}
