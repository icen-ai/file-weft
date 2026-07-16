package ai.icen.fw.workflow.document.selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaDocumentSelectionRuntimeCompatibilityTest {
    private static final String DIGEST =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void javaHostCanImplementSelectionPortsAndConstructRuntime() {
        DocumentSelectionFactsPort facts = request -> null;
        DocumentSelectionAuthorizationPort authorization = request -> null;
        ExactDocumentWorkflowSelectionRuntime runtime =
            new ExactDocumentWorkflowSelectionRuntime(facts, authorization);
        DocumentSelectionPolicyDescriptor descriptor = DocumentSelectionPolicyDescriptor.of(
            "host-policy",
            "document-routing",
            "1",
            DIGEST,
            "1",
            DIGEST
        );
        DocumentSelectionPolicyProvider provider = new DocumentSelectionPolicyProvider() {
            @Override
            public DocumentSelectionPolicyDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public DocumentSelectionPolicyResult select(DocumentSelectionPolicyRequest request) {
                return DocumentSelectionPolicyResult.unsupported(
                    request.getRequestDigest(),
                    descriptor.getDescriptorDigest(),
                    "policy-revision-1",
                    DIGEST,
                    "classification-unsupported",
                    1_000L,
                    1_000L
                );
            }
        };

        assertNotNull(runtime);
        assertEquals("document-routing", provider.descriptor().getProfileId());
        assertNotNull(descriptor.getDescriptorDigest());
    }
}
