package ai.icen.fw.web.spring.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogBindingCommand
import ai.icen.fw.application.document.DocumentDetailView
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentPageCursor
import ai.icen.fw.application.document.DocumentPageRequest
import ai.icen.fw.application.document.DocumentPageResult
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.document.DocumentSummaryView
import ai.icen.fw.application.document.DocumentVersionView
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryService
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryRepository
import ai.icen.fw.application.retention.DeletionVisibilityFence
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.application.retention.DeletionVisibilityQuerySource
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentReadController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentCatalogController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentCatalogRequestFailureHandler
import ai.icen.fw.web.runtime.v1.catalog.DocumentCatalogApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentSyncStatusController
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.charset.StandardCharsets
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun catalogBindingFixture(): DocumentCatalogBindingCommand = object : DocumentCatalogBindingCommand {
    override fun move(documentId: Identifier, folderId: String): Document = Document(
        documentId,
        Identifier("tenant-a"),
        Identifier("asset-a"),
        "DOC-1",
        "Document",
    )
}

class FileWeftWebBoot3AutoConfigurationTest {
    private val contextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                FileWeftWebBoot3AutoConfiguration::class.java,
            ),
        )

    @Test
    fun `registers its Boot 3 auto configuration resource`() {
        val resource = requireNotNull(
            javaClass.classLoader.getResourceAsStream(
                "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports",
            ),
        )

        val registrations = resource.use { input -> String(input.readBytes(), StandardCharsets.UTF_8) }

        assertTrue(registrations.lineSequence().any { line ->
            line.trim() == FileWeftWebBoot3AutoConfiguration::class.java.name
        })
    }

    @Test
    fun `does not expose document read routes until the host supplies DocumentQueryService`() {
        contextRunner.run { context ->
            assertNull(context.getBeanProvider(V1DocumentReadController::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(ai.icen.fw.web.runtime.v1.document.DocumentApiReadFacade::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(ai.icen.fw.web.runtime.v1.V1ApiResponseFactory::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(DocumentCatalogApiFacade::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(V1DocumentCatalogController::class.java).getIfAvailable())
            assertNull(context.getBeanProvider(V1DocumentCatalogRequestFailureHandler::class.java).getIfAvailable())
        }
    }

    @Test
    fun `assembles a read-only catalog capability and fails closed for move without one command`() {
        contextRunner
            .withBean(DocumentCatalogAccessService::class.java, Supplier { catalogAccess() })
            .run { context ->
                assertNotNull(context.getBeanProvider(DocumentCatalogApiFacade::class.java).getIfAvailable())
                assertNotNull(context.getBeanProvider(V1DocumentCatalogController::class.java).getIfAvailable())
                assertNotNull(
                    context.getBeanProvider(V1DocumentCatalogRequestFailureHandler::class.java).getIfAvailable(),
                )
                assertFailsWith<IllegalStateException> {
                    context.getBean(DocumentCatalogApiFacade::class.java).move("document-a", "child")
                }
            }
    }

    @Test
    fun `uses one catalog binding command and rejects ambiguous candidates`() {
        contextRunner
            .withBean(DocumentCatalogAccessService::class.java, Supplier { catalogAccess() })
            .withUserConfiguration(UniqueCatalogBindingConfiguration::class.java)
            .run { context ->
                val result = context.getBean(DocumentCatalogApiFacade::class.java).move("document-a", "child")
                assertEquals("document-a", result.documentId)
            }

        contextRunner
            .withBean(DocumentCatalogAccessService::class.java, Supplier { catalogAccess() })
            .withUserConfiguration(AmbiguousCatalogBindingConfiguration::class.java)
            .run { context ->
                assertFailsWith<IllegalStateException> {
                    context.getBean(DocumentCatalogApiFacade::class.java).move("document-a", "child")
                }
            }
    }

    @Test
    fun `exposes sync status independently from the general document query service`() {
        contextRunner
            .withBean(
                DocumentSyncStatusQueryService::class.java,
                Supplier { syncStatusQueryService() },
            )
            .run { context ->
                assertNull(context.getBeanProvider(V1DocumentReadController::class.java).getIfAvailable())
                assertNotNull(
                    context.getBeanProvider(V1DocumentSyncStatusController::class.java).getIfAvailable(),
                )
                assertNotNull(
                    context.getBeanProvider(
                        ai.icen.fw.web.runtime.v1.document.DocumentSyncStatusApiFacade::class.java,
                    ).getIfAvailable(),
                )
            }
    }

    @Test
    fun `returns an authorized detail with only public document fields`() {
        val queries = RecordingQueries(detail = detail("document-1"))

        withDocumentApi(documentQueryService(queries)) { context ->
            val result = mockMvc(context)
                .perform(get("/fileweft/v1/documents/document-1"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.document.id").value("document-1"))
                .andExpect(jsonPath("$.data.document.documentNumber").value("DOC-document-1"))
                .andExpect(jsonPath("$.data.document.lifecycleState").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.versions[0].id").value("version-1"))
                .andExpect(jsonPath("$.data.versions[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.data.document.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.document.assetId").doesNotExist())
                .andExpect(jsonPath("$.data.document.fileObjectId").doesNotExist())
                .andExpect(jsonPath("$.data.versions[0].contentHash").doesNotExist())
                .andExpect(jsonPath("$.data.versions[0].storagePath").doesNotExist())
                .andReturn()

            assertEquals("document-1", queries.lastDetailDocumentId?.value)
            assertFalse(result.response.contentAsString.contains("tenant-1"))
            assertFalse(result.response.contentAsString.contains("internal-storage"))
            assertFalse(result.response.contentAsString.contains("\"success\""))
            assertFalse(result.response.contentAsString.contains("\"failure\""))
        }
    }

    @Test
    fun `parses page parameters as bounded strings and returns an opaque cursor`() {
        val queries = RecordingQueries(
            page = DocumentPageResult(
                items = listOf(summary("document-2", LifecycleState.PUBLISHED)),
                nextCursor = DocumentPageCursor(400, Identifier("document-2")),
            ),
        )
        val folders = RecordingFolderReadAccess()

        withDocumentApi(documentQueryService(queries, folderReadAccess = folders)) { context ->
            mockMvc(context)
                .perform(
                    get("/fileweft/v1/documents")
                        .param("limit", "5")
                        .param("lifecycleState", "PUBLISHED")
                        .param("folderId", "finance"),
                )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].id").value("document-2"))
                .andExpect(jsonPath("$.data.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].storagePath").doesNotExist())

            assertEquals("finance", folders.lastRequiredFolderId)
            assertEquals(5, queries.lastPageRequest?.limit)
            assertEquals(LifecycleState.PUBLISHED, queries.lastPageRequest?.lifecycleState)
            assertEquals("finance", queries.lastPageRequest?.folderId)
            assertNull(queries.lastPageRequest?.cursor)
        }
    }

    @Test
    fun `maps a missing trusted user to a fixed 401 response`() {
        val queries = RecordingQueries(detail = detail("document-1"))

        withDocumentApi(documentQueryService(queries, currentUser = null)) { context ->
            val body = mockMvc(context)
                .perform(get("/fileweft/v1/documents/document-1"))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Authentication is required."))
                .andExpect(jsonPath("$.error.message").value("Authentication is required."))
                .andReturn()
                .response
                .contentAsString

            assertFalse(body.contains("current user"))
            assertEquals(0, queries.detailCalls)
        }
    }

    @Test
    fun `maps a policy denial to a fixed 403 response without policy text`() {
        val queries = RecordingQueries(detail = detail("document-1"))

        withDocumentApi(
            documentQueryService(
                queries = queries,
                authorizationDecision = AuthorizationDecision(false, "host-policy: internal-restricted-folder"),
            ),
        ) { context ->
            val body = mockMvc(context)
                .perform(get("/fileweft/v1/documents/document-1"))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access denied."))
                .andReturn()
                .response
                .contentAsString

            assertFalse(body.contains("host-policy"))
            assertEquals(0, queries.detailCalls)
        }
    }

    @Test
    fun `maps missing documents to a fixed 404 response without path input`() {
        val queries = RecordingQueries(detail = null)

        withDocumentApi(documentQueryService(queries)) { context ->
            val body = mockMvc(context)
                .perform(get("/fileweft/v1/documents/private-document"))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource was not found."))
                .andReturn()
                .response
                .contentAsString

            assertFalse(body.contains("private-document"))
        }
    }

    @Test
    fun `rejects malformed string parameters without echoing them`() {
        val queries = RecordingQueries()

        withDocumentApi(documentQueryService(queries)) { context ->
            val body = mockMvc(context)
                .perform(get("/fileweft/v1/documents").param("limit", "12e3"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is invalid."))
                .andReturn()
                .response
                .contentAsString

            assertFalse(body.contains("12e3"))
            assertEquals(0, queries.pageCalls)
        }
    }

    @Test
    fun `rejects a leading-zero page limit with the stable 400 contract`() {
        val queries = RecordingQueries()

        withDocumentApi(documentQueryService(queries)) { context ->
            val body = mockMvc(context)
                .perform(get("/fileweft/v1/documents").param("limit", "001"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is invalid."))
                .andReturn()
                .response
                .contentAsString

            assertFalse(body.contains("001"))
            assertEquals(0, queries.pageCalls)
        }
    }

    @Test
    fun `rejects a damaged cursor with the stable 400 contract without echoing it`() {
        val queries = RecordingQueries()

        withDocumentApi(documentQueryService(queries)) { context ->
            val body = mockMvc(context)
                .perform(get("/fileweft/v1/documents").param("cursor", "***"))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request is invalid."))
                .andReturn()
                .response
                .contentAsString

            assertFalse(body.contains("***"))
            assertEquals(0, queries.pageCalls)
        }
    }

    @Test
    fun `reports folder filtering as unavailable when the host has no catalog read SPI`() {
        val queries = RecordingQueries()

        withDocumentApi(documentQueryService(queries)) { context ->
            val body = mockMvc(context)
                .perform(get("/fileweft/v1/documents").param("folderId", "finance"))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The requested feature is unavailable."))
                .andReturn()
                .response
                .contentAsString

            assertFalse(body.contains("finance"))
            assertEquals(0, queries.pageCalls)
        }
    }

    @Test
    fun `uses a safe 500 response for unexpected failures and carries an optional trace id`() {
        val queries = RecordingQueries(failure = Exception("jdbc://internal-storage/password"))
        val traceProvider = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = TraceContext(Identifier("trace-public-1"))
        }

        withDocumentApi(documentQueryService(queries), traceProvider) { context ->
            val body = mockMvc(context)
                .perform(get("/fileweft/v1/documents/document-1"))
                .andExpect(status().isInternalServerError)
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.traceId").value("trace-public-1"))
                .andReturn()
                .response
                .contentAsString

            assertFalse(body.contains("jdbc://"))
            assertFalse(body.contains("password"))
        }
    }

    @Test
    fun `does not accept tenant or user request parameters in public handlers`() {
        val handlerMethods = V1DocumentReadController::class.java.declaredMethods
            .filter { method -> method.name == "detail" || method.name == "page" }

        assertTrue(handlerMethods.isNotEmpty())
        assertTrue(handlerMethods.all { method ->
            method.parameterTypes.all { type -> type == String::class.java }
        })
    }

    private fun withDocumentApi(
        service: DocumentQueryService,
        traceContextProvider: TraceContextProvider? = null,
        assertion: (ApplicationContext) -> Unit,
    ) {
        var runner = contextRunner.withBean(DocumentQueryService::class.java, Supplier { service })
        if (traceContextProvider != null) {
            runner = runner.withBean(TraceContextProvider::class.java, Supplier { traceContextProvider })
        }
        runner.run { context ->
            assertNotNull(context.getBeanProvider(V1DocumentReadController::class.java).getIfAvailable())
            assertion(context)
        }
    }

    private fun mockMvc(context: ApplicationContext): MockMvc = MockMvcBuilders.standaloneSetup(
        context.getBean(V1DocumentReadController::class.java),
    ).setMessageConverters(
        MappingJackson2HttpMessageConverter(context.getBean(ObjectMapper::class.java)),
    ).build()

    private fun documentQueryService(
        queries: RecordingQueries,
        currentUser: UserIdentity? = UserIdentity(Identifier("user-1"), "User One"),
        authorizationDecision: AuthorizationDecision = AuthorizationDecision(true),
        folderReadAccess: DocumentFolderReadAccess? = null,
    ): DocumentQueryService = DocumentQueryService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity? = currentUser

            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = authorizationDecision
        },
        queries = queries,
        transaction = DirectTransaction,
        folderReadAccess = folderReadAccess,
    )

    private fun syncStatusQueryService(): DocumentSyncStatusQueryService = DocumentSyncStatusQueryService(
        Mockito.mock(TenantProvider::class.java),
        Mockito.mock(UserRealmProvider::class.java),
        Mockito.mock(AuthorizationProvider::class.java),
        Mockito.mock(DocumentSyncStatusQueryRepository::class.java),
        Mockito.mock(ApplicationTransaction::class.java),
    )

    private fun catalogAccess(): DocumentCatalogAccessService = DocumentCatalogAccessService(
        object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
        },
        object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-a"), "User A")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
        },
        object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = listOf(
                DocumentCatalogFolder("root", null, "Root"),
                DocumentCatalogFolder("child", "root", "Child"),
            )
        },
    )

    @Configuration(proxyBeanMethods = false)
    class UniqueCatalogBindingConfiguration {
        @Bean
        fun catalogBinding(): DocumentCatalogBindingCommand = catalogBindingFixture()
    }

    @Configuration(proxyBeanMethods = false)
    class AmbiguousCatalogBindingConfiguration {
        @Bean
        @Primary
        fun firstCatalogBinding(): DocumentCatalogBindingCommand = catalogBindingFixture()

        @Bean
        fun secondCatalogBinding(): DocumentCatalogBindingCommand = catalogBindingFixture()
    }

    private fun detail(documentId: String): DocumentDetailView = DocumentDetailView(
        document = summary(documentId, LifecycleState.PUBLISHED, Identifier("version-1")),
        versions = listOf(
            DocumentVersionView(
                id = Identifier("version-1"),
                versionNumber = "1.0",
                fileName = "公开文件.pdf",
                contentLength = 64,
                createdTime = 100,
                updatedTime = 200,
                contentType = "application/pdf",
            ),
        ),
    )

    private fun summary(
        documentId: String,
        lifecycleState: LifecycleState,
        currentVersionId: Identifier? = null,
    ): DocumentSummaryView = DocumentSummaryView(
        id = Identifier(documentId),
        documentNumber = "DOC-$documentId",
        title = "Public document $documentId",
        lifecycleState = lifecycleState,
        createdTime = 100,
        updatedTime = 200,
        currentVersionId = currentVersionId,
        folderId = "finance",
    )

    private class RecordingQueries(
        private val detail: DocumentDetailView? = null,
        private val page: DocumentPageResult = DocumentPageResult(emptyList()),
        private val failure: Exception? = null,
    ) : DocumentQueryRepository, DeletionVisibilityQuerySource {
        var detailCalls: Int = 0
        var pageCalls: Int = 0
        var lastDetailDocumentId: Identifier? = null
        var lastPageRequest: DocumentPageRequest? = null

        override fun findDetail(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentDetailView? {
            detailCalls++
            lastDetailDocumentId = documentId
            failure?.let { throw it }
            return detail
        }

        override fun findPage(
            tenantId: Identifier,
            request: DocumentPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentPageResult {
            pageCalls++
            lastPageRequest = request
            failure?.let { throw it }
            return page
        }

        override fun deletionVisibilityQuery(): DeletionVisibilityQuery = object : DeletionVisibilityQuery {
            override fun findFence(
                tenantId: Identifier,
                resourceType: String,
                resourceId: Identifier,
            ): DeletionVisibilityFence? = null
        }
    }

    private class RecordingFolderReadAccess : DocumentFolderReadAccess {
        var lastRequiredFolderId: String? = null

        override fun requireFolderForDocumentRead(folderId: String) {
            lastRequiredFolderId = folderId
        }

        override fun readableFolderIds(): Set<String> = setOf("finance")
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
