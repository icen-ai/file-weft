package com.fileweft.web.spring.boot2

import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiDownloadFacade
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

/**
 * Optional Boot 2 MVC transport for the formal v1 document content API.
 *
 * It is independent from the document read and write adapters: a host exposes
 * downloads only when its normal trusted [DocumentDownloadService] capability
 * is available. The runtime facade remains the sole boundary allowed to turn
 * an application download into HTTP-safe metadata.
 */
@AutoConfiguration(afterName = ["com.fileweft.starter.boot2.FileWeftAutoConfiguration"])
@AutoConfigureAfter(
    FileWeftWebBoot2AutoConfiguration::class,
    FileWeftWebBoot2WriteAutoConfiguration::class,
)
@ConditionalOnClass(RestController::class, StreamingResponseBody::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(DocumentDownloadService::class)
class FileWeftWebBoot2ContentAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentApiDownloadFacade::class)
    fun fileWeftV1DocumentApiDownloadFacade(downloads: DocumentDownloadService): DocumentApiDownloadFacade =
        DocumentApiDownloadFacade(downloads)

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1ContentApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(DocumentV1ContentControllerAdvice::class)
    fun fileWeftV1DocumentContentControllerAdvice(): DocumentV1ContentControllerAdvice =
        DocumentV1ContentControllerAdvice()

    @Bean
    @ConditionalOnMissingBean(DocumentV1ContentController::class)
    fun fileWeftV1DocumentContentController(
        documents: DocumentApiDownloadFacade,
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): DocumentV1ContentController = DocumentV1ContentController(
        documents = documents,
        responses = responses,
        traceContextProvider = traceContextProviders.getIfUnique(),
    )
}
