package ai.icen.fw.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.icen.fw.agent.api.AgentContentOrigin;
import ai.icen.fw.agent.api.AgentTextContentBlock;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class AgentContentBlockPersistenceJavaCompatibilityTest {
    @Test
    void exposesJavaFriendlyImmutableEnvelopeAndRegistry() {
        AgentContentBlockPersistenceRegistry registry =
            new AgentContentBlockPersistenceRegistry(Collections.<AgentContentBlockPersistenceCodec>emptyList());
        AgentEncodedContentBlock encoded = registry.encode(
            new AgentTextContentBlock(AgentContentOrigin.USER, "java-consumer")
        );

        assertEquals("text", encoded.getKind());
        assertEquals(1, encoded.getCodecVersion());
        assertEquals(64, encoded.getPayloadDigest().length());
        AgentEncodedContentBlock restored = AgentEncodedContentBlock.restore(
            encoded.getKind(),
            encoded.getOrigin(),
            encoded.getCodecVersion(),
            encoded.getBindingDigest(),
            encoded.getPayload(),
            encoded.getCanonicalPayloadSizeBytes(),
            encoded.getPayloadDigest()
        );
        assertEquals("java-consumer", ((AgentTextContentBlock) registry.decode(restored)).getText());
    }
}
