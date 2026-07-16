package ai.icen.fw.workflow.web.spring.boot3

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

/** JDK 17 / Jakarta servlet transport with the same fail-closed contract as the Boot 2 adapter. */
@AutoConfiguration
@ConditionalOnClass(RestController::class, ObjectMapper::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(WorkflowWebTrustedContextProvider::class, ObjectMapper::class)
class FlowWeftWorkflowWebBoot3AutoConfiguration {
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
    @ConditionalOnMissingBean(FlowWeftWorkflowWebBoot3ApplicationPorts::class)
    fun flowWeftWorkflowWebBoot3ApplicationPorts(
        capabilities: ObjectProvider<WorkflowWebCapabilityApplicationPort>,
        definitions: ObjectProvider<WorkflowDefinitionWebApplicationPort>,
        instances: ObjectProvider<WorkflowInstanceWebApplicationPort>,
        tasks: ObjectProvider<WorkflowTaskWebApplicationPort>,
        collaboration: ObjectProvider<WorkflowCollaborationWebApplicationPort>,
        operations: ObjectProvider<WorkflowOperationsWebApplicationPort>,
    ): FlowWeftWorkflowWebBoot3ApplicationPorts = FlowWeftWorkflowWebBoot3ApplicationPorts(
        capabilities.onlyCandidate(),
        definitions.onlyCandidate(),
        instances.onlyCandidate(),
        tasks.onlyCandidate(),
        collaboration.onlyCandidate(),
        operations.onlyCandidate(),
    )

    @Bean
    @ConditionalOnMissingBean(FlowWeftWorkflowWebBoot3JsonCodec::class)
    fun flowWeftWorkflowWebBoot3JsonCodec(objectMapper: ObjectMapper): FlowWeftWorkflowWebBoot3JsonCodec =
        FlowWeftWorkflowWebBoot3JsonCodec(objectMapper)

    @Bean
    @ConditionalOnMissingBean(FlowWeftWorkflowWebBoot3Controller::class)
    fun flowWeftWorkflowWebBoot3Controller(
        runtime: WorkflowWebControllerRuntime,
        ports: FlowWeftWorkflowWebBoot3ApplicationPorts,
        codec: FlowWeftWorkflowWebBoot3JsonCodec,
    ): FlowWeftWorkflowWebBoot3Controller = FlowWeftWorkflowWebBoot3Controller(runtime, ports, codec)
}

class FlowWeftWorkflowWebBoot3ApplicationPorts(
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
