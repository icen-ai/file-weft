package com.fileweft.application.delivery

import com.fileweft.core.id.Identifier

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
