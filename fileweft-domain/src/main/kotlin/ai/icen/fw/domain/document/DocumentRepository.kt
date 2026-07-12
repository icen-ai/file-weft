package ai.icen.fw.domain.document

import ai.icen.fw.core.id.Identifier

interface DocumentRepository {
    fun findById(tenantId: Identifier, documentId: Identifier): Document?

    /**
     * Loads a document for a state-changing operation inside the caller's
     * business transaction. Implementations must serialize concurrent
     * read-modify-save operations for the same document (for example with a
     * row lock or compare-and-set); silently falling back to an ordinary read
     * can lose a lifecycle transition or document update.
     */
    fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
        throw UnsupportedOperationException(
            "DocumentRepository must implement findForMutation with concurrency-safe mutation semantics.",
        )

    /**
     * Locates a document by its business number inside one tenant.
     *
     * The default preserves compatibility for existing repository extensions;
     * production repositories should override it so draft creation can reject a
     * duplicate before any bytes are sent to storage.
     */
    fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null

    fun save(document: Document)
}
