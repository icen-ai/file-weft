package ai.icen.fw.testkit.agent;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentSecurityRedTeamJavaCompatibilityTest {

    @Test
    void javaCanImplementTheHostHarnessAndSubclassTheContract() {
        AgentSecurityRedTeamHarness harness = scenario -> CompletableFuture.completedFuture(
            AgentSecurityRedTeamContractTestBehaviorTest.safeResult(scenario)
        );
        AgentSecurityRedTeamContractTest contract = new AgentSecurityRedTeamContractTest() {
            @Override
            protected AgentSecurityRedTeamHarness getRedTeamHarness() {
                return harness;
            }
        };
        AgentRedTeamFixtureCatalog fixtures = AgentSecurityRedTeamFixtures.standard();
        AgentRedTeamScenario scenario = fixtures.scenario(AgentRedTeamAttack.APPROVAL_ARGUMENT_REPLAY);
        AgentRedTeamExecutionResult result = harness.execute(scenario).toCompletableFuture().join();

        AgentSecurityRedTeamAssertions.assertSafe(scenario, result);
        assertNotNull(contract);
        assertEquals(AgentRedTeamApprovalReplayKind.ARGUMENTS, scenario.getApprovalReplay().getKind());
        assertEquals(AgentRedTeamAttack.values().length, fixtures.getScenarios().size());
    }
}
