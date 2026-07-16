package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowDefinitionRef;
import ai.icen.fw.workflow.api.WorkflowInstanceRef;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.api.WorkflowWorkItemRef;
import ai.icen.fw.workflow.runtime.WorkflowAttestationCommand;
import ai.icen.fw.workflow.runtime.WorkflowAttestationProviderProfile;
import ai.icen.fw.workflow.runtime.WorkflowAttestationResultCode;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAction;
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext;
import ai.icen.fw.workflow.spi.WorkflowAttestationProfileRef;
import ai.icen.fw.workflow.spi.WorkflowAttestationStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaWorkflowAttestationRuntimeCompatibilityTest {
    @Test
    void exposesJavaFriendlyAttestationBoundary() {
        WorkflowPrincipalRef actor = WorkflowPrincipalRef.of("user", "alice");
        WorkflowSubjectSnapshot subject = WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of("legal-file", "file-1"),
                "subject-r1",
                repeat('a'));
        WorkflowAttestationStatement statement = WorkflowAttestationStatement.of(
                WorkflowDefinitionRef.of("legal-review", "1", repeat('b')),
                WorkflowInstanceRef.of("instance-1", 4L),
                WorkflowWorkItemRef.of("work-item-1", 2L),
                subject,
                actor,
                repeat('c'),
                "idempotency-1",
                repeat('d'));
        WorkflowTrustedCallContext context = WorkflowTrustedCallContext.of(
                "tenant-1",
                actor,
                "authentication-1",
                repeat('e'));
        WorkflowAttestationProfileRef profile = WorkflowAttestationProfileRef.of(
                "signature-provider",
                "enterprise-signature",
                "profile-v1",
                repeat('f'));

        WorkflowAttestationCommand command = WorkflowAttestationCommand.electronicSignature(
                context,
                "request-1",
                profile,
                statement);
        WorkflowAttestationProviderProfile provider = WorkflowAttestationProviderProfile.of(
                "signature-provider",
                "provider-r1",
                1_000L,
                4_096,
                4_096);

        assertNotNull(command.getRequestDigest());
        assertEquals("signature-provider", provider.getProviderId());
        assertEquals("attest-human-decision", WorkflowRuntimeAction.ATTEST_HUMAN_DECISION.getCode());
        assertEquals("outcome-unknown", WorkflowAttestationResultCode.OUTCOME_UNKNOWN.getCode());
    }

    private static String repeat(char value) {
        char[] chars = new char[64];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
