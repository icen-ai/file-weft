package com.fileweft.dev.api.security

import com.fileweft.core.context.TraceContext
import com.fileweft.spi.observability.TraceContextScope

/** Servlet-thread trace carrier used solely by the development platform. */
class DevTraceContextProvider : TraceContextScope {
    private val current = ThreadLocal<TraceContext?>()

    override fun currentTraceContext(): TraceContext? = current.get()

    override fun bindTraceContext(traceContext: TraceContext?) {
        if (traceContext == null) current.remove() else current.set(traceContext)
    }
}
