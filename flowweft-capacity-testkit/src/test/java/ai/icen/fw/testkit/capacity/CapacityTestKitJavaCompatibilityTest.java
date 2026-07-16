package ai.icen.fw.testkit.capacity;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapacityTestKitJavaCompatibilityTest {
    @Test
    void exposesJavaFriendlyEntryPointsAndSubclassHooks() throws Exception {
        DeterministicCapacityClock clock = CapacityTestKit.clock(100_000L);
        DeterministicCapacityIds ids = CapacityTestKit.identifiers();
        CapacityHierarchyFixture hierarchy = CapacityTestKit.hierarchy();
        CapacityProviderContractHarness provider = CapacityTestKit.inMemoryProviderHarness();
        CapacityRuntimeContractHarness runtime = CapacityTestKit.inMemoryRuntimeHarness();

        assertEquals(100_000L, clock.currentTimeMillis());
        assertEquals(0L, ids.currentSequence());
        assertNotNull(hierarchy.resolution(hierarchy.getNowEpochMilli()));
        assertNotNull(provider.getProbe());
        assertNotNull(runtime.getRuntime());
        assertEquals(64, CapacityTestKit.digest('a').length());
        assertEquals(64, CapacityTestKit.sha256("java-contract").length());

        assertTrue(Modifier.isStatic(
            CapacityTestKit.class.getMethod("inMemoryProviderHarness").getModifiers()
        ));
        assertTrue(Modifier.isStatic(
            CapacityDurableStateAssertions.class
                .getMethod(
                    "assertAdmissionRoundTrip",
                    ai.icen.fw.capacity.api.CapacityAdmissionDecision.class,
                    ai.icen.fw.capacity.runtime.CapacityOutcomeReconciliationEvidence.class
                )
                .getModifiers()
        ));
        assertNotNull(ProviderContract.class.getDeclaredMethod("newHarness"));
        assertNotNull(RuntimeContract.class.getDeclaredMethod("newHarness"));
        assertNotNull(PersistenceContract.class.getDeclaredMethod("newHarness"));
    }

    private static UnsupportedOperationException fixtureOnly() {
        return new UnsupportedOperationException("Compilation fixture only");
    }

    private static final class ProviderContract extends CapacityProviderContractTest {
        @Override protected CapacityProviderContractHarness newHarness() { throw fixtureOnly(); }
    }

    private static final class RuntimeContract extends CapacityRuntimeContractTest {
        @Override protected CapacityRuntimeContractHarness newHarness() { throw fixtureOnly(); }
    }

    private static final class PersistenceContract extends CapacityPersistenceContractTest {
        @Override protected CapacityProviderContractHarness newHarness() { throw fixtureOnly(); }
    }
}
