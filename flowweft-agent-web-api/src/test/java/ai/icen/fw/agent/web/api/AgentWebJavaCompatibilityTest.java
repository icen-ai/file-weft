package ai.icen.fw.agent.web.api;

import ai.icen.fw.agent.api.AgentBudget;
import ai.icen.fw.agent.api.AgentCapabilityId;
import ai.icen.fw.agent.api.AgentRunContext;
import ai.icen.fw.agent.api.ModelId;
import ai.icen.fw.agent.api.ProviderId;
import ai.icen.fw.core.id.Identifier;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AgentWebJavaCompatibilityTest {
    @Test
    void javaCanUseFrameworkNeutralAgentWebContracts() {
        AgentRunContext runContext = new AgentRunContext(
            id("tenant-java"),
            id("principal-java"),
            "USER",
            id("request-java"),
            100L
        );
        AgentWebTrustedContext context = AgentWebTrustedContext.authenticated(
            runContext,
            id("authentication-java"),
            "auth-r1",
            1_000L,
            repeat('a', 64)
        );
        context.requireFresh(200L);
        assertEquals(64, context.getTrustedContextDigest().length());
        AgentWebTrustedContextProvider provider = () -> context;
        assertEquals("tenant-java", provider.currentContext().getTenantId().getValue());

        AgentWebWritePreconditions preconditions = AgentWebWritePreconditions.parse(
            "idem-java",
            "\"fw-agent-0\""
        );
        assertEquals(0L, preconditions.getVersionTag().getExpectedVersion());

        AgentWebUserMessageCommand message = new AgentWebUserMessageCommand(
            id("message-java"),
            "Authorized Java message"
        );
        AgentWebConversationCreateCommand command = new AgentWebConversationCreateCommand(
            new AgentCapabilityId("agent.answer"),
            new AgentBudget(10_000L, 2_000L, 10, 5, 60_000L, 0L)
        );
        assertNotNull(command.getDefaultBudget());
        assertFalse(command.toString().contains("Authorized Java message"));
        AgentWebRunCreateCommand runCommand = new AgentWebRunCreateCommand(
            new AgentCapabilityId("agent.answer"),
            message,
            command.getDefaultBudget(),
            50_000L
        );
        assertFalse(runCommand.toString().contains("Authorized Java message"));

        ArrayList<String> source = new ArrayList<>();
        source.add("one");
        AgentWebPage<String> page = new AgentWebPage<>(source, AgentWebCursor.of("cursor-java"));
        source.add("two");
        assertEquals(1, page.getItems().size());
        assertEquals(
            AgentWebErrorCode.OK,
            AgentWebApplicationResult.success(page).getCode()
        );

        AgentWebDurableCursor durableCursor = new AgentWebDurableCursor(
            id("run-java"),
            2L,
            AgentWebCursor.of("event-cursor-java"),
            100L,
            1_000L
        );
        AgentWebDurablePage<String> durablePage = new AgentWebDurablePage<>(
            id("run-java"),
            Collections.singletonList("frame"),
            durableCursor
        );
        assertEquals(2L, durablePage.getNextCursor().getNextSequence());

        AgentWebProviderConfigurationCommand configuration =
            new AgentWebProviderConfigurationCommand(
                new ProviderId("provider.java"),
                id("connection-profile-java"),
                id("credential-reference-java"),
                new ModelId("model.java"),
                Collections.singleton(new AgentCapabilityId("agent.answer")),
                true
            );
        assertEquals("credential-reference-java", configuration.getCredentialReference().getValue());
        assertFalse(configuration.toString().contains("credential-reference-java"));

        assertEquals(25, AgentWebRoute.all().size());
        assertEquals("flowweft.agent.web.v1", AgentWebRoute.CONTRACT_VERSION);
        assertEquals(500, AgentWebHttpStatusPolicy.statusFor(new AgentWebErrorCode("FUTURE_PLUGIN_CODE")));
    }

    private static Identifier id(String value) {
        return new Identifier(value);
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }
}
