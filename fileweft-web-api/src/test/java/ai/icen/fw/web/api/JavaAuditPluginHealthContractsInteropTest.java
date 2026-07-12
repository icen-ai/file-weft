package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.audit.DocumentAuditLogDto;
import ai.icen.fw.web.api.v1.audit.DocumentAuditLogPageQuery;
import ai.icen.fw.web.api.v1.health.HealthDto;
import ai.icen.fw.web.api.v1.plugin.PluginCapabilityDto;
import ai.icen.fw.web.api.v1.plugin.PluginDto;
import ai.icen.fw.web.api.v1.plugin.PluginPageQuery;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaAuditPluginHealthContractsInteropTest {
    @Test
    void constructsThePublicContractsWithoutKotlinOnlyCallSites() {
        DocumentAuditLogDto audit = new DocumentAuditLogDto(
            "audit-java",
            "document:create",
            10L,
            "external-user-java",
            "Java reviewer",
            "trace-java"
        );
        PluginDto plugin = new PluginDto(
            "java-plugin",
            Collections.singletonList(new PluginCapabilityDto("CONNECTOR", 1))
        );
        HealthDto health = new HealthDto("UP");

        assertEquals("external-user-java", audit.getOperatorId());
        assertEquals("java-plugin", plugin.getId());
        assertEquals("UP", health.getStatus());
        assertEquals(20, new DocumentAuditLogPageQuery().getLimit());
        assertNull(new DocumentAuditLogPageQuery().getCursor());
        assertEquals(20, new PluginPageQuery().getLimit());
        assertNull(new PluginPageQuery().getCursor());
    }
}
