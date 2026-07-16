package ai.icen.fw.testkit.capacity

class CapacityRuntimeContractBehaviorTest : CapacityRuntimeContractTest() {
    override fun newHarness(): CapacityRuntimeContractHarness = CapacityRuntimeContractHarness.inMemory()
}
