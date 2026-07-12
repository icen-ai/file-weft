package ai.icen.fw.runtime.plugin

import ai.icen.fw.spi.ai.AgentTaskTrigger
import ai.icen.fw.spi.ai.FileWeftAgent
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
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

    @Test
    fun `snapshots every plugin contribution once and reuses the same instances`() {
        val plugin = FreshContributionPlugin()
        val registry = FileWeftPluginRegistry(listOf(plugin), javaClass.classLoader)

        FreshContributionPlugin.CONTRIBUTION_NAMES.forEach { contribution ->
            assertEquals(1, plugin.callCount(contribution), "$contribution must be read exactly once during construction.")
        }
        assertEquals(1, plugin.callCount(FreshContributionPlugin.ID_GETTER))

        val storage = registry.storageAdapters().single()
        val connector = registry.connectors().getValue(FreshContributionPlugin.CONNECTOR_ID)
        val doctor = registry.doctorCheckers().single()
        val agent = registry.agents().single()
        val trigger = registry.agentTaskTriggers().single()
        val outbox = registry.outboxEventHandlers().single()
        val task = registry.taskHandlers().single()
        val route = registry.reviewRouteProviders().single()
        val inventory = registry.inventory().single { descriptor -> descriptor.id == FreshContributionPlugin.ID }

        assertEquals(
            FreshContributionPlugin.CONTRIBUTION_NAMES.size,
            inventory.capabilities.size,
        )
        assertTrue(inventory.capabilities.all { capability -> capability.count == 1 })

        repeat(3) {
            assertSame(storage, registry.storageAdapters().single())
            assertSame(connector, registry.connectors().getValue(FreshContributionPlugin.CONNECTOR_ID))
            assertSame(connector, registry.mergeConnectors(emptyMap()).getValue(FreshContributionPlugin.CONNECTOR_ID))
            assertSame(doctor, registry.doctorCheckers().single())
            assertSame(agent, registry.agents().single())
            assertSame(trigger, registry.agentTaskTriggers().single())
            assertSame(outbox, registry.outboxEventHandlers().single())
            assertSame(task, registry.taskHandlers().single())
            assertSame(route, registry.reviewRouteProviders().single())
            assertSame(inventory, registry.inventory().single { descriptor -> descriptor.id == FreshContributionPlugin.ID })
        }

        FreshContributionPlugin.CONTRIBUTION_NAMES.forEach { contribution ->
            assertEquals(1, plugin.callCount(contribution), "$contribution must not be re-read by registry getters.")
        }
        assertEquals(1, plugin.callCount(FreshContributionPlugin.ID_GETTER))
        assertFailsWith<UnsupportedOperationException> {
            (registry.inventory() as MutableList<ai.icen.fw.application.plugin.PluginInventoryDescriptor>).clear()
        }
    }

    @Test
    fun `identifies the plugin origin when eager contribution snapshotting fails`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            FileWeftPluginRegistry(listOf(object : FileWeftPlugin {
                override fun id(): String = "broken-plugin"

                override fun connectors(): Map<String, FileConnector> = error("broken contribution")
            }), javaClass.classLoader)
        }

        assertEquals("Spring FileWeftPlugin broken-plugin contribution snapshot failed.", failure.message)
        assertEquals("broken contribution", failure.cause?.message)
    }

    @Test
    fun `copies mutable contribution collections before exposing an immutable snapshot`() {
        val stableConnector = NoOpConnector
        val lateConnector = freshProxy(FileConnector::class.java)
        val connectors = linkedMapOf<String, FileConnector>("stable" to stableConnector)
        val stableRoute = freshProxy(DocumentReviewRouteProvider::class.java)
        val routes = mutableListOf(stableRoute)
        val registry = FileWeftPluginRegistry(listOf(object : FileWeftPlugin {
            override fun id(): String = "mutable-plugin"

            override fun connectors(): Map<String, FileConnector> = connectors

            override fun reviewRouteProviders(): List<DocumentReviewRouteProvider> = routes
        }), javaClass.classLoader)

        connectors.clear()
        connectors["late"] = lateConnector
        routes.clear()

        assertEquals(setOf("stable"), registry.connectors().keys)
        assertSame(stableConnector, registry.connectors().getValue("stable"))
        assertEquals(listOf(stableRoute), registry.reviewRouteProviders())
        assertFailsWith<UnsupportedOperationException> {
            (registry.connectors() as MutableMap<String, FileConnector>)["late"] = lateConnector
        }
        assertFailsWith<UnsupportedOperationException> {
            (registry.reviewRouteProviders() as MutableList<DocumentReviewRouteProvider>).clear()
        }
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

    private class FreshContributionPlugin : FileWeftPlugin {
        private val calls = linkedMapOf<String, Int>()

        override fun id(): String = counted(ID_GETTER) { ID }

        override fun storageAdapters(): List<StorageAdapter> =
            counted(STORAGE_ADAPTERS) { listOf(freshProxy(StorageAdapter::class.java)) }

        override fun connectors(): Map<String, FileConnector> =
            counted(CONNECTORS) { mapOf(CONNECTOR_ID to freshProxy(FileConnector::class.java)) }

        override fun doctorCheckers(): List<DoctorChecker> =
            counted(DOCTOR_CHECKERS) { listOf(freshProxy(DoctorChecker::class.java)) }

        override fun agents(): List<FileWeftAgent> =
            counted(AGENTS) { listOf(freshProxy(FileWeftAgent::class.java)) }

        override fun agentTaskTriggers(): List<AgentTaskTrigger> =
            counted(AGENT_TASK_TRIGGERS) { listOf(freshProxy(AgentTaskTrigger::class.java)) }

        override fun outboxEventHandlers(): List<OutboxEventHandler> =
            counted(OUTBOX_EVENT_HANDLERS) { listOf(freshProxy(OutboxEventHandler::class.java)) }

        override fun taskHandlers(): List<FileWeftTaskHandler> =
            counted(TASK_HANDLERS) { listOf(freshProxy(FileWeftTaskHandler::class.java)) }

        override fun reviewRouteProviders(): List<DocumentReviewRouteProvider> =
            counted(REVIEW_ROUTE_PROVIDERS) { listOf(freshProxy(DocumentReviewRouteProvider::class.java)) }

        fun callCount(name: String): Int = calls[name] ?: 0

        private fun <T> counted(name: String, contribution: () -> T): T {
            calls[name] = callCount(name) + 1
            return contribution()
        }

        companion object {
            const val ID = "fresh-contribution-plugin"
            const val CONNECTOR_ID = "fresh-connector"
            const val ID_GETTER = "id"
            const val STORAGE_ADAPTERS = "storageAdapters"
            const val CONNECTORS = "connectors"
            const val DOCTOR_CHECKERS = "doctorCheckers"
            const val AGENTS = "agents"
            const val AGENT_TASK_TRIGGERS = "agentTaskTriggers"
            const val OUTBOX_EVENT_HANDLERS = "outboxEventHandlers"
            const val TASK_HANDLERS = "taskHandlers"
            const val REVIEW_ROUTE_PROVIDERS = "reviewRouteProviders"
            val CONTRIBUTION_NAMES = listOf(
                STORAGE_ADAPTERS,
                CONNECTORS,
                DOCTOR_CHECKERS,
                AGENTS,
                AGENT_TASK_TRIGGERS,
                OUTBOX_EVENT_HANDLERS,
                TASK_HANDLERS,
                REVIEW_ROUTE_PROVIDERS,
            )
        }
    }

    companion object {
        private fun <T : Any> freshProxy(type: Class<T>): T {
            val instance = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
                when (method.name) {
                    "equals" -> proxy === arguments?.singleOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Fresh${type.simpleName}@${System.identityHashCode(proxy)}"
                    else -> throw UnsupportedOperationException("Test contribution ${type.name} must not be invoked.")
                }
            }
            return type.cast(instance)
        }
    }
}
