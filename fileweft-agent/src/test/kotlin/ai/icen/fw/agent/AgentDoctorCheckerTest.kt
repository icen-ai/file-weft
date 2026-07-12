package ai.icen.fw.agent

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentResult
import ai.icen.fw.spi.ai.AgentTask
import ai.icen.fw.spi.ai.FileWeftAgent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentDoctorCheckerTest {
    @Test
    fun `reports skipped when no optional Agent is installed`() {
        val result = AgentDoctorChecker(emptyList()).check(context())

        assertEquals(AgentDoctorChecker.NAME, result.checkerName)
        assertEquals(DoctorStatus.SKIPPED, result.status)
        assertEquals(emptyMap(), result.evidence)
        assertEquals(
            "Register a FileWeftAgent and an AgentTaskTrigger when Agent analysis is required.",
            result.repairSuggestion,
        )
    }

    @Test
    fun `reports registered capabilities without invoking Agents`() {
        val result = AgentDoctorChecker(listOf(agent(AgentCapability.SECURITY), agent(AgentCapability.CLASSIFICATION))).check(context())

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals("2", result.evidence["capabilityCount"])
        assertEquals("CLASSIFICATION,SECURITY", result.evidence["capabilities"])
    }

    @Test
    fun `rejects duplicate capabilities`() {
        assertFailsWith<IllegalArgumentException> {
            AgentDoctorChecker(listOf(agent(AgentCapability.METADATA), agent(AgentCapability.METADATA)))
        }
    }

    private fun context() = DoctorCheckContext(Identifier("tenant-1"), Identifier("document-1"))

    private fun agent(capability: AgentCapability): FileWeftAgent = object : FileWeftAgent {
        override fun capability(): AgentCapability = capability

        override fun execute(task: AgentTask): AgentResult = error("Doctor must not execute an Agent.")
    }
}
