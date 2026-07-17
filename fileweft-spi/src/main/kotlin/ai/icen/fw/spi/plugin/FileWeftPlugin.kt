package ai.icen.fw.spi.plugin

import ai.icen.fw.spi.ai.AgentTaskTrigger
import ai.icen.fw.spi.ai.FileWeftAgent
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider

/**
 * Optional extension bundle discovered either as a Spring bean or with Java's
 * ServiceLoader. The plugin itself owns any vendor SDKs and configuration;
 * FlowWeft only receives its SPI contributions.
 */
interface FileWeftPlugin {
    /** Stable, globally unique plugin identity used for startup diagnostics. */
    fun id(): String

    fun storageAdapters(): List<StorageAdapter> = emptyList()

    /** Connector keys are the same opaque ids referenced by delivery profiles. */
    fun connectors(): Map<String, FileConnector> = emptyMap()

    fun doctorCheckers(): List<DoctorChecker> = emptyList()

    fun agents(): List<FileWeftAgent> = emptyList()

    fun agentTaskTriggers(): List<AgentTaskTrigger> = emptyList()

    fun outboxEventHandlers(): List<OutboxEventHandler> = emptyList()

    fun taskHandlers(): List<FileWeftTaskHandler> = emptyList()

    fun reviewRouteProviders(): List<DocumentReviewRouteProvider> = emptyList()
}
