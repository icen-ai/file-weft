package ai.icen.fw.application.plugin;

import ai.icen.fw.core.context.TenantContext;
import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.spi.authorization.AuthorizationDecision;
import ai.icen.fw.spi.authorization.AuthorizationProvider;
import ai.icen.fw.spi.identity.UserIdentity;
import ai.icen.fw.spi.identity.UserRealmProvider;
import ai.icen.fw.spi.tenant.TenantProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaPluginInventoryInteropTest {
    @Test
    void queriesTheSafeInventoryThroughJavaFriendlyContracts() {
        PluginInventoryProvider provider = new PluginInventoryProvider() {
            @Override
            public List<PluginInventoryDescriptor> inventory() {
                return Collections.singletonList(
                    new PluginInventoryDescriptor(
                        "java-plugin",
                        Collections.singletonList(
                            new PluginCapabilityDescriptor(PluginCapabilityType.CONNECTOR, 1)
                        )
                    )
                );
            }
        };
        PluginInventoryQueryService service = new PluginInventoryQueryService(
            new TenantProvider() {
                @Override
                public TenantContext currentTenant() {
                    return new TenantContext(new Identifier("tenant-java"));
                }
            },
            new UserRealmProvider() {
                @Override
                public UserIdentity currentUser() {
                    return new UserIdentity(new Identifier("operator-java"), "Java operator", Collections.emptyMap());
                }

                @Override
                public UserIdentity findUser(Identifier userId) {
                    return null;
                }
            },
            request -> new AuthorizationDecision(true, null),
            provider
        );

        PluginInventoryPageResult result = service.page(new PluginInventoryPageRequest());

        assertEquals("java-plugin", result.getItems().get(0).getId());
        assertEquals(PluginCapabilityType.CONNECTOR, result.getItems().get(0).getCapabilities().get(0).getType());
    }
}
