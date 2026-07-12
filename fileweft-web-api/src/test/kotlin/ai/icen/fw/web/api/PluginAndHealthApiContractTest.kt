package ai.icen.fw.web.api

import ai.icen.fw.web.api.v1.health.HealthDto
import ai.icen.fw.web.api.v1.plugin.PluginCapabilityDto
import ai.icen.fw.web.api.v1.plugin.PluginDto
import ai.icen.fw.web.api.v1.plugin.PluginPageQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginAndHealthApiContractTest {
    @Test
    fun `plugin inventory is immutable bounded and contains only allow-listed fields`() {
        val source = mutableListOf(PluginCapabilityDto("CONNECTOR", 2))
        val plugin = PluginDto("search-plugin", source)
        source.clear()

        assertEquals(1, plugin.capabilities.size)
        assertThrows<UnsupportedOperationException> {
            (plugin.capabilities as MutableList<PluginCapabilityDto>).clear()
        }
        assertThrows<IllegalArgumentException> { PluginPageQuery(limit = 0) }

        val fields = PluginDto::class.java.declaredFields.map { field -> field.name }.toSet()
        assertEquals(setOf("id", "capabilities"), fields)
        val forbidden = setOf(
            "tenantId", "source", "className", "beanName", "connectorId", "configuration",
            "path", "exception", "plugin", "instance",
        )
        assertTrue(fields.intersect(forbidden).isEmpty())
    }

    @Test
    fun `plugin ids reject surrounding unicode spaces controls and format characters`() {
        val unsafeIds = listOf(
            " plugin",
            "plugin ",
            "\u00A0plugin",
            "plugin\u2007",
            "\u202Fplugin",
            "unsafe\nplugin",
            "unsafe\u200Bplugin",
            "unsafe\u202Eplugin",
        )

        unsafeIds.forEach { id ->
            assertThrows<IllegalArgumentException> { PluginDto(id, emptyList()) }
        }
    }

    @Test
    fun `health contract is intentionally limited to liveness status`() {
        val health = HealthDto("UP")

        assertEquals("UP", health.status)
        assertEquals(setOf("status"), HealthDto::class.java.declaredFields.map { field -> field.name }.toSet())
        assertThrows<IllegalArgumentException> { HealthDto("UP\nDOWN") }
    }
}
