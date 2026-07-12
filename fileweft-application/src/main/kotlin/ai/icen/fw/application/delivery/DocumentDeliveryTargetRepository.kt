package ai.icen.fw.application.delivery

import ai.icen.fw.core.id.Identifier

interface DocumentDeliveryTargetRepository {
    fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget?

    fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget>

    fun findByDocumentGeneration(
        tenantId: Identifier,
        documentId: Identifier,
        deliveryGeneration: Int,
    ): List<DocumentDeliveryTarget> = findByDocument(tenantId, documentId)
        .filter { it.deliveryGeneration == deliveryGeneration }

    fun save(target: DocumentDeliveryTarget)
}

/**
 * Stronger mutation boundary for delivery state machines. Implementations
 * must serialize read-modify-save work for one target for the lifetime of the
 * caller's transaction, for example with a row lock. A plain snapshot read is
 * not a valid implementation because stale delivery events could overwrite a
 * newer manual dispatch fence.
 */
interface DocumentDeliveryTargetMutationRepository : DocumentDeliveryTargetRepository {
    fun findForMutation(
        tenantId: Identifier,
        deliveryId: Identifier,
    ): DocumentDeliveryTarget?
}
