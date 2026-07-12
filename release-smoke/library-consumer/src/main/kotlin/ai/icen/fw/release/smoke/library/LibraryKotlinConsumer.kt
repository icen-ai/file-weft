package ai.icen.fw.release.smoke.library

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftMetrics
import ai.icen.fw.agent.AgentTaskHandler
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.task.LeasedTaskHandler
import ai.icen.fw.persistence.jdbc.JdbcDoctorReportRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentQueryRepository
import ai.icen.fw.spi.plugin.FileWeftPlugin
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock

/** Exercises the same Maven-facing public ABI from Kotlin. */
class LibraryKotlinConsumer {
    fun plugin(plugin: FileWeftPlugin): FileWeftPlugin = plugin

    fun taskHandler(handler: AgentTaskHandler): LeasedTaskHandler = handler

    fun documentQueries(): DocumentQueryRepository = JdbcDocumentQueryRepository()

    fun doctorReports(mapper: ObjectMapper, clock: Clock) = JdbcDoctorReportRepository(mapper, clock)

    fun metrics(registry: MeterRegistry) = MicrometerFileWeftMetrics(registry)
}
