package com.fileweft.release.smoke.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.agent.AgentTaskScheduler
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.persistence.jdbc.JdbcTaskRepository
import com.fileweft.starter.boot3.FileWeftRuntimeConfiguration
import com.fileweft.web.api.v1.doctor.DoctorTaskDto
import com.fileweft.web.spring.boot3.FileWeftWebBoot3DoctorAutoConfiguration
import java.time.Clock

/** Kotlin metadata and public signatures must also resolve from Maven POM scopes. */
class Boot3KotlinConsumer {
    fun taskRepository(configuration: FileWeftRuntimeConfiguration, clock: Clock): JdbcTaskRepository =
        configuration.tasks(ObjectMapper(), clock)

    fun scheduler(
        configuration: FileWeftRuntimeConfiguration,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): AgentTaskScheduler = configuration.agentTaskScheduler(identifiers, clock)

    fun webContract(
        ignored: FileWeftWebBoot3DoctorAutoConfiguration,
        task: DoctorTaskDto,
    ): DoctorTaskDto = task
}
