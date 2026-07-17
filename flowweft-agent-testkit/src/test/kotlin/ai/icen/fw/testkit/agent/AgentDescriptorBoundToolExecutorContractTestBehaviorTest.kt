package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentDescriptorBoundToolExecutor
import ai.icen.fw.agent.api.AgentExecutableToolInvocation
import ai.icen.fw.agent.api.AgentToolDescriptor

class AgentDescriptorBoundToolExecutorContractTestBehaviorTest :
    AgentDescriptorBoundToolExecutorContractTest() {
    private val fixture = AgentContractFixture()

    override val toolExecutor: AgentDescriptorBoundToolExecutor = fixture.toolExecutor

    override fun toolDescriptor(): AgentToolDescriptor = fixture.descriptor

    override fun executableInvocation(): AgentExecutableToolInvocation = fixture.newExecutableInvocation()
}
