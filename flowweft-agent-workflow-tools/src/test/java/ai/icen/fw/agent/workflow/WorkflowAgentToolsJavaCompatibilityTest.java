package ai.icen.fw.agent.workflow;

import ai.icen.fw.agent.api.AgentRunContext;
import ai.icen.fw.agent.api.ProviderId;
import ai.icen.fw.core.id.Identifier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkflowAgentToolsJavaCompatibilityTest {
    @Test
    void javaCanDiscoverPlanAndImplementExplicitApplicationPorts() {
        WorkflowAgentToolCatalog catalog = new WorkflowAgentToolCatalog();
        AgentRunContext context = new AgentRunContext(
            new Identifier("tenant-java"),
            new Identifier("principal-java"),
            "USER",
            new Identifier("request-java"),
            1L
        );
        assertEquals(17, catalog.descriptors(context).getDescriptors().size());
        assertEquals(64, catalog.getCatalogDigest().length());
        assertNotNull(catalog.descriptor(WorkflowAgentOperation.PUBLISH_DEFINITION.getToolId()));

        WorkflowAgentAuthorizationTarget target = WorkflowAgentAuthorizationTarget.decode(
            WorkflowAgentOperation.PUBLISH_DEFINITION.getToolId(),
            publishArguments()
        );
        assertEquals("workflow.definition.publish", target.getAction());
        assertEquals("definition-java", target.getResourceId());
        assertEquals(64, target.getResourceRevision().length());

        WorkflowAgentApplicationPorts ports = new WorkflowAgentApplicationPorts(
            new DefinitionPorts(),
            new InstancePorts(),
            new HumanTaskPorts(),
            command -> rejected()
        );
        WorkflowAgentExecutionAuthorizationPort authorization = request ->
            WorkflowAgentExecutionAuthorizationDecision.authorize(
                "decision-java",
                request,
                request.getContext().getAuthorizationRevision(),
                repeat('b', 64),
                request.getRequestedAt(),
                request.getContext().getAuthorizationExpiresAt()
            );
        WorkflowAgentToolSuite suite = new WorkflowAgentToolSuite(authorization, ports);
        assertNotNull(suite.executor(WorkflowAgentOperation.TERMINATE_INSTANCE.getToolId()));
        assertNotNull(new WorkflowAgentToolPlanResolver(
            catalog,
            new ProviderId("authorization.java"),
            new ProviderId("policy.java")
        ));
    }

    private static final class DefinitionPorts implements WorkflowAgentDefinitionUseCasePort {
        @Override public CompletionStage<WorkflowAgentUseCaseResult> saveDraft(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> publish(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> retire(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
    }

    private static final class InstancePorts implements WorkflowAgentInstanceUseCasePort {
        @Override public CompletionStage<WorkflowAgentUseCaseResult> start(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> suspend(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> resume(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> cancel(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> terminate(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
    }

    private static final class HumanTaskPorts implements WorkflowAgentHumanTaskUseCasePort {
        @Override public CompletionStage<WorkflowAgentUseCaseResult> approve(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> reject(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> claim(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> unclaim(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> delegate(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> transfer(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> addSign(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
        @Override public CompletionStage<WorkflowAgentUseCaseResult> returnTask(WorkflowAgentAuthorizedCommand command) {
            return rejected();
        }
    }

    private static CompletionStage<WorkflowAgentUseCaseResult> rejected() {
        return CompletableFuture.completedFuture(WorkflowAgentUseCaseResult.rejected("WORKFLOW_UNSUPPORTED"));
    }

    private static byte[] publishArguments() {
        String json = "{\"definitionDigest\":\"" + repeat('a', 64) +
            "\",\"definitionId\":\"definition-java\",\"definitionVersion\":\"1\"," +
            "\"executionNonce\":\"nonce-java\",\"expectedDefinitionStateVersion\":1," +
            "\"expectedIncidentVersion\":0,\"expectedInstanceVersion\":0," +
            "\"expectedWorkItemVersion\":0,\"idempotencyKey\":\"idem-java\"," +
            "\"incidentId\":\"-\",\"instanceId\":\"-\"," +
            "\"operation\":\"workflow.definition.publish\",\"payload\":{}," +
            "\"purpose\":\"publish-java\",\"resourceId\":\"definition-java\"," +
            "\"resourceType\":\"workflow-definition\",\"workItemId\":\"-\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
