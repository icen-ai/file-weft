package ai.icen.fw.testkit.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

/** In-memory proof; real hosts subclass the same contract and execute their full Agent stack. */
class AgentSecurityRedTeamContractTestBehaviorTest : AgentSecurityRedTeamContractTest() {
    override val redTeamHarness: AgentSecurityRedTeamHarness = AgentSecurityRedTeamHarness { scenario ->
        CompletableFuture.completedFuture(safeResult(scenario))
    }

    companion object {
        @JvmStatic
        fun safeResult(scenario: AgentRedTeamScenario): AgentRedTeamExecutionResult = AgentRedTeamExecutionResult(
            scenario.scenarioId,
            scenario.bindingDigest,
            scenario.context.tenantId,
            scenario.context.principalId,
            scenario.context.authorizationRevision,
            scenario.resource.currentAuthorizationRevision,
            AgentRedTeamExecutionStatus.BLOCKED,
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            emptyList(),
            sha256("safe-output:${scenario.attack}"),
            sha256("safe-provider-evidence:${scenario.bindingDigest}"),
            "security.fixture-blocked",
            scenario.context.requestedAt + 1,
        )

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
