package ai.icen.fw.agent.runtime;

import ai.icen.fw.agent.api.AgentCapabilityId;
import ai.icen.fw.agent.api.AgentContentOrigin;
import ai.icen.fw.agent.api.AgentMessage;
import ai.icen.fw.agent.api.AgentMessageRole;
import ai.icen.fw.agent.api.AgentTextContentBlock;
import ai.icen.fw.agent.api.ProviderId;
import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaAgentRuntimeCompatibilityTest {

    @Test
    void publicRuntimeContractsAreJava8Friendly() {
        AgentRuntimeConfiguration defaults = new AgentRuntimeConfiguration();
        AgentRuntimeConfiguration configured = new AgentRuntimeConfiguration(1_000L, 2);
        AgentRuntimeClock clock = () -> 42L;
        AgentRuntimeIdGenerator ids = purpose -> new Identifier(purpose + "-1");
        AgentRunKey key = new AgentRunKey(new Identifier("tenant-1"), new Identifier("run-1"));
        AgentRunCommandContext commandContext = new AgentRunCommandContext(
            new Identifier("tenant-1"),
            new Identifier("principal-1"),
            "USER",
            new Identifier("command-request-1"),
            41L
        );
        AgentRunIdempotencyScope scope = AgentRunIdempotencyScope.of(
            new Identifier("tenant-1"),
            new Identifier("principal-1"),
            "USER",
            new AgentCapabilityId("agent.answer"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );
        AgentMessage message = new AgentMessage(
            new Identifier("message-1"),
            AgentMessageRole.USER,
            Collections.singletonList(new AgentTextContentBlock(AgentContentOrigin.USER, "hello")),
            41L
        );
        AgentContentSecurityRequest contentRequest = new AgentContentSecurityRequest(
            new Identifier("content-request-1"),
            new Identifier("tenant-1"),
            new Identifier("principal-1"),
            "USER",
            new Identifier("run-1"),
            new AgentCapabilityId("agent.answer"),
            1L,
            AgentContentSecurityBoundary.MODEL_INPUT,
            new ProviderId("model.local"),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "2123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "authorization-v1",
            Collections.singletonList(message),
            Collections.emptyList(),
            Collections.emptyList(),
            42L,
            100L
        );
        AgentContentSecurityDecision contentDecision = AgentContentSecurityDecision.allow(
            new Identifier("content-decision-1"),
            new ProviderId("content-policy.local"),
            contentRequest,
            "policy-v1",
            42L,
            100L
        );

        assertEquals(30_000L, defaults.getLeaseDurationMillis());
        assertEquals(1_000L, configured.getLeaseDurationMillis());
        assertEquals(2, configured.getMaximumProviderAttempts());
        assertEquals(42L, clock.currentTimeMillis());
        assertEquals("purpose-1", ids.nextId("purpose").getValue());
        assertEquals("run-1", key.getRunId().getValue());
        assertEquals("principal-1", commandContext.getPrincipalId().getValue());
        assertEquals(AgentRunCommandAction.REPLAY, AgentRunCommandAction.valueOf("REPLAY"));
        assertNotNull(scope.getScopeDigest());
        contentDecision.requireAllowedFor(contentRequest, 42L);
        assertEquals(AgentContentSecurityOutcome.ALLOW, contentDecision.getOutcome());
    }
}
