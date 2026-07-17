package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.document.DocumentDetailView
import ai.icen.fw.application.document.DocumentPageRequest
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.document.DocumentSummaryView
import ai.icen.fw.application.document.DocumentVersionView
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.web.api.ApiPage
import ai.icen.fw.web.api.v1.document.DocumentDetailDto
import ai.icen.fw.web.api.v1.document.DocumentDto
import ai.icen.fw.web.api.v1.document.DocumentPageQuery
import ai.icen.fw.web.api.v1.document.DocumentVersionDto

/**
 * Pure JVM facade for the first formal v1 document read surface.
 *
 * Trusted tenant and user identity are never accepted here. The injected
 * [DocumentQueryService] derives them from FlowWeft SPI context, performs the
 * authorization check, and returns public-safe immutable views.
 */
class DocumentApiReadFacade(
    private val documents: DocumentQueryService,
) {
    private val cursorCodec = DocumentPageCursorCodec()

    fun detail(documentId: String): DocumentDetailDto =
        documents.detail(DocumentApiInputs.documentId(documentId)).toPublicDetail()

    fun page(query: DocumentPageQuery): ApiPage<DocumentDto> {
        val request = DocumentPageRequest(
            cursor = query.cursor?.let(cursorCodec::decode),
            limit = query.limit,
            lifecycleState = query.lifecycleState?.let(::toLifecycleState),
            folderId = query.folderId,
        )
        val result = documents.page(request)
        return ApiPage(
            items = result.items.map { view -> view.toPublicDocument() },
            nextCursor = result.nextCursor?.let(cursorCodec::encode),
        )
    }

    private fun toLifecycleState(value: String): LifecycleState = try {
        LifecycleState.valueOf(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Unsupported document lifecycle state.")
    }

    private fun DocumentDetailView.toPublicDetail(): DocumentDetailDto =
        DocumentDetailDto(document.toPublicDocument(), versions.map { version -> version.toPublicVersion() })

    private fun DocumentSummaryView.toPublicDocument(): DocumentDto = DocumentDto(
        id = id.value,
        documentNumber = documentNumber,
        title = title,
        lifecycleState = lifecycleState.name,
        createdTime = createdTime,
        updatedTime = updatedTime,
        currentVersionId = currentVersionId?.value,
        folderId = folderId,
    )

    private fun DocumentVersionView.toPublicVersion(): DocumentVersionDto = DocumentVersionDto(
        id = id.value,
        versionNumber = versionNumber,
        fileName = fileName,
        contentLength = contentLength,
        createdTime = createdTime,
        updatedTime = updatedTime,
        contentType = contentType,
    )
}
