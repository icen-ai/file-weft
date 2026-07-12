package ai.icen.fw.agent

import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AgentTaskSchedulerTest {
    @Test
    fun `turns a committed event into a tenant scoped idempotent task`() {
        val tasks = CapturingTaskRepository()
        val event = OutboxEvent(
            Identifier("event-1"), Identifier("tenant-1"), "document.created",
            mapOf("documentId" to "document-1"), 1,
        )

        val scheduled = AgentTaskScheduler(SequenceIdentifiers(), fixedClock()).schedule(
            event, ai.icen.fw.spi.ai.AgentCapability.METADATA, tasks, Identifier("document-1"),
        )

        assertSame(scheduled, tasks.enqueued.single())
        assertEquals(AgentTaskHandler.TASK_TYPE, scheduled.type)
        assertEquals("agent:METADATA:event-1", scheduled.idempotencyKey)
        assertEquals("METADATA", scheduled.payload[AgentTaskHandler.CAPABILITY_KEY])
        assertEquals("document-1", scheduled.payload[AgentTaskHandler.CONTEXT_PREFIX + "documentId"])
        assertEquals(10, scheduled.nextAttemptTime)
    }

    private class SequenceIdentifiers : IdentifierGenerator {
        override fun nextId(): Identifier = Identifier("task-1")
    }

    private class CapturingTaskRepository : TaskRepository {
        val enqueued = mutableListOf<BackgroundTask>()
        override fun enqueue(task: BackgroundTask) {
            enqueued += task
        }

        override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? =
            enqueued.singleOrNull { it.tenantId == tenantId && it.id == taskId }

        override fun findByBusiness(tenantId: Identifier, businessId: Identifier, limit: Int): List<BackgroundTask> =
            enqueued.filter { it.tenantId == tenantId && it.businessId == businessId }.take(limit)
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
