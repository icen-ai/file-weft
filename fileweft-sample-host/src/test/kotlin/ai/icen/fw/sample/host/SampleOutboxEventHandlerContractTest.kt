package ai.icen.fw.sample.host

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.testkit.event.OutboxEventHandlerContractTest

class SampleOutboxEventHandlerContractTest : OutboxEventHandlerContractTest() {

    override val outboxEventHandler = SampleOutboxEventHandler(supportedType = "sample.event")

    override fun supportedEvent(): OutboxEvent {
        return OutboxEvent(
            id = Identifier("event-1"),
            tenantId = Identifier("sample-tenant"),
            type = "sample.event",
            payload = mapOf("key" to "value"),
            timestamp = 1L,
        )
    }

    override fun unsupportedEvent(): OutboxEvent {
        return OutboxEvent(
            id = Identifier("event-2"),
            tenantId = Identifier("sample-tenant"),
            type = "other.event",
            payload = emptyMap(),
            timestamp = 1L,
        )
    }
}
