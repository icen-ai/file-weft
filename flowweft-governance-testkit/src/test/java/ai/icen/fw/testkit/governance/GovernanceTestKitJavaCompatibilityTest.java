package ai.icen.fw.testkit.governance;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceTestKitJavaCompatibilityTest {
    @Test
    void exposesJavaFriendlyEntryPointsAndContractSubclassHooks() throws Exception {
        DeterministicGovernanceClock clock = GovernanceTestKit.clock(100_000L);
        DeterministicGovernanceIds ids = GovernanceTestKit.identifiers();
        GovernanceRepositoryBundle repositories = GovernanceTestKit.inMemoryRepositories();
        MutationCountingGovernanceProviderProbe provider = GovernanceTestKit.mutationCountingProvider();
        GovernanceRuntimeContractHarness harness = GovernanceTestKit.inMemoryHarness();

        assertEquals(100_000L, clock.nowEpochMilli());
        assertEquals(0L, ids.currentSequence());
        assertNotNull(repositories.getDeletion());
        assertNotNull(repositories.getOutbox());
        assertNotNull(provider.registry());
        assertNotNull(harness.getPlanning());
        assertEquals(64, GovernanceTestKit.digest('a').length());

        assertTrue(Modifier.isStatic(GovernanceTestKit.class.getMethod("inMemoryHarness").getModifiers()));
        assertTrue(Modifier.isStatic(
            GovernanceDurableStateAssertions.class
                .getMethod("assertRunRoundTrip", ai.icen.fw.governance.runtime.GovernanceDeletionRun.class)
                .getModifiers()
        ));
        assertNotNull(RetentionContract.class.getDeclaredMethod("newHarness"));
        assertNotNull(RecoveryContract.class.getDeclaredMethod("newHarness"));
        assertNotNull(RepositoryContract.class.getDeclaredMethod("newRepositories"));
    }

    private static UnsupportedOperationException fixtureOnly() {
        return new UnsupportedOperationException("Compilation fixture only");
    }

    private static final class RetentionContract extends GovernanceRetentionLegalHoldContractTest {
        @Override protected GovernanceRuntimeContractHarness newHarness() { throw fixtureOnly(); }
    }

    private static final class RecoveryContract extends GovernanceDeletionRecoveryContractTest {
        @Override protected GovernanceRuntimeContractHarness newHarness() { throw fixtureOnly(); }
    }

    private static final class RepositoryContract extends GovernanceRepositoryContractTest {
        @Override protected GovernanceRepositoryBundle newRepositories() { throw fixtureOnly(); }
    }
}
