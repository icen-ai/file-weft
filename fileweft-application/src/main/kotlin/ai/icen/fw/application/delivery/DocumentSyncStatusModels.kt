package ai.icen.fw.application.delivery

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.delivery.DeliveryRequirement
import java.util.ArrayList
import java.util.Collections

/**
 * Public-safe status for one frozen target in the document's current
 * publication generation.
 *
 * Connector identity, profile ownership, downstream external identifiers,
 * diagnostic messages and dispatch fencing data deliberately never cross this boundary.
 * The only diagnostic-derived value is [lastErrorCategory]: a fixed
 * [DocumentDeliveryErrorCategory] classified from the persisted internal
 * diagnostic, so operators without database access can route a failure
 * while the raw text stays inside the trust boundary.
 * The retryable flags describe durable state readiness only; callers must
 * still pass the separate retry authorization check before issuing a command.
 */
class DocumentDeliveryStatusView @JvmOverloads constructor(
    val deliveryId: Identifier,
    targetId: String,
    displayName: String,
    val requirement: DeliveryRequirement,
    val deliveryStatus: DocumentDeliveryStatus,
    val deliveryRetryCount: Int,
    val removalStatus: DocumentDeliveryRemovalStatus,
    val removalRetryCount: Int,
    val deliveryRetryable: Boolean,
    val removalRetryable: Boolean,
    val updatedTime: Long,
    val lastErrorCategory: DocumentDeliveryErrorCategory? = null,
) {
    val targetId: String = safeRequiredText(targetId, "Delivery target id", MAX_TARGET_ID_LENGTH)
    val displayName: String = safeRequiredText(displayName, "Delivery target display name", MAX_DISPLAY_NAME_LENGTH)

    init {
        require(deliveryRetryCount >= 0) { "Delivery retry count must not be negative." }
        require(removalRetryCount >= 0) { "Delivery removal retry count must not be negative." }
        require(updatedTime >= 0) { "Delivery status update time must not be negative." }
        require(
            removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED ||
                deliveryStatus == DocumentDeliveryStatus.SUCCEEDED
        ) {
            "A requested downstream removal requires a successfully delivered target."
        }
        require(!deliveryRetryable || deliveryStatus in RECOVERABLE_DELIVERY_STATUSES) {
            "Only an active or failed delivery can be ready for recovery."
        }
        require(!removalRetryable || removalStatus in RECOVERABLE_REMOVAL_STATUSES) {
            "Only an active or failed downstream removal can be ready for recovery."
        }
        require(!(deliveryRetryable && removalRetryable)) {
            "Delivery and downstream removal cannot both be ready for manual retry."
        }
        require(lastErrorCategory == null || deliveryStatus in DIAGNOSTIC_DELIVERY_STATUSES) {
            "Only a retrying or failed delivery can carry a delivery error category."
        }
    }

    private companion object {
        const val MAX_TARGET_ID_LENGTH: Int = 128
        const val MAX_DISPLAY_NAME_LENGTH: Int = 256
        val DIAGNOSTIC_DELIVERY_STATUSES = setOf(
            DocumentDeliveryStatus.RETRYING,
            DocumentDeliveryStatus.FAILED,
        )
        val RECOVERABLE_DELIVERY_STATUSES = setOf(
            DocumentDeliveryStatus.PENDING,
            DocumentDeliveryStatus.RETRYING,
            DocumentDeliveryStatus.FAILED,
        )
        val RECOVERABLE_REMOVAL_STATUSES = setOf(
            DocumentDeliveryRemovalStatus.PENDING,
            DocumentDeliveryRemovalStatus.RETRYING,
            DocumentDeliveryRemovalStatus.FAILED,
        )
    }
}

/** A visible document plus only its current-generation downstream targets. */
class DocumentSyncStatusView @JvmOverloads constructor(
    val documentId: Identifier,
    deliveryTargets: List<DocumentDeliveryStatusView> = emptyList(),
) {
    val deliveryTargets: List<DocumentDeliveryStatusView> =
        Collections.unmodifiableList(ArrayList(deliveryTargets))

    init {
        require(this.deliveryTargets.map { target -> target.deliveryId }.distinct().size == this.deliveryTargets.size) {
            "Document synchronization targets must have unique delivery identifiers."
        }
        require(this.deliveryTargets.map { target -> target.targetId }.distinct().size == this.deliveryTargets.size) {
            "Document synchronization targets must have unique target identifiers."
        }
    }
}

private fun safeRequiredText(value: String, label: String, maximumLength: Int): String {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters." }
    require(value.none { character ->
        Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
    }) {
        "$label must not contain unsafe characters."
    }
    return value
}
