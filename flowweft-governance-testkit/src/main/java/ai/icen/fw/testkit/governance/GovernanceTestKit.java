package ai.icen.fw.testkit.governance;

/** Java-first entry point for the most common deterministic governance fixtures. */
public final class GovernanceTestKit {
    private GovernanceTestKit() {
    }

    public static DeterministicGovernanceClock clock(long epochMilli) {
        return DeterministicGovernanceClock.startingAt(epochMilli);
    }

    public static DeterministicGovernanceIds identifiers() {
        return DeterministicGovernanceIds.create();
    }

    public static GovernanceRepositoryBundle inMemoryRepositories() {
        return GovernanceRepositoryBundle.inMemory();
    }

    public static MutationCountingGovernanceProviderProbe mutationCountingProvider() {
        return MutationCountingGovernanceProviderProbe.create();
    }

    public static GovernanceRuntimeContractHarness inMemoryHarness() {
        return GovernanceRuntimeContractHarness.inMemory();
    }

    public static String digest(char character) {
        return GovernanceContractAssertions.digest(character);
    }
}
