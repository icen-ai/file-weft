package ai.icen.fw.application.outbox

import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.TraceContextProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TraceAwareOutboxEventRepositoryTest {
    @Test
    fun `captures the active trace without changing business payload`() {
        val delegate = RecordingRepository()
        val event = event()
        TraceAwareOutboxEventRepository(delegate, provider("trace-request-1")).append(event)

        val persisted = delegate.events.single()
        assertEquals("trace-request-1", persisted.traceId?.value)
        assertEquals("document-1", persisted.payload["documentId"])
        assertEquals(null, persisted.payload["traceId"])
    }

    @Test
    fun `preserves an explicit event trace over ambient context`() {
        val delegate = RecordingRepository()
        val event = OutboxEvent(
            Identifier("event-1"), Identifier("tenant-1"), "document.publish.requested",
            emptyMap(), 1, Identifier("trace-message-1"),
        )
        TraceAwareOutboxEventRepository(delegate, provider("trace-request-1")).append(event)

        assertSame(event, delegate.events.single())
        assertEquals("trace-message-1", delegate.events.single().traceId?.value)
    }

    private fun provider(traceId: String): TraceContextProvider = object : TraceContextProvider {
        override fun currentTraceContext(): TraceContext = TraceContext(Identifier(traceId))
    }

    private fun event() = OutboxEvent(
        Identifier("event-1"), Identifier("tenant-1"), "document.publish.requested",
        mapOf("documentId" to "document-1"), 1,
    )

    private class RecordingRepository : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()

        override fun append(event: OutboxEvent) {
            events += event
        }
    }
}
