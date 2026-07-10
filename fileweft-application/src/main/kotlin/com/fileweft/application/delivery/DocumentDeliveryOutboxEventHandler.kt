package com.fileweft.application.delivery

import com.fileweft.core.event.OutboxEvent
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.event.OutboxHandlingResult

/** Handles one per-target delivery event and persists terminal retry exhaustion. */
class DocumentDeliveryOutboxEventHandler(
    private val deliverySyncService: DocumentDeliverySyncService,
) : OutboxEventHandler {
    override fun supports(event: OutboxEvent): Boolean = event.type == DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE

    override fun handle(event: OutboxEvent): OutboxHandlingResult = deliverySyncService.synchronize(event)

    override fun onExhausted(event: OutboxEvent, message: String) = deliverySyncService.exhaust(event, message)
}
