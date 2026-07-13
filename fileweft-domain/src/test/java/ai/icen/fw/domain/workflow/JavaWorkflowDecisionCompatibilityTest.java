package ai.icen.fw.domain.workflow;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaWorkflowDecisionCompatibilityTest {
    @Test
    void releasedConstructorsAndDecisionMethodsRemainCallable() {
        Identifier tenantId = new Identifier("tenant-java");
        Identifier workflowId = new Identifier("workflow-java");
        Identifier reviewerId = new Identifier("reviewer-java");
        WorkflowTask task = new WorkflowTask(
            new Identifier("task-java"),
            tenantId,
            workflowId,
            reviewerId,
            WorkflowTaskState.PENDING,
            null
        );
        WorkflowInstance workflow = new WorkflowInstance(
            workflowId,
            tenantId,
            new Identifier("document-java"),
            "DOCUMENT_REVIEW",
            WorkflowState.PENDING,
            Collections.singletonList(task)
        );

        workflow.approve(task.getId(), reviewerId, "approved by released Java API");

        assertEquals(WorkflowTaskState.APPROVED, workflow.getTasks().get(0).getState());
        assertEquals(reviewerId, workflow.getTasks().get(0).getDecisionOperatorId());
        assertNull(workflow.getTasks().get(0).getDecisionOperatorName());
    }
}
