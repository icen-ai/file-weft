package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogBindingCommand
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.metadata.MetadataSchemaQueryService
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.catalog.DocumentCatalogApiFacade
import ai.icen.fw.web.runtime.v1.document.DocumentApiReadFacade
import ai.icen.fw.web.runtime.v1.document.DocumentSyncStatusApiFacade
import ai.icen.fw.web.runtime.v1.metadata.MetadataSchemaApiFacade
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/**
 * Optional Spring Boot 2 MVC transport for the formal FlowWeft v1 read API.
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
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogApiFacade::class)
    fun fileWeftDocumentCatalogApiFacade(
        catalog: DocumentCatalogAccessService,
        bindings: ObjectProvider<DocumentCatalogBindingCommand>,
    ): DocumentCatalogApiFacade = DocumentCatalogApiFacade(catalog, bindings.onlyCandidate())

    @Bean
    @ConditionalOnBean(MetadataSchemaQueryService::class)
    @ConditionalOnMissingBean(MetadataSchemaApiFacade::class)
    fun fileWeftMetadataSchemaApiFacade(schemas: MetadataSchemaQueryService): MetadataSchemaApiFacade =
        MetadataSchemaApiFacade(schemas)

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
    @ConditionalOnBean(MetadataSchemaQueryService::class)
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1MetadataApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1CatalogApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogV1RequestFailureHandler::class)
    fun fileWeftV1DocumentCatalogRequestFailureHandler(
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): DocumentCatalogV1RequestFailureHandler = DocumentCatalogV1RequestFailureHandler(
        responses,
        traceContextProviders.getIfUnique(),
    )

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

    @Bean
    @ConditionalOnBean(MetadataSchemaQueryService::class)
    @ConditionalOnMissingBean(MetadataV1SchemaController::class)
    fun fileWeftV1MetadataSchemaController(
        schemas: MetadataSchemaApiFacade,
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): MetadataV1SchemaController = MetadataV1SchemaController(
        schemas,
        responses,
        traceContextProviders.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogV1Controller::class)
    fun fileWeftV1DocumentCatalogController(
        catalog: DocumentCatalogApiFacade,
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): DocumentCatalogV1Controller = DocumentCatalogV1Controller(
        catalog,
        responses,
        traceContextProviders.getIfUnique(),
    )
}

/** Ignores Spring primary/priority hints: a security-sensitive command must have exactly one bean. */
private fun <T : Any> ObjectProvider<T>.onlyCandidate(): T? {
    val candidates = stream().iterator()
    if (!candidates.hasNext()) return null
    val candidate = candidates.next()
    return if (candidates.hasNext()) null else candidate
}
