package ai.icen.fw.application.sync

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.event.OutboxHandlingResult

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
