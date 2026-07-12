package ai.icen.fw.runtime.plugin

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask
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

    @Test
    fun `exposes review route providers contributed by a plugin`() {
        val provider = object : DocumentReviewRouteProvider {
            override fun id(): String = "dual"
            override fun resolve(request: DocumentReviewRouteRequest) = DocumentReviewRoute("DUAL", listOf(DocumentReviewRouteTask()))
        }
        val registry = FileWeftPluginRegistry(listOf(object : FileWeftPlugin {
            override fun id(): String = "workflow-plugin"
            override fun reviewRouteProviders(): List<DocumentReviewRouteProvider> = listOf(provider)
        }), javaClass.classLoader)

        assertEquals(listOf(provider), registry.reviewRouteProviders())
    }

    private fun plugin(id: String, connectors: Map<String, FileConnector> = emptyMap()): FileWeftPlugin = object : FileWeftPlugin {
        override fun id(): String = id
        override fun connectors(): Map<String, FileConnector> = connectors
    }

    private object NoOpConnector : FileConnector {
        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult = ConnectorSyncResult(ai.icen.fw.spi.connector.ConnectorSyncStatus.SUCCESS)
        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult = ConnectorSyncResult(ai.icen.fw.spi.connector.ConnectorSyncStatus.SUCCESS)
        override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.HEALTHY)
    }
}
