package com.fileweft.agent

import com.fileweft.application.agent.AgentResultRepository
import com.fileweft.application.agent.PersistedAgentResult
import com.fileweft.application.agent.PersistedAgentSuggestionConfirmation
import com.fileweft.application.task.BackgroundTask
import com.fileweft.application.task.BackgroundTaskStatus
import com.fileweft.application.task.TaskRepository
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistedAgentSuggestionConfirmationServiceTest {
    @Test
    fun `legacy constructor confirms a durable result and keeps the first confirmation`() {
        val repository = InMemoryResults(result())
        val service = PersistedAgentSuggestionConfirmationService(
            repository,
            DirectTransaction,
            SequenceIdentifiers(),
            fixedClock(),
        )

        val first = service.confirm(
            Identifier("tenant-1"), Identifier("task-1"),
            Identifier("suggestion-1"), Identifier("operator-1"),
        )
        val duplicate = service.confirm(
            Identifier("tenant-1"), Identifier("task-1"),
            Identifier("suggestion-1"), Identifier("operator-2"),
        )

        assertEquals("operator-1", first.confirmedBy.value)
        assertEquals("operator-1", duplicate.confirmedBy.value)
        assertEquals(1, repository.confirmations.size)
    }

    @Test
    fun `strong constructor verifies success and confirms in the same transaction`() {
        val transaction = TrackingTransaction()
        val repository = InMemoryResults(result(), transaction)
        val tasks = Tasks(task(BackgroundTaskStatus.SUCCESS), transaction)
        val service = PersistedAgentSuggestionConfirmationService(
            repository,
            transaction,
            SequenceIdentifiers(),
            fixedClock(),
            tasks,
        )

        val confirmation = service.confirm(
            Identifier("tenant-1"), Identifier("task-1"),
            Identifier("suggestion-1"), Identifier("operator-1"),
        )

        assertEquals("operator-1", confirmation.confirmedBy.value)
        assertEquals(1, transaction.executions)
        assertEquals(1, tasks.findCalls)
        assertEquals(1, repository.findCalls)
        assertEquals(1, repository.confirmations.size)
    }

    @Test
    fun `strong constructor rejects running retry and failed task states`() {
        listOf(
            BackgroundTaskStatus.RUNNING,
            BackgroundTaskStatus.RETRY,
            BackgroundTaskStatus.FAILED,
        ).forEach { status ->
            val repository = InMemoryResults(result())
            val service = strongService(repository, Tasks(task(status)))

            assertFailsWith<IllegalStateException>(status.name) {
                service.confirm(
                    Identifier("tenant-1"), Identifier("task-1"),
                    Identifier("suggestion-1"), Identifier("operator-1"),
                )
            }
            assertEquals(0, repository.findCalls, status.name)
            assertTrue(repository.confirmations.isEmpty(), status.name)
        }
    }

    @Test
    fun `strong constructor rejects missing and cross tenant task state`() {
        listOf(
            "missing" to Tasks(null),
            "cross tenant" to Tasks(task(BackgroundTaskStatus.SUCCESS, tenant = "tenant-2")),
        ).forEach { (name, tasks) ->
            val repository = InMemoryResults(result())
            val service = strongService(repository, tasks)

            assertFailsWith<NoSuchElementException>(name) {
                service.confirm(
                    Identifier("tenant-1"), Identifier("task-1"),
                    Identifier("suggestion-1"), Identifier("operator-1"),
                )
            }
            assertEquals(0, repository.findCalls, name)
            assertTrue(repository.confirmations.isEmpty(), name)
        }
    }

    @Test
    fun `retains Java friendly legacy constructor and exposes strong constructor`() {
        assertTrue(
            PersistedAgentSuggestionConfirmationService::class.java.getConstructor(
                AgentResultRepository::class.java,
                ApplicationTransaction::class.java,
                IdentifierGenerator::class.java,
                Clock::class.java,
            ) != null,
        )
        assertTrue(
            PersistedAgentSuggestionConfirmationService::class.java.getConstructor(
                AgentResultRepository::class.java,
                ApplicationTransaction::class.java,
                IdentifierGenerator::class.java,
                Clock::class.java,
                TaskRepository::class.java,
            ) != null,
        )
    }

    private fun strongService(
        repository: InMemoryResults,
        tasks: TaskRepository,
    ) = PersistedAgentSuggestionConfirmationService(
        repository,
        DirectTransaction,
        SequenceIdentifiers(),
        fixedClock(),
        tasks,
    )

    private fun result() = PersistedAgentResult(
        Identifier("result-1"),
        Identifier("tenant-1"),
        Identifier("task-1"),
        AgentCapability.METADATA,
        Identifier("event-1"),
        "document.created",
        AgentResult(
            Identifier("task-1"),
            AgentExecutionStatus.SUCCEEDED,
            listOf(AgentSuggestion(Identifier("suggestion-1"), "document.metadata")),
            completedAt = 1,
        ),
        1,
    )

    private fun task(
        status: BackgroundTaskStatus,
        tenant: String = "tenant-1",
    ) = BackgroundTask(
        id = Identifier("task-1"),
        tenantId = Identifier(tenant),
        type = AgentTaskHandler.TASK_TYPE,
        idempotencyKey = "agent:METADATA:event-1",
        status = status,
    )

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
            private set
        var executions = 0
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active)
            executions++
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private class SequenceIdentifiers : IdentifierGenerator {
        private var next = 1
        override fun nextId(): Identifier = Identifier("confirmation-${next++}")
    }

    private class Tasks(
        private val task: BackgroundTask?,
        private val transaction: TrackingTransaction? = null,
    ) : TaskRepository {
        var findCalls = 0
            private set

        override fun enqueue(task: BackgroundTask) = Unit

        override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? {
            transaction?.let { check(it.active) { "Task state must be checked in the confirmation transaction." } }
            findCalls++
            return task?.takeIf { it.tenantId == tenantId && it.id == taskId }
        }

        override fun findByBusiness(
            tenantId: Identifier,
            businessId: Identifier,
            limit: Int,
        ): List<BackgroundTask> = emptyList()
    }

    private class InMemoryResults(
        private val result: PersistedAgentResult,
        private val transaction: TrackingTransaction? = null,
    ) : AgentResultRepository {
        val confirmations = mutableListOf<PersistedAgentSuggestionConfirmation>()
        var findCalls = 0
            private set

        override fun save(result: PersistedAgentResult) = Unit

        override fun findByTask(tenantId: Identifier, taskId: Identifier): PersistedAgentResult? {
            transaction?.let { check(it.active) { "Agent result must be read in the confirmation transaction." } }
            findCalls++
            return result.takeIf { it.tenantId == tenantId && it.taskId == taskId }
        }

        override fun saveConfirmation(
            confirmation: PersistedAgentSuggestionConfirmation,
        ): PersistedAgentSuggestionConfirmation {
            transaction?.let { check(it.active) { "Confirmation must be saved in the task-check transaction." } }
            return confirmations.firstOrNull {
                it.tenantId == confirmation.tenantId &&
                    it.taskId == confirmation.taskId &&
                    it.suggestionId == confirmation.suggestionId
            } ?: confirmation.also { confirmations += it }
        }

        override fun findConfirmations(
            tenantId: Identifier,
            taskId: Identifier,
        ): List<PersistedAgentSuggestionConfirmation> =
            confirmations.filter { it.tenantId == tenantId && it.taskId == taskId }
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
