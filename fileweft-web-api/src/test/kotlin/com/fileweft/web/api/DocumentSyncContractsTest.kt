package com.fileweft.web.api

import com.fileweft.web.api.v1.document.DocumentDeliveryRecoveryResultDto
import com.fileweft.web.api.v1.document.DocumentDeliverySyncStatusDto
import com.fileweft.web.api.v1.document.DocumentSyncStatusDto
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentSyncContractsTest {
    @Test
    fun `sync status owns an immutable defensive copy with unique delivery ids`() {
        val source = mutableListOf(delivery("delivery-1", "target-1"))
        val status = DocumentSyncStatusDto("document-1", source)

        source += delivery("delivery-2", "target-2")

        assertEquals(listOf("delivery-1"), status.deliveryTargets.map { it.deliveryId })
        assertFailsWith<UnsupportedOperationException> {
            (status.deliveryTargets as MutableList<DocumentDeliverySyncStatusDto>).clear()
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentSyncStatusDto(
                "document-1",
                listOf(delivery("delivery-1", "target-1"), delivery("delivery-1", "target-2")),
            )
        }
    }

    @Test
    fun `delivery status rejects invalid counters timestamps and contradictory retry state`() {
        assertFailsWith<IllegalArgumentException> { delivery("delivery-1", "target-1", deliveryRetries = -1) }
        assertFailsWith<IllegalArgumentException> { delivery("delivery-1", "target-1", removalRetries = -1) }
        assertFailsWith<IllegalArgumentException> { delivery("delivery-1", "target-1", updatedTime = -1) }
        assertFailsWith<IllegalArgumentException> {
            delivery("delivery-1", "target-1", deliveryRetryable = true, removalRetryable = true)
        }
    }

    @Test
    fun `public synchronization receipts expose only the stable redacted getter whitelist`() {
        assertEquals(
            setOf(
                "getDeliveryId",
                "getTargetId",
                "getDisplayName",
                "getRequirement",
                "getDeliveryStatus",
                "getDeliveryRetryCount",
                "getRemovalStatus",
                "getRemovalRetryCount",
                "getDeliveryRetryable",
                "getRemovalRetryable",
                "getUpdatedTime",
            ),
            publicGetters(DocumentDeliverySyncStatusDto::class.java),
        )
        assertEquals(
            setOf("getDocumentId", "getDeliveryTargets"),
            publicGetters(DocumentSyncStatusDto::class.java),
        )
        assertEquals(
            setOf("getDocumentId", "getDeliveryId", "getOperation"),
            publicGetters(DocumentDeliveryRecoveryResultDto::class.java),
        )
    }

    private fun publicGetters(type: Class<*>): Set<String> = type.declaredMethods
        .filter { method ->
            java.lang.reflect.Modifier.isPublic(method.modifiers) &&
                method.parameterCount == 0 &&
                (method.name.startsWith("get") || method.name.startsWith("is"))
        }
        .mapTo(linkedSetOf()) { method -> method.name }

    private fun delivery(
        deliveryId: String,
        targetId: String,
        deliveryRetries: Int = 1,
        removalRetries: Int = 0,
        deliveryRetryable: Boolean = true,
        removalRetryable: Boolean = false,
        updatedTime: Long = 200,
    ): DocumentDeliverySyncStatusDto = DocumentDeliverySyncStatusDto(
        deliveryId = deliveryId,
        targetId = targetId,
        displayName = "Primary archive",
        requirement = "REQUIRED",
        deliveryStatus = "FAILED",
        deliveryRetryCount = deliveryRetries,
        removalStatus = "NOT_REQUESTED",
        removalRetryCount = removalRetries,
        deliveryRetryable = deliveryRetryable,
        removalRetryable = removalRetryable,
        updatedTime = updatedTime,
    )
}
