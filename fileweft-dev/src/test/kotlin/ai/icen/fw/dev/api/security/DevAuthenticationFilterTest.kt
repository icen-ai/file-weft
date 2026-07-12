package ai.icen.fw.dev.api.security

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.dev.api.config.DevRole
import ai.icen.fw.dev.api.web.DevApiExceptionHandler
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

class DevAuthenticationFilterTest {
    private lateinit var mvc: MockMvc
    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        val sessions = DevSessionStore()
        token = sessions.create(
            DevPrincipal(
                id = Identifier("viewer-1"),
                username = "viewer@test",
                displayName = "Test viewer",
                tenantId = Identifier("tenant-private"),
                role = DevRole.VIEWER,
            ),
        )
        val filter = DevAuthenticationFilter(
            sessions = sessions,
            responses = V1ApiResponseFactory(),
            objectMapper = jacksonObjectMapper(),
            traces = DevTraceContextProvider(),
        )
        mvc = MockMvcBuilders.standaloneSetup(CacheProbeController())
            .setControllerAdvice(DevApiExceptionHandler(), CacheProbeExceptionHandler())
            .addFilters<StandaloneMockMvcBuilder>(filter)
            .build()
    }

    @AfterEach
    fun clearIdentity() {
        DevRequestIdentityContext.clear()
    }

    @Test
    fun `adds private no-store headers to authenticated Dev and formal GET responses`() {
        mvc.perform(get("/api/cache-probe").bearer())
            .andExpect(status().isOk)
            .andExpectPrivateNoStore()
        mvc.perform(get("/fileweft/v1/cache-probe").bearer())
            .andExpect(status().isOk)
            .andExpectPrivateNoStore()
        mvc.perform(get("/fileweft/plugins").bearer())
            .andExpect(status().isOk)
            .andExpectPrivateNoStore()
        mvc.perform(get("/fileweft/doctor").bearer())
            .andExpect(status().isOk)
            .andExpectPrivateNoStore()
    }

    @Test
    fun `adds private no-store headers before unauthenticated formal and Dev short circuits`() {
        mvc.perform(get("/api/cache-probe"))
            .andExpect(status().isUnauthorized)
            .andExpectPrivateNoStore()
        mvc.perform(get("/fileweft/v1/cache-probe"))
            .andExpect(status().isUnauthorized)
            .andExpectPrivateNoStore()
    }

    @Test
    fun `formal compatibility aliases use the formal unauthenticated envelope`() {
        listOf("/fileweft/plugins", "/fileweft/doctor").forEach { path ->
            mvc.perform(get(path))
                .andExpect(status().isUnauthorized)
                .andExpect(content().contentType(JSON_CONTENT_TYPE))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, FILEWEFT_DEV_BEARER_CHALLENGE))
                .andExpect(header().string(X_CONTENT_TYPE_OPTIONS, NOSNIFF))
                .andExpectPrivateNoStore()
                .andExpect(jsonPath("$.*").value(org.hamcrest.Matchers.hasSize<Any>(5)))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Authentication is required."))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.message").value("Authentication is required."))
                .andExpect(jsonPath("$.traceId").isEmpty())
        }
    }

    @Test
    fun `adds private no-store headers to public login and health responses`() {
        mvc.perform(post("/api/auth/login"))
            .andExpect(status().isOk)
            .andExpectPrivateNoStore()
        mvc.perform(get("/api/health"))
            .andExpect(status().isOk)
            .andExpectPrivateNoStore()
        mvc.perform(get("/fileweft/v1/health"))
            .andExpect(status().isOk)
            .andExpectPrivateNoStore()
        mvc.perform(get("/fileweft/health"))
            .andExpect(status().isOk)
            .andExpectPrivateNoStore()
    }

    @Test
    fun `retains private no-store headers on forbidden not-found and exceptional responses`() {
        mvc.perform(get("/api/cache-probe/forbidden").bearer())
            .andExpect(status().isForbidden)
            .andExpectPrivateNoStore()
        mvc.perform(get("/api/cache-probe/missing").bearer())
            .andExpect(status().isNotFound)
            .andExpectPrivateNoStore()
        mvc.perform(get("/api/cache-probe/failure").bearer())
            .andExpect(status().isInternalServerError)
            .andExpectPrivateNoStore()
    }

    @Test
    fun `does not replace a stricter download cache-control response`() {
        mvc.perform(get("/fileweft/v1/cache-probe/download").bearer())
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, STRICT_DOWNLOAD_CACHE_CONTROL))
            .andExpect(header().string(HttpHeaders.PRAGMA, NO_CACHE))
    }

    @Test
    fun `prevents downstream responses from weakening or resetting private no-store headers`() {
        mvc.perform(get("/api/cache-probe/weaker-cache-policy").bearer())
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=3600, private, no-store"))
            .andExpect(header().string(HttpHeaders.PRAGMA, NO_CACHE))
        mvc.perform(get("/api/cache-probe/reset").bearer())
            .andExpect(status().isOk)
            .andExpectPrivateNoStore()
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.bearer() =
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")

    private fun ResultActions.andExpectPrivateNoStore(): ResultActions =
        andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andExpect(header().string(HttpHeaders.PRAGMA, NO_CACHE))

    @RestController
    private class CacheProbeController {
        @GetMapping("/api/cache-probe", "/fileweft/v1/cache-probe", "/fileweft/plugins", "/fileweft/doctor")
        fun authenticated(): Map<String, String> = mapOf("tenantId" to "tenant-private")

        @PostMapping("/api/auth/login")
        fun login(): Map<String, String> = mapOf("token" to "private-session", "tenantId" to "tenant-private")

        @GetMapping("/api/health", "/fileweft/v1/health", "/fileweft/health")
        fun health(): Map<String, String> = mapOf("status" to "UP")

        @GetMapping("/api/cache-probe/forbidden")
        fun forbidden(): Nothing = throw SecurityException("Policy details must not be returned.")

        @GetMapping("/api/cache-probe/missing")
        fun missing(): Nothing = throw NoSuchElementException("Tenant resource details must not be returned.")

        @GetMapping("/api/cache-probe/failure")
        fun failure(): Nothing = throw IllegalStateException("Internal tenant failure details must not be returned.")

        @GetMapping("/fileweft/v1/cache-probe/download")
        fun download(): ResponseEntity<String> = ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, STRICT_DOWNLOAD_CACHE_CONTROL)
            .header(HttpHeaders.PRAGMA, NO_CACHE)
            .body("download")

        @GetMapping("/api/cache-probe/weaker-cache-policy")
        fun weakerCachePolicy(): ResponseEntity<String> = ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
            .body("private")

        @GetMapping("/api/cache-probe/reset")
        fun reset(response: HttpServletResponse): Map<String, String> {
            response.reset()
            return mapOf("tenantId" to "tenant-private")
        }
    }

    @RestControllerAdvice
    private class CacheProbeExceptionHandler {
        @ExceptionHandler(IllegalStateException::class)
        fun internalError(): ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf("code" to "INTERNAL_ERROR"))
    }

    private companion object {
        const val PRIVATE_NO_STORE = "private, no-store"
        const val NO_CACHE = "no-cache"
        const val STRICT_DOWNLOAD_CACHE_CONTROL = "private, no-store, no-cache, max-age=0"
        const val JSON_CONTENT_TYPE = "application/json;charset=UTF-8"
        const val FILEWEFT_DEV_BEARER_CHALLENGE = "Bearer realm=\"fileweft-dev\""
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
    }
}
