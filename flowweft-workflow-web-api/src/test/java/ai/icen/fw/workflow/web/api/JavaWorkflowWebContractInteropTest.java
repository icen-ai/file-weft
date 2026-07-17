package ai.icen.fw.workflow.web.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowWebContractInteropTest {

    @Test
    void constructsAndReadsTheContractsFromJava() {
        String digest = repeat('a', 64);
        WorkflowWebTrustedContext context = WorkflowWebTrustedContext.authenticated(
            "tenant-1",
            "USER",
            "user-1",
            "authentication-1",
            digest
        );
        WorkflowWebWritePreconditions preconditions = WorkflowWebWritePreconditions.parse(
            "command-1",
            "\"fw-7\""
        );
        WorkflowSubjectDto subject = new WorkflowSubjectDto("DOCUMENT", "document-1", "version-1", digest);
        WorkflowInstanceStartCommand start = new WorkflowInstanceStartCommand("leave", "1.0", subject);
        WorkflowPrincipalTargetCommand target = new WorkflowPrincipalTargetCommand("USER", "approver-1");
        WorkflowTaskAddSignCommand addSign = new WorkflowTaskAddSignCommand(
            Collections.singletonList(target),
            "PARALLEL",
            "EXPERT_REVIEW"
        );
        WorkflowCommentDocumentCommand comment = new WorkflowCommentDocumentCommand(Arrays.asList(
            WorkflowCommentTokenCommand.text("Please review"),
            WorkflowCommentTokenCommand.mention(target)
        ));
        WorkflowTaskDecisionCommand decision = new WorkflowTaskDecisionCommand("APPROVE", comment);
        WorkflowWebCommandReceiptDto receipt = new WorkflowWebCommandReceiptDto("INSTANCE", "instance-1", 8L, "RUNNING");
        WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto> result = WorkflowWebApplicationResult.success(receipt, true);
        WorkflowWebResponse<WorkflowWebCommandReceiptDto> response = WorkflowWebResponse.success(receipt, "trace-1");

        assertEquals("tenant-1", context.getTenantId());
        assertEquals(7L, preconditions.getVersionTag().getExpectedVersion());
        assertEquals("leave", start.getDefinitionKey());
        assertEquals(1, addSign.getTargets().size());
        assertEquals("APPROVE", decision.getAction());
        assertEquals("instance-1", result.getValue().getResourceId());
        assertTrue(result.getReplayed());
        assertTrue(response.isSuccess());
        assertFalse(response.isFailure());
        assertEquals(200, WorkflowWebHttpStatusPolicy.statusFor(WorkflowWebErrorCodes.OK));
        assertEquals("Idempotency-Key", WorkflowWebRoute.IDEMPOTENCY_HEADER);
        assertEquals("If-Match", WorkflowWebRoute.IF_MATCH_HEADER);
        assertEquals("private, no-store", WorkflowWebHttpContract.CACHE_CONTROL_VALUE);
        assertEquals("nosniff", WorkflowWebHttpContract.CONTENT_TYPE_OPTIONS_VALUE);
        assertNotNull(new WorkflowTaskClaimCommand());
        assertEquals(50, new WorkflowWebPageQuery().getLimit());
    }

    @Test
    void exposesExplicitUnsupportedAndHiddenResultsToJavaAdapters() {
        WorkflowWebApplicationResult<String> unsupported = WorkflowWebApplicationResult.unsupported();
        WorkflowWebApplicationResult<String> hidden = WorkflowWebApplicationResult.hidden();

        assertEquals("CAPABILITY_UNSUPPORTED", unsupported.getCode());
        assertNull(unsupported.getValue());
        assertEquals("NOT_FOUND", hidden.getCode());
        assertEquals(503, WorkflowWebHttpStatusPolicy.statusFor(unsupported.getCode()));
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
