package ai.icen.fw.runtime.plugin

import ai.icen.fw.application.plugin.PluginCapabilityDescriptor
import ai.icen.fw.application.plugin.PluginCapabilityType
import ai.icen.fw.application.plugin.PluginInventoryDescriptor
import ai.icen.fw.application.plugin.PluginInventoryProvider
import ai.icen.fw.spi.ai.AgentTaskTrigger
import ai.icen.fw.spi.ai.FileWeftAgent
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import java.util.Collections
import java.util.LinkedHashMap
import java.util.ServiceLoader

/**
 * Deterministic plugin registry. Spring contributions take precedence over a
 * ServiceLoader copy of the same implementation, while conflicting plugin ids
 * and connector ids fail fast with a startup diagnostic. Every accepted
 * plugin is queried exactly once during construction; all consumers then read
 * immutable contribution snapshots containing the original instances.
 */
class FileWeftPluginRegistry @JvmOverloads constructor(
    springPlugins: List<FileWeftPlugin> = emptyList(),
    classLoader: ClassLoader = contextClassLoader(),
) : PluginInventoryProvider {
    private val snapshotsById: Map<String, PluginSnapshot> = collectPlugins(springPlugins, classLoader)
    private val inventorySnapshot: List<PluginInventoryDescriptor> = immutableList(
        snapshotsById.values.map(::inventoryDescriptor),
    )
    private val pluginList: List<FileWeftPlugin> = immutableList(snapshotsById.values.map { it.plugin })
    private val storageAdapterList: List<StorageAdapter> = contributions { it.storageAdapters }
    private val connectorSnapshot: ConnectorSnapshot = snapshotConnectors(snapshotsById.values)
    private val doctorCheckerList: List<DoctorChecker> = contributions { it.doctorCheckers }
    private val agentList: List<FileWeftAgent> = contributions { it.agents }
    private val agentTaskTriggerList: List<AgentTaskTrigger> = contributions { it.agentTaskTriggers }
    private val outboxEventHandlerList: List<OutboxEventHandler> = contributions { it.outboxEventHandlers }
    private val taskHandlerList: List<FileWeftTaskHandler> = contributions { it.taskHandlers }
    private val reviewRouteProviderList: List<DocumentReviewRouteProvider> = contributions { it.reviewRouteProviders }

    fun plugins(): List<FileWeftPlugin> = pluginList

    override fun inventory(): List<PluginInventoryDescriptor> = inventorySnapshot

    fun storageAdapters(): List<StorageAdapter> = storageAdapterList

    fun connectors(): Map<String, FileConnector> = connectorSnapshot.connectors

    fun mergeConnectors(springConnectors: Map<String, FileConnector>): Map<String, FileConnector> {
        val merged = LinkedHashMap<String, FileConnector>()
        springConnectors.forEach { (id, connector) ->
            require(id.isNotBlank()) { "Spring FileConnector bean name must not be blank." }
            merged[id] = connector
        }
        connectorSnapshot.connectors.forEach { (connectorId, connector) ->
            val pluginId = requireNotNull(connectorSnapshot.pluginIds[connectorId])
            require(merged.putIfAbsent(connectorId, connector) == null) {
                "Plugin $pluginId connector id $connectorId conflicts with a Spring or earlier plugin connector."
            }
        }
        return Collections.unmodifiableMap(merged)
    }

    fun doctorCheckers(): List<DoctorChecker> = doctorCheckerList

    fun agents(): List<FileWeftAgent> = agentList

    fun agentTaskTriggers(): List<AgentTaskTrigger> = agentTaskTriggerList

    fun outboxEventHandlers(): List<OutboxEventHandler> = outboxEventHandlerList

    fun taskHandlers(): List<FileWeftTaskHandler> = taskHandlerList

    fun reviewRouteProviders(): List<DocumentReviewRouteProvider> = reviewRouteProviderList

    private fun <T> contributions(extract: (PluginSnapshot) -> List<T>): List<T> {
        val merged = ArrayList<T>()
        snapshotsById.values.forEach { snapshot -> merged.addAll(extract(snapshot)) }
        return Collections.unmodifiableList(merged)
    }

    private companion object {
        fun collectPlugins(springPlugins: List<FileWeftPlugin>, classLoader: ClassLoader): Map<String, PluginSnapshot> {
            val discovered = LinkedHashMap<String, DiscoveredPlugin>()
            springPlugins.forEach { plugin -> register(discovered, discover(plugin, PluginOrigin.SPRING)) }
            ServiceLoader.load(FileWeftPlugin::class.java, classLoader).forEach { plugin ->
                val candidate = discover(plugin, PluginOrigin.SERVICE_LOADER)
                val existing = discovered[candidate.id]
                if (existing == null) {
                    register(discovered, candidate)
                } else {
                    require(existing.plugin.javaClass == candidate.plugin.javaClass) {
                        "ServiceLoader plugin id ${candidate.id} conflicts with ${existing.plugin.javaClass.name}."
                    }
                }
            }
            val snapshots = LinkedHashMap<String, PluginSnapshot>()
            discovered.forEach { (id, plugin) -> snapshots[id] = snapshot(plugin) }
            return Collections.unmodifiableMap(snapshots)
        }

        fun discover(plugin: FileWeftPlugin, origin: PluginOrigin): DiscoveredPlugin {
            val id = plugin.id()
            require(id.isNotBlank()) { "${origin.label} FileWeftPlugin id must not be blank." }
            return DiscoveredPlugin(id, plugin, origin)
        }

        fun register(target: MutableMap<String, DiscoveredPlugin>, discovered: DiscoveredPlugin) {
            require(target.putIfAbsent(discovered.id, discovered) == null) {
                "${discovered.origin.label} FileWeftPlugin id ${discovered.id} is registered more than once."
            }
        }

        fun snapshot(discovered: DiscoveredPlugin): PluginSnapshot = try {
            val plugin = discovered.plugin
            PluginSnapshot(
                id = discovered.id,
                plugin = plugin,
                origin = discovered.origin,
                storageAdapters = immutableList(plugin.storageAdapters()),
                connectors = immutableMap(plugin.connectors()),
                doctorCheckers = immutableList(plugin.doctorCheckers()),
                agents = immutableList(plugin.agents()),
                agentTaskTriggers = immutableList(plugin.agentTaskTriggers()),
                outboxEventHandlers = immutableList(plugin.outboxEventHandlers()),
                taskHandlers = immutableList(plugin.taskHandlers()),
                reviewRouteProviders = immutableList(plugin.reviewRouteProviders()),
            )
        } catch (failure: Exception) {
            throw IllegalArgumentException(
                "${discovered.origin.label} FileWeftPlugin ${discovered.id} contribution snapshot failed.",
                failure,
            )
        }

        fun snapshotConnectors(snapshots: Collection<PluginSnapshot>): ConnectorSnapshot {
            val connectors = LinkedHashMap<String, FileConnector>()
            val pluginIds = LinkedHashMap<String, String>()
            snapshots.forEach { snapshot ->
                snapshot.connectors.forEach { (connectorId, connector) ->
                    require(connectorId.isNotBlank()) { "Plugin ${snapshot.id} contributes a blank connector id." }
                    require(connectors.putIfAbsent(connectorId, connector) == null) {
                        "Plugin ${snapshot.id} connector id $connectorId conflicts with a Spring or earlier plugin connector."
                    }
                    pluginIds[connectorId] = snapshot.id
                }
            }
            return ConnectorSnapshot(immutableMap(connectors), immutableMap(pluginIds))
        }

        fun inventoryDescriptor(snapshot: PluginSnapshot): PluginInventoryDescriptor {
            val capabilities = ArrayList<PluginCapabilityDescriptor>()
            capabilities.addIfPresent(PluginCapabilityType.STORAGE_ADAPTER, snapshot.storageAdapters.size)
            capabilities.addIfPresent(PluginCapabilityType.CONNECTOR, snapshot.connectors.size)
            capabilities.addIfPresent(PluginCapabilityType.DOCTOR_CHECKER, snapshot.doctorCheckers.size)
            capabilities.addIfPresent(PluginCapabilityType.AGENT, snapshot.agents.size)
            capabilities.addIfPresent(PluginCapabilityType.AGENT_TASK_TRIGGER, snapshot.agentTaskTriggers.size)
            capabilities.addIfPresent(PluginCapabilityType.OUTBOX_EVENT_HANDLER, snapshot.outboxEventHandlers.size)
            capabilities.addIfPresent(PluginCapabilityType.TASK_HANDLER, snapshot.taskHandlers.size)
            capabilities.addIfPresent(PluginCapabilityType.REVIEW_ROUTE_PROVIDER, snapshot.reviewRouteProviders.size)
            return PluginInventoryDescriptor(
                id = snapshot.id,
                capabilities = capabilities,
            )
        }

        fun MutableList<PluginCapabilityDescriptor>.addIfPresent(type: PluginCapabilityType, count: Int) {
            if (count > 0) add(PluginCapabilityDescriptor(type, count))
        }

        fun contextClassLoader(): ClassLoader = Thread.currentThread().contextClassLoader
            ?: FileWeftPluginRegistry::class.java.classLoader

        fun <T> immutableList(source: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(source))

        fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(source))
    }

    private class DiscoveredPlugin(
        val id: String,
        val plugin: FileWeftPlugin,
        val origin: PluginOrigin,
    )

    private class PluginSnapshot(
        val id: String,
        val plugin: FileWeftPlugin,
        /** Retained for later inventory and Doctor diagnostics without rediscovering the plugin. */
        val origin: PluginOrigin,
        val storageAdapters: List<StorageAdapter>,
        val connectors: Map<String, FileConnector>,
        val doctorCheckers: List<DoctorChecker>,
        val agents: List<FileWeftAgent>,
        val agentTaskTriggers: List<AgentTaskTrigger>,
        val outboxEventHandlers: List<OutboxEventHandler>,
        val taskHandlers: List<FileWeftTaskHandler>,
        val reviewRouteProviders: List<DocumentReviewRouteProvider>,
    )

    private class ConnectorSnapshot(
        val connectors: Map<String, FileConnector>,
        val pluginIds: Map<String, String>,
    )

    private enum class PluginOrigin(val label: String) {
        SPRING("Spring"),
        SERVICE_LOADER("ServiceLoader"),
    }
}
