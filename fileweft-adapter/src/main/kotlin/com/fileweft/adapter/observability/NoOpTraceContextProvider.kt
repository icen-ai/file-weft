package com.fileweft.adapter.observability

import com.fileweft.core.context.TraceContext
import com.fileweft.spi.observability.TraceContextProvider

/** Safe default for non-web and non-message deployments. */
class NoOpTraceContextProvider : TraceContextProvider {
    override fun currentTraceContext(): TraceContext? = null
}
