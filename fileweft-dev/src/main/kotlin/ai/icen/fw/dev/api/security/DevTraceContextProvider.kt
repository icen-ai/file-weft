package ai.icen.fw.dev.api.security

import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.spi.observability.TraceContextScope

/** Servlet-thread trace carrier used solely by the development platform. */
class DevTraceContextProvider : TraceContextScope {
    private val current = ThreadLocal<TraceContext?>()

    override fun currentTraceContext(): TraceContext? = current.get()

    override fun bindTraceContext(traceContext: TraceContext?) {
        if (traceContext == null) current.remove() else current.set(traceContext)
    }
}
