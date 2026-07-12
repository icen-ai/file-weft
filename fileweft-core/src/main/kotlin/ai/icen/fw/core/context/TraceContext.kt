package ai.icen.fw.core.context

import ai.icen.fw.core.id.Identifier

class TraceContext @JvmOverloads constructor(
    val traceId: Identifier,
    val parentTraceId: Identifier? = null,
)
