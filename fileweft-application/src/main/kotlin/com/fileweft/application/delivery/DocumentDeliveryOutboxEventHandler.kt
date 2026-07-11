package com.fileweft.application.delivery

import com.fileweft.core.event.OutboxEvent
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.event.OutboxHandlingResult

/** Handles one per-target delivery event and persists terminal retry exhaustion. */
class DocumentDeliveryOutboxEventHandler(
    private val deliverySyncService: DocumentDeliverySyncService,
    private val deliveryRemovalService: DocumentDeliveryRemovalService,
) : OutboxEventHandler {
    override fun supports(event: OutboxEvent): Boolean = event.type in setOf(
        DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE,
        DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE,
    )

    override fun handle(event: OutboxEvent): OutboxHandlingResult = when (event.type) {
        DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE -> deliverySyncService.synchronize(event)
        DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE -> deliveryRemovalService.remove(event)
        else -> OutboxHandlingResult(com.fileweft.spi.event.OutboxHandlingStatus.PERMANENT_FAILURE, "Unsupported delivery event type.")
    }

    override fun onExhausted(event: OutboxEvent, message: String) = when (event.type) {
        DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE -> deliverySyncService.exhaust(event, message)
        DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE -> deliveryRemovalService.exhaust(event, message)
        else -> Unit
    }
}
