package com.fileweft.spi.event

import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OutboxEventHandlerContractTest {
    @Test
    fun `supports an idempotent event handling contract`() {
        val handler = object : OutboxEventHandler {
            override fun supports(event: OutboxEvent): Boolean = event.type == "document.publish.requested"
            override fun handle(event: OutboxEvent): OutboxHandlingResult =
                OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
        }
        val event = OutboxEvent(
            Identifier("event-1"), Identifier("tenant-1"), "document.publish.requested", emptyMap(), 1,
        )

        assertTrue(handler.supports(event))
        assertEquals(OutboxHandlingStatus.SUCCEEDED, handler.handle(event).status)
    }

    @Test
    fun `rejects empty handler result messages`() {
        assertFailsWith<IllegalArgumentException> {
            OutboxHandlingResult(OutboxHandlingStatus.RETRYABLE_FAILURE, " ")
        }
    }
}
