package com.fileweft.web.runtime.v1.document

import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.application.catalog.DocumentCatalogMutationService
import com.fileweft.application.document.AddDocumentVersionCommand as ApplicationAddVersionCommand
import com.fileweft.application.document.CreateDocumentDraftCommand as ApplicationCreateDraftCommand
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.domain.document.Document
import com.fileweft.web.api.v1.document.AddDocumentVersionCommand
import com.fileweft.web.api.v1.document.CreateDocumentDraftCommand
import com.fileweft.web.api.v1.document.DocumentCommandResultDto
import com.fileweft.web.api.v1.document.RenameDocumentCommand
import com.fileweft.web.runtime.v1.V1FeatureUnavailableException
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
    private val catalogMutations: DocumentCatalogMutationService? = null,
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
            catalogMutations != null || command.folderId != null -> throw V1FeatureUnavailableException()
            else -> drafts.create(applicationCommand, content)
        }
        return document.toPublicCommandResult(includeCurrentVersion = true)
    }

    fun addVersion(
        documentId: String,
        command: AddDocumentVersionCommand,
        content: InputStream,
    ): DocumentCommandResultDto {
        val identifier = DocumentApiInputs.documentId(documentId)
        val applicationCommand = ApplicationAddVersionCommand(
            versionNumber = command.versionNumber,
            fileName = command.fileName,
            contentLength = command.contentLength,
            contentType = command.contentType,
        )
        val document = when {
            catalogMutations != null -> catalogMutations.addVersion(identifier, applicationCommand, content)
            catalogDrafts != null -> throw V1FeatureUnavailableException()
            else -> drafts.addVersion(identifier, applicationCommand, content)
        }
        return document.toPublicCommandResult(includeCurrentVersion = true)
    }

    fun rename(documentId: String, command: RenameDocumentCommand): DocumentCommandResultDto {
        val identifier = DocumentApiInputs.documentId(documentId)
        val document = when {
            catalogMutations != null -> catalogMutations.rename(identifier, command.title)
            catalogDrafts != null -> throw V1FeatureUnavailableException()
            else -> drafts.rename(identifier, command.title)
        }
        return document
            .toPublicCommandResult(includeCurrentVersion = false)
    }

    private fun Document.toPublicCommandResult(includeCurrentVersion: Boolean): DocumentCommandResultDto = DocumentCommandResultDto(
        documentId = id.value,
        versionId = currentVersionId?.value?.takeIf { includeCurrentVersion },
    )
}
