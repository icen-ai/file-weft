package com.fileweft.application.delivery

import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.LifecycleState
import java.time.Clock

class DocumentDeliveryRemovalPlan(
    val deliveries: List<DocumentDeliveryTarget>,
)

/**
 * Freezes downstream withdrawal requests in the same business transaction as
 * taking a document offline or archiving it. Connector calls remain outbox work.
 */
class DocumentDeliveryRemovalPlanner(
    private val deliveries: DocumentDeliveryTargetRepository,
    private val outbox: OutboxEventRepository,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
) {
    fun plan(document: Document): DocumentDeliveryRemovalPlan {
        require(document.lifecycleState == LifecycleState.OFFLINE || document.lifecycleState == LifecycleState.HISTORY) {
            "Document must be offline or archived before downstream removal is planned."
        }
        val requested = deliveries.findByDocument(document.tenantId, document.id)
            .filter { it.status == DocumentDeliveryStatus.SUCCEEDED && it.removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED }
            .onEach { delivery ->
                delivery.requestRemoval()
                deliveries.save(delivery)
                outbox.append(
                    OutboxEvent(
                        id = identifiers.nextId(),
                        tenantId = document.tenantId,
                        type = DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE,
                        payload = mapOf(
                            DocumentDeliveryPlanner.DOCUMENT_ID_PAYLOAD_KEY to document.id.value,
                            DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY to delivery.id.value,
                        ),
                        timestamp = clock.millis(),
                    ),
                )
            }
        return DocumentDeliveryRemovalPlan(requested)
    }

    companion object {
        const val DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE = "document.delivery.target.removal.requested"
    }
}
