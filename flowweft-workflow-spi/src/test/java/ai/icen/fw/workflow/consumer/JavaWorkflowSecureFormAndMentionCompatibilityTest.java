package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.spi.WorkflowMentionResolver;
import ai.icen.fw.workflow.spi.WorkflowMentionSearchRequest;
import ai.icen.fw.workflow.spi.WorkflowMentionSearchResult;
import ai.icen.fw.workflow.spi.WorkflowMentionVisibilityRequest;
import ai.icen.fw.workflow.spi.WorkflowMentionVisibilityResult;
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidationRequest;
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidationResult;
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaWorkflowSecureFormAndMentionCompatibilityTest {
    @Test
    void completionStageBoundariesAreUsableFromPureJava() {
        WorkflowSecureFormValidator validator = request -> CompletableFuture.completedFuture(null);
        WorkflowMentionResolver resolver = new WorkflowMentionResolver() {
            @Override
            public CompletionStage<WorkflowMentionSearchResult> search(WorkflowMentionSearchRequest request) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<WorkflowMentionVisibilityResult> verifyVisibility(
                WorkflowMentionVisibilityRequest request
            ) {
                return CompletableFuture.completedFuture(null);
            }
        };

        assertNotNull(validator);
        assertNotNull(resolver);
        assertNotNull(WorkflowPrincipalRef.of("user", "stable-id"));
    }

    private CompletionStage<WorkflowSecureFormValidationResult> validate(
        WorkflowSecureFormValidator validator,
        WorkflowSecureFormValidationRequest request
    ) {
        return validator.validate(request);
    }
}
