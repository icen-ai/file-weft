package ai.icen.fw.testkit.reliability;

import ai.icen.fw.reliability.runtime.ReliabilityRunRepository;
import ai.icen.fw.reliability.runtime.ReliabilitySloSchedule;
import ai.icen.fw.reliability.runtime.ReliabilitySloScheduleRepository;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliabilityTestKitJavaCompatibilityTest {
    @Test
    void exposesJavaFriendlyFactoriesAndContractSubclassHooks() throws Exception {
        DeterministicReliabilityClock clock = DeterministicReliabilityClock.startingAt(100_000L);
        DeterministicReliabilityIds ids = DeterministicReliabilityIds.create();
        ReliabilityContractTopology single = ReliabilityTopologyFixtures.singleDatabase("tenant-java");
        ReliabilityContractTopology multi = ReliabilityTopologyFixtures.multiComponent("tenant-java", 100_000L);
        StrictReliabilityAuthorizationFixture authorization =
            StrictReliabilityAuthorizationFixture.forTenant("tenant-java");
        ReliabilityRuntimeContractHarness harness = ReliabilityRuntimeContractHarness.inMemory(single);
        ReliabilitySloContractScenario slo = ReliabilitySloContractScenario.missingData("tenant-java");

        assertEquals(100_000L, clock.nowEpochMilli());
        assertEquals(0L, ids.currentSequence());
        assertEquals(1, single.getComponents().size());
        assertEquals(3, multi.getComponents().size());
        assertEquals("tenant-java", authorization.getTenantId());
        assertNotNull(harness.getSubmission());
        assertNotNull(slo.getSchedule());
        assertEquals(64, ReliabilityContractAssertions.digest('a').length());

        assertStaticFactory(DeterministicReliabilityClock.class, "startingAt", long.class);
        assertStaticFactory(DeterministicReliabilityIds.class, "create");
        assertStaticFactory(ReliabilityTopologyFixtures.class, "singleDatabase", String.class);
        assertStaticFactory(ReliabilityRuntimeContractHarness.class, "inMemory", ReliabilityContractTopology.class);
        assertStaticFactory(InMemoryReliabilityRunRepository.class, "create");
        assertStaticFactory(InMemoryReliabilitySloRepository.class, "containing", ReliabilitySloSchedule.class);
        assertTrue(Modifier.isStatic(
            ReliabilityDurableStateAssertions.class
                .getMethod("assertRunRoundTrip", ai.icen.fw.reliability.runtime.ReliabilityRun.class)
                .getModifiers()
        ));

        assertNotNull(RecoveryContract.class.getDeclaredMethod("newHarness"));
        assertNotNull(RunRepositoryContract.class.getDeclaredMethod("newRepository"));
        assertNotNull(SloRepositoryContract.class.getDeclaredMethod("newRepository", ReliabilitySloSchedule.class));
    }

    private static void assertStaticFactory(Class<?> type, String name, Class<?>... parameters) throws Exception {
        assertTrue(Modifier.isStatic(type.getMethod(name, parameters).getModifiers()));
    }

    private static UnsupportedOperationException fixtureOnly() {
        return new UnsupportedOperationException("Compilation fixture only");
    }

    private static final class RecoveryContract extends ReliabilityRuntimeRecoveryContractTest {
        @Override protected ReliabilityRuntimeContractHarness newHarness() { throw fixtureOnly(); }
    }

    private static final class RunRepositoryContract extends ReliabilityRunRepositoryContractTest {
        @Override protected ReliabilityRunRepository newRepository() { throw fixtureOnly(); }
    }

    private static final class SloRepositoryContract extends ReliabilitySloFailClosedContractTest {
        @Override protected ReliabilitySloScheduleRepository newRepository(ReliabilitySloSchedule initial) {
            throw fixtureOnly();
        }
    }
}
