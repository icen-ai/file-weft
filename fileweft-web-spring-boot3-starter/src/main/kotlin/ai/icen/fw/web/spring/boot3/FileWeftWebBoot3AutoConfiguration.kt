package ai.icen.fw.web.spring.boot3

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
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentReadController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentCatalogController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentCatalogRequestFailureHandler
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentSyncStatusController
import ai.icen.fw.web.spring.boot3.v1.metadata.V1MetadataSchemaController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/**
 * Optional Spring Boot 3 MVC surface for the formal FileWeft v1 API.
 *
 * It is intentionally a separate starter from the FileWeft runtime starter:
 * hosts must opt in to HTTP routing and retain ownership of authentication,
 * request identity propagation, CORS, CSRF, and all perimeter controls.
 */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot3AutoConfiguration {
    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogApiFacade::class)
    fun fileWeftV1DocumentCatalogApiFacade(
        catalog: DocumentCatalogAccessService,
        bindings: ObjectProvider<DocumentCatalogBindingCommand>,
    ): DocumentCatalogApiFacade = DocumentCatalogApiFacade(catalog, bindings.onlyCandidate())

    @Bean
    @ConditionalOnBean(MetadataSchemaQueryService::class)
    @ConditionalOnMissingBean(MetadataSchemaApiFacade::class)
    fun fileWeftV1MetadataSchemaApiFacade(schemas: MetadataSchemaQueryService): MetadataSchemaApiFacade =
        MetadataSchemaApiFacade(schemas)

    @Bean
    @ConditionalOnBean(DocumentQueryService::class)
    @ConditionalOnMissingBean(DocumentApiReadFacade::class)
    fun fileWeftV1DocumentApiReadFacade(documents: DocumentQueryService): DocumentApiReadFacade =
        DocumentApiReadFacade(documents)

    @Bean
    @ConditionalOnBean(DocumentSyncStatusQueryService::class)
    @ConditionalOnMissingBean(DocumentSyncStatusApiFacade::class)
    fun fileWeftV1DocumentSyncStatusApiFacade(
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
    @ConditionalOnMissingBean(V1DocumentCatalogRequestFailureHandler::class)
    fun fileWeftV1DocumentCatalogRequestFailureHandler(
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1DocumentCatalogRequestFailureHandler = V1DocumentCatalogRequestFailureHandler(
        responses,
        traceContexts.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(DocumentQueryService::class)
    @ConditionalOnMissingBean(V1DocumentReadController::class)
    fun fileWeftV1DocumentReadController(
        documents: DocumentApiReadFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1DocumentReadController = V1DocumentReadController(
        documents = documents,
        responses = responses,
        traceContextProvider = traceContexts.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(DocumentSyncStatusQueryService::class)
    @ConditionalOnMissingBean(V1DocumentSyncStatusController::class)
    fun fileWeftV1DocumentSyncStatusController(
        synchronization: DocumentSyncStatusApiFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1DocumentSyncStatusController = V1DocumentSyncStatusController(
        synchronization,
        responses,
        traceContexts.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(MetadataSchemaQueryService::class)
    @ConditionalOnMissingBean(V1MetadataSchemaController::class)
    fun fileWeftV1MetadataSchemaController(
        schemas: MetadataSchemaApiFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1MetadataSchemaController = V1MetadataSchemaController(
        schemas,
        responses,
        traceContexts.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(V1DocumentCatalogController::class)
    fun fileWeftV1DocumentCatalogController(
        catalog: DocumentCatalogApiFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1DocumentCatalogController = V1DocumentCatalogController(
        catalog,
        responses,
        traceContexts.getIfUnique(),
    )
}

/** Ignores Spring primary/priority hints: a security-sensitive command must have exactly one bean. */
private fun <T : Any> ObjectProvider<T>.onlyCandidate(): T? {
    val candidates = stream().iterator()
    if (!candidates.hasNext()) return null
    val candidate = candidates.next()
    return if (candidates.hasNext()) null else candidate
}
