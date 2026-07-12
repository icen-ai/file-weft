package ai.icen.fw.application.delivery

import ai.icen.fw.application.outbox.LeasedOutboxEventHandler
import ai.icen.fw.application.outbox.OutboxEventLease
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.spi.event.OutboxHandlingResult
import ai.icen.fw.spi.event.OutboxHandlingStatus

/** Handles one per-target delivery event and persists terminal retry exhaustion. */
class DocumentDeliveryOutboxEventHandler private constructor(
    private val deliverySyncService: DocumentDeliverySyncService,
    private val deliveryRemovalService: DocumentDeliveryRemovalService,
    private val outboxMutations: OutboxEventMutationRepository?,
    private val documents: DocumentRepository?,
    private val fenced: Boolean,
) : LeasedOutboxEventHandler {
    constructor(
        deliverySyncService: DocumentDeliverySyncService,
        deliveryRemovalService: DocumentDeliveryRemovalService,
    ) : this(deliverySyncService, deliveryRemovalService, null, null, false)

    constructor(
        deliverySyncService: DocumentDeliverySyncService,
        deliveryRemovalService: DocumentDeliveryRemovalService,
        outboxMutations: OutboxEventMutationRepository,
        documents: DocumentRepository,
    ) : this(deliverySyncService, deliveryRemovalService, outboxMutations, documents, true)

    init {
        require(fenced == (outboxMutations != null && documents != null)) {
            "Delivery handler fencing dependencies must be supplied together."
        }
    }

    override fun supports(event: OutboxEvent): Boolean = event.type in setOf(
        DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE,
        DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE,
    )

    override fun handle(event: OutboxEvent): OutboxHandlingResult {
        if (fenced) return unavailableLease()
        return handleLegacy(event)
    }

    private fun handleLegacy(event: OutboxEvent): OutboxHandlingResult = when (event.type) {
        DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE -> deliverySyncService.synchronize(event)
        DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE -> deliveryRemovalService.remove(event)
        else -> OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Unsupported delivery event type.")
    }

    override fun handle(lease: OutboxEventLease): OutboxHandlingResult {
        if (!fenced) return handleLegacy(lease.event)
        val mutations = outboxMutations ?: return unavailableFence()
        val documentRepository = documents ?: return unavailableFence()
        return when (lease.event.type) {
            DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE ->
                deliverySyncService.synchronize(lease, mutations)
            DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE ->
                deliveryRemovalService.remove(lease, mutations, documentRepository)
            else -> OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Unsupported delivery event type.")
        }
    }

    override fun onExhausted(event: OutboxEvent, message: String) {
        when (event.type) {
            DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE -> if (fenced) {
                deliverySyncService.exhaust(event, message, requireNotNull(outboxMutations))
            } else {
                deliverySyncService.exhaust(event, message)
            }
            DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE -> if (fenced) {
                deliveryRemovalService.exhaust(
                    event,
                    message,
                    requireNotNull(documents),
                    requireNotNull(outboxMutations),
                )
            } else {
                deliveryRemovalService.exhaust(event, message)
            }
            else -> Unit
        }
    }

    private fun unavailableFence(): OutboxHandlingResult = OutboxHandlingResult(
        OutboxHandlingStatus.PERMANENT_FAILURE,
        "Delivery processing requires mutation-capable target and Outbox repositories.",
    )

    private fun unavailableLease(): OutboxHandlingResult = OutboxHandlingResult(
        OutboxHandlingStatus.PERMANENT_FAILURE,
        "Delivery processing requires the current persisted Outbox lease.",
    )
}
