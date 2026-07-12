package ai.icen.fw.application.outbox

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class OutboxEventStateTest {
    @Test
    fun `accepts only the exact current running owner and token`() {
        val lease = lease()
        val state = state()

        assertSame(state, state.requireCurrentLease(lease))

        listOf(
            lease(owner = "worker-b"),
            lease(token = "token-b"),
            lease(eventId = "event-2"),
            lease(tenantId = "tenant-2"),
            lease(tokenized = false),
        ).forEach { changed ->
            assertFailsWith<OutboxLeaseLostException> { state.requireCurrentLease(changed) }
        }
        assertFailsWith<OutboxLeaseLostException> {
            state(status = OutboxEventStatus.RETRY, owner = null, token = null).requireCurrentLease(lease)
        }
        assertFailsWith<OutboxLeaseLostException> {
            state(eventType = "document.delivery.target.removal.requested").requireCurrentLease(lease)
        }
    }

    @Test
    fun `rejects impossible persisted ownership shapes`() {
        assertFailsWith<IllegalArgumentException> { state(owner = "worker-a", token = null) }
        assertFailsWith<IllegalArgumentException> { state(owner = null, token = "token-a") }
        assertFailsWith<IllegalArgumentException> { state(owner = " ", token = "token-a") }
        assertFailsWith<IllegalArgumentException> { state(owner = "worker-a", token = " ") }
        assertFailsWith<IllegalArgumentException> {
            state(status = OutboxEventStatus.SUCCESS, owner = "worker-a", token = "token-a")
        }
        assertFailsWith<IllegalArgumentException> {
            OutboxEventState(
                Identifier("event-1"),
                Identifier("tenant-1"),
                OutboxEventStatus.FAILED,
                null,
                null,
                " ",
            )
        }
    }

    private fun state(
        status: OutboxEventStatus = OutboxEventStatus.RUNNING,
        owner: String? = "worker-a",
        token: String? = "token-a",
        eventType: String? = "document.delivery.target.requested",
    ) = OutboxEventState(
        Identifier("event-1"),
        Identifier("tenant-1"),
        status,
        owner,
        token,
        eventType,
    )

    private fun lease(
        eventId: String = "event-1",
        tenantId: String = "tenant-1",
        owner: String = "worker-a",
        token: String = "token-a",
        tokenized: Boolean = true,
    ) = OutboxEventLease(
        OutboxEvent(
            Identifier(eventId),
            Identifier(tenantId),
            "document.delivery.target.requested",
            emptyMap(),
            1,
        ),
        0,
        if (tokenized) owner else null,
        if (tokenized) token else null,
    )
}
