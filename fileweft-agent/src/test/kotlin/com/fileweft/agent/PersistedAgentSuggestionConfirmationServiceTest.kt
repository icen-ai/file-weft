package com.fileweft.agent

import com.fileweft.application.agent.AgentResultRepository
import com.fileweft.application.agent.PersistedAgentResult
import com.fileweft.application.agent.PersistedAgentSuggestionConfirmation
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentExecutionStatus
import com.fileweft.spi.ai.AgentResult
import com.fileweft.spi.ai.AgentSuggestion
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class PersistedAgentSuggestionConfirmationServiceTest {
    @Test
    fun `confirms a durable result and keeps the first confirmation idempotently`() {
        val repository = InMemoryResults(result())
        val service = PersistedAgentSuggestionConfirmationService(repository, DirectTransaction, SequenceIdentifiers(), fixedClock())

        val first = service.confirm(Identifier("tenant-1"), Identifier("task-1"), Identifier("suggestion-1"), Identifier("operator-1"))
        val duplicate = service.confirm(Identifier("tenant-1"), Identifier("task-1"), Identifier("suggestion-1"), Identifier("operator-2"))

        assertEquals("operator-1", first.confirmedBy.value)
        assertEquals("operator-1", duplicate.confirmedBy.value)
        assertEquals(1, repository.confirmations.size)
    }

    private fun result() = PersistedAgentResult(
        Identifier("result-1"), Identifier("tenant-1"), Identifier("task-1"), AgentCapability.METADATA,
        Identifier("event-1"), "document.created",
        AgentResult(
            Identifier("task-1"), AgentExecutionStatus.SUCCEEDED,
            listOf(AgentSuggestion(Identifier("suggestion-1"), "document.metadata")), completedAt = 1,
        ),
        1,
    )

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class SequenceIdentifiers : IdentifierGenerator {
        private var next = 1
        override fun nextId(): Identifier = Identifier("confirmation-${next++}")
    }

    private class InMemoryResults(private val result: PersistedAgentResult) : AgentResultRepository {
        val confirmations = mutableListOf<PersistedAgentSuggestionConfirmation>()
        override fun save(result: PersistedAgentResult) = Unit
        override fun findByTask(tenantId: Identifier, taskId: Identifier): PersistedAgentResult? =
            result.takeIf { it.tenantId == tenantId && it.taskId == taskId }

        override fun saveConfirmation(confirmation: PersistedAgentSuggestionConfirmation): PersistedAgentSuggestionConfirmation =
            confirmations.firstOrNull {
                it.tenantId == confirmation.tenantId && it.taskId == confirmation.taskId && it.suggestionId == confirmation.suggestionId
            } ?: confirmation.also { confirmations += it }

        override fun findConfirmations(tenantId: Identifier, taskId: Identifier): List<PersistedAgentSuggestionConfirmation> =
            confirmations.filter { it.tenantId == tenantId && it.taskId == taskId }
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
