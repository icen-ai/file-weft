package com.fileweft.web.runtime.v1.document

import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.application.document.AddDocumentVersionCommand as ApplicationAddVersionCommand
import com.fileweft.application.document.CreateDocumentDraftCommand as ApplicationCreateDraftCommand
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.domain.document.Document
import com.fileweft.web.api.v1.document.AddDocumentVersionCommand
import com.fileweft.web.api.v1.document.CreateDocumentDraftCommand
import com.fileweft.web.api.v1.document.DocumentCommandResultDto
import com.fileweft.web.api.v1.document.RenameDocumentCommand
import java.io.InputStream

/**
 * Pure JVM boundary for formal v1 draft mutations.
 *
 * The facade accepts no tenant, user, role, storage path, or arbitrary asset
 * metadata. Trusted context and authorization remain inside the injected
 * application services. It returns only the committed aggregate state and
 * never performs a second read after a successful mutation.
 */
class DocumentApiWriteFacade @JvmOverloads constructor(
    private val drafts: DocumentDraftService,
    private val catalogDrafts: DocumentCatalogDraftService? = null,
) {
    fun create(command: CreateDocumentDraftCommand, content: InputStream): DocumentCommandResultDto {
        val applicationCommand = ApplicationCreateDraftCommand(
            documentNumber = command.documentNumber,
            title = command.title,
            fileName = command.fileName,
            contentLength = command.contentLength,
            contentType = command.contentType,
        )
        val document = when {
            catalogDrafts != null -> catalogDrafts.createInFolder(
                applicationCommand,
                requireNotNull(command.folderId) {
                    "Document folder id is required when a host catalog is configured."
                },
                content,
            )
            command.folderId != null -> throw V1FeatureUnavailableException()
            else -> drafts.create(applicationCommand, content)
        }
        return document.toPublicCommandResult(includeCurrentVersion = true)
    }

    fun addVersion(
        documentId: String,
        command: AddDocumentVersionCommand,
        content: InputStream,
    ): DocumentCommandResultDto {
        requireFlatMutationMode()
        val document = drafts.addVersion(
            DocumentApiInputs.documentId(documentId),
            ApplicationAddVersionCommand(
                versionNumber = command.versionNumber,
                fileName = command.fileName,
                contentLength = command.contentLength,
                contentType = command.contentType,
            ),
            content,
        )
        return document.toPublicCommandResult(includeCurrentVersion = true)
    }

    fun rename(documentId: String, command: RenameDocumentCommand): DocumentCommandResultDto {
        requireFlatMutationMode()
        return drafts.rename(DocumentApiInputs.documentId(documentId), command.title)
            .toPublicCommandResult(includeCurrentVersion = false)
    }

    private fun requireFlatMutationMode() {
        if (catalogDrafts != null) {
            // Generic draft mutations do not yet re-check the document's
            // current catalog binding under the document mutation lock. Fail
            // closed until the catalog-aware application use case is present.
            throw V1FeatureUnavailableException()
        }
    }

    private fun Document.toPublicCommandResult(includeCurrentVersion: Boolean): DocumentCommandResultDto = DocumentCommandResultDto(
        documentId = id.value,
        versionId = currentVersionId?.value?.takeIf { includeCurrentVersion },
    )
}

/** Raised when a requested optional v1 capability is not installed by the host. */
internal class V1FeatureUnavailableException : IllegalStateException("The requested v1 feature is unavailable.")
