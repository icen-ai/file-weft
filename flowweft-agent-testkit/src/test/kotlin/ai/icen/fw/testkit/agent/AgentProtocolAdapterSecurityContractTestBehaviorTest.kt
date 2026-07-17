package ai.icen.fw.testkit.agent

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture

/** In-memory contract proof; external hosts execute the same suite against their real MCP/A2A adapter. */
class AgentProtocolAdapterSecurityContractTestBehaviorTest : AgentProtocolAdapterSecurityContractTest() {
    override val protocolHarness: AgentProtocolAdapterSecurityHarness = AgentProtocolAdapterSecurityHarness { scenario ->
        CompletableFuture.completedFuture(safeResult(scenario))
    }

    companion object {
        @JvmStatic
        fun safeResult(scenario: AgentProtocolConformanceScenario): AgentProtocolConformanceResult {
            val allowed = scenario.expectedDisposition == AgentProtocolExpectedDisposition.ALLOW_BOUND_OPERATION
            val cancellationOutcome = when {
                scenario.approvedOperation.operation != AgentProtocolOperationKind.CANCEL_TASK ->
                    AgentProtocolCancellationOutcome.NOT_APPLICABLE
                allowed -> AgentProtocolCancellationOutcome.CONFIRMED
                else -> AgentProtocolCancellationOutcome.REJECTED_BEFORE_DISPATCH
            }
            return AgentProtocolConformanceResult(
                scenario.scenarioId,
                scenario.bindingDigest,
                if (allowed) AgentProtocolExecutionStatus.SAFE_COMPLETION else AgentProtocolExecutionStatus.BLOCKED,
                if (allowed) scenario.approvedProfile.peerId else null,
                if (allowed) scenario.approvedProfile.version else null,
                if (allowed) scenario.approvedProfile.profileDigest else null,
                if (allowed) scenario.approvedProfile.capabilityDigest else null,
                if (allowed) scenario.credential?.credentialReference else null,
                allowed,
                if (allowed) 1 else 0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                cancellationOutcome,
                sha256("protocol-evidence:${scenario.bindingDigest}"),
                if (allowed) null else "protocol.fixture-blocked",
                1L,
            )
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
