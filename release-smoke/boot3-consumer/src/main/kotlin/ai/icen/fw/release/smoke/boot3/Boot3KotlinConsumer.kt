package ai.icen.fw.release.smoke.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.agent.AgentTaskScheduler
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.persistence.jdbc.JdbcTaskRepository
import ai.icen.fw.starter.boot3.FileWeftRuntimeConfiguration
import ai.icen.fw.web.api.v1.doctor.DoctorTaskDto
import ai.icen.fw.web.spring.boot3.FileWeftWebBoot3DoctorAutoConfiguration
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
