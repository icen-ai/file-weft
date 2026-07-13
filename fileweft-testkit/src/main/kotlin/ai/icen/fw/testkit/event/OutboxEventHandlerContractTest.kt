package ai.icen.fw.testkit.event

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.event.OutboxHandlingStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class OutboxEventHandlerContractTest {
    protected abstract val outboxEventHandler: OutboxEventHandler

    protected abstract fun supportedEvent(): OutboxEvent

    protected abstract fun unsupportedEvent(): OutboxEvent

    @Test
    fun `supports the configured event type`() {
        assertTrue(outboxEventHandler.supports(supportedEvent()), "Handler must support its configured event type.")
        assertFalse(outboxEventHandler.supports(unsupportedEvent()), "Handler must not support an unrelated event type.")
    }

    @Test
    fun `handles a supported event idempotently`() {
        val event = supportedEvent()
        val first = outboxEventHandler.handle(event)
        val second = outboxEventHandler.handle(event)

        assertEquals(OutboxHandlingStatus.SUCCEEDED, first.status, first.message)
        assertEquals(OutboxHandlingStatus.SUCCEEDED, second.status, second.message)
    }
}
