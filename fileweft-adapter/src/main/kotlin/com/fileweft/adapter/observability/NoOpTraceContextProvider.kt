package com.fileweft.adapter.observability

import com.fileweft.core.context.TraceContext
import com.fileweft.spi.observability.TraceContextScope

/** Safe default for non-web and non-message deployments. */
class NoOpTraceContextProvider : TraceContextScope {
    override fun currentTraceContext(): TraceContext? = null

    override fun bindTraceContext(traceContext: TraceContext?) = Unit
}
