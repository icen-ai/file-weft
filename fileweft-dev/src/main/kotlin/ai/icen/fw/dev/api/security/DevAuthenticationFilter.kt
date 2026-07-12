package ai.icen.fw.dev.api.security

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
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
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val protectedResponse = if (isPrivateNoStorePath(request.requestURI)) {
            PrivateNoStoreResponse(response)
        } else {
            response
        }
        if (isPublicPath(request.requestURI)) {
            chain.doFilter(request, protectedResponse)
            return
        }

        val authorization = request.getHeader(AUTHORIZATION_HEADER)
        val token = authorization?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)
        val principal = token?.let(sessions::find)
        if (principal == null) {
            if (isFormalApiPath(request.requestURI)) {
                writeFormalUnauthenticated(protectedResponse)
            } else {
                protectedResponse.status = HttpServletResponse.SC_UNAUTHORIZED
                protectedResponse.contentType = JSON_CONTENT_TYPE
                protectedResponse.writer.write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"请先登录开发测试平台。\"}")
            }
            return
        }
        DevRequestIdentityContext.bind(principal)
        try {
            chain.doFilter(request, protectedResponse)
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

    private fun isFormalApiPath(requestUri: String): Boolean =
        isFormalV1Path(requestUri) || requestUri in FORMAL_COMPATIBILITY_PATHS

    private fun isPrivateNoStorePath(requestUri: String): Boolean =
        requestUri == DEV_API_ROOT || requestUri.startsWith("$DEV_API_ROOT/") ||
            isFormalApiPath(requestUri)

    private fun isPublicPath(requestUri: String): Boolean =
        requestUri == LOGIN_PATH || requestUri == HEALTH_PATH ||
            requestUri == FORMAL_HEALTH_PATH || requestUri == FORMAL_HEALTH_COMPATIBILITY_PATH

    /**
     * Installs the safe defaults before the request can be rejected, while presenting those defaults as
     * unwritten to downstream response writers. This lets a download endpoint replace them with stricter
     * directives. Any downstream cache policy is augmented when it omits the mandatory private/no-store
     * directives, so a controller cannot accidentally weaken the boundary.
     */
    private class PrivateNoStoreResponse(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
        private val explicitlyWrittenHeaders = mutableSetOf<String>()

        init {
            super.setHeader(CACHE_CONTROL_HEADER, PRIVATE_NO_STORE)
            super.setHeader(PRAGMA_HEADER, NO_CACHE)
        }

        override fun containsHeader(name: String): Boolean =
            if (isImplicitManagedHeader(name)) false else super.containsHeader(name)

        override fun getHeader(name: String): String? =
            if (isImplicitManagedHeader(name)) null else super.getHeader(name)

        override fun getHeaders(name: String): Collection<String> =
            if (isImplicitManagedHeader(name)) emptyList() else super.getHeaders(name)

        override fun getHeaderNames(): Collection<String> =
            super.getHeaderNames().filterNot(::isImplicitManagedHeader)

        override fun setHeader(name: String, value: String?) {
            if (isManagedHeader(name)) {
                explicitlyWrittenHeaders += normalize(name)
                super.setHeader(name, secureValue(name, value.orEmpty()))
            } else {
                super.setHeader(name, value)
            }
        }

        override fun addHeader(name: String, value: String?) {
            if (isManagedHeader(name)) {
                setHeader(name, value)
            } else {
                super.addHeader(name, value)
            }
        }

        override fun reset() {
            super.reset()
            explicitlyWrittenHeaders.clear()
            super.setHeader(CACHE_CONTROL_HEADER, PRIVATE_NO_STORE)
            super.setHeader(PRAGMA_HEADER, NO_CACHE)
        }

        private fun isImplicitManagedHeader(name: String): Boolean =
            isManagedHeader(name) && normalize(name) !in explicitlyWrittenHeaders

        private fun isManagedHeader(name: String): Boolean =
            name.equals(CACHE_CONTROL_HEADER, ignoreCase = true) || name.equals(PRAGMA_HEADER, ignoreCase = true)

        private fun secureValue(name: String, value: String): String = when {
            name.equals(CACHE_CONTROL_HEADER, ignoreCase = true) -> ensureCacheControl(value)
            name.equals(PRAGMA_HEADER, ignoreCase = true) -> ensureDirectives(value, NO_CACHE)
            else -> value
        }

        private fun ensureCacheControl(value: String): String = appendMissingDirectives(
            directives = parseDirectives(value).filterNot { directiveName(it) == PUBLIC_DIRECTIVE },
            required = listOf(PRIVATE_DIRECTIVE, NO_STORE_DIRECTIVE),
        )

        private fun ensureDirectives(value: String, vararg required: String): String =
            appendMissingDirectives(parseDirectives(value), required.toList())

        private fun appendMissingDirectives(directives: List<String>, required: List<String>): String {
            val present = directives
                .map(::directiveName)
                .toSet()
            return (directives + required.filterNot(present::contains)).joinToString(", ")
        }

        private fun parseDirectives(value: String): List<String> =
            value.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)

        private fun directiveName(directive: String): String = directive.substringBefore('=').trim().lowercase()

        private fun normalize(name: String): String = name.lowercase()
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val DEV_API_ROOT = "/api"
        const val FORMAL_V1_ROOT = "/fileweft/v1"
        const val LOGIN_PATH = "/api/auth/login"
        const val HEALTH_PATH = "/api/health"
        const val FORMAL_HEALTH_PATH = "/fileweft/v1/health"
        const val FORMAL_HEALTH_COMPATIBILITY_PATH = "/fileweft/health"
        const val FORMAL_PLUGIN_COMPATIBILITY_PATH = "/fileweft/plugins"
        const val FORMAL_DOCTOR_COMPATIBILITY_PATH = "/fileweft/doctor"
        val FORMAL_COMPATIBILITY_PATHS = setOf(
            FORMAL_HEALTH_COMPATIBILITY_PATH,
            FORMAL_PLUGIN_COMPATIBILITY_PATH,
            FORMAL_DOCTOR_COMPATIBILITY_PATH,
        )
        const val CACHE_CONTROL_HEADER = "Cache-Control"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val PRIVATE_DIRECTIVE = "private"
        const val NO_STORE_DIRECTIVE = "no-store"
        const val PUBLIC_DIRECTIVE = "public"
        const val PRAGMA_HEADER = "Pragma"
        const val NO_CACHE = "no-cache"
        const val JSON_CONTENT_TYPE = "application/json;charset=UTF-8"
    }
}
