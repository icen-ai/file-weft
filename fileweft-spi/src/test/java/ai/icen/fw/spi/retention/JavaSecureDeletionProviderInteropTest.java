package ai.icen.fw.spi.retention;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSecureDeletionProviderInteropTest {
    @Test
    void invokesProviderNeutralDeletionAndReconciliationFromJava() {
        SecureDeletionProviderRequest request = new SecureDeletionProviderRequest(
            new Identifier("tenant-java"),
            new Identifier("plan-java"),
            new Identifier("tombstone-java"),
            "DOCUMENT",
            new Identifier("document-java"),
            3L,
            SecureDeletionTarget.INDEX,
            "delete-index-java",
            Duration.ofSeconds(30)
        );
        SecureDeletionProvider provider = new SecureDeletionProvider() {
            @Override
            public String providerId() {
                return "java-index";
            }

            @Override
            public SecureDeletionTarget target() {
                return SecureDeletionTarget.INDEX;
            }

            @Override
            public SecureDeletionProviderReceipt requestDeletion(SecureDeletionProviderRequest currentRequest) {
                return new SecureDeletionProviderReceipt(
                    providerId(),
                    target(),
                    SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED,
                    currentRequest.getBindingDigest(),
                    "receipt-1",
                    "accepted",
                    Collections.singletonMap("generationCount", "2")
                );
            }

            @Override
            public SecureDeletionProviderReceipt reconcileDeletion(
                SecureDeletionProviderRequest currentRequest,
                SecureDeletionProviderReceipt previousReceipt
            ) {
                assertEquals("receipt-1", previousReceipt.getReceiptReference());
                return new SecureDeletionProviderReceipt(
                    providerId(),
                    target(),
                    SecureDeletionProviderStatus.VERIFIED_ABSENT,
                    currentRequest.getBindingDigest(),
                    "receipt-2",
                    "verified",
                    Collections.emptyMap()
                );
            }
        };

        SecureDeletionProviderReceipt accepted = provider.requestDeletion(request);
        SecureDeletionProviderReceipt verified = provider.reconcileDeletion(request, accepted);

        assertEquals(SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED, accepted.getStatus());
        assertEquals(request.getBindingDigest(), accepted.getRequestBindingDigest());
        assertTrue(verified.isVerifiedAbsent());
    }
}
