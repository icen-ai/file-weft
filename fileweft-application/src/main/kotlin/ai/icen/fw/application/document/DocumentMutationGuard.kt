package ai.icen.fw.application.document

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document

/**
 * Internal two-phase guard for document mutations that need an external policy
 * decision before storage or a database write begins.
 *
 * [prepare] must run outside the final mutation transaction. [revalidate]
 * repeats external policy checks after an upload and must also run outside a
 * transaction. [verifyLocked] runs only after the caller has loaded the
 * document through `DocumentRepository.findForMutation`; it must perform local
 * persistence checks only and must never invoke an external SPI.
 */
internal interface DocumentMutationGuard {
    fun prepare(tenantId: Identifier, documentId: Identifier): DocumentMutationPermit

    fun revalidate(
        tenantId: Identifier,
        documentId: Identifier,
        permit: DocumentMutationPermit,
    )

    fun verifyLocked(
        tenantId: Identifier,
        document: Document,
        permit: DocumentMutationPermit,
    )
}

/** Opaque, invocation-local evidence produced by a [DocumentMutationGuard]. */
internal interface DocumentMutationPermit
