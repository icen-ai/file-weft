package ai.icen.fw.testkit.agent;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentProtocolAdapterSecurityJavaCompatibilityTest {

    @Test
    void javaCanImplementTheProtocolHarnessAndSubclassTheContract() {
        AgentProtocolAdapterSecurityHarness harness = scenario -> CompletableFuture.completedFuture(
            AgentProtocolAdapterSecurityContractTestBehaviorTest.safeResult(scenario)
        );
        AgentProtocolAdapterSecurityContractTest contract = new AgentProtocolAdapterSecurityContractTest() {
            @Override
            protected AgentProtocolAdapterSecurityHarness getProtocolHarness() {
                return harness;
            }
        };
        AgentProtocolFixtureCatalog fixtures = AgentProtocolConformanceFixtures.standard();
        AgentProtocolConformanceScenario scenario = fixtures.scenario(AgentProtocolSecurityAttack.A2A_BOUND_CANCELLATION);
        AgentProtocolConformanceResult result = harness.execute(scenario).toCompletableFuture().join();

        AgentProtocolConformanceAssertions.assertConformant(scenario, result);
        assertNotNull(contract);
        assertEquals(AgentProtocolCancellationOutcome.CONFIRMED, result.getCancellationOutcome());
        assertEquals(AgentProtocolBaselines.A2A_VERSION, scenario.getApprovedProfile().getVersion());
        assertEquals(AgentProtocolSecurityAttack.values().length, fixtures.getScenarios().size());
    }
}
