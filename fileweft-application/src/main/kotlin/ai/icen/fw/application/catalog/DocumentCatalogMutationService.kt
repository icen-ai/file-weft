package ai.icen.fw.application.catalog

import ai.icen.fw.application.document.AddDocumentVersionCommand
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import java.io.InputStream

/**
 * Catalog-aware draft mutations for hosts whose folder visibility is narrower
 * than their tenant. It preserves [DocumentDraftService]'s upload,
 * compensation, audit and transaction behavior while adding a source-folder
 * ACL decision and a locked binding recheck.
 *
 * Call this service as a top-level application boundary, outside any host
 * database transaction. All writers of
 * [DocumentCatalogBinding.METADATA_KEY] must acquire the document mutation
 * lock and then the asset mutation lock; host code must not mutate that
 * metadata directly. The host ACL decision is a short-lived snapshot and
 * cannot be atomic with a
 * permission change made during this call.
 */
class DocumentCatalogMutationService(
    private val drafts: DocumentDraftService,
    catalogAccess: DocumentCatalogAccessService,
) {
    private val guard = DocumentCatalogMutationGuard(catalogAccess, drafts.mutationComponents)

    fun addVersion(
        documentId: Identifier,
        command: AddDocumentVersionCommand,
        content: InputStream,
    ): Document = drafts.addVersion(documentId, command, content, guard)

    fun rename(documentId: Identifier, title: String): Document =
        drafts.rename(documentId, title, guard)
}
