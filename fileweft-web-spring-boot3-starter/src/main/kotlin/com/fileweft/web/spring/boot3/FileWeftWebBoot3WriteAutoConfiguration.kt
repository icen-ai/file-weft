package com.fileweft.web.spring.boot3

import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.application.catalog.DocumentCatalogMutationService
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiWriteFacade
import com.fileweft.web.spring.boot3.v1.document.V1DocumentWriteController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/**
 * Optional Spring Boot 3 MVC transport for formal v1 document mutations.
 *
 * It is independent from the read adapter so write routes exist only when the
 * host has supplied the normal FileWeft draft capability. Catalog-aware mode
 * is selected explicitly and never falls back to an unscoped flat mutation.
 */
@AutoConfiguration(afterName = ["com.fileweft.starter.boot3.FileWeftAutoConfiguration"])
@AutoConfigureAfter(FileWeftWebBoot3AutoConfiguration::class)
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(DocumentDraftService::class)
class FileWeftWebBoot3WriteAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentApiWriteFacade::class)
    fun fileWeftV1DocumentApiWriteFacade(
        drafts: DocumentDraftService,
        catalogDrafts: ObjectProvider<DocumentCatalogDraftService>,
        catalogMutations: ObjectProvider<DocumentCatalogMutationService>,
    ): DocumentApiWriteFacade = DocumentApiWriteFacade(
        drafts = drafts,
        // Multiple catalog services are ambiguous and must fail startup rather than silently dropping folder ACLs.
        catalogDrafts = catalogDrafts.getIfAvailable(),
        catalogMutations = catalogMutations.getIfAvailable(),
    )

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1WriteApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(V1DocumentWriteController::class)
    fun fileWeftV1DocumentWriteController(
        documents: DocumentApiWriteFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1DocumentWriteController = V1DocumentWriteController(
        documents = documents,
        responses = responses,
        traceContextProvider = traceContexts.getIfUnique(),
    )
}
