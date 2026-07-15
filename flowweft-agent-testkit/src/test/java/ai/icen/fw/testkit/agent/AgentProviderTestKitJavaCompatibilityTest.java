package ai.icen.fw.testkit.agent;

import ai.icen.fw.agent.api.AgentAtomicDispatchAuthorizationProvider;
import ai.icen.fw.agent.api.AgentAuthorizationProvider;
import ai.icen.fw.agent.api.AgentAuthorizationRequest;
import ai.icen.fw.agent.api.AgentDescriptorBoundToolExecutor;
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceRequest;
import ai.icen.fw.agent.api.AgentEvaluationRequest;
import ai.icen.fw.agent.api.AgentEvaluator;
import ai.icen.fw.agent.api.AgentEvaluatorDescriptor;
import ai.icen.fw.agent.api.AgentExecutableToolInvocation;
import ai.icen.fw.agent.api.AgentExecutionContextConsumer;
import ai.icen.fw.agent.api.AgentPolicyProposal;
import ai.icen.fw.agent.api.AgentPolicyProvider;
import ai.icen.fw.agent.api.AgentProviderFailureMapper;
import ai.icen.fw.agent.api.AgentRunContext;
import ai.icen.fw.agent.api.AgentToolDescriptor;
import ai.icen.fw.agent.api.AgentToolDescriptorProvider;
import ai.icen.fw.agent.api.AuthorizedToolInvocation;
import ai.icen.fw.agent.api.LanguageModelDescriptor;
import ai.icen.fw.agent.api.LanguageModelProvider;
import ai.icen.fw.agent.api.LanguageModelRequest;
import ai.icen.fw.agent.api.ProviderId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Proves every public Agent provider contract remains directly subclassable from Java 8. */
class AgentProviderTestKitJavaCompatibilityTest {
    @Test
    void allAgentContractsAreSubclassableFromJavaEight() {
        assertNotNull(new AuthorizationContract());
        assertNotNull(new AtomicAuthorizationContract());
        assertNotNull(new ExecutionContextContract());
        assertNotNull(new FailureMapperContract());
        assertNotNull(new PolicyContract());
        assertNotNull(new ModelContract());
        assertNotNull(new EvaluatorContract());
        assertNotNull(new ToolDiscoveryContract());
        assertNotNull(new ToolExecutionContract());
    }

    private static UnsupportedOperationException fixtureOnly() {
        return new UnsupportedOperationException("Compilation fixture only");
    }

    private static final class AuthorizationContract extends AgentAuthorizationProviderContractTest {
        @Override protected AgentAuthorizationProvider getAuthorizationProvider() { throw fixtureOnly(); }
        @Override protected AgentAuthorizationRequest authorizationRequest() { throw fixtureOnly(); }
    }

    private static final class AtomicAuthorizationContract
            extends AgentAtomicDispatchAuthorizationProviderContractTest {
        @Override protected AgentAtomicDispatchAuthorizationProvider getAuthorizationProvider() {
            throw fixtureOnly();
        }
        @Override protected AgentAuthorizationRequest authorizationRequest() { throw fixtureOnly(); }
        @Override protected AgentDispatchAuthorizationFenceRequest dispatchFenceRequest() { throw fixtureOnly(); }
    }

    private static final class ExecutionContextContract extends AgentExecutionContextConsumerContractTest {
        @Override protected AgentExecutionContextConsumer getExecutionContextConsumer() { throw fixtureOnly(); }
        @Override protected AuthorizedToolInvocation authorizedInvocation() { throw fixtureOnly(); }
    }

    private static final class FailureMapperContract extends AgentProviderFailureMapperContractTest {
        @Override protected AgentProviderFailureMapper getFailureMapper() { throw fixtureOnly(); }
        @Override protected ProviderId providerId() { throw fixtureOnly(); }
    }

    private static final class PolicyContract extends AgentPolicyProviderContractTest {
        @Override protected AgentPolicyProvider getPolicyProvider() { throw fixtureOnly(); }
        @Override protected AgentPolicyProposal policyProposal() { throw fixtureOnly(); }
    }

    private static final class ModelContract extends LanguageModelProviderContractTest {
        @Override protected LanguageModelProvider getLanguageModelProvider() { throw fixtureOnly(); }
        @Override protected LanguageModelRequest modelRequest(LanguageModelDescriptor descriptor) {
            throw fixtureOnly();
        }
    }

    private static final class EvaluatorContract extends AgentEvaluatorContractTest {
        @Override protected AgentEvaluator getAgentEvaluator() { throw fixtureOnly(); }
        @Override protected AgentEvaluationRequest evaluationRequest(AgentEvaluatorDescriptor descriptor) {
            throw fixtureOnly();
        }
    }

    private static final class ToolDiscoveryContract extends AgentToolDescriptorProviderContractTest {
        @Override protected AgentToolDescriptorProvider getToolDescriptorProvider() { throw fixtureOnly(); }
        @Override protected AgentRunContext runContext() { throw fixtureOnly(); }
    }

    private static final class ToolExecutionContract extends AgentDescriptorBoundToolExecutorContractTest {
        @Override protected AgentDescriptorBoundToolExecutor getToolExecutor() { throw fixtureOnly(); }
        @Override protected AgentToolDescriptor toolDescriptor() { throw fixtureOnly(); }
        @Override protected AgentExecutableToolInvocation executableInvocation() { throw fixtureOnly(); }
    }
}
