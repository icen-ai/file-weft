package com.fileweft.domain.document

import com.fileweft.core.id.Identifier

interface DocumentRepository {
    fun findById(tenantId: Identifier, documentId: Identifier): Document?

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
