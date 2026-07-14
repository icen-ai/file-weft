package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.application.catalog.DocumentCatalogMutationService
import ai.icen.fw.application.document.AddDocumentVersionCommand as ApplicationAddVersionCommand
import ai.icen.fw.application.document.CreateDocumentDraftCommand as ApplicationCreateDraftCommand
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.metadata.DocumentMetadataWriteService
import ai.icen.fw.domain.document.Document
import ai.icen.fw.web.api.v1.document.AddDocumentVersionCommand
import ai.icen.fw.web.api.v1.document.CreateDocumentDraftCommand
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto
import ai.icen.fw.web.api.v1.document.DocumentMetadataCommand
import ai.icen.fw.web.api.v1.document.RenameDocumentCommand
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException
import java.io.InputStream

/**
 * Pure JVM boundary for formal v1 draft mutations.
 *
 * The facade accepts no tenant, user, role, storage path, or arbitrary asset
 * metadata. Trusted context and authorization remain inside the injected
 * application services. It returns only the committed aggregate state and
 * never performs a second read after a successful mutation.
 */
class DocumentApiWriteFacade {
    private val drafts: DocumentDraftService
    private val catalogDrafts: DocumentCatalogDraftService?
    private val catalogMutations: DocumentCatalogMutationService?
    private val metadataWrites: DocumentMetadataWriteService?

    @JvmOverloads
    constructor(
        drafts: DocumentDraftService,
        catalogDrafts: DocumentCatalogDraftService? = null,
        catalogMutations: DocumentCatalogMutationService? = null,
    ) {
        this.drafts = drafts
        this.catalogDrafts = catalogDrafts
        this.catalogMutations = catalogMutations
        this.metadataWrites = null
    }

    constructor(
        drafts: DocumentDraftService,
        catalogDrafts: DocumentCatalogDraftService?,
        catalogMutations: DocumentCatalogMutationService?,
        metadataWrites: DocumentMetadataWriteService?,
    ) {
        this.drafts = drafts
        this.catalogDrafts = catalogDrafts
        this.catalogMutations = catalogMutations
        this.metadataWrites = metadataWrites
    }
    fun create(command: CreateDocumentDraftCommand, content: InputStream): DocumentCommandResultDto =
        createWithoutMetadata(command, content)

    fun create(
        command: CreateDocumentDraftCommand,
        metadata: DocumentMetadataCommand,
        content: InputStream,
    ): DocumentCommandResultDto {
        val applicationCommand = applicationCreateCommand(command)
        val document = (metadataWrites ?: throw V1FeatureUnavailableException()).create(
            applicationCommand,
            command.folderId,
            metadata.schemaId,
            metadata.values,
            content,
        )
        return document.toPublicCommandResult(includeCurrentVersion = true)
    }

    private fun createWithoutMetadata(
        command: CreateDocumentDraftCommand,
        content: InputStream,
    ): DocumentCommandResultDto {
        val applicationCommand = applicationCreateCommand(command)
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
    ): DocumentCommandResultDto = addVersionWithoutMetadata(documentId, command, content)

    fun addVersion(
        documentId: String,
        command: AddDocumentVersionCommand,
        metadata: DocumentMetadataCommand,
        content: InputStream,
    ): DocumentCommandResultDto {
        val identifier = DocumentApiInputs.documentId(documentId)
        val applicationCommand = applicationAddVersionCommand(command)
        val document = (metadataWrites ?: throw V1FeatureUnavailableException()).addVersion(
            identifier,
            applicationCommand,
            metadata.schemaId,
            metadata.values,
            content,
        )
        return document.toPublicCommandResult(includeCurrentVersion = true)
    }

    private fun addVersionWithoutMetadata(
        documentId: String,
        command: AddDocumentVersionCommand,
        content: InputStream,
    ): DocumentCommandResultDto {
        val identifier = DocumentApiInputs.documentId(documentId)
        val applicationCommand = applicationAddVersionCommand(command)
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

    private fun applicationCreateCommand(command: CreateDocumentDraftCommand): ApplicationCreateDraftCommand =
        ApplicationCreateDraftCommand(
            documentNumber = command.documentNumber,
            title = command.title,
            fileName = command.fileName,
            contentLength = command.contentLength,
            contentType = command.contentType,
        )

    private fun applicationAddVersionCommand(command: AddDocumentVersionCommand): ApplicationAddVersionCommand =
        ApplicationAddVersionCommand(
            versionNumber = command.versionNumber,
            fileName = command.fileName,
            contentLength = command.contentLength,
            contentType = command.contentType,
        )

    private fun Document.toPublicCommandResult(includeCurrentVersion: Boolean): DocumentCommandResultDto = DocumentCommandResultDto(
        documentId = id.value,
        versionId = currentVersionId?.value?.takeIf { includeCurrentVersion },
    )
}
