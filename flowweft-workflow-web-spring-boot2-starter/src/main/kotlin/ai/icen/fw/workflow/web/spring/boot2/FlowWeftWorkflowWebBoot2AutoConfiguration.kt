package ai.icen.fw.workflow.web.spring.boot2

import ai.icen.fw.workflow.web.api.WorkflowCollaborationWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowDefinitionWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowInstanceWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowOperationsWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowTaskWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowWebCapabilityApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContextProvider
import ai.icen.fw.workflow.web.runtime.WorkflowWebControllerRuntime
import ai.icen.fw.workflow.web.runtime.WorkflowWebRuntimeObservationPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/**
 * Opt-in Spring Boot 2 servlet transport for the standalone FlowWeft Workflow API.
 *
 * A host must provide exactly one [WorkflowWebTrustedContextProvider]. No request header, path,
 * query parameter or JSON property is ever treated as tenant or principal identity.
 */
@AutoConfiguration
@ConditionalOnClass(RestController::class, ObjectMapper::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(WorkflowWebTrustedContextProvider::class, ObjectMapper::class)
class FlowWeftWorkflowWebBoot2AutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorkflowWebControllerRuntime::class)
    fun flowWeftWorkflowWebControllerRuntime(
        contexts: ObjectProvider<WorkflowWebTrustedContextProvider>,
        observations: ObjectProvider<WorkflowWebRuntimeObservationPort>,
    ): WorkflowWebControllerRuntime = WorkflowWebControllerRuntime(
        contexts.exactlyOne("FlowWeft Workflow Web requires exactly one trusted context provider."),
        observations.onlyCandidate() ?: WorkflowWebRuntimeObservationPort.NO_OP,
    )

    @Bean
    @ConditionalOnMissingBean(FlowWeftWorkflowWebBoot2ApplicationPorts::class)
    fun flowWeftWorkflowWebBoot2ApplicationPorts(
        capabilities: ObjectProvider<WorkflowWebCapabilityApplicationPort>,
        definitions: ObjectProvider<WorkflowDefinitionWebApplicationPort>,
        instances: ObjectProvider<WorkflowInstanceWebApplicationPort>,
        tasks: ObjectProvider<WorkflowTaskWebApplicationPort>,
        collaboration: ObjectProvider<WorkflowCollaborationWebApplicationPort>,
        operations: ObjectProvider<WorkflowOperationsWebApplicationPort>,
    ): FlowWeftWorkflowWebBoot2ApplicationPorts = FlowWeftWorkflowWebBoot2ApplicationPorts(
        capabilities.onlyCandidate(),
        definitions.onlyCandidate(),
        instances.onlyCandidate(),
        tasks.onlyCandidate(),
        collaboration.onlyCandidate(),
        operations.onlyCandidate(),
    )

    @Bean
    @ConditionalOnMissingBean(FlowWeftWorkflowWebBoot2JsonCodec::class)
    fun flowWeftWorkflowWebBoot2JsonCodec(objectMapper: ObjectMapper): FlowWeftWorkflowWebBoot2JsonCodec =
        FlowWeftWorkflowWebBoot2JsonCodec(objectMapper)

    @Bean
    @ConditionalOnMissingBean(FlowWeftWorkflowWebBoot2Controller::class)
    fun flowWeftWorkflowWebBoot2Controller(
        runtime: WorkflowWebControllerRuntime,
        ports: FlowWeftWorkflowWebBoot2ApplicationPorts,
        codec: FlowWeftWorkflowWebBoot2JsonCodec,
    ): FlowWeftWorkflowWebBoot2Controller = FlowWeftWorkflowWebBoot2Controller(runtime, ports, codec)
}

class FlowWeftWorkflowWebBoot2ApplicationPorts(
    val capabilities: WorkflowWebCapabilityApplicationPort?,
    val definitions: WorkflowDefinitionWebApplicationPort?,
    val instances: WorkflowInstanceWebApplicationPort?,
    val tasks: WorkflowTaskWebApplicationPort?,
    val collaboration: WorkflowCollaborationWebApplicationPort?,
    val operations: WorkflowOperationsWebApplicationPort?,
)

private fun <T : Any> ObjectProvider<T>.onlyCandidate(): T? {
    val iterator = stream().iterator()
    if (!iterator.hasNext()) return null
    val candidate = iterator.next()
    return if (iterator.hasNext()) null else candidate
}

private fun <T : Any> ObjectProvider<T>.exactlyOne(message: String): T =
    onlyCandidate() ?: throw IllegalStateException(message)
