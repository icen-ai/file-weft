package com.fileweft.core.context

import com.fileweft.core.event.DomainEvent
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContextAndEventTest {
    @Test
    fun `context carries tenant and trace identifiers without global state`() {
        val tenantContext = TenantContext(Identifier("tenant-1"))
        val traceContext = TraceContext(Identifier("trace-1"))

        assertEquals("tenant-1", tenantContext.tenantId.value)
        assertEquals("trace-1", traceContext.traceId.value)
        assertNull(traceContext.parentTraceId)
    }

    @Test
    fun `domain event includes tenant ownership`() {
        val event = TestEvent(
            id = Identifier("event-1"),
            tenantId = Identifier("tenant-1"),
            timestamp = 1_735_689_600_000,
        )

        assertEquals("event-1", event.id.value)
        assertEquals("tenant-1", event.tenantId.value)
        assertEquals(1_735_689_600_000, event.timestamp)
    }

    @Test
    fun `outbox event snapshots its payload for asynchronous processing`() {
        val payload = linkedMapOf("documentId" to "document-1")
        val event = OutboxEvent(
            id = Identifier("event-1"),
            tenantId = Identifier("tenant-1"),
            type = "document.publish.requested",
            payload = payload,
            timestamp = 1,
        )
        payload["documentId"] = "changed"

        assertEquals("document-1", event.payload["documentId"])
    }

    private class TestEvent(
        override val id: Identifier,
        override val tenantId: Identifier,
        override val timestamp: Long,
    ) : DomainEvent
}
