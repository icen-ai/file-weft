package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDecisionEvidenceDto;
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDecisionEvidencePageQuery;
import ai.icen.fw.web.api.v1.workflow.WorkflowDecisionTaskEvidenceDto;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowDecisionEvidenceContractsInteropTest {
    @Test
    void constructsAndReadsDecisionEvidenceFromJava() {
        WorkflowDecisionTaskEvidenceDto legacy = new WorkflowDecisionTaskEvidenceDto(
            "task-legacy", "APPROVED", 1L, 2L
        );
        WorkflowDecisionTaskEvidenceDto recorded = new WorkflowDecisionTaskEvidenceDto(
            "task-1", "APPROVED", 1L, 2L, "reviewer-java", "Java Reviewer", 2L
        );
        DocumentWorkflowDecisionEvidenceDto workflow = new DocumentWorkflowDecisionEvidenceDto(
            "workflow-1", "document-1", "DOCUMENT_REVIEW", "APPROVED", 1L, 2L,
            Collections.singletonList(recorded)
        );
        DocumentWorkflowDecisionEvidencePageQuery defaults = new DocumentWorkflowDecisionEvidencePageQuery();
        DocumentWorkflowDecisionEvidencePageQuery query =
            new DocumentWorkflowDecisionEvidencePageQuery("cursor", 30);

        assertFalse(legacy.getDecisionEvidenceRecorded());
        assertNull(legacy.getDecisionOperatorId());
        assertTrue(recorded.getDecisionEvidenceRecorded());
        assertEquals("reviewer-java", recorded.getDecisionOperatorId());
        assertEquals("Java Reviewer", workflow.getTasks().get(0).getDecisionOperatorName());
        assertEquals(20, defaults.getLimit());
        assertNull(defaults.getCursor());
        assertEquals("cursor", query.getCursor());
        assertEquals(30, query.getLimit());
    }
}
