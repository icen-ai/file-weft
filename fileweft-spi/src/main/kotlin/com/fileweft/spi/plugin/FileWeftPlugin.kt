package com.fileweft.spi.plugin

import com.fileweft.spi.ai.AgentTaskTrigger
import com.fileweft.spi.ai.FileWeftAgent
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.doctor.DoctorChecker
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.task.FileWeftTaskHandler
import com.fileweft.spi.workflow.DocumentReviewRouteProvider

/**
 * Optional extension bundle discovered either as a Spring bean or with Java's
 * ServiceLoader. The plugin itself owns any vendor SDKs and configuration;
 * FileWeft only receives its SPI contributions.
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
