package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.delivery.IdempotentDocumentCatalogDeliveryRecoveryService
import ai.icen.fw.application.delivery.IdempotentDocumentDeliveryRecoveryService
import ai.icen.fw.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import ai.icen.fw.application.lifecycle.IdempotentDocumentLifecycleService
import ai.icen.fw.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentReviewWorkflowService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentLifecycleApiFacade
import ai.icen.fw.web.runtime.v1.document.DocumentDeliveryRecoveryApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentDeliveryRecoveryController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentLifecycleController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/**
 * Formal Spring Boot 3 lifecycle transport.
 *
 * The facade and controller deliberately remain present when a host has not
 * installed lifecycle capabilities: calls then receive a stable 503 envelope.
 * Every application capability candidate is retained so `@Primary` can never
 * hide an ambiguous or unsafe flat/catalog configuration.
 */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
@AutoConfigureAfter(FileWeftWebBoot3AutoConfiguration::class)
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot3LifecycleAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentLifecycleApiFacade::class)
    fun fileWeftV1DocumentLifecycleApiFacade(
        catalogAccess: ObjectProvider<DocumentCatalogAccessService>,
        flatLifecycles: ObjectProvider<IdempotentDocumentLifecycleService>,
        catalogLifecycles: ObjectProvider<IdempotentDocumentCatalogLifecycleService>,
        flatReviews: ObjectProvider<IdempotentDocumentReviewWorkflowService>,
        catalogReviews: ObjectProvider<IdempotentDocumentCatalogReviewWorkflowService>,
    ): DocumentLifecycleApiFacade = DocumentLifecycleApiFacade(
        catalogAccessCount = catalogAccess.allCandidates().size,
        flatLifecycles = flatLifecycles.allCandidates(),
        catalogLifecycles = catalogLifecycles.allCandidates(),
        flatReviews = flatReviews.allCandidates(),
        catalogReviews = catalogReviews.allCandidates(),
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryRecoveryApiFacade::class)
    fun fileWeftV1DocumentDeliveryRecoveryApiFacade(
        catalogAccess: ObjectProvider<DocumentCatalogAccessService>,
        flatRecoveries: ObjectProvider<IdempotentDocumentDeliveryRecoveryService>,
        catalogRecoveries: ObjectProvider<IdempotentDocumentCatalogDeliveryRecoveryService>,
    ): DocumentDeliveryRecoveryApiFacade = DocumentDeliveryRecoveryApiFacade(
        catalogAccessCount = catalogAccess.allCandidates().size,
        flatRecoveries = flatRecoveries.allCandidates(),
        catalogRecoveries = catalogRecoveries.allCandidates(),
    )

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1LifecycleApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(V1DocumentLifecycleController::class)
    fun fileWeftV1DocumentLifecycleController(
        documents: DocumentLifecycleApiFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1DocumentLifecycleController {
        val traceCandidates = traceContexts.allCandidates()
        require(traceCandidates.size <= 1) {
            "Formal lifecycle API requires at most one trace context provider."
        }
        return V1DocumentLifecycleController(
            documents = documents,
            responses = responses,
            traceContextProvider = traceCandidates.singleOrNull(),
        )
    }

    @Bean
    @ConditionalOnMissingBean(V1DocumentDeliveryRecoveryController::class)
    fun fileWeftV1DocumentDeliveryRecoveryController(
        recoveries: DocumentDeliveryRecoveryApiFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1DocumentDeliveryRecoveryController {
        val traceCandidates = traceContexts.allCandidates()
        require(traceCandidates.size <= 1) {
            "Formal recovery API requires at most one trace context provider."
        }
        return V1DocumentDeliveryRecoveryController(
            recoveries,
            responses,
            traceCandidates.singleOrNull(),
        )
    }

    private fun <T> ObjectProvider<T>.allCandidates(): List<T> =
        iterator().asSequence().toList()
}
