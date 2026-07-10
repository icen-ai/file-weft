package com.fileweft.application.outbox

import com.fileweft.core.event.OutboxEvent
import com.fileweft.spi.observability.TraceContextProvider

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
