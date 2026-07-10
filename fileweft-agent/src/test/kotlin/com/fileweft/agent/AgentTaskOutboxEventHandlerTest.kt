package com.fileweft.agent

import com.fileweft.application.task.BackgroundTask
import com.fileweft.application.task.TaskRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentTaskTrigger
import com.fileweft.spi.event.OutboxHandlingStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTaskOutboxEventHandlerTest {
    @Test
    fun `schedules each explicitly configured capability from a matching event`() {
        val tasks = CapturingTaskRepository()
        val event = OutboxEvent(Identifier("event-1"), Identifier("tenant-1"), "document.created", emptyMap(), 1)
        val handler = AgentTaskOutboxEventHandler(
            listOf(trigger("document.created", AgentCapability.METADATA, AgentCapability.CLASSIFICATION)),
            AgentTaskScheduler(SequenceIdentifiers(), fixedClock()), tasks, DirectTransaction,
        )

        assertTrue(handler.supports(event))
        assertEquals(OutboxHandlingStatus.SUCCEEDED, handler.handle(event).status)
        assertEquals(listOf("METADATA", "CLASSIFICATION"), tasks.enqueued.map { it.payload[AgentTaskHandler.CAPABILITY_KEY] })
    }

    @Test
    fun `does not claim unrelated outbox events`() {
        val handler = AgentTaskOutboxEventHandler(
            listOf(trigger("document.created", AgentCapability.METADATA)),
            AgentTaskScheduler(SequenceIdentifiers(), fixedClock()), CapturingTaskRepository(), DirectTransaction,
        )

        assertFalse(handler.supports(OutboxEvent(Identifier("event-2"), Identifier("tenant-1"), "document.archived", emptyMap(), 1)))
    }

    private fun trigger(type: String, vararg capabilities: AgentCapability): AgentTaskTrigger = object : AgentTaskTrigger {
        override fun supports(event: OutboxEvent): Boolean = event.type == type
        override fun capabilities(event: OutboxEvent): Collection<AgentCapability> = capabilities.asList()
        override fun businessId(event: OutboxEvent): Identifier = Identifier("document-1")
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class SequenceIdentifiers : IdentifierGenerator {
        private var next = 1
        override fun nextId(): Identifier = Identifier("task-${next++}")
    }

    private class CapturingTaskRepository : TaskRepository {
        val enqueued = mutableListOf<BackgroundTask>()
        override fun enqueue(task: BackgroundTask) {
            enqueued += task
        }

        override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? = null
        override fun findByBusiness(tenantId: Identifier, businessId: Identifier, limit: Int): List<BackgroundTask> = emptyList()
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
