package ai.icen.fw.application.delivery

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentDeliveryErrorCategoryTest {
    @Test
    fun `returns null when no diagnostic was persisted`() {
        assertNull(DocumentDeliveryErrorCategory.classify(null))
        assertNull(DocumentDeliveryErrorCategory.classify(""))
        assertNull(DocumentDeliveryErrorCategory.classify("   "))
    }

    @Test
    fun `classifies missing or removed delivery targets`() {
        assertEquals(
            DocumentDeliveryErrorCategory.TARGET_NOT_FOUND,
            DocumentDeliveryErrorCategory.classify("Delivery target was not found in the event tenant."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.TARGET_NOT_FOUND,
            DocumentDeliveryErrorCategory.classify("Delivery target was removed before synchronization completed."),
        )
    }

    @Test
    fun `classifies documents that are missing removed or not deliverable`() {
        assertEquals(
            DocumentDeliveryErrorCategory.DOCUMENT_UNAVAILABLE,
            DocumentDeliveryErrorCategory.classify("Document was not found in the event tenant."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.DOCUMENT_UNAVAILABLE,
            DocumentDeliveryErrorCategory.classify("Document was removed before synchronization completed."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.DOCUMENT_UNAVAILABLE,
            DocumentDeliveryErrorCategory.classify("Document is not available for delivery from lifecycle state DRAFT."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.DOCUMENT_UNAVAILABLE,
            DocumentDeliveryErrorCategory.classify("Document has no active version."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.DOCUMENT_UNAVAILABLE,
            DocumentDeliveryErrorCategory.classify("Active document version references a missing file object."),
        )
    }

    @Test
    fun `classifies a missing dispatch fence`() {
        assertEquals(
            DocumentDeliveryErrorCategory.FENCE_INVALID,
            DocumentDeliveryErrorCategory.classify("Delivery target is missing its dispatch fence."),
        )
    }

    @Test
    fun `classifies connector resolution configuration and invocation failures`() {
        assertEquals(
            DocumentDeliveryErrorCategory.CONNECTOR_FAILURE,
            DocumentDeliveryErrorCategory.classify("Delivery connector resolution could not complete."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.CONNECTOR_FAILURE,
            DocumentDeliveryErrorCategory.classify("Delivery connector 'dify-main' is no longer configured."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.CONNECTOR_FAILURE,
            DocumentDeliveryErrorCategory.classify("Connector invocation could not complete."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.CONNECTOR_FAILURE,
            DocumentDeliveryErrorCategory.classify(
                "Connector reported success with an invalid external identifier; expected a non-blank value " +
                    "without ISO control characters and at most 512 UTF-16 code units.",
            ),
        )
    }

    @Test
    fun `classifies storage source access failures`() {
        assertEquals(
            DocumentDeliveryErrorCategory.STORAGE_FAILURE,
            DocumentDeliveryErrorCategory.classify("Storage source access could not be prepared."),
        )
    }

    @Test
    fun `classifies persistence failures from the outbox wrapper exception class`() {
        assertEquals(
            DocumentDeliveryErrorCategory.PERSISTENCE_FAILURE,
            DocumentDeliveryErrorCategory.classify(
                "Outbox handler failed: org.postgresql.util.PSQLException: connection to host refused",
            ),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.PERSISTENCE_FAILURE,
            DocumentDeliveryErrorCategory.classify("Outbox handler failed: java.sql.SQLException"),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.PERSISTENCE_FAILURE,
            DocumentDeliveryErrorCategory.classify(
                "Outbox handler selection failed: org.springframework.jdbc.CannotGetJdbcConnectionException: pool exhausted",
            ),
        )
    }

    @Test
    fun `collapses unrecognized and generic diagnostics to unknown`() {
        assertEquals(
            DocumentDeliveryErrorCategory.UNKNOWN,
            DocumentDeliveryErrorCategory.classify("Outbox handler failed: java.lang.IllegalStateException: mutation-capable repository required"),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.UNKNOWN,
            DocumentDeliveryErrorCategory.classify("Outbox handler requested a retry."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.UNKNOWN,
            DocumentDeliveryErrorCategory.classify("Delivery retry limit was reached."),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.UNKNOWN,
            DocumentDeliveryErrorCategory.classify("vendor downstream returned HTTP 503 with trace abc123"),
        )
        assertEquals(
            DocumentDeliveryErrorCategory.UNKNOWN,
            DocumentDeliveryErrorCategory.classify("jdbc://private-delivery-error"),
        )
    }

    @Test
    fun `matches fixed phrases deterministically regardless of surrounding whitespace`() {
        assertEquals(
            DocumentDeliveryErrorCategory.FENCE_INVALID,
            DocumentDeliveryErrorCategory.classify("  Delivery target is missing its dispatch fence.  "),
        )
    }
}
