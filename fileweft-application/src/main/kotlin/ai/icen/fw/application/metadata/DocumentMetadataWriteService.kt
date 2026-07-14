package ai.icen.fw.application.metadata

import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.application.catalog.DocumentCatalogMutationService
import ai.icen.fw.application.document.AddDocumentVersionCommand
import ai.icen.fw.application.document.CreateDocumentDraftCommand
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import java.io.InputStream

/**
 * Metadata-aware document write use case.
 *
 * Authentication, authorization and optional catalog ACL checks are performed
 * by the existing draft boundaries before the schema provider is invoked.
 * This prevents unauthenticated callers from probing schema existence or
 * validation rules and keeps one trusted tenant snapshot through validation
 * and persistence.
 */
class DocumentMetadataWriteService @JvmOverloads constructor(
    private val drafts: DocumentDraftService,
    private val metadata: DocumentMetadataService,
    private val catalogDrafts: DocumentCatalogDraftService? = null,
    private val catalogMutations: DocumentCatalogMutationService? = null,
) {
    fun create(
        command: CreateDocumentDraftCommand,
        folderId: String?,
        schemaId: String,
        values: Map<String, String>,
        content: InputStream,
    ): Document {
        val provider = provider(schemaId, values, DocumentMetadataService.CREATE_OPERATION)
        return when {
            catalogDrafts != null -> catalogDrafts.createInFolderWithMetadata(
                command,
                requireNotNull(folderId) {
                    "Document folder id is required when a host catalog is configured."
                },
                content,
                provider,
            )
            catalogMutations != null || folderId != null -> throw DocumentMetadataWriteUnavailableException()
            else -> drafts.createWithMetadata(command, content, provider)
        }
    }

    fun addVersion(
        documentId: Identifier,
        command: AddDocumentVersionCommand,
        schemaId: String,
        values: Map<String, String>,
        content: InputStream,
    ): Document {
        val provider = provider(schemaId, values, DocumentMetadataService.ADD_VERSION_OPERATION)
        return when {
            catalogMutations != null -> catalogMutations.addVersionWithMetadata(
                documentId,
                command,
                content,
                provider,
            )
            catalogDrafts != null -> throw DocumentMetadataWriteUnavailableException()
            else -> drafts.addVersionWithMetadata(documentId, command, content, null, provider)
        }
    }

    private fun provider(
        schemaId: String,
        values: Map<String, String>,
        operation: String,
    ): (Identifier) -> Map<String, String> = { tenantId ->
        metadata.processTrusted(tenantId, schemaId, values, operation)
    }
}

class DocumentMetadataWriteUnavailableException : IllegalStateException(
    "Schema-qualified document metadata writes are unavailable.",
)
