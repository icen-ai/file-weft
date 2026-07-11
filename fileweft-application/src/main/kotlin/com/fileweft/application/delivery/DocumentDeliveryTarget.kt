package com.fileweft.application.delivery

import com.fileweft.core.id.Identifier
import com.fileweft.spi.delivery.DeliveryRequirement

enum class DocumentDeliveryStatus {
    PENDING,
    RETRYING,
    SUCCEEDED,
    FAILED,
}

/** Separate lifecycle for the asynchronous withdrawal of an already delivered target. */
enum class DocumentDeliveryRemovalStatus {
    NOT_REQUESTED,
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
    removalStatus: DocumentDeliveryRemovalStatus = DocumentDeliveryRemovalStatus.NOT_REQUESTED,
    removalErrorMessage: String? = null,
    removalRetryCount: Int = 0,
    val deliveryGeneration: Int = 0,
) {
    var status: DocumentDeliveryStatus = status
        private set
    var externalId: String? = externalId
        private set
    var errorMessage: String? = errorMessage
        private set
    var retryCount: Int = retryCount
        private set
    var removalStatus: DocumentDeliveryRemovalStatus = removalStatus
        private set
    var removalErrorMessage: String? = removalErrorMessage
        private set
    var removalRetryCount: Int = removalRetryCount
        private set

    init {
        require(profileId.isNotBlank()) { "Delivery profile id must not be blank." }
        require(targetId.isNotBlank()) { "Delivery target id must not be blank." }
        require(displayName.isNotBlank()) { "Delivery target display name must not be blank." }
        require(connectorId.isNotBlank()) { "Delivery connector id must not be blank." }
        require(ownerRef == null || ownerRef.isNotBlank()) { "Delivery owner reference must not be blank when provided." }
        require(retryCount >= 0) { "Delivery retry count must not be negative." }
        require(removalRetryCount >= 0) { "Delivery removal retry count must not be negative." }
        require(deliveryGeneration >= 0) { "Delivery generation must not be negative." }
        require(errorMessage == null || errorMessage.isNotBlank()) { "Delivery error message must not be blank when provided." }
        require(removalErrorMessage == null || removalErrorMessage.isNotBlank()) {
            "Delivery removal error message must not be blank when provided."
        }
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

    fun requestRemoval() {
        require(status == DocumentDeliveryStatus.SUCCEEDED) { "Only a delivered target can be removed downstream." }
        require(removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED) {
            "Delivery target removal has already been requested."
        }
        removalStatus = DocumentDeliveryRemovalStatus.PENDING
        removalErrorMessage = null
    }

    fun markRemovalSucceeded() {
        require(removalStatus == DocumentDeliveryRemovalStatus.PENDING || removalStatus == DocumentDeliveryRemovalStatus.RETRYING) {
            "Only a pending delivery removal can succeed."
        }
        removalStatus = DocumentDeliveryRemovalStatus.SUCCEEDED
        removalErrorMessage = null
    }

    fun markRemovalRetrying(message: String?) {
        require(removalStatus == DocumentDeliveryRemovalStatus.PENDING || removalStatus == DocumentDeliveryRemovalStatus.RETRYING) {
            "Only a pending delivery removal can be retried."
        }
        removalStatus = DocumentDeliveryRemovalStatus.RETRYING
        removalErrorMessage = message
        removalRetryCount++
    }

    fun markRemovalFailed(message: String?) {
        require(removalStatus != DocumentDeliveryRemovalStatus.SUCCEEDED) {
            "A completed delivery removal cannot fail."
        }
        removalStatus = DocumentDeliveryRemovalStatus.FAILED
        removalErrorMessage = message
    }

    fun retryRemovalManually() {
        require(removalStatus == DocumentDeliveryRemovalStatus.FAILED) {
            "Only a failed delivery removal can be retried manually."
        }
        removalStatus = DocumentDeliveryRemovalStatus.PENDING
        removalErrorMessage = null
    }
}
