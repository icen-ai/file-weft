package com.fileweft.web.spring.boot2

import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import com.fileweft.application.lifecycle.IdempotentDocumentLifecycleService
import com.fileweft.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import com.fileweft.application.workflow.IdempotentDocumentReviewWorkflowService
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentLifecycleApiFacade
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/**
 * Boot 2 transport for formal lifecycle and review commands.
 *
 * The routes remain installed when an Application capability is absent so a
 * partially configured catalog host receives a deterministic 503 instead of
 * an unsafe flat fallback. Candidate enumeration deliberately ignores
 * `@Primary`; ambiguity is rejected by [DocumentLifecycleApiFacade].
 */
@AutoConfiguration(afterName = ["com.fileweft.starter.boot2.FileWeftAutoConfiguration"])
@AutoConfigureAfter(FileWeftWebBoot2AutoConfiguration::class)
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot2LifecycleAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentLifecycleApiFacade::class)
    fun fileWeftV1DocumentLifecycleApiFacade(
        catalogAccesses: ObjectProvider<DocumentCatalogAccessService>,
        flatLifecycles: ObjectProvider<IdempotentDocumentLifecycleService>,
        catalogLifecycles: ObjectProvider<IdempotentDocumentCatalogLifecycleService>,
        flatReviews: ObjectProvider<IdempotentDocumentReviewWorkflowService>,
        catalogReviews: ObjectProvider<IdempotentDocumentCatalogReviewWorkflowService>,
    ): DocumentLifecycleApiFacade {
        val catalogAccessCandidates = catalogAccesses.allCandidates()
        return DocumentLifecycleApiFacade(
            catalogAccessCount = catalogAccessCandidates.size,
            flatLifecycles = flatLifecycles.allCandidates(),
            catalogLifecycles = catalogLifecycles.allCandidates(),
            flatReviews = flatReviews.allCandidates(),
            catalogReviews = catalogReviews.allCandidates(),
        )
    }

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1LifecycleApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(DocumentV1LifecycleController::class)
    fun fileWeftV1DocumentLifecycleController(
        documents: DocumentLifecycleApiFacade,
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): DocumentV1LifecycleController = DocumentV1LifecycleController(
        documents = documents,
        responses = responses,
        traceContextProvider = traceContextProviders.uniqueOptional("trace context"),
    )
}

private fun <T> ObjectProvider<T>.allCandidates(): List<T> = iterator().asSequence().toList()

private fun <T> ObjectProvider<T>.uniqueOptional(label: String): T? {
    val candidates = allCandidates()
    require(candidates.size <= 1) { "Formal lifecycle API has multiple $label candidates." }
    return candidates.singleOrNull()
}
