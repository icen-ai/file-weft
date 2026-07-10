package com.fileweft.spi.observability

import com.fileweft.core.context.TraceContext

/**
 * Optional mutable trace carrier for controlled worker-thread propagation.
 * Implementations must clear their carrier when passed null and must be safe
 * to restore to a previous context after a nested operation completes.
 */
interface TraceContextScope : TraceContextProvider {
    fun bindTraceContext(traceContext: TraceContext?)
}
