package ai.icen.fw.adapter.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavaFixedTenantProviderInteropTest {
    @Test
    void constructsTheExplicitSingleTenantAdapterFromJava() {
        FixedTenantProvider provider = new FixedTenantProvider("java-tenant");

        assertEquals("java-tenant", provider.currentTenant().getTenantId().getValue());
    }
}
