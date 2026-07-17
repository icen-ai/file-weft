package ai.icen.fw.workflow.document.fileweft;

import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.document.DocumentWorkflowPortOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaFileWeftDocumentWorkflowCompatibilityTest {
    private static final String DIGEST =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void javaHostCanImplementAndConsumeTheNarrowRevisionFacade() {
        WorkflowSubjectSnapshot subject = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("document", "document-1"),
            "version-1",
            DIGEST
        );
        FileWeftOpenDocumentRevisionDraftCommand command =
            FileWeftOpenDocumentRevisionDraftCommand.of(
                new Identifier("tenant-1"),
                new Identifier("alice"),
                new Identifier("document-1"),
                subject,
                "instance-1",
                1L,
                "idempotency-key-1",
                DIGEST,
                DIGEST,
                DIGEST,
                DIGEST,
                1_000L
            );

        FileWeftDocumentRevisionCycleApplicationFacade custom = request ->
            FileWeftDocumentRevisionCycleResult.success(
                DocumentWorkflowPortOutcome.APPLIED,
                request.getRetainedSubject(),
                DIGEST
            );
        FileWeftDocumentRevisionCycleResult applied = custom.openRevisionDraft(command);
        FileWeftDocumentRevisionCycleResult unsupported =
            FileWeftDocumentRevisionCycleApplicationFacade.unsupported()
                .openRevisionDraft(command);

        assertNotNull(command.getCommandDigest());
        assertEquals(DocumentWorkflowPortOutcome.APPLIED, applied.getOutcome());
        assertEquals(subject, applied.getRetainedSubject());
        assertEquals(DocumentWorkflowPortOutcome.REJECTED, unsupported.getOutcome());
        assertEquals(
            FileWeftDocumentRevisionCycleApplicationFacade.UNSUPPORTED_FAILURE_CODE,
            unsupported.getFailureCode()
        );
    }
}
