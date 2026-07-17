package ai.icen.fw.workflow.document;

import ai.icen.fw.workflow.api.WorkflowDefinitionRef;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.domain.WorkflowExecutionIds;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommandOptions;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeStartRequest;
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class JavaDocumentWorkflowAdapterCompatibilityTest {
    private static final String A = repeat('a');
    private static final String B = repeat('b');
    private static final String C = repeat('c');
    private static final String D = repeat('d');

    @Test
    void publicContractsAreConsumableFromJava8WithoutKotlinFunctionTypes() {
        WorkflowPrincipalRef actor = WorkflowPrincipalRef.of("user", "alice");
        WorkflowTrustedCallContext context = WorkflowTrustedCallContext.of("tenant-a", actor, "auth-1", A);
        WorkflowSubjectRef subjectRef = WorkflowSubjectRef.of(
            DocumentWorkflowSubmissionRequest.DOCUMENT_SUBJECT_TYPE,
            "document-1"
        );
        WorkflowSubjectSnapshot subject = WorkflowSubjectSnapshot.of(subjectRef, "version-1", B);
        DocumentWorkflowSelection selection = DocumentWorkflowSelection.of(
            "definition-id-1",
            WorkflowDefinitionRef.of("legal-document-approval", "5", C),
            DocumentWorkflowTemplateRef.of("legal-document", "template-4", D),
            DocumentWorkflowRevisionPolicyRef.of("revision-policy-2", A, "legal-review"),
            "selection-revision-8"
        );
        WorkflowRuntimeCommandOptions options = WorkflowRuntimeCommandOptions.of(
            "command-1",
            "idempotency-1",
            0L,
            100L,
            16,
            WorkflowExecutionIds.empty()
        );
        DocumentWorkflowSubmissionRequest submission = DocumentWorkflowSubmissionRequest.of(
            context,
            options,
            "instance-1",
            subject,
            selection,
            A
        );

        DocumentWorkflowSubjectApplicationPort subjectPort = request -> null;
        DocumentWorkflowSelectionApplicationPort selectionPort = request -> null;
        DocumentWorkflowAuthorizationApplicationPort authorizationPort = request ->
            DocumentWorkflowAuthorizationDecision.of(
                "authorization-1",
                request.getCallContext().getTenantId(),
                request.getCallContext().getActor(),
                request.getRequestDigest(),
                DocumentWorkflowAuthorizationStatus.AUTHORIZED,
                "policy-revision-1",
                A,
                request.getEvaluatedAtEpochMilli(),
                request.getEvaluatedAtEpochMilli() + 100L
            );
        DocumentWorkflowBindingApplicationPort bindingPort = new DocumentWorkflowBindingApplicationPort() {
            @Override
            public DocumentWorkflowBindingReservation reserve(DocumentWorkflowBindingReserveRequest request) {
                return DocumentWorkflowBindingReservation.rejected(
                    DocumentWorkflowBindingReservationCode.ACTIVE_CONFLICT
                );
            }

            @Override
            public DocumentWorkflowBinding find(DocumentWorkflowBindingLookupRequest request) {
                return null;
            }

            @Override
            public DocumentWorkflowBindingTransitionResult transition(
                DocumentWorkflowBindingTransitionRequest request
            ) {
                return DocumentWorkflowBindingTransitionResult.failure(
                    DocumentWorkflowPortOutcome.REJECTED,
                    "not-installed"
                );
            }
        };
        DocumentWorkflowDocumentApplicationPort documentPort = request ->
            DocumentWorkflowDocumentMutationResult.failure(
                DocumentWorkflowPortOutcome.REJECTED,
                "not-installed"
            );
        DocumentWorkflowGenericApplicationPort workflowPort = new DocumentWorkflowGenericApplicationPort() {
            @Override
            public DocumentWorkflowGenericCommandResult start(WorkflowRuntimeStartRequest request) {
                return DocumentWorkflowGenericCommandResult.failure(
                    DocumentWorkflowPortOutcome.REJECTED,
                    "not-installed"
                );
            }

            @Override
            public DocumentWorkflowGenericCommandResult transitionSubjectRevision(
                DocumentWorkflowSubjectRevisionCommand request
            ) {
                return DocumentWorkflowGenericCommandResult.failure(
                    DocumentWorkflowPortOutcome.REJECTED,
                    "not-installed"
                );
            }
        };

        DocumentWorkflowAdapter adapter = new DocumentWorkflowAdapter(
            subjectPort,
            selectionPort,
            authorizationPort,
            bindingPort,
            documentPort,
            workflowPort
        );

        assertNotNull(adapter);
        assertEquals("instance-1", submission.getInstanceId());
        assertSame(DocumentWorkflowAction.SUBMIT, submission.getAction());
        assertEquals("template-4", submission.getExpectedSelection().getTemplateRef().getRevision());
        assertEquals("legal-review", selection.getRevisionPolicy().getResumeNodeId());
        assertEquals("idempotency-1", submission.getOptions().getIdempotencyKey());
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) result.append(value);
        return result.toString();
    }
}
