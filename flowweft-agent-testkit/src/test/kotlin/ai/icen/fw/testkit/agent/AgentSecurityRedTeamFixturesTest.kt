package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentSecurityRedTeamFixturesTest {

    @Test
    fun `standard catalog covers every approved attack exactly once`() {
        val catalog = AgentSecurityRedTeamFixtures.standard()

        assertEquals(AgentRedTeamAttack.values().size, catalog.scenarios.size)
        assertEquals(AgentRedTeamAttack.values().toSet(), catalog.scenarios.map { it.attack }.toSet())
        assertTrue(
            catalog.scenario(AgentRedTeamAttack.INDIRECT_PROMPT_INJECTION)
                .payloads.any { it.origin == AgentContentOrigin.RETRIEVAL },
        )
        assertTrue(
            catalog.scenario(AgentRedTeamAttack.TOOL_RESULT_POISONING)
                .payloads.any { it.origin == AgentContentOrigin.TOOL },
        )
        assertFalse(
            catalog.scenario(AgentRedTeamAttack.SECRET_EXFILTRATION)
                .canaries.single().toString().contains("FW_TEST_SECRET"),
        )
    }

    @Test
    fun `tool arguments and suite collections are defensive snapshots`() {
        val scenario = AgentSecurityRedTeamFixtures.unauthorizedTool()
        val tool = requireNotNull(scenario.toolAttempt)
        val original = tool.arguments()
        val changed = tool.arguments()
        changed[0] = 0

        assertArrayEquals(original, tool.arguments())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (AgentSecurityRedTeamFixtures.standard().scenarios as MutableList<AgentRedTeamScenario>).clear()
        }
    }

    @Test
    fun `approval replay fixtures change exactly one security binding`() {
        val subject = requireNotNull(AgentSecurityRedTeamFixtures.approvalSubjectReplay().approvalReplay)
        val arguments = requireNotNull(AgentSecurityRedTeamFixtures.approvalArgumentReplay().approvalReplay)
        val version = requireNotNull(AgentSecurityRedTeamFixtures.approvalResourceVersionReplay().approvalReplay)

        assertNotEquals(subject.approvedPrincipalId, subject.replayPrincipalId)
        assertEquals(subject.approvedArgumentsDigest, subject.replayArgumentsDigest)
        assertNotEquals(arguments.approvedArgumentsDigest, arguments.replayArgumentsDigest)
        assertEquals(arguments.approvedPrincipalId, arguments.replayPrincipalId)
        assertNotEquals(version.approvedResourceVersion, version.replayResourceVersion)
        assertEquals(version.approvedArgumentsDigest, version.replayArgumentsDigest)
    }

    @Test
    fun `scenario binding is independent of canary collection order`() {
        val fixture = AgentSecurityRedTeamFixtures.secretExfiltration()
        val secondCanary = AgentRedTeamCanary(
            Identifier("provider-secret-canary-b"),
            "FW_TEST_SECRET_DO_NOT_DISCLOSE_2R8M",
        )
        val first = AgentRedTeamScenario(
            fixture.scenarioId,
            fixture.attack,
            fixture.context,
            fixture.resource,
            fixture.payloads,
            fixture.canaries + secondCanary,
            fixture.toolAttempt,
            fixture.approvalReplay,
        )
        val reversed = AgentRedTeamScenario(
            fixture.scenarioId,
            fixture.attack,
            fixture.context,
            fixture.resource,
            fixture.payloads,
            (fixture.canaries + secondCanary).reversed(),
            fixture.toolAttempt,
            fixture.approvalReplay,
        )

        assertEquals(first.bindingDigest, reversed.bindingDigest)
    }
}
