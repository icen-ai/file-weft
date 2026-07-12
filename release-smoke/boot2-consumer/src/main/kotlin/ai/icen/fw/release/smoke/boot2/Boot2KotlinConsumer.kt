package ai.icen.fw.release.smoke.boot2

import ai.icen.fw.agent.AgentTaskScheduler
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.persistence.jdbc.JdbcTaskRepository
import ai.icen.fw.starter.boot2.FileWeftRuntimeConfiguration
import ai.icen.fw.web.api.v1.doctor.DoctorTaskDto
import ai.icen.fw.web.spring.boot2.FileWeftWebBoot2DoctorAutoConfiguration
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
