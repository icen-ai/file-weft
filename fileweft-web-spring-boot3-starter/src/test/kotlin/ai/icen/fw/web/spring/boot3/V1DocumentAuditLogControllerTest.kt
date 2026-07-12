package ai.icen.fw.web.spring.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.audit.DocumentAuditLogPageCursor
import ai.icen.fw.application.audit.DocumentAuditLogPageRequest
import ai.icen.fw.application.audit.DocumentAuditLogPageResult
import ai.icen.fw.application.audit.DocumentAuditLogQueryRepository
import ai.icen.fw.application.audit.DocumentAuditLogQueryService
import ai.icen.fw.application.audit.DocumentAuditLogView
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.audit.DocumentAuditLogApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentAuditLogController
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals

class V1DocumentAuditLogControllerTest {
    @Test
    fun `returns the redacted DTO opaque cursor trace and private response headers`() {
        val fixture = Boot3AuditLogControllerFixture()
        val mvc = mvc(fixture, traceProvider(TRACE_ID.value))

        val body = mvc.perform(get(PATH).param("limit", "1"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andExpect(header().string(X_CONTENT_TYPE_OPTIONS, NOSNIFF))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID.value))
            .andExpect(jsonPath("$.data.items[0].id").value("audit-b"))
            .andExpect(jsonPath("$.data.items[0].action").value("document:publish"))
            .andExpect(jsonPath("$.data.items[0].createdTime").value(200))
            .andExpect(jsonPath("$.data.items[0].operatorId").value("external-user-9"))
            .andExpect(jsonPath("$.data.items[0].operatorName").value("审核员乙"))
            .andExpect(jsonPath("$.data.items[0].traceId").value("trace-audit-b"))
            .andExpect(jsonPath("$.data.nextCursor").isString)
            .andExpect(jsonPath("$.data.items[0].tenantId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].details").doesNotExist())
            .andReturn().response.contentAsString

        assertEquals(1, fixture.pageRequests.single().limit)
        val cursor = ObjectMapper().readTree(body).path("data").path("nextCursor").asText()
        mvc.perform(get(PATH).param("cursor", cursor).param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].id").value("audit-a"))
            .andExpect(jsonPath("$.data.nextCursor").isEmpty())

        assertEquals(2, fixture.pageRequests.last().limit)
        assertEquals(200, fixture.pageRequests.last().cursor?.createdTime)
        assertEquals("audit-b", fixture.pageRequests.last().cursor?.id?.value)
    }

    @Test
    fun `maps invalid limits and cursors to fixed 400 responses before querying`() {
        val fixture = Boot3AuditLogControllerFixture()
        val mvc = mvc(fixture)

        listOf("0", "101", "001", "-1", "12e3", "1000").forEach { invalidLimit ->
            mvc.perform(get(PATH).param("limit", invalidLimit))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is invalid."))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
                .andExpect(content().string(not(containsString(invalidLimit))))
        }
        mvc.perform(get(PATH).param("cursor", "***private-cursor***"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(content().string(not(containsString("private-cursor"))))

        assertEquals(0, fixture.pageRequests.size)
        assertEquals(0, fixture.authorizationRequests.size)
    }

    @Test
    fun `maps denied missing and unavailable capabilities without leaking context`() {
        val denied = Boot3AuditLogControllerFixture().apply {
            authorizationDecision = AuthorizationDecision(false, "host-policy=secret")
        }
        mvc(denied).perform(get(PATH))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("FORBIDDEN"))
            .andExpect(jsonPath("$.message").value("Access denied."))
            .andExpect(content().string(not(containsString("host-policy"))))
        assertEquals(0, denied.pageRequests.size)

        val missing = Boot3AuditLogControllerFixture().apply { documentMissing = true }
        mvc(missing).perform(get("/fileweft/v1/documents/private-document/logs"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Resource was not found."))
            .andExpect(content().string(not(containsString("private-document"))))

        mvc(DocumentAuditLogApiFacade(emptyList())).perform(get(PATH))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("The requested feature is unavailable."))
    }

    @Test
    fun `rejects explicit HEAD with Allow GET without invoking the capability`() {
        val fixture = Boot3AuditLogControllerFixture()

        mvc(fixture).perform(head(PATH))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andExpect(header().string(X_CONTENT_TYPE_OPTIONS, NOSNIFF))

        assertEquals(0, fixture.pageRequests.size)
        assertEquals(0, fixture.authorizationRequests.size)
    }

    private fun mvc(
        fixture: Boot3AuditLogControllerFixture,
        traces: TraceContextProvider? = null,
    ): MockMvc = mvc(DocumentAuditLogApiFacade(fixture.service()), traces)

    private fun mvc(
        facade: DocumentAuditLogApiFacade,
        traces: TraceContextProvider? = null,
    ): MockMvc = MockMvcBuilders.standaloneSetup(
        V1DocumentAuditLogController(facade, V1ApiResponseFactory(), traces),
    ).setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper())).build()

    private fun traceProvider(traceId: String): TraceContextProvider = object : TraceContextProvider {
        override fun currentTraceContext(): TraceContext = TraceContext(Identifier(traceId))
    }

    private companion object {
        const val PATH = "/fileweft/v1/documents/document-1/logs"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
        val TRACE_ID = Identifier("trace-http-audit-3")
    }
}

internal class Boot3AuditLogControllerFixture {
    var authorizationDecision: AuthorizationDecision = AuthorizationDecision(true)
    var documentMissing: Boolean = false
    val authorizationRequests = ArrayList<AuthorizationRequest>()
    val pageRequests = ArrayList<DocumentAuditLogPageRequest>()

    fun service(): DocumentAuditLogQueryService = DocumentAuditLogQueryService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("reviewer-1"), "Reviewer One")

            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
                authorizationRequests += request
                return authorizationDecision
            }
        },
        queries = object : DocumentAuditLogQueryRepository {
            override fun findPage(
                tenantId: Identifier,
                documentId: Identifier,
                request: DocumentAuditLogPageRequest,
                folderReadScope: DocumentFolderReadScope?,
            ): DocumentAuditLogPageResult? {
                pageRequests += request
                if (documentMissing) return null
                return if (request.cursor == null) {
                    val item = log("audit-b", "document:publish", 200, "trace-audit-b")
                    DocumentAuditLogPageResult(
                        documentId,
                        listOf(item),
                        DocumentAuditLogPageCursor(item.createdTime, item.id),
                    )
                } else {
                    DocumentAuditLogPageResult(
                        documentId,
                        listOf(log("audit-a", "document:create", 100, null)),
                    )
                }
            }
        },
        transaction = object : ApplicationTransaction {
            override fun <T> execute(action: () -> T): T = action()
        },
    )

    private fun log(id: String, action: String, createdTime: Long, traceId: String?): DocumentAuditLogView =
        DocumentAuditLogView(
            id = Identifier(id),
            action = action,
            createdTime = createdTime,
            operatorId = Identifier("external-user-9"),
            operatorName = "审核员乙",
            traceId = traceId?.let(::Identifier),
        )
}
