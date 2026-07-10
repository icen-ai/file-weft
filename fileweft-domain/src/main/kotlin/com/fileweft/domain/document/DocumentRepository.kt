package com.fileweft.domain.document

import com.fileweft.core.id.Identifier

interface DocumentRepository {
    fun findById(tenantId: Identifier, documentId: Identifier): Document?

    fun save(document: Document)
}
