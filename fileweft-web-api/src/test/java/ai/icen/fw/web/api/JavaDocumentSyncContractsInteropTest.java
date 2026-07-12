package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.document.DocumentDeliveryRecoveryResultDto;
import ai.icen.fw.web.api.v1.document.DocumentDeliverySyncStatusDto;
import ai.icen.fw.web.api.v1.document.DocumentSyncStatusDto;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDocumentSyncContractsInteropTest {
    @Test
    void exposesJava8FriendlyImmutableSynchronizationContracts() {
        DocumentDeliverySyncStatusDto target = new DocumentDeliverySyncStatusDto(
            "delivery-1",
            "target-1",
            "Primary archive",
            "REQUIRED",
            "FAILED",
            2,
            "NOT_REQUESTED",
            0,
            true,
            false,
            200L
        );
        DocumentSyncStatusDto status = new DocumentSyncStatusDto(
            "document-1",
            Collections.singletonList(target)
        );
        DocumentDeliveryRecoveryResultDto recovery = new DocumentDeliveryRecoveryResultDto(
            "document-1",
            "delivery-1",
            "DELIVERY"
        );

        assertEquals("document-1", status.getDocumentId());
        assertEquals("delivery-1", status.getDeliveryTargets().get(0).getDeliveryId());
        assertEquals("target-1", target.getTargetId());
        assertEquals("Primary archive", target.getDisplayName());
        assertEquals("REQUIRED", target.getRequirement());
        assertEquals("FAILED", target.getDeliveryStatus());
        assertEquals(2, target.getDeliveryRetryCount());
        assertEquals("NOT_REQUESTED", target.getRemovalStatus());
        assertEquals(0, target.getRemovalRetryCount());
        assertTrue(target.getDeliveryRetryable());
        assertEquals(200L, target.getUpdatedTime());
        assertEquals("document-1", recovery.getDocumentId());
        assertEquals("delivery-1", recovery.getDeliveryId());
        assertEquals("DELIVERY", recovery.getOperation());
        assertThrows(UnsupportedOperationException.class, () -> status.getDeliveryTargets().clear());
    }
}
