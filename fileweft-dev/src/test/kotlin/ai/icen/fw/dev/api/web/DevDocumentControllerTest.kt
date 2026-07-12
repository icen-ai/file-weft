package ai.icen.fw.dev.api.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.agent.ConfirmAgentSuggestionService
import ai.icen.fw.application.catalog.DocumentCatalogBindingService
import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService
import ai.icen.fw.application.catalog.DocumentCatalogMutationService
import ai.icen.fw.application.delivery.RetryDocumentDeliveryService
import ai.icen.fw.application.document.AddDocumentVersionCommand
import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.dev.api.catalog.DevCatalogDocumentService
import ai.icen.fw.dev.api.config.DevRole
import ai.icen.fw.dev.api.connector.DevPlatformMirrorDocument
import ai.icen.fw.dev.api.connector.DevPlatformMirrorRecord
import ai.icen.fw.dev.api.connector.DevPlatformMirrorService
import ai.icen.fw.dev.api.security.DevAuthorizationProvider
import ai.icen.fw.dev.api.security.DevPrincipal
import ai.icen.fw.dev.api.security.DevRequestIdentityContext
import ai.icen.fw.dev.api.security.DevTenantProvider
import ai.icen.fw.dev.api.service.DevAccessService
import ai.icen.fw.dev.api.service.DevDeliveryView
import ai.icen.fw.dev.api.service.DevDocumentDetail
import ai.icen.fw.dev.api.service.DevDocumentQueryService
import ai.icen.fw.dev.api.service.DevReviewService
import ai.icen.fw.domain.document.Document
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DevDocumentControllerTest {
    @AfterEach
    fun clearIdentity() {
        DevRequestIdentityContext.clear()
    }

    @Test
    fun `requires catalog guarded mutations and no raw draft service`() {
        val dependencyTypes = DevDocumentController::class.java.declaredConstructors.single().parameterTypes.toSet()

        assertTrue(DocumentCatalogMutationService::class.java in dependencyTypes)
        assertFalse(DocumentDraftService::class.java in dependencyTypes)
        assertFalse(dependencyTypes.any { type -> type.simpleName.contains("Doctor") })
        assertFalse(DevDocumentController::class.java.declaredMethods.any { method -> method.name.contains("doctor", ignoreCase = true) })
    }

    @Test
    fun `keeps legacy Dev Doctor and document log routes unmapped`() {
        val mvc = MockMvcBuilders.standaloneSetup(
            controller(
                Mockito.mock(DocumentCatalogMutationService::class.java),
                Mockito.mock(DevDocumentQueryService::class.java),
            ),
        ).build()

        mvc.perform(get("/api/documents/document-1/doctor"))
            .andExpect(status().isNotFound)
        mvc.perform(post("/api/documents/document-1/doctor/tasks"))
            .andExpect(status().isNotFound)
        mvc.perform(get("/api/documents/document-1/logs"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `renames through the catalog mutation boundary`() {
        val document = document()
        val detail = Mockito.mock(DevDocumentDetail::class.java)
        val catalogMutations = Mockito.mock(DocumentCatalogMutationService::class.java)
        val queries = Mockito.mock(DevDocumentQueryService::class.java)
        Mockito.`when`(catalogMutations.rename(document.id, "目录内新标题")).thenReturn(document)
        Mockito.`when`(queries.detail(document.id)).thenReturn(detail)

        val response = controller(catalogMutations, queries)
            .rename(document.id.value, DevRenameDocumentRequest("目录内新标题"))

        assertSame(detail, response)
        Mockito.verify(catalogMutations).rename(document.id, "目录内新标题")
        Mockito.verify(queries).detail(document.id)
    }

    @Test
    fun `adds versions through the catalog mutation boundary`() {
        val document = document()
        val detail = Mockito.mock(DevDocumentDetail::class.java)
        val bytes = "目录保护版本".toByteArray()
        val file = MockMultipartFile("file", "证明.pdf", "application/pdf", bytes)
        var capturedDocumentId: Identifier? = null
        var capturedCommand: AddDocumentVersionCommand? = null
        val catalogMutations = Mockito.mock(DocumentCatalogMutationService::class.java) { invocation ->
            if (invocation.method.name == "addVersion") {
                capturedDocumentId = invocation.arguments[0] as Identifier
                capturedCommand = invocation.arguments[1] as AddDocumentVersionCommand
                document
            } else {
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val queries = Mockito.mock(DevDocumentQueryService::class.java)
        Mockito.`when`(queries.detail(document.id)).thenReturn(detail)

        val response = controller(catalogMutations, queries).addVersion(document.id.value, "1.1", file)

        val command = requireNotNull(capturedCommand)
        assertSame(detail, response)
        assertEquals(document.id, capturedDocumentId)
        assertEquals("1.1", command.versionNumber)
        assertEquals("证明.pdf", command.fileName)
        assertEquals("application/pdf", command.contentType)
        assertEquals(bytes.size.toLong(), command.contentLength)
        Mockito.verify(queries).detail(document.id)
    }

    @Test
    fun `platform mirror rejects every non-admin role before reading downstream state`() {
        val queries = Mockito.mock(DevDocumentQueryService::class.java)
        val mirror = Mockito.mock(DevPlatformMirrorService::class.java)
        val mvc = MockMvcBuilders.standaloneSetup(
            controller(
                catalogMutations = Mockito.mock(DocumentCatalogMutationService::class.java),
                queries = queries,
                access = accessService(),
                platformMirror = mirror,
            ),
        ).setControllerAdvice(DevApiExceptionHandler()).build()

        listOf(DevRole.VIEWER, DevRole.REVIEWER, DevRole.EDITOR).forEach { role ->
            DevRequestIdentityContext.bind(principal(role))
            try {
                mvc.perform(get("/api/documents/document-1/platform-mirror"))
                    .andExpect(status().isForbidden)
            } finally {
                DevRequestIdentityContext.clear()
            }
        }

        Mockito.verifyNoInteractions(queries, mirror)
    }

    @Test
    fun `platform mirror allows admin and returns only recursively safe fields`() {
        val documentId = Identifier("document-1")
        val deliveries = listOf(delivery())
        val detail = Mockito.mock(DevDocumentDetail::class.java)
        val queries = Mockito.mock(DevDocumentQueryService::class.java)
        val mirror = Mockito.mock(DevPlatformMirrorService::class.java)
        Mockito.`when`(detail.deliveries).thenReturn(deliveries)
        Mockito.`when`(queries.detail(documentId)).thenReturn(detail)
        Mockito.`when`(mirror.readDocument(documentId, deliveries)).thenReturn(
            listOf(
                DevPlatformMirrorRecord(
                    targetId = "compliance",
                    deliveryStatus = "SUCCESS",
                    platform = DevPlatformMirrorDocument(
                        fileName = "approved-contract.pdf",
                        downloadedBytes = 4_096,
                        createdTime = 1_000,
                        updatedTime = 2_000,
                    ),
                ),
            ),
        )
        val mvc = MockMvcBuilders.standaloneSetup(
            controller(
                catalogMutations = Mockito.mock(DocumentCatalogMutationService::class.java),
                queries = queries,
                access = accessService(),
                platformMirror = mirror,
            ),
        ).setControllerAdvice(DevApiExceptionHandler()).build()
        DevRequestIdentityContext.bind(principal(DevRole.ADMIN))

        val response = mvc.perform(get("/api/documents/document-1/platform-mirror"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .contentAsString

        val json = ObjectMapper().readTree(response)
        assertEquals("approved-contract.pdf", json.path(0).path("platform").path("fileName").asText())
        assertEquals(setOf("targetId", "deliveryStatus", "platform"), json.path(0).properties().map { it.key }.toSet())
        assertEquals(
            setOf("fileName", "downloadedBytes", "createdTime", "updatedTime"),
            json.path(0).path("platform").properties().map { it.key }.toSet(),
        )
        assertNoSensitiveData(
            json,
            setOf(
                "tenant-internal",
                "https://storage.internal/object?capability=secret",
                "idempotency-secret",
                "platform-shared-secret",
            ),
        )
        Mockito.verify(queries).detail(documentId)
        Mockito.verify(mirror).readDocument(documentId, deliveries)
    }

    private fun controller(
        catalogMutations: DocumentCatalogMutationService,
        queries: DevDocumentQueryService,
        access: DevAccessService = Mockito.mock(DevAccessService::class.java),
        platformMirror: DevPlatformMirrorService = Mockito.mock(DevPlatformMirrorService::class.java),
    ): DevDocumentController = DevDocumentController(
        catalogMutations = catalogMutations,
        downloads = Mockito.mock(DocumentDownloadService::class.java),
        catalogDrafts = Mockito.mock(DevCatalogDocumentService::class.java),
        catalogBindings = Mockito.mock(DocumentCatalogBindingService::class.java),
        reviews = Mockito.mock(DevReviewService::class.java),
        lifecycle = Mockito.mock(DocumentCatalogLifecycleService::class.java),
        access = access,
        queries = queries,
        retryDeliveries = Mockito.mock(RetryDocumentDeliveryService::class.java),
        agentSuggestions = Mockito.mock(ConfirmAgentSuggestionService::class.java),
        platformMirror = platformMirror,
    )

    private fun accessService(): DevAccessService = DevAccessService(
        tenants = DevTenantProvider(),
        users = object : UserRealmProvider {
            override fun currentUser(): UserIdentity? = DevRequestIdentityContext.current()?.toUserIdentity()

            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorization = DevAuthorizationProvider(),
    )

    private fun principal(role: DevRole): DevPrincipal = DevPrincipal(
        id = Identifier("${role.name.lowercase()}-user"),
        username = "${role.name.lowercase()}@alpha",
        displayName = role.name,
        tenantId = Identifier("tenant-internal"),
        role = role,
    )

    private fun delivery(): DevDeliveryView = DevDeliveryView(
        id = "delivery-1",
        profileId = "regulated",
        targetId = "compliance",
        displayName = "Compliance archive",
        connectorId = "complianceConnector",
        requirement = "REQUIRED",
        ownerRef = "compliance-ops",
        status = "SUCCESS",
        externalId = null,
        errorMessage = null,
        retryCount = 0,
        removalStatus = "NONE",
        removalErrorMessage = null,
        removalRetryCount = 0,
        deliveryGeneration = 1,
        updatedTime = 2_000,
    )

    private fun assertNoSensitiveData(node: JsonNode, forbiddenValues: Set<String>) {
        when {
            node.isObject -> node.properties().forEach { (name, value) ->
                val normalizedName = name.lowercase().replace("_", "").replace("-", "")
                val forbiddenKeyFragments = listOf("downloaduri", "lastidempotencykey", "tenantid", "token", "secret")
                assertFalse(forbiddenKeyFragments.any(normalizedName::contains), "Sensitive key was exposed: $name")
                assertNoSensitiveData(value, forbiddenValues)
            }
            node.isArray -> node.forEach { child -> assertNoSensitiveData(child, forbiddenValues) }
            node.isTextual -> assertFalse(
                forbiddenValues.any { forbidden -> node.textValue().contains(forbidden) },
                "Sensitive value was exposed: ${node.textValue()}",
            )
        }
    }

    private fun document(): Document = Document(
        id = Identifier("document-1"),
        tenantId = Identifier("tenant-1"),
        assetId = Identifier("asset-1"),
        documentNumber = "DOC-1",
        title = "目录保护文档",
    )
}
