package com.fileweft.dev.api.web

import com.fileweft.application.agent.ConfirmAgentSuggestionService
import com.fileweft.application.catalog.DocumentCatalogBindingService
import com.fileweft.application.catalog.DocumentCatalogLifecycleService
import com.fileweft.application.catalog.DocumentCatalogMutationService
import com.fileweft.application.delivery.RetryDocumentDeliveryService
import com.fileweft.application.doctor.ScheduleDocumentDoctorService
import com.fileweft.application.document.AddDocumentVersionCommand
import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.catalog.DevCatalogDocumentService
import com.fileweft.dev.api.connector.DevPlatformMirrorService
import com.fileweft.dev.api.service.DevDocumentDetail
import com.fileweft.dev.api.service.DevDocumentQueryService
import com.fileweft.dev.api.service.DevOperationsService
import com.fileweft.dev.api.service.DevReviewService
import com.fileweft.domain.document.Document
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.mock.web.MockMultipartFile
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
        operations = Mockito.mock(DevOperationsService::class.java),
        retryDeliveries = Mockito.mock(RetryDocumentDeliveryService::class.java),
        doctorScheduler = Mockito.mock(ScheduleDocumentDoctorService::class.java),
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
