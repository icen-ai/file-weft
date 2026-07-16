package ai.icen.fw.testkit.capacity

class CapacityPersistenceContractBehaviorTest : CapacityPersistenceContractTest() {
    override fun newHarness(): CapacityProviderContractHarness = CapacityProviderContractHarness.inMemory()
}
