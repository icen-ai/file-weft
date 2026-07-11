package com.fileweft.dev.api.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.security.ApplicationUnauthenticatedException
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class DevAuthenticationFilter(
    private val sessions: DevSessionStore,
    private val responses: V1ApiResponseFactory,
    private val objectMapper: ObjectMapper,
    private val traces: DevTraceContextProvider,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI == "/api/auth/login" || request.requestURI == "/api/health"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authorization = request.getHeader(AUTHORIZATION_HEADER)
        val token = authorization?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)
        val principal = token?.let(sessions::find)
        if (principal == null) {
            if (isFormalV1Path(request.requestURI)) {
                writeFormalUnauthenticated(response)
            } else {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = JSON_CONTENT_TYPE
                response.writer.write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录开发测试平台。\"}")
            }
            return
        }
        DevRequestIdentityContext.bind(principal)
        try {
            chain.doFilter(request, response)
        } finally {
            DevRequestIdentityContext.clear()
        }
    }

    private fun writeFormalUnauthenticated(response: HttpServletResponse) {
        val traceId = try {
            traces.currentTraceContext()?.traceId?.value
        } catch (_: Exception) {
            null
        }
        val failure = responses.failure(ApplicationUnauthenticatedException(), traceId)
        response.status = failure.status.statusCode
        response.contentType = JSON_CONTENT_TYPE
        response.setHeader("WWW-Authenticate", "Bearer realm=\"fileweft-dev\"")
        response.setHeader("Cache-Control", "private, no-store")
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.writer.write(objectMapper.writeValueAsString(failure.response))
    }

    private fun isFormalV1Path(requestUri: String): Boolean =
        requestUri == FORMAL_V1_ROOT || requestUri.startsWith("$FORMAL_V1_ROOT/")

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val FORMAL_V1_ROOT = "/fileweft/v1"
        const val JSON_CONTENT_TYPE = "application/json;charset=UTF-8"
    }
}
