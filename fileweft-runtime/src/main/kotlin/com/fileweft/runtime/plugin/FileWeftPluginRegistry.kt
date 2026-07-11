package com.fileweft.runtime.plugin

import com.fileweft.spi.ai.AgentTaskTrigger
import com.fileweft.spi.ai.FileWeftAgent
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.doctor.DoctorChecker
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.plugin.FileWeftPlugin
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.task.FileWeftTaskHandler
import com.fileweft.spi.workflow.DocumentReviewRouteProvider
import java.util.Collections
import java.util.LinkedHashMap
import java.util.ServiceLoader

/**
 * Deterministic plugin registry. Spring contributions take precedence over a
 * ServiceLoader copy of the same implementation, while conflicting plugin ids
 * and connector ids fail fast with a startup diagnostic.
 */
class FileWeftPluginRegistry @JvmOverloads constructor(
    springPlugins: List<FileWeftPlugin> = emptyList(),
    classLoader: ClassLoader = contextClassLoader(),
) {
    private val pluginsById: Map<String, FileWeftPlugin> = collectPlugins(springPlugins, classLoader)

    fun plugins(): List<FileWeftPlugin> = Collections.unmodifiableList(ArrayList(pluginsById.values))

    fun storageAdapters(): List<StorageAdapter> = contributions { it.storageAdapters() }

    fun connectors(): Map<String, FileConnector> = mergeConnectors(emptyMap())

    fun mergeConnectors(springConnectors: Map<String, FileConnector>): Map<String, FileConnector> {
        val merged = LinkedHashMap<String, FileConnector>()
        springConnectors.forEach { (id, connector) ->
            require(id.isNotBlank()) { "Spring FileConnector bean name must not be blank." }
            merged[id] = connector
        }
        plugins().forEach { plugin ->
            plugin.connectors().forEach { (connectorId, connector) ->
                require(connectorId.isNotBlank()) { "Plugin ${plugin.id()} contributes a blank connector id." }
                require(merged.putIfAbsent(connectorId, connector) == null) {
                    "Plugin ${plugin.id()} connector id $connectorId conflicts with a Spring or earlier plugin connector."
                }
            }
        }
        return Collections.unmodifiableMap(merged)
    }

    fun doctorCheckers(): List<DoctorChecker> = contributions { it.doctorCheckers() }

    fun agents(): List<FileWeftAgent> = contributions { it.agents() }

    fun agentTaskTriggers(): List<AgentTaskTrigger> = contributions { it.agentTaskTriggers() }

    fun outboxEventHandlers(): List<OutboxEventHandler> = contributions { it.outboxEventHandlers() }

    fun taskHandlers(): List<FileWeftTaskHandler> = contributions { it.taskHandlers() }

    fun reviewRouteProviders(): List<DocumentReviewRouteProvider> = contributions { it.reviewRouteProviders() }

    private fun <T> contributions(extract: (FileWeftPlugin) -> List<T>): List<T> =
        Collections.unmodifiableList(plugins().flatMap(extract))

    private companion object {
        fun collectPlugins(springPlugins: List<FileWeftPlugin>, classLoader: ClassLoader): Map<String, FileWeftPlugin> {
            val discovered = LinkedHashMap<String, FileWeftPlugin>()
            springPlugins.forEach { register(discovered, it, "Spring") }
            ServiceLoader.load(FileWeftPlugin::class.java, classLoader).forEach { plugin ->
                val existing = discovered[plugin.id()]
                if (existing == null) {
                    register(discovered, plugin, "ServiceLoader")
                } else {
                    require(existing.javaClass == plugin.javaClass) {
                        "ServiceLoader plugin id ${plugin.id()} conflicts with ${existing.javaClass.name}."
                    }
                }
            }
            return Collections.unmodifiableMap(discovered)
        }

        fun register(target: MutableMap<String, FileWeftPlugin>, plugin: FileWeftPlugin, source: String) {
            val id = plugin.id()
            require(id.isNotBlank()) { "$source FileWeftPlugin id must not be blank." }
            require(target.putIfAbsent(id, plugin) == null) { "$source FileWeftPlugin id $id is registered more than once." }
        }

        fun contextClassLoader(): ClassLoader = Thread.currentThread().contextClassLoader
            ?: FileWeftPluginRegistry::class.java.classLoader
    }
}
