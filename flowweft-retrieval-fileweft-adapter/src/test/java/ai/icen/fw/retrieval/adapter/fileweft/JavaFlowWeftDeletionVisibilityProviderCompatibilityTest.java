package ai.icen.fw.retrieval.adapter.fileweft;

import ai.icen.fw.application.retention.DeletionVisibilityFence;
import ai.icen.fw.application.retention.DeletionVisibilityQuery;
import ai.icen.fw.core.id.Identifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaFlowWeftDeletionVisibilityProviderCompatibilityTest {
    @Test
    void exposesJava8FriendlyHostBridge() {
        DeletionVisibilityQuery query = new DeletionVisibilityQuery() {
            @Override
            public DeletionVisibilityFence findFence(
                    Identifier tenantId,
                    String resourceType,
                    Identifier resourceId) {
                return null;
            }

            @Override
            public Map<Identifier, DeletionVisibilityFence> findFences(
                    Identifier tenantId,
                    String resourceType,
                    java.util.Collection<Identifier> resourceIds) {
                return java.util.Collections.emptyMap();
            }
        };

        FlowWeftDeletionVisibilityProvider provider =
                new FlowWeftDeletionVisibilityProvider(query, repeat('a', 64));

        assertNotNull(provider.descriptor());
        assertEquals(
                FlowWeftDeletionVisibilityProvider.PROVIDER_TYPE_ID,
                provider.descriptor().getProviderTypeId());
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
