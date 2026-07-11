package com.fileweft.web.spring.boot2

import com.fileweft.application.document.DocumentQueryService
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiReadFacade
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
@AutoConfiguration(afterName = ["com.fileweft.starter.boot2.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(DocumentQueryService::class)
class FileWeftWebBoot2AutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentApiReadFacade::class)
    fun fileWeftDocumentApiReadFacade(documents: DocumentQueryService): DocumentApiReadFacade =
        DocumentApiReadFacade(documents)

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1ApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
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
}
