package ai.icen.fw.sample.host

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.event.OutboxHandlingResult
import ai.icen.fw.spi.event.OutboxHandlingStatus

/**
 * Sample host outbox event handler that succeeds for its supported event type.
 */
class SampleOutboxEventHandler(
    private val supportedType: String = "sample.event",
) : OutboxEventHandler {

    override fun supports(event: OutboxEvent): Boolean = event.type == supportedType

    override fun handle(event: OutboxEvent): OutboxHandlingResult {
        require(supports(event)) { "Unsupported event type: ${event.type}" }
        return OutboxHandlingResult(status = OutboxHandlingStatus.SUCCEEDED, message = "Sample event handled.")
    }
}
