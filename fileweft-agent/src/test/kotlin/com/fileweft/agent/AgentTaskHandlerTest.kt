package com.fileweft.agent

import com.fileweft.application.agent.AgentResultRepository
import com.fileweft.application.agent.PersistedAgentResult
import com.fileweft.application.agent.PersistedAgentSuggestionConfirmation
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentExecutionStatus
import com.fileweft.spi.ai.AgentResult
import com.fileweft.spi.ai.AgentTask
import com.fileweft.spi.ai.FileWeftAgent
import com.fileweft.spi.task.TaskExecution
import com.fileweft.spi.task.TaskHandlingStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentTaskHandlerTest {
    @Test
    fun `persists successful agent evidence through the durable task handler`() {
        val results = InMemoryResults()
        val handler = handler(results) { task -> AgentResult(task.id, AgentExecutionStatus.SUCCEEDED, completedAt = 3) }

        val outcome = handler.handle(execution())

        assertEquals(TaskHandlingStatus.SUCCEEDED, outcome.status)
        assertEquals(1, results.saved.size)
        assertEquals("event-1", results.saved.single().sourceEventId.value)
        assertEquals(AgentExecutionStatus.SUCCEEDED, results.saved.single().result.status)
    }

    @Test
    fun `requests retry for transient agent failure and writes final evidence after exhaustion`() {
        val results = InMemoryResults()
        val handler = handler(results) { task -> AgentResult(task.id, AgentExecutionStatus.FAILED, message = "model offline", completedAt = 3) }

        assertEquals(TaskHandlingStatus.RETRYABLE_FAILURE, handler.handle(execution()).status)
        handler.onExhausted(execution(), "retry budget exhausted")

        assertEquals(2, results.saved.size)
        assertEquals("retry budget exhausted", results.saved.last().result.message)
    }

    @Test
    fun `rejects malformed task payload without invoking an agent`() {
        val results = InMemoryResults()
        val handler = AgentTaskHandler(AgentTaskOrchestrator(emptyList(), fixedClock()), results, DirectTransaction, fixedClock())
        val malformed = TaskExecution(Identifier("task-1"), Identifier("tenant-1"), AgentTaskHandler.TASK_TYPE, payload = emptyMap())

        assertEquals(TaskHandlingStatus.PERMANENT_FAILURE, handler.handle(malformed).status)
        assertEquals(0, results.saved.size)
    }

    private fun handler(results: InMemoryResults, execute: (AgentTask) -> AgentResult): AgentTaskHandler = AgentTaskHandler(
        AgentTaskOrchestrator(listOf(agent(execute)), fixedClock()), results, DirectTransaction, fixedClock(),
    )

    private fun execution() = TaskExecution(
        Identifier("task-1"), Identifier("tenant-1"), AgentTaskHandler.TASK_TYPE,
        payload = mapOf(
            AgentTaskHandler.CAPABILITY_KEY to "METADATA",
            AgentTaskHandler.SOURCE_EVENT_ID_KEY to "event-1",
            AgentTaskHandler.SOURCE_EVENT_TYPE_KEY to "document.created",
            AgentTaskHandler.CONTEXT_PREFIX + "documentId" to "document-1",
        ),
    )

    private fun agent(execute: (AgentTask) -> AgentResult): FileWeftAgent = object : FileWeftAgent {
        override fun capability(): AgentCapability = AgentCapability.METADATA
        override fun execute(task: AgentTask): AgentResult = execute(task)
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class InMemoryResults : AgentResultRepository {
        val saved = mutableListOf<PersistedAgentResult>()
        override fun save(result: PersistedAgentResult) {
            saved += result
        }

        override fun findByTask(tenantId: Identifier, taskId: Identifier): PersistedAgentResult? =
            saved.lastOrNull { it.tenantId == tenantId && it.taskId == taskId }

        override fun saveConfirmation(
            confirmation: PersistedAgentSuggestionConfirmation,
        ): PersistedAgentSuggestionConfirmation = throw UnsupportedOperationException()

        override fun findConfirmations(tenantId: Identifier, taskId: Identifier): List<PersistedAgentSuggestionConfirmation> = emptyList()
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
