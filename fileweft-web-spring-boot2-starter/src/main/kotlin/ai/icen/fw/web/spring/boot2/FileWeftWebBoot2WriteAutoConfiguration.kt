package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.application.catalog.DocumentCatalogMutationService
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiWriteFacade
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/**
 * Optional Boot 2 MVC transport for formal v1 document mutations.
 *
 * It remains independent from the read adapter so a host can expose writes
 * only when its normal FileWeft draft capability is available. A catalog is
 * optional, but a requested folder never silently falls back to a flat draft.
 */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot2.FileWeftAutoConfiguration"])
@AutoConfigureAfter(FileWeftWebBoot2AutoConfiguration::class)
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(DocumentDraftService::class)
class FileWeftWebBoot2WriteAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentApiWriteFacade::class)
    fun fileWeftV1DocumentApiWriteFacade(
        drafts: DocumentDraftService,
        catalogDrafts: ObjectProvider<DocumentCatalogDraftService>,
        catalogMutations: ObjectProvider<DocumentCatalogMutationService>,
    ): DocumentApiWriteFacade = DocumentApiWriteFacade(
        drafts = drafts,
        // getIfAvailable intentionally fails on multiple catalog services rather than silently dropping folder ACLs.
        catalogDrafts = catalogDrafts.getIfAvailable(),
        catalogMutations = catalogMutations.getIfAvailable(),
    )

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1WriteApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(DocumentV1WriteController::class)
    fun fileWeftV1DocumentWriteController(
        documents: DocumentApiWriteFacade,
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): DocumentV1WriteController = DocumentV1WriteController(
        documents = documents,
        responses = responses,
        traceContextProvider = traceContextProviders.getIfUnique(),
    )
}
