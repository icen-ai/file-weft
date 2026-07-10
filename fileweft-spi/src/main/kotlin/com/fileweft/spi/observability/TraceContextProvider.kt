package com.fileweft.spi.observability

import com.fileweft.core.context.TraceContext

/**
 * Optional request or message trace source. FileWeft never assumes a logging
 * framework or tracing vendor; hosts may bridge this SPI to OpenTelemetry,
 * Micrometer Tracing, servlet filters, or their own message headers.
 */
interface TraceContextProvider {
    fun currentTraceContext(): TraceContext?
}
