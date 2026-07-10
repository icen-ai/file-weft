package com.fileweft.dev.api.security

import com.fileweft.core.context.TraceContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.observability.TraceContextProvider

/** Servlet-thread trace carrier used solely by the development platform. */
class DevTraceContextProvider : TraceContextProvider {
    private val current = ThreadLocal<TraceContext?>()

    fun bind(traceId: String) {
        current.set(TraceContext(Identifier(traceId)))
    }

    fun clear() {
        current.remove()
    }

    override fun currentTraceContext(): TraceContext? = current.get()
}
