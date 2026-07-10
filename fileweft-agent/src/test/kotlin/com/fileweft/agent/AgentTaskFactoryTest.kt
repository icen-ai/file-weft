package com.fileweft.agent

import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.spi.ai.AgentCapability
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class AgentTaskFactoryTest {
    @Test
    fun `preserves event tenant context payload and deterministic idempotency key`() {
        val task = AgentTaskFactory(
            object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("task-1") },
            Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        ).create(
            OutboxEvent(Identifier("event-1"), Identifier("tenant-1"), "file.uploaded", mapOf("fileObjectId" to "file-1"), 1),
            AgentCapability.CLASSIFICATION,
        )

        assertEquals("task-1", task.id.value)
        assertEquals("tenant-1", task.tenantId.value)
        assertEquals("file-1", task.context["fileObjectId"])
        assertEquals("agent:CLASSIFICATION:event-1", task.idempotencyKey)
        assertEquals(100, task.submittedAt)
    }
}
