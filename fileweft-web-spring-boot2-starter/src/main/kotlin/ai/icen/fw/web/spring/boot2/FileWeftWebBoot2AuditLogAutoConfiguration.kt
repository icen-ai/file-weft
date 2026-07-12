package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.audit.DocumentAuditLogQueryService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.audit.DocumentAuditLogApiFacade
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/** Optional Spring Boot 2 transport for the formal redacted document audit-log API. */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot2.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot2AuditLogAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentAuditLogApiFacade::class)
    fun fileWeftV1DocumentAuditLogApiFacade(
        services: ObjectProvider<DocumentAuditLogQueryService>,
    ): DocumentAuditLogApiFacade = DocumentAuditLogApiFacade(services.orderedStream().iterator().asSequence().toList())

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1DocumentAuditLogApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(DocumentV1AuditLogController::class)
    fun fileWeftV1DocumentAuditLogController(
        auditLogs: DocumentAuditLogApiFacade,
        responses: V1ApiResponseFactory,
        traces: ObjectProvider<TraceContextProvider>,
    ): DocumentV1AuditLogController = DocumentV1AuditLogController(auditLogs, responses, traces.getIfUnique())
}
