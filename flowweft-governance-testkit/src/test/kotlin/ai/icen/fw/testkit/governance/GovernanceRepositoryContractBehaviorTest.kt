package ai.icen.fw.testkit.governance

class GovernanceRepositoryContractBehaviorTest : GovernanceRepositoryContractTest() {
    override fun newRepositories(): GovernanceRepositoryBundle = GovernanceRepositoryBundle.inMemory()
}
