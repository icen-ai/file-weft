package com.fileweft.core.context

import com.fileweft.core.id.Identifier

class TraceContext @JvmOverloads constructor(
    val traceId: Identifier,
    val parentTraceId: Identifier? = null,
)
