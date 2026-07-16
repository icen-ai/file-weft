package ai.icen.fw.testkit.governance

class GovernanceDeletionRecoveryContractBehaviorTest : GovernanceDeletionRecoveryContractTest() {
    override fun newHarness(): GovernanceRuntimeContractHarness = GovernanceRuntimeContractHarness.inMemory()
}
