package ai.icen.fw.application.delivery

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.delivery.DeliveryRequirement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentSyncStatusModelsTest {
    @Test
    fun `represents only safe target state and exact retry readiness`() {
        val delivery = target(
            deliveryId = "delivery-a",
            targetId = "archive",
            deliveryStatus = DocumentDeliveryStatus.RETRYING,
            deliveryRetryable = true,
            lastErrorCategory = DocumentDeliveryErrorCategory.CONNECTOR_FAILURE,
        )
        val removal = target(
            deliveryId = "delivery-b",
            targetId = "workspace",
            deliveryStatus = DocumentDeliveryStatus.SUCCEEDED,
            removalStatus = DocumentDeliveryRemovalStatus.RETRYING,
            removalRetryable = true,
        )
        val status = DocumentSyncStatusView(Identifier("document-a"), listOf(delivery, removal))

        assertEquals("document-a", status.documentId.value)
        assertEquals(listOf("delivery-a", "delivery-b"), status.deliveryTargets.map { it.deliveryId.value })
        assertEquals("archive", delivery.targetId)
        assertEquals("Archive", delivery.displayName)
        assertEquals(DeliveryRequirement.REQUIRED, delivery.requirement)
        assertEquals(3, delivery.deliveryRetryCount)
        assertTrue(delivery.deliveryRetryable)
        assertFalse(delivery.removalRetryable)
        assertTrue(removal.removalRetryable)
        assertEquals(DocumentDeliveryErrorCategory.CONNECTOR_FAILURE, delivery.lastErrorCategory)
        assertNull(removal.lastErrorCategory)
    }

    @Test
    fun `copies freezes and validates current target identities`() {
        val source = mutableListOf(target("delivery-a", "archive"))
        val status = DocumentSyncStatusView(Identifier("document-a"), source)
        source.clear()

        assertEquals(listOf("delivery-a"), status.deliveryTargets.map { it.deliveryId.value })
        @Suppress("UNCHECKED_CAST")
        val mutableTargets = status.deliveryTargets as MutableList<DocumentDeliveryStatusView>
        assertFailsWith<UnsupportedOperationException> {
            mutableTargets.add(target("delivery-b", "workspace"))
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentSyncStatusView(
                Identifier("document-a"),
                listOf(target("delivery-a", "archive"), target("delivery-a", "workspace")),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentSyncStatusView(
                Identifier("document-a"),
                listOf(target("delivery-a", "archive"), target("delivery-b", "archive")),
            )
        }
    }

    @Test
    fun `rejects unsafe target metadata counters timestamps and retry flags`() {
        assertFailsWith<IllegalArgumentException> { target(targetId = " ") }
        assertFailsWith<IllegalArgumentException> { target(targetId = "x".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { target(targetId = "archive\u000a") }
        assertFailsWith<IllegalArgumentException> { target(displayName = "Archive\u200B") }
        assertFailsWith<IllegalArgumentException> { target(deliveryRetryCount = -1) }
        assertFailsWith<IllegalArgumentException> { target(removalRetryCount = -1) }
        assertFailsWith<IllegalArgumentException> { target(updatedTime = -1) }
        assertFailsWith<IllegalArgumentException> {
            target(deliveryStatus = DocumentDeliveryStatus.SUCCEEDED, deliveryRetryable = true)
        }
        assertFailsWith<IllegalArgumentException> {
            target(removalStatus = DocumentDeliveryRemovalStatus.NOT_REQUESTED, removalRetryable = true)
        }
        assertFailsWith<IllegalArgumentException> {
            target(
                deliveryStatus = DocumentDeliveryStatus.FAILED,
                removalStatus = DocumentDeliveryRemovalStatus.FAILED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            target(
                deliveryStatus = DocumentDeliveryStatus.FAILED,
                removalStatus = DocumentDeliveryRemovalStatus.FAILED,
                deliveryRetryable = true,
                removalRetryable = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            target(lastErrorCategory = DocumentDeliveryErrorCategory.UNKNOWN)
        }
        assertFailsWith<IllegalArgumentException> {
            target(
                deliveryStatus = DocumentDeliveryStatus.SUCCEEDED,
                lastErrorCategory = DocumentDeliveryErrorCategory.CONNECTOR_FAILURE,
            )
        }
    }

    @Test
    fun `keeps synchronization projections free of integration and dispatch internals`() {
        val forbiddenGetters = setOf(
            "getTenantId", "getProfileId", "getConnectorId", "getOwnerRef", "getExternalId",
            "getErrorMessage", "getRemovalErrorMessage", "getSourceEventId", "getOutboxEvents",
            "getSyncRecords", "getCurrentEventId", "getCurrentOperation", "getDispatchSequence",
            "getCurrentDispatchFence", "getPayload", "getLastError", "getDeliveryGeneration",
            "getEventType", "getEventStatus", "getTraceId",
        )
        val getters = listOf(DocumentDeliveryStatusView::class.java, DocumentSyncStatusView::class.java)
            .flatMap { type ->
                type.methods.filter { method -> method.parameterCount == 0 }.map { method -> method.name }
            }
            .toSet()

        assertTrue(forbiddenGetters.none(getters::contains))
    }

    private fun target(
        deliveryId: String = "delivery-a",
        targetId: String = "archive",
        displayName: String = "Archive",
        deliveryStatus: DocumentDeliveryStatus = DocumentDeliveryStatus.PENDING,
        deliveryRetryCount: Int = 3,
        removalStatus: DocumentDeliveryRemovalStatus = DocumentDeliveryRemovalStatus.NOT_REQUESTED,
        removalRetryCount: Int = 0,
        deliveryRetryable: Boolean = false,
        removalRetryable: Boolean = false,
        updatedTime: Long = 100,
        lastErrorCategory: DocumentDeliveryErrorCategory? = null,
    ): DocumentDeliveryStatusView = DocumentDeliveryStatusView(
        deliveryId = Identifier(deliveryId),
        targetId = targetId,
        displayName = displayName,
        requirement = DeliveryRequirement.REQUIRED,
        deliveryStatus = deliveryStatus,
        deliveryRetryCount = deliveryRetryCount,
        removalStatus = removalStatus,
        removalRetryCount = removalRetryCount,
        deliveryRetryable = deliveryRetryable,
        removalRetryable = removalRetryable,
        updatedTime = updatedTime,
        lastErrorCategory = lastErrorCategory,
    )
}
