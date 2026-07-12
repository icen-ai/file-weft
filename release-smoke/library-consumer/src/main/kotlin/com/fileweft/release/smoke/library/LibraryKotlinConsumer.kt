package com.fileweft.release.smoke.library

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.adapter.micrometer.MicrometerFileWeftMetrics
import com.fileweft.agent.AgentTaskHandler
import com.fileweft.application.document.DocumentQueryRepository
import com.fileweft.application.task.LeasedTaskHandler
import com.fileweft.persistence.jdbc.JdbcDoctorReportRepository
import com.fileweft.persistence.jdbc.JdbcDocumentQueryRepository
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock

/** Exercises the same Maven-facing public ABI from Kotlin. */
class LibraryKotlinConsumer {
    fun taskHandler(handler: AgentTaskHandler): LeasedTaskHandler = handler

    fun documentQueries(): DocumentQueryRepository = JdbcDocumentQueryRepository()

    fun doctorReports(mapper: ObjectMapper, clock: Clock) = JdbcDoctorReportRepository(mapper, clock)

    fun metrics(registry: MeterRegistry) = MicrometerFileWeftMetrics(registry)
}
