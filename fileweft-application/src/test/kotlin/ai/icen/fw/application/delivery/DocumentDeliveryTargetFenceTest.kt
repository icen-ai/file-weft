package ai.icen.fw.application.delivery

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.delivery.DeliveryRequirement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentDeliveryTargetFenceTest {
    @Test
    fun `advances the logical dispatch sequence only for new delivery and removal events`() {
        val target = target()

        val initial = target.bindInitialDelivery(Identifier("event-delivery-1"))
        target.markFailed("rejected")
        val retry = target.retryManually(Identifier("event-delivery-2"))
        target.markSucceeded("external-1")
        val removal = target.requestRemoval(Identifier("event-removal-1"))
        target.markRemovalFailed("remote unavailable")
        val removalRetry = target.retryRemovalManually(Identifier("event-removal-2"))

        assertEquals(1, initial.sequence)
        assertEquals(DeliveryDispatchOperation.DELIVERY, initial.operation)
        assertEquals(2, retry.sequence)
        assertEquals(DeliveryDispatchOperation.DELIVERY, retry.operation)
        assertEquals(3, removal.sequence)
        assertEquals(DeliveryDispatchOperation.REMOVAL, removal.operation)
        assertEquals(4, removalRetry.sequence)
        assertEquals(DocumentDeliveryRemovalStatus.PENDING, target.removalStatus)
        assertEquals(removalRetry, target.currentDispatchFence)
        assertTrue(target.matchesDispatch(removalRetry.eventId, DeliveryDispatchOperation.REMOVAL, 4))
        assertFalse(target.matchesDispatch(retry.eventId, DeliveryDispatchOperation.DELIVERY, 2))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `fails closed when legacy callers mutate asynchronous state without an event id`() {
        val initial = target()
        assertFailsWith<IllegalStateException> { initial.retryManually() }
        assertFailsWith<IllegalStateException> { initial.requestRemoval() }
        assertFailsWith<IllegalStateException> { initial.retryRemovalManually() }

        val failedWithoutFence = target(status = DocumentDeliveryStatus.FAILED)
        assertFailsWith<IllegalArgumentException> {
            failedWithoutFence.retryManually(Identifier("replacement-event"))
        }

        val deliveredWithoutFence = target(status = DocumentDeliveryStatus.SUCCEEDED)
        assertFailsWith<IllegalArgumentException> {
            deliveredWithoutFence.requestRemoval(Identifier("removal-event"))
        }
    }

    @Test
    fun `restores an exact persisted fence once without changing the constructor ABI state`() {
        val target = target(status = DocumentDeliveryStatus.FAILED)
        val persisted = DeliveryDispatchFence(
            Identifier("persisted-event"),
            DeliveryDispatchOperation.DELIVERY,
            41,
        )

        assertSame(target, target.restoreDispatch(persisted))
        assertSame(persisted, target.currentDispatchFence)
        assertTrue(target.matchesDispatch(Identifier("persisted-event"), DeliveryDispatchOperation.DELIVERY, 41))
        assertFailsWith<IllegalStateException> { target.restoreDispatch(persisted) }
    }

    @Test
    fun `rejects restored operation and target state combinations that cannot be produced`() {
        assertFailsWith<IllegalArgumentException> {
            target(status = DocumentDeliveryStatus.FAILED).restoreDispatch(
                DeliveryDispatchFence(Identifier("removal-event"), DeliveryDispatchOperation.REMOVAL, 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            target(
                status = DocumentDeliveryStatus.SUCCEEDED,
                removalStatus = DocumentDeliveryRemovalStatus.PENDING,
            ).restoreDispatch(
                DeliveryDispatchFence(Identifier("delivery-event"), DeliveryDispatchOperation.DELIVERY, 1),
            )
        }
    }

    @Test
    fun `rejects invalid fences repeated event ids and sequence overflow`() {
        assertFailsWith<IllegalArgumentException> {
            DeliveryDispatchFence(Identifier("event"), DeliveryDispatchOperation.DELIVERY, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DeliveryDispatchFence(Identifier("e".repeat(65)), DeliveryDispatchOperation.DELIVERY, 1)
        }

        val target = target()
        target.bindInitialDelivery(Identifier("event-1"))
        target.markFailed("failed")
        assertFailsWith<IllegalArgumentException> { target.retryManually(Identifier("event-1")) }

        val exhausted = target(status = DocumentDeliveryStatus.FAILED).restoreDispatch(
            DeliveryDispatchFence(Identifier("event-max"), DeliveryDispatchOperation.DELIVERY, Long.MAX_VALUE),
        )
        assertFailsWith<IllegalStateException> { exhausted.retryManually(Identifier("event-next")) }
        assertEquals(DocumentDeliveryStatus.FAILED, exhausted.status)
        assertEquals("event-max", exhausted.currentDispatchFence?.eventId?.value)
    }

    private fun target(
        status: DocumentDeliveryStatus = DocumentDeliveryStatus.PENDING,
        removalStatus: DocumentDeliveryRemovalStatus = DocumentDeliveryRemovalStatus.NOT_REQUESTED,
    ) = DocumentDeliveryTarget(
        id = Identifier("delivery-1"),
        tenantId = Identifier("tenant-1"),
        documentId = Identifier("document-1"),
        profileId = "profile-1",
        targetId = "archive",
        displayName = "Archive",
        connectorId = "archive-connector",
        requirement = DeliveryRequirement.REQUIRED,
        status = status,
        removalStatus = removalStatus,
    )
}
