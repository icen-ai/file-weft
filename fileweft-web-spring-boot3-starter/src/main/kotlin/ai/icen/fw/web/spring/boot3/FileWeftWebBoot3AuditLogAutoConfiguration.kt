package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.audit.DocumentAuditLogQueryService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.audit.DocumentAuditLogApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentAuditLogController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/** Optional Spring Boot 3 transport for the formal redacted document audit-log API. */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot3AuditLogAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentAuditLogApiFacade::class)
    fun fileWeftV1DocumentAuditLogApiFacade(
        services: ObjectProvider<DocumentAuditLogQueryService>,
    ): DocumentAuditLogApiFacade = DocumentAuditLogApiFacade(services.orderedStream().iterator().asSequence().toList())

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1DocumentAuditLogApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(V1DocumentAuditLogController::class)
    fun fileWeftV1DocumentAuditLogController(
        auditLogs: DocumentAuditLogApiFacade,
        responses: V1ApiResponseFactory,
        traces: ObjectProvider<TraceContextProvider>,
    ): V1DocumentAuditLogController = V1DocumentAuditLogController(auditLogs, responses, traces.getIfUnique())
}
