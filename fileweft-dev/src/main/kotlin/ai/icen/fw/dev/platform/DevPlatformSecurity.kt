package ai.icen.fw.dev.platform

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Authenticates FileWeft's development connector before the downstream
 * simulator accepts a request.  This is deliberately separate from the proof
 * lab's user session: a downstream platform authenticates the calling system,
 * not an interactive FileWeft user.
 */
class DevPlatformAuthenticator(
    sharedSecret: String,
) {
    private val expectedSecret = sharedSecret.toByteArray(StandardCharsets.UTF_8)

    init {
        require(sharedSecret.length >= MINIMUM_SECRET_LENGTH) {
            "Development platform shared secret must contain at least $MINIMUM_SECRET_LENGTH characters."
        }
    }

    fun isAuthenticated(candidate: String?): Boolean = candidate != null &&
        MessageDigest.isEqual(expectedSecret, candidate.toByteArray(StandardCharsets.UTF_8))

    companion object {
        const val HEADER_NAME = "X-FileWeft-Dev-Platform-Key"
        private const val MINIMUM_SECRET_LENGTH = 32
    }
}

/** Protects every simulator endpoint except its unauthenticated liveness probe. */
class DevPlatformAuthenticationFilter(
    private val authenticator: DevPlatformAuthenticator,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI == HEALTH_PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!authenticator.isAuthenticated(request.getHeader(DevPlatformAuthenticator.HEADER_NAME))) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("{\"code\":\"PLATFORM_AUTHENTICATION_REQUIRED\",\"message\":\"Development platform credentials are required.\"}")
            return
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        const val HEALTH_PATH = "/platform/v1/health"
    }
}
