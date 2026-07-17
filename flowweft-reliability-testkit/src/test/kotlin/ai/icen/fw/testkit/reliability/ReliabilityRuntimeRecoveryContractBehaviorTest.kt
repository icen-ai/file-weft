package ai.icen.fw.testkit.reliability

class ReliabilityRuntimeRecoveryContractBehaviorTest : ReliabilityRuntimeRecoveryContractTest() {
    override fun newHarness(): ReliabilityRuntimeContractHarness = ReliabilityRuntimeContractHarness.inMemory(
        ReliabilityTopologyFixtures.multiComponent("tenant-contract"),
    )
}
