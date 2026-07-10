package com.fileweft.application.sync

import com.fileweft.core.event.OutboxEvent
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.event.OutboxHandlingResult

/** Routes committed document publication events to one connector synchronization service. */
class DocumentPublishOutboxEventHandler(
    private val documentSyncService: DocumentSyncService,
) : OutboxEventHandler {
    override fun supports(event: OutboxEvent): Boolean = event.type == PUBLISH_REQUESTED_EVENT_TYPE

    override fun handle(event: OutboxEvent): OutboxHandlingResult = documentSyncService.synchronize(event)

    companion object {
        const val PUBLISH_REQUESTED_EVENT_TYPE = "document.publish.requested"
    }
}
