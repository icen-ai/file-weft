package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiReadFacade
import ai.icen.fw.web.runtime.v1.document.DocumentSyncStatusApiFacade
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/**
 * Optional Spring Boot 2 MVC transport for the formal FileWeft v1 read API.
 *
 * The underlying [DocumentQueryService] remains the security boundary: it
 * obtains the trusted tenant and user from SPI providers rather than from any
 * HTTP request field. The adapter is deliberately not component scanned; a
 * host gets these routes only by adding this starter and supplying the query
 * service.
 */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot2.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot2AutoConfiguration {
    @Bean
    @ConditionalOnBean(DocumentQueryService::class)
    @ConditionalOnMissingBean(DocumentApiReadFacade::class)
    fun fileWeftDocumentApiReadFacade(documents: DocumentQueryService): DocumentApiReadFacade =
        DocumentApiReadFacade(documents)

    @Bean
    @ConditionalOnBean(DocumentSyncStatusQueryService::class)
    @ConditionalOnMissingBean(DocumentSyncStatusApiFacade::class)
    fun fileWeftDocumentSyncStatusApiFacade(
        synchronization: DocumentSyncStatusQueryService,
    ): DocumentSyncStatusApiFacade = DocumentSyncStatusApiFacade(synchronization)

    @Bean
    @ConditionalOnBean(DocumentQueryService::class)
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1DocumentApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnBean(DocumentSyncStatusQueryService::class)
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1SyncStatusApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnBean(DocumentQueryService::class)
    @ConditionalOnMissingBean(DocumentV1Controller::class)
    fun fileWeftV1DocumentController(
        documents: DocumentApiReadFacade,
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): DocumentV1Controller = DocumentV1Controller(
        documents = documents,
        responses = responses,
        traceContextProvider = traceContextProviders.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(DocumentSyncStatusQueryService::class)
    @ConditionalOnMissingBean(DocumentV1SyncStatusController::class)
    fun fileWeftV1DocumentSyncStatusController(
        synchronization: DocumentSyncStatusApiFacade,
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): DocumentV1SyncStatusController = DocumentV1SyncStatusController(
        synchronization,
        responses,
        traceContextProviders.getIfUnique(),
    )
}
