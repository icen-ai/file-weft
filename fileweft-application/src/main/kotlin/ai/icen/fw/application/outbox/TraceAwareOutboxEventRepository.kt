package ai.icen.fw.application.outbox

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.spi.observability.TraceContextProvider

/** Captures the request trace with an event without leaking it into business payload. */
class TraceAwareOutboxEventRepository(
    private val delegate: OutboxEventRepository,
    private val traceContextProvider: TraceContextProvider,
) : OutboxEventRepository {
    override fun append(event: OutboxEvent) {
        val traceId = event.traceId ?: traceContextProvider.currentTraceContext()?.traceId
        delegate.append(
            if (traceId == event.traceId) event else OutboxEvent(
                event.id,
                event.tenantId,
                event.type,
                event.payload,
                event.timestamp,
                traceId,
            ),
        )
    }
}
