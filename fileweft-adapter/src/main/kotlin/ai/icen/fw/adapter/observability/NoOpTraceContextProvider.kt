package ai.icen.fw.adapter.observability

import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.spi.observability.TraceContextScope

/** Safe default for non-web and non-message deployments. */
class NoOpTraceContextProvider : TraceContextScope {
    override fun currentTraceContext(): TraceContext? = null

    override fun bindTraceContext(traceContext: TraceContext?) = Unit
}
