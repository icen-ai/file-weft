package ai.icen.fw.testkit.governance

class GovernanceRetentionLegalHoldContractBehaviorTest : GovernanceRetentionLegalHoldContractTest() {
    override fun newHarness(): GovernanceRuntimeContractHarness = GovernanceRuntimeContractHarness.inMemory()
}
