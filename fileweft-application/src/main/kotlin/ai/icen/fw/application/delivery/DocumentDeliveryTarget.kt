package ai.icen.fw.application.delivery

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.delivery.DeliveryRequirement

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

/** The logical downstream operation owned by one durable delivery event. */
enum class DeliveryDispatchOperation {
    DELIVERY,
    REMOVAL,
}

/**
 * Persisted identity fence for the newest logical event allowed to mutate a
 * delivery target. Worker lease attempts are deliberately not represented
 * here: reclaiming the same event must keep the same dispatch sequence.
 */
class DeliveryDispatchFence(
    val eventId: Identifier,
    val operation: DeliveryDispatchOperation,
    val sequence: Long,
) {
    init {
        require(eventId.value.length <= MAX_EVENT_ID_LENGTH) {
            "Delivery dispatch event id must not exceed $MAX_EVENT_ID_LENGTH characters."
        }
        require(sequence > 0) { "Delivery dispatch sequence must be positive." }
    }

    fun matches(
        eventId: Identifier,
        operation: DeliveryDispatchOperation,
        sequence: Long,
    ): Boolean =
        this.eventId == eventId &&
            this.operation == operation &&
            this.sequence == sequence

    private companion object {
        const val MAX_EVENT_ID_LENGTH = 64
    }
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
    private var restoredDispatchFence: DeliveryDispatchFence? = null

    /** The newest logical event that may mutate this target, or null before its initial plan is frozen. */
    val currentDispatchFence: DeliveryDispatchFence?
        get() = restoredDispatchFence

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

    /**
     * Compatibility entry retained for already compiled callers. A retry
     * without a replacement event identity cannot be fenced and therefore
     * fails closed.
     */
    @Deprecated("Supply the replacement outbox event id so the retry can advance its dispatch fence.")
    fun retryManually() {
        throw IllegalStateException("A manual delivery retry requires a replacement outbox event id.")
    }

    fun retryManually(eventId: Identifier): DeliveryDispatchFence {
        require(restoredDispatchFence != null) {
            "A persisted delivery dispatch fence is required before manual retry."
        }
        require(status == DocumentDeliveryStatus.FAILED) { "Only failed delivery targets can be retried manually." }
        require(removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED) {
            "A delivery target cannot be redelivered after downstream removal has been requested."
        }
        val fence = nextFence(eventId, DeliveryDispatchOperation.DELIVERY)
        status = DocumentDeliveryStatus.PENDING
        errorMessage = null
        restoredDispatchFence = fence
        return fence
    }

    /**
     * Replaces the current delivery dispatch after its exact Outbox event is
     * durably FAILED. The application recovery boundary owns that precondition;
     * accepting active states here closes the crash window between durable
     * Outbox failure and its best-effort local terminal projection.
     */
    @JvmSynthetic
    internal fun recoverDeliveryAfterOutboxFailure(eventId: Identifier): DeliveryDispatchFence {
        require(restoredDispatchFence != null) {
            "A persisted delivery dispatch fence is required before delivery recovery."
        }
        require(status in RECOVERABLE_DELIVERY_STATUSES) {
            "Only an active or failed delivery target can recover a failed Outbox dispatch."
        }
        require(removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED) {
            "A delivery target cannot be redelivered after downstream removal has been requested."
        }
        val fence = nextFence(eventId, DeliveryDispatchOperation.DELIVERY)
        status = DocumentDeliveryStatus.PENDING
        errorMessage = null
        restoredDispatchFence = fence
        return fence
    }

    /**
     * Compatibility entry retained for already compiled callers. A removal
     * without its event identity would leave the previous delivery event able
     * to mutate the removal state, so it fails closed.
     */
    @Deprecated("Supply the removal outbox event id so the request can advance its dispatch fence.")
    fun requestRemoval() {
        throw IllegalStateException("A downstream removal request requires an outbox event id.")
    }

    fun requestRemoval(eventId: Identifier): DeliveryDispatchFence {
        require(restoredDispatchFence != null) {
            "A persisted delivery dispatch fence is required before downstream removal."
        }
        require(status == DocumentDeliveryStatus.SUCCEEDED) { "Only a delivered target can be removed downstream." }
        require(removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED) {
            "Delivery target removal has already been requested."
        }
        val fence = nextFence(eventId, DeliveryDispatchOperation.REMOVAL)
        removalStatus = DocumentDeliveryRemovalStatus.PENDING
        removalErrorMessage = null
        restoredDispatchFence = fence
        return fence
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

    /** See [retryManually]; removal retries are fenced independently by operation. */
    @Deprecated("Supply the replacement removal event id so the retry can advance its dispatch fence.")
    fun retryRemovalManually() {
        throw IllegalStateException("A manual delivery removal retry requires a replacement outbox event id.")
    }

    fun retryRemovalManually(eventId: Identifier): DeliveryDispatchFence {
        require(restoredDispatchFence != null) {
            "A persisted delivery dispatch fence is required before manual removal retry."
        }
        require(status == DocumentDeliveryStatus.SUCCEEDED) {
            "Only a delivered target can retry downstream removal."
        }
        require(removalStatus == DocumentDeliveryRemovalStatus.FAILED) {
            "Only a failed delivery removal can be retried manually."
        }
        val fence = nextFence(eventId, DeliveryDispatchOperation.REMOVAL)
        removalStatus = DocumentDeliveryRemovalStatus.PENDING
        removalErrorMessage = null
        restoredDispatchFence = fence
        return fence
    }

    /** Removal counterpart of [recoverDeliveryAfterOutboxFailure]. */
    @JvmSynthetic
    internal fun recoverRemovalAfterOutboxFailure(eventId: Identifier): DeliveryDispatchFence {
        require(restoredDispatchFence != null) {
            "A persisted delivery dispatch fence is required before removal recovery."
        }
        require(status == DocumentDeliveryStatus.SUCCEEDED) {
            "Only a delivered target can recover downstream removal."
        }
        require(removalStatus in RECOVERABLE_REMOVAL_STATUSES) {
            "Only an active or failed removal can recover a failed Outbox dispatch."
        }
        val fence = nextFence(eventId, DeliveryDispatchOperation.REMOVAL)
        removalStatus = DocumentDeliveryRemovalStatus.PENDING
        removalErrorMessage = null
        restoredDispatchFence = fence
        return fence
    }

    /** Binds the first delivery event before this new target is persisted. */
    fun bindInitialDelivery(eventId: Identifier): DeliveryDispatchFence {
        require(restoredDispatchFence == null) { "Initial delivery dispatch has already been bound." }
        require(status == DocumentDeliveryStatus.PENDING) { "Initial delivery target must be pending." }
        require(removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED) {
            "Initial delivery target cannot already have a removal request."
        }
        return nextFence(eventId, DeliveryDispatchOperation.DELIVERY).also { restoredDispatchFence = it }
    }

    /**
     * Persistence hydration entry. It restores an exact historical sequence
     * and never advances it. Calling it twice would hide repository mapping
     * defects and is rejected.
     */
    fun restoreDispatch(fence: DeliveryDispatchFence): DocumentDeliveryTarget {
        check(restoredDispatchFence == null) { "Delivery dispatch fence has already been restored or bound." }
        requireFenceMatchesState(fence.operation)
        restoredDispatchFence = fence
        return this
    }

    fun matchesDispatch(
        eventId: Identifier,
        operation: DeliveryDispatchOperation,
        sequence: Long,
    ): Boolean = restoredDispatchFence?.matches(eventId, operation, sequence) == true

    private fun nextFence(
        eventId: Identifier,
        operation: DeliveryDispatchOperation,
    ): DeliveryDispatchFence {
        require(restoredDispatchFence?.eventId != eventId) {
            "A replacement delivery dispatch must use a new event id."
        }
        val nextSequence = try {
            Math.addExact(restoredDispatchFence?.sequence ?: 0L, 1L)
        } catch (failure: ArithmeticException) {
            throw IllegalStateException("Delivery dispatch sequence is exhausted.", failure)
        }
        return DeliveryDispatchFence(eventId, operation, nextSequence)
    }

    private fun requireFenceMatchesState(operation: DeliveryDispatchOperation) {
        when (operation) {
            DeliveryDispatchOperation.DELIVERY -> require(removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED) {
                "A delivery dispatch fence cannot own a target with an active removal lifecycle."
            }
            DeliveryDispatchOperation.REMOVAL -> {
                require(status == DocumentDeliveryStatus.SUCCEEDED) {
                    "A removal dispatch fence requires a successfully delivered target."
                }
                require(removalStatus != DocumentDeliveryRemovalStatus.NOT_REQUESTED) {
                    "A removal dispatch fence requires a requested removal lifecycle."
                }
            }
        }
    }

    private companion object {
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
