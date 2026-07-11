package com.fileweft.spi.observability

import com.fileweft.core.context.TraceContext

/**
 * Optional request or message trace source. FileWeft never assumes a logging
 * framework or tracing vendor; hosts may bridge this SPI to OpenTelemetry,
 * Micrometer Tracing, servlet filters, or their own message headers.
 * Implementations must read already-propagated in-process context only: this
 * method may run while FileWeft persists an audit or outbox record and must
 * not perform network I/O, blocking lookups, or remote context resolution.
 */
interface TraceContextProvider {
    fun currentTraceContext(): TraceContext?
}
