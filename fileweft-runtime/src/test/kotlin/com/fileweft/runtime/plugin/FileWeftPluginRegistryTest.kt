package com.fileweft.runtime.plugin

import com.fileweft.core.id.Identifier
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.plugin.FileWeftPlugin
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileWeftPluginRegistryTest {
    @Test
    fun `discovers Java ServiceLoader plugins from the supplied class loader`() {
        val registry = FileWeftPluginRegistry(emptyList(), javaClass.classLoader)

        assertTrue(registry.plugins().any { it.id() == RuntimeServiceLoadedPlugin.ID })
    }

    @Test
    fun `merges named connector contributions and rejects collisions with customer beans`() {
        val connector = NoOpConnector
        val registry = FileWeftPluginRegistry(listOf(plugin("plugin-a", mapOf("search" to connector))), javaClass.classLoader)

        assertEquals(connector, registry.mergeConnectors(emptyMap())["search"])
        assertFailsWith<IllegalArgumentException> {
            registry.mergeConnectors(mapOf("search" to NoOpConnector))
        }
    }

    @Test
    fun `rejects two distinct plugins with the same stable id`() {
        assertFailsWith<IllegalArgumentException> {
            FileWeftPluginRegistry(listOf(plugin("duplicate"), plugin("duplicate")), javaClass.classLoader)
        }
    }

    private fun plugin(id: String, connectors: Map<String, FileConnector> = emptyMap()): FileWeftPlugin = object : FileWeftPlugin {
        override fun id(): String = id
        override fun connectors(): Map<String, FileConnector> = connectors
    }

    private object NoOpConnector : FileConnector {
        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult = ConnectorSyncResult(com.fileweft.spi.connector.ConnectorSyncStatus.SUCCESS)
        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult = ConnectorSyncResult(com.fileweft.spi.connector.ConnectorSyncStatus.SUCCESS)
        override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }
}
