package ai.icen.fw.agent

import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentTaskTrigger
import ai.icen.fw.spi.event.OutboxHandlingStatus
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

    @Test
    fun `freezes trigger decisions outside the task persistence transaction`() {
        val transaction = TrackingTransaction()
        val trigger = object : AgentTaskTrigger {
            override fun supports(event: OutboxEvent): Boolean {
                check(!transaction.active) { "Agent trigger supports must not run in a transaction." }
                return true
            }

            override fun capabilities(event: OutboxEvent): Collection<AgentCapability> {
                check(!transaction.active) { "Agent trigger capabilities must not run in a transaction." }
                return listOf(AgentCapability.CLASSIFICATION)
            }

            override fun businessId(event: OutboxEvent): Identifier? {
                check(!transaction.active) { "Agent trigger business id must not run in a transaction." }
                return Identifier("document-1")
            }
        }
        val tasks = CapturingTaskRepository(transaction)
        val handler = AgentTaskOutboxEventHandler(
            listOf(trigger), AgentTaskScheduler(SequenceIdentifiers(), fixedClock()), tasks, transaction,
        )

        assertEquals(OutboxHandlingStatus.SUCCEEDED, handler.handle(OutboxEvent(Identifier("event-3"), Identifier("tenant-1"), "document.created", emptyMap(), 1)).status)
        assertEquals(listOf("CLASSIFICATION"), tasks.enqueued.map { it.payload[AgentTaskHandler.CAPABILITY_KEY] })
    }

    @Test
    fun `does not enqueue a partial task plan when trigger evaluation fails`() {
        val tasks = CapturingTaskRepository()
        val handler = AgentTaskOutboxEventHandler(
            listOf(object : AgentTaskTrigger {
                override fun supports(event: OutboxEvent): Boolean = true
                override fun capabilities(event: OutboxEvent): Collection<AgentCapability> =
                    throw IllegalStateException("policy unavailable")
            }),
            AgentTaskScheduler(SequenceIdentifiers(), fixedClock()),
            tasks,
            DirectTransaction,
        )

        val result = handler.handle(OutboxEvent(Identifier("event-4"), Identifier("tenant-1"), "document.created", emptyMap(), 1))

        assertEquals(OutboxHandlingStatus.RETRYABLE_FAILURE, result.status)
        assertTrue(tasks.enqueued.isEmpty())
    }

    private fun trigger(type: String, vararg capabilities: AgentCapability): AgentTaskTrigger = object : AgentTaskTrigger {
        override fun supports(event: OutboxEvent): Boolean = event.type == type
        override fun capabilities(event: OutboxEvent): Collection<AgentCapability> = capabilities.asList()
        override fun businessId(event: OutboxEvent): Identifier = Identifier("document-1")
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested transaction is not expected in this fixture." }
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
        override fun nextId(): Identifier = Identifier("task-${next++}")
    }

    private class CapturingTaskRepository(
        private val transaction: TrackingTransaction? = null,
    ) : TaskRepository {
        val enqueued = mutableListOf<BackgroundTask>()
        override fun enqueue(task: BackgroundTask) {
            transaction?.let { check(it.active) { "Task persistence must occur in a transaction." } }
            enqueued += task
        }

        override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? = null
        override fun findByBusiness(tenantId: Identifier, businessId: Identifier, limit: Int): List<BackgroundTask> = emptyList()
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
