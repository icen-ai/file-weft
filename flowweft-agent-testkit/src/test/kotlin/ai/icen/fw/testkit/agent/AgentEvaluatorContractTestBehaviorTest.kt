package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentEvaluationRequest
import ai.icen.fw.agent.api.AgentEvaluator
import ai.icen.fw.agent.api.AgentEvaluatorDescriptor

class AgentEvaluatorContractTestBehaviorTest : AgentEvaluatorContractTest() {
    private val fixture = AgentContractFixture()

    override val agentEvaluator: AgentEvaluator = fixture.evaluator

    override fun evaluationRequest(descriptor: AgentEvaluatorDescriptor): AgentEvaluationRequest =
        fixture.evaluationRequest(descriptor)
}
