package com.fileweft.agent

import com.fileweft.core.id.Identifier
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentExecutionStatus
import com.fileweft.spi.ai.AgentResult
import com.fileweft.spi.ai.AgentTask
import com.fileweft.spi.ai.FileWeftAgent
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentTaskOrchestratorTest {
    @Test
    fun `dispatches matching agent result without any domain mutation path`() {
        val task = task(AgentCapability.METADATA)
        val orchestrator = AgentTaskOrchestrator(
            listOf(agent(AgentCapability.METADATA) { AgentResult(it.id, AgentExecutionStatus.SUCCEEDED, completedAt = 2) }),
            fixedClock(),
        )

        assertEquals(AgentExecutionStatus.SUCCEEDED, orchestrator.execute(task).status)
    }

    @Test
    fun `returns unsupported result when no matching agent is installed`() {
        val result = AgentTaskOrchestrator(emptyList(), fixedClock()).execute(task(AgentCapability.SECURITY))

        assertEquals(AgentExecutionStatus.UNSUPPORTED, result.status)
        assertEquals("task-1", result.taskId.value)
        assertEquals(10, result.completedAt)
    }

    @Test
    fun `contains throwing and malformed agent results`() {
        val throwing = AgentTaskOrchestrator(
            listOf(agent(AgentCapability.DUPLICATE) { throw IllegalStateException("offline") }),
            fixedClock(),
        ).execute(task(AgentCapability.DUPLICATE))
        val malformed = AgentTaskOrchestrator(
            listOf(agent(AgentCapability.DUPLICATE) { AgentResult(Identifier("other-task"), AgentExecutionStatus.SUCCEEDED, completedAt = 1) }),
            fixedClock(),
        ).execute(task(AgentCapability.DUPLICATE))

        assertEquals(AgentExecutionStatus.FAILED, throwing.status)
        assertEquals(AgentExecutionStatus.FAILED, malformed.status)
        assertEquals("task-1", malformed.taskId.value)
    }

    @Test
    fun `rejects duplicate agent capability registrations`() {
        assertFailsWith<IllegalArgumentException> {
            AgentTaskOrchestrator(
                listOf(agent(AgentCapability.METADATA) { AgentResult(it.id, AgentExecutionStatus.SUCCEEDED, completedAt = 1) },
                    agent(AgentCapability.METADATA) { AgentResult(it.id, AgentExecutionStatus.SUCCEEDED, completedAt = 1) }),
                fixedClock(),
            )
        }
    }

    private fun task(capability: AgentCapability): AgentTask = AgentTask(
        Identifier("task-1"), Identifier("tenant-1"), capability,
        Identifier("event-1"), "file.uploaded", "event-1:${capability.name}", submittedAt = 1,
    )

    private fun agent(capability: AgentCapability, execute: (AgentTask) -> AgentResult): FileWeftAgent = object : FileWeftAgent {
        override fun capability(): AgentCapability = capability
        override fun execute(task: AgentTask): AgentResult = execute(task)
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
