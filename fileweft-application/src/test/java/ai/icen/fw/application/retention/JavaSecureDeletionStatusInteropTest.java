package ai.icen.fw.application.retention;

import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.domain.retention.SecureDeletionStage;
import ai.icen.fw.spi.retention.SecureDeletionProviderStatus;
import ai.icen.fw.spi.retention.SecureDeletionTarget;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaSecureDeletionStatusInteropTest {
    @Test
    void readsRedactedSecureDeletionStatusFromJava() {
        SecureDeletionProviderStatusView receipt = new SecureDeletionProviderStatusView(
            "java-index",
            SecureDeletionTarget.INDEX,
            SecureDeletionProviderStatus.VERIFIED_ABSENT,
            1_000L
        );
        SecureDeletionStatusView view = new SecureDeletionStatusView(
            new Identifier("plan-java"),
            new Identifier("tenant-java"),
            new Identifier("tombstone-java"),
            "DOCUMENT",
            new Identifier("document-java"),
            4L,
            SecureDeletionStage.PURGE_OBJECT_STORAGE,
            SecureDeletionExecutionStatus.PENDING,
            0,
            null,
            1_000L,
            Collections.singletonList(receipt)
        );

        assertEquals("plan-java", view.getPlanId().getValue());
        assertEquals(SecureDeletionTarget.INDEX, view.getProviderReceipts().get(0).getTarget());
    }
}
