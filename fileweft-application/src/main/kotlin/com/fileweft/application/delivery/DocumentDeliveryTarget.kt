package com.fileweft.application.delivery

import com.fileweft.core.id.Identifier
import com.fileweft.spi.delivery.DeliveryRequirement

enum class DocumentDeliveryStatus {
    PENDING,
    RETRYING,
    SUCCEEDED,
    FAILED,
}

/**
 * Persisted snapshot for one downstream target of a document publication.
 * Connector identity and responsibility are frozen here rather than read from
 * live configuration during retry processing.
 */
class DocumentDeliveryTarget(
    val id: Identifier,
    val tenantId: Identifier,
    val documentId: Identifier,
    val profileId: String,
    val targetId: String,
    val displayName: String,
    val connectorId: String,
    val requirement: DeliveryRequirement,
    val ownerRef: String? = null,
    status: DocumentDeliveryStatus = DocumentDeliveryStatus.PENDING,
    externalId: String? = null,
    errorMessage: String? = null,
    retryCount: Int = 0,
) {
    var status: DocumentDeliveryStatus = status
        private set
    var externalId: String? = externalId
        private set
    var errorMessage: String? = errorMessage
        private set
    var retryCount: Int = retryCount
        private set

    init {
        require(profileId.isNotBlank()) { "Delivery profile id must not be blank." }
        require(targetId.isNotBlank()) { "Delivery target id must not be blank." }
        require(displayName.isNotBlank()) { "Delivery target display name must not be blank." }
        require(connectorId.isNotBlank()) { "Delivery connector id must not be blank." }
        require(ownerRef == null || ownerRef.isNotBlank()) { "Delivery owner reference must not be blank when provided." }
        require(retryCount >= 0) { "Delivery retry count must not be negative." }
        require(errorMessage == null || errorMessage.isNotBlank()) { "Delivery error message must not be blank when provided." }
    }

    fun markSucceeded(newExternalId: String?) {
        status = DocumentDeliveryStatus.SUCCEEDED
        externalId = newExternalId ?: externalId
        errorMessage = null
    }

    fun markRetrying(message: String?) {
        status = DocumentDeliveryStatus.RETRYING
        errorMessage = message
        retryCount++
    }

    fun markFailed(message: String?) {
        status = DocumentDeliveryStatus.FAILED
        errorMessage = message
    }

    fun retryManually() {
        require(status == DocumentDeliveryStatus.FAILED) { "Only failed delivery targets can be retried manually." }
        status = DocumentDeliveryStatus.PENDING
        errorMessage = null
    }
}
