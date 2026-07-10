package com.fileweft.dev.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Assigns or continues a bounded trace identifier for every development HTTP request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class DevTraceFilter(
    private val traces: DevTraceContextProvider,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val traceId = request.getHeader(TRACE_HEADER)?.trim()?.takeIf(SAFE_TRACE_ID::matches)
            ?: UUID.randomUUID().toString()
        traces.bind(traceId)
        response.setHeader(TRACE_HEADER, traceId)
        try {
            chain.doFilter(request, response)
        } finally {
            traces.clear()
        }
    }

    private companion object {
        const val TRACE_HEADER = "X-Trace-Id"
        val SAFE_TRACE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}
