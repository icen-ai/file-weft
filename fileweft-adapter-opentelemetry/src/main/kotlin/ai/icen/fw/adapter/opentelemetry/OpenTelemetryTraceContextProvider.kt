package ai.icen.fw.adapter.opentelemetry

import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.TraceContextProvider
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext

/**
 * Bridges OpenTelemetry's in-process span context to FlowWeft's trace SPI.
 *
 * The provider reads the current [SpanContext] without performing network I/O
 * or remote lookups, satisfying the SPI contract that this method must remain
 * safe to call during persistence and audit operations.
 */
class OpenTelemetryTraceContextProvider : TraceContextProvider {
    override fun currentTraceContext(): TraceContext? {
        val spanContext = Span.current().spanContext
        return if (spanContext.isValid) {
            TraceContext(traceId = Identifier(spanContext.traceId))
        } else {
            null
        }
    }
}
