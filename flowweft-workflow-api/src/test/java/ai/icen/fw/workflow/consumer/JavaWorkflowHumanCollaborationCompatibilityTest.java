package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction;
import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JavaWorkflowHumanCollaborationCompatibilityTest {
    private static final String DIGEST =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void javaEightConsumerCanBindFormRuleAndCollaborationAction() {
        WorkflowHumanTaskEvidenceBinding binding = WorkflowHumanTaskEvidenceBinding.of(
            "expense-form", "v3", DIGEST, "expense-rule", "v7", DIGEST);

        assertEquals("v3", binding.getFormVersion());
        assertEquals("v7", binding.getRuleVersion());
        assertSame(WorkflowHumanCollaborationAction.CLAIM, WorkflowHumanCollaborationAction.of("claim"));
        assertSame(WorkflowHumanCollaborationAction.TRANSFER, WorkflowHumanCollaborationAction.of("transfer"));
        assertSame(WorkflowHumanCollaborationAction.ADD_SIGN, WorkflowHumanCollaborationAction.of("add-sign"));
        assertSame(WorkflowHumanCollaborationAction.RETURN, WorkflowHumanCollaborationAction.of("return"));
    }
}
