package com.fileweft.release.smoke.boot2

import com.fileweft.agent.AgentTaskScheduler
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.persistence.jdbc.JdbcTaskRepository
import com.fileweft.starter.boot2.FileWeftRuntimeConfiguration
import com.fileweft.web.api.v1.doctor.DoctorTaskDto
import com.fileweft.web.spring.boot2.FileWeftWebBoot2DoctorAutoConfiguration
import java.time.Clock

/** Kotlin metadata and public signatures must also resolve from Maven POM scopes. */
class Boot2KotlinConsumer {
    fun taskRepository(configuration: FileWeftRuntimeConfiguration, clock: Clock): JdbcTaskRepository =
        configuration.fileWeftTaskRepository(com.fasterxml.jackson.databind.ObjectMapper(), clock)

    fun scheduler(
        configuration: FileWeftRuntimeConfiguration,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): AgentTaskScheduler = configuration.fileWeftAgentTaskScheduler(identifiers, clock)

    fun webContract(
        ignored: FileWeftWebBoot2DoctorAutoConfiguration,
        task: DoctorTaskDto,
    ): DoctorTaskDto = task
}
