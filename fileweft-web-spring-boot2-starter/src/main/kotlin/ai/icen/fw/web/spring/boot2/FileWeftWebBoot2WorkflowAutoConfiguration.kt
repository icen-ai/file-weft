package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.workflow.WorkflowQueryService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.workflow.WorkflowApiReadFacade
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot2.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(WorkflowQueryService::class)
class FileWeftWebBoot2WorkflowAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(WorkflowApiReadFacade::class)
    fun fileWeftWorkflowApiReadFacade(
        services: ObjectProvider<WorkflowQueryService>,
    ): WorkflowApiReadFacade = WorkflowApiReadFacade(requiredSingle(services, "workflow query service"))

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftWorkflowV1ApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(WorkflowV1Controller::class)
    fun fileWeftWorkflowV1Controller(
        facades: ObjectProvider<WorkflowApiReadFacade>,
        responses: V1ApiResponseFactory,
        traces: ObjectProvider<TraceContextProvider>,
    ): WorkflowV1Controller = WorkflowV1Controller(
        workflows = requiredSingle(facades, "workflow API facade"),
        responses = responses,
        traceContextProvider = optionalSingle(traces, "trace context provider"),
    )

    private fun <T> requiredSingle(provider: ObjectProvider<T>, label: String): T {
        val candidates = provider.orderedStream().iterator().asSequence().toList()
        check(candidates.size == 1) { "Formal workflow API requires exactly one $label." }
        return candidates.single()
    }

    private fun <T> optionalSingle(provider: ObjectProvider<T>, label: String): T? {
        val candidates = provider.orderedStream().iterator().asSequence().toList()
        check(candidates.size <= 1) { "Formal workflow API requires at most one $label." }
        return candidates.singleOrNull()
    }
}
