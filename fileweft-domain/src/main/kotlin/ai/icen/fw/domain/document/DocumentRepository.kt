package ai.icen.fw.domain.document

import ai.icen.fw.core.id.Identifier

/**
 * Read and save capability of the document aggregate. State-changing write
 * paths additionally require [DocumentMutationRepository]; it is a separate
 * subinterface so a read-only implementation can never silently disable the
 * document-number uniqueness check or the mutation lock.
 */
interface DocumentRepository {
    fun findById(tenantId: Identifier, documentId: Identifier): Document?

    fun save(document: Document)
}
