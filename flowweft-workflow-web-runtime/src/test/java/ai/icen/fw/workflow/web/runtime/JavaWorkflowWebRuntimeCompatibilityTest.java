package ai.icen.fw.workflow.web.runtime;

import ai.icen.fw.workflow.web.api.WorkflowWebApplicationResult;
import ai.icen.fw.workflow.web.api.WorkflowWebRoute;
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContext;
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContextProvider;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaWorkflowWebRuntimeCompatibilityTest {
    @Test
    void javaEightAdaptersCanExecuteTypedRoutes() {
        WorkflowWebTrustedContext context = WorkflowWebTrustedContext.authenticated(
            "tenant-java", "user", "user-java", "auth-java", repeat('a'));
        WorkflowWebControllerRuntime runtime = new WorkflowWebControllerRuntime(
            new WorkflowWebTrustedContextProvider() {
                @Override public WorkflowWebTrustedContext currentContext() { return context; }
            });
        WorkflowWebRoute route = null;
        for (WorkflowWebRoute candidate : WorkflowWebRoute.all()) {
            if (candidate.getOperationId().equals("getWorkflowInstance")) route = candidate;
        }
        WorkflowWebHttpResponse<String> response = runtime.executeRead(
            route,
            WorkflowWebRequestMetadata.of(
                "GET", null, "application/json", 0,
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            new WorkflowWebReadInvocation<String>() {
                @Override public WorkflowWebApplicationResult<String> invoke(WorkflowWebTrustedContext trusted) {
                    return WorkflowWebApplicationResult.success("ok", false);
                }
            });
        assertEquals(200, response.getStatus());
        assertEquals("ok", response.getBody().getData());
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < 64; index++) result.append(value);
        return result.toString();
    }
}
