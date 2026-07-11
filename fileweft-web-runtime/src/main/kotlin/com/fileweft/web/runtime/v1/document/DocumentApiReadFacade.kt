package com.fileweft.web.runtime.v1.document

import com.fileweft.application.document.DocumentDetailView
import com.fileweft.application.document.DocumentPageRequest
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.document.DocumentVersionView
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import com.fileweft.web.api.ApiPage
import com.fileweft.web.api.v1.document.DocumentDetailDto
import com.fileweft.web.api.v1.document.DocumentDto
import com.fileweft.web.api.v1.document.DocumentPageQuery
import com.fileweft.web.api.v1.document.DocumentVersionDto

/**
 * Pure JVM facade for the first formal v1 document read surface.
 *
 * Trusted tenant and user identity are never accepted here. The injected
 * [DocumentQueryService] derives them from FileWeft SPI context, performs the
 * authorization check, and returns public-safe immutable views.
 */
class DocumentApiReadFacade(
    private val documents: DocumentQueryService,
) {
    private val cursorCodec = DocumentPageCursorCodec()

    fun detail(documentId: String): DocumentDetailDto =
        documents.detail(toDocumentIdentifier(documentId)).toPublicDetail()

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

    private fun toDocumentIdentifier(value: String): Identifier {
        require(value.isNotBlank()) { "Document id must not be blank." }
        require(value.length <= MAX_DOCUMENT_ID_LENGTH) { "Document id must not exceed $MAX_DOCUMENT_ID_LENGTH characters." }
        require(value.none(::isUnsafeControlCharacter)) { "Document id must not contain control characters." }
        return Identifier(value)
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

    private companion object {
        const val MAX_DOCUMENT_ID_LENGTH: Int = 128
    }
}

private fun isUnsafeControlCharacter(character: Char): Boolean =
    character.code in 0..31 || character.code in 127..159
