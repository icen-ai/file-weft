package ai.icen.fw.testkit.capacity

class CapacityProviderContractBehaviorTest : CapacityProviderContractTest() {
    override fun newHarness(): CapacityProviderContractHarness = CapacityProviderContractHarness.inMemory()
}
