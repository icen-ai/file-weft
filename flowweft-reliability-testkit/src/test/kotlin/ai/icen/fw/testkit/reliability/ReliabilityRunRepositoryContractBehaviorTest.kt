package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.runtime.ReliabilityRunRepository

class ReliabilityRunRepositoryContractBehaviorTest : ReliabilityRunRepositoryContractTest() {
    override fun newRepository(): ReliabilityRunRepository = InMemoryReliabilityRunRepository.create()
}
