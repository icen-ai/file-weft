package ai.icen.fw.agent.interoperability.spi;

import ai.icen.fw.agent.api.ProviderId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaAgentInteroperabilitySpiCompatibilityTest {
    @Test
    void descriptorsAndPortsAreUsableFromJava8() {
        ProviderId peer = new ProviderId("peer.mcp");
        McpResourceDescriptor resource = McpResourceDescriptor.of(
            AgentInteroperabilityContractVersions.V1,
            peer,
            new McpResourceId("resource.policy"),
            "revision-1",
            "descriptor-v1",
            digest('a'),
            digest('b'),
            "application/json",
            null,
            4096
        );
        McpPromptDescriptor prompt = McpPromptDescriptor.of(
            AgentInteroperabilityContractVersions.V1,
            peer,
            new McpPromptId("prompt.policy-summary"),
            "revision-1",
            "descriptor-v1",
            digest('c'),
            "json-schema-2020-12",
            digest('d'),
            digest('e'),
            2048,
            8192
        );
        AgentInteroperabilityDoctorFinding finding = AgentInteroperabilityDoctorFinding.of(
            AgentInteroperabilityDoctorCode.CATALOG_MATCHED,
            AgentInteroperabilityDoctorSeverity.INFO,
            1L,
            digest('f')
        );
        AgentInteroperabilityCapabilityProvider capabilityProvider = new AgentInteroperabilityCapabilityProvider() {
            @Override
            public ProviderId providerId() {
                return peer;
            }

            @Override
            public CompletionStage<AgentInteroperabilityCapabilityResult> capabilities(
                AgentInteroperabilityCapabilityRequest request
            ) {
                return new CompletableFuture<AgentInteroperabilityCapabilityResult>();
            }
        };
        AgentInteroperabilityDoctor doctor = new AgentInteroperabilityDoctor() {
            @Override
            public ProviderId providerId() {
                return peer;
            }

            @Override
            public CompletionStage<AgentInteroperabilityDoctorResult> inspect(
                AgentInteroperabilityDoctorRequest request
            ) {
                return new CompletableFuture<AgentInteroperabilityDoctorResult>();
            }
        };

        assertEquals("resource.policy", resource.getResourceId().getValue());
        assertEquals(AgentInteroperabilityCapabilities.MCP_RESOURCES_READ, resource.getRequiredCapability());
        assertEquals("prompt.policy-summary", prompt.getPromptId().getValue());
        assertEquals(AgentInteroperabilityCapabilities.MCP_PROMPTS_GET, prompt.getRequiredCapability());
        assertEquals(AgentInteroperabilityDoctorCode.CATALOG_MATCHED, finding.getCode());
        assertEquals(peer, capabilityProvider.providerId());
        assertEquals(peer, doctor.providerId());
        assertNotNull(capabilityProvider.capabilities(null));
        assertNotNull(doctor.inspect(null));
        assertFalse(resource.toString().contains("https://"));
        assertFalse(prompt.toString().contains("prompt text"));
    }

    private static String digest(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
