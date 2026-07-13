package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.workflow.WorkflowQueryService
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.workflow.WorkflowApiReadFacade
import ai.icen.fw.web.runtime.v1.workflow.WorkflowDecisionEvidenceApiReadFacade
import ai.icen.fw.web.spring.boot3.v1.workflow.V1WorkflowDecisionEvidenceController
import ai.icen.fw.web.spring.boot3.v1.workflow.V1WorkflowReadController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase
import org.springframework.web.bind.annotation.RestController

@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot3WorkflowAutoConfiguration {
    @Bean
    @ConditionalOnBean(WorkflowQueryService::class)
    @ConditionalOnMissingBean(WorkflowApiReadFacade::class)
    fun fileWeftV1WorkflowApiReadFacade(
        services: ObjectProvider<WorkflowQueryService>,
    ): WorkflowApiReadFacade = WorkflowApiReadFacade(requiredSingle(services, "workflow query service"))

    @Bean
    @ConditionalOnBean(WorkflowDecisionEvidenceQueryService::class)
    @ConditionalOnMissingBean(WorkflowDecisionEvidenceApiReadFacade::class)
    fun fileWeftV1WorkflowDecisionEvidenceApiReadFacade(
        services: ObjectProvider<WorkflowDecisionEvidenceQueryService>,
    ): WorkflowDecisionEvidenceApiReadFacade = WorkflowDecisionEvidenceApiReadFacade(
        requiredSingle(services, "workflow decision evidence query service"),
    )

    @Bean
    @Conditional(WorkflowWebCapabilityCondition::class)
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftWorkflowV1ApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnBean(WorkflowQueryService::class)
    @ConditionalOnMissingBean(V1WorkflowReadController::class)
    fun fileWeftV1WorkflowReadController(
        facades: ObjectProvider<WorkflowApiReadFacade>,
        responses: V1ApiResponseFactory,
        traces: ObjectProvider<TraceContextProvider>,
    ): V1WorkflowReadController = V1WorkflowReadController(
        workflows = requiredSingle(facades, "workflow API facade"),
        responses = responses,
        traceContextProvider = optionalSingle(traces, "trace context provider"),
    )

    @Bean
    @ConditionalOnBean(WorkflowDecisionEvidenceQueryService::class)
    @ConditionalOnMissingBean(V1WorkflowDecisionEvidenceController::class)
    fun fileWeftV1WorkflowDecisionEvidenceController(
        facades: ObjectProvider<WorkflowDecisionEvidenceApiReadFacade>,
        responses: V1ApiResponseFactory,
        traces: ObjectProvider<TraceContextProvider>,
    ): V1WorkflowDecisionEvidenceController = V1WorkflowDecisionEvidenceController(
        evidence = requiredSingle(facades, "workflow decision evidence API facade"),
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

internal class WorkflowWebCapabilityCondition : AnyNestedCondition(ConfigurationPhase.REGISTER_BEAN) {
    @ConditionalOnBean(WorkflowQueryService::class)
    class WorkflowQueryAvailable

    @ConditionalOnBean(WorkflowDecisionEvidenceQueryService::class)
    class WorkflowDecisionEvidenceQueryAvailable
}
