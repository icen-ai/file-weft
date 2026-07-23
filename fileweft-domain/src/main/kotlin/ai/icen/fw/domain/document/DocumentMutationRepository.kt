package ai.icen.fw.domain.document

import ai.icen.fw.core.id.Identifier

/**
 * Additive persistence capability for serializing a document mutation.
 *
 * Write paths depend on this subinterface so a read-only [DocumentRepository]
 * can never silently disable the document-number uniqueness check or fall back
 * to an ordinary read inside a mutation transaction. Implementations must not
 * fail open: draft creation rejects duplicates through [findByDocumentNumber]
 * before any bytes are sent to storage, and [findForMutation] must serialize
 * concurrent read-modify-save operations for the same document.
 */
interface DocumentMutationRepository : DocumentRepository {
    /**
     * Loads a document for a state-changing operation inside the caller's
     * business transaction. Implementations must serialize concurrent
     * read-modify-save operations for the same document (for example with a
     * row lock or compare-and-set); silently falling back to an ordinary read
     * can lose a lifecycle transition or document update.
     */
    fun findForMutation(tenantId: Identifier, documentId: Identifier): Document?

    /**
     * Locates a document by its business number inside one tenant. Draft
     * creation relies on this lookup to reject a duplicate before any bytes
     * are sent to storage, so implementations must never fail open.
     */
    fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document?
}
