package com.fileweft.dev.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class DevAuthenticationFilter(
    private val sessions: DevSessionStore,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI == "/api/auth/login" || request.requestURI == "/api/health"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authorization = request.getHeader(AUTHORIZATION_HEADER)
        val token = authorization?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)
        val principal = token?.let(sessions::find)
        if (principal == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录开发测试平台。\"}")
            return
        }
        DevRequestIdentityContext.bind(principal)
        try {
            chain.doFilter(request, response)
        } finally {
            DevRequestIdentityContext.clear()
        }
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
