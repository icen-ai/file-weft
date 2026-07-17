package ai.icen.fw.testkit.capacity;

/** Stable Java 8 entry points for the public capacity contract fixtures. */
public final class CapacityTestKit {
    private CapacityTestKit() {
    }

    public static DeterministicCapacityClock clock(long epochMilli) {
        return DeterministicCapacityClock.startingAt(epochMilli);
    }

    public static DeterministicCapacityIds identifiers() {
        return DeterministicCapacityIds.create();
    }

    public static CapacityHierarchyFixture hierarchy() {
        return CapacityHierarchyFixture.standard();
    }

    public static CapacityProviderContractHarness inMemoryProviderHarness() {
        return CapacityProviderContractHarness.inMemory();
    }

    public static CapacityRuntimeContractHarness inMemoryRuntimeHarness() {
        return CapacityRuntimeContractHarness.inMemory();
    }

    public static String digest(char character) {
        return CapacityContractAssertions.digest(character);
    }

    public static String sha256(String value) {
        return CapacityContractAssertions.sha256(value);
    }
}
