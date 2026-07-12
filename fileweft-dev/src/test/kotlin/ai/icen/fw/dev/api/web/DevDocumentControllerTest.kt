package ai.icen.fw.dev.api.web

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
import ai.icen.fw.dev.api.connector.DevPlatformMirrorService
import ai.icen.fw.dev.api.service.DevDocumentDetail
import ai.icen.fw.dev.api.service.DevDocumentQueryService
import ai.icen.fw.dev.api.service.DevReviewService
import ai.icen.fw.domain.document.Document
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
    @Test
    fun `requires catalog guarded mutations and no raw draft service`() {
        val dependencyTypes = DevDocumentController::class.java.declaredConstructors.single().parameterTypes.toSet()

        assertTrue(DocumentCatalogMutationService::class.java in dependencyTypes)
        assertFalse(DocumentDraftService::class.java in dependencyTypes)
        assertFalse(dependencyTypes.any { type -> type.simpleName.contains("Doctor") })
        assertFalse(DevDocumentController::class.java.declaredMethods.any { method -> method.name.contains("doctor", ignoreCase = true) })
    }

    @Test
    fun `keeps legacy Dev Doctor routes unmapped`() {
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

    private fun controller(
        catalogMutations: DocumentCatalogMutationService,
        queries: DevDocumentQueryService,
    ): DevDocumentController = DevDocumentController(
        catalogMutations = catalogMutations,
        downloads = Mockito.mock(DocumentDownloadService::class.java),
        catalogDrafts = Mockito.mock(DevCatalogDocumentService::class.java),
        catalogBindings = Mockito.mock(DocumentCatalogBindingService::class.java),
        reviews = Mockito.mock(DevReviewService::class.java),
        lifecycle = Mockito.mock(DocumentCatalogLifecycleService::class.java),
        queries = queries,
        retryDeliveries = Mockito.mock(RetryDocumentDeliveryService::class.java),
        agentSuggestions = Mockito.mock(ConfirmAgentSuggestionService::class.java),
        platformMirror = Mockito.mock(DevPlatformMirrorService::class.java),
    )

    private fun document(): Document = Document(
        id = Identifier("document-1"),
        tenantId = Identifier("tenant-1"),
        assetId = Identifier("asset-1"),
        documentNumber = "DOC-1",
        title = "目录保护文档",
    )
}
