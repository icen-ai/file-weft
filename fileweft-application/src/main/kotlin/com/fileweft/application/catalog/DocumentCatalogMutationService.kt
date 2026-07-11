package com.fileweft.application.catalog

import com.fileweft.application.document.AddDocumentVersionCommand
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.spi.catalog.DocumentCatalogBinding
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
