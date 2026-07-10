package com.fileweft.application.delivery

import com.fileweft.core.id.Identifier

interface DocumentDeliveryTargetRepository {
    fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget?

    fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget>

    fun save(target: DocumentDeliveryTarget)
}
