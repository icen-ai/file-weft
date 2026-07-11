package com.fileweft.web.spring.boot3

import com.fileweft.application.document.DocumentQueryService
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.document.DocumentApiReadFacade
import com.fileweft.web.spring.boot3.v1.document.V1DocumentReadController
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
@AutoConfiguration(afterName = ["com.fileweft.starter.boot3.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(DocumentQueryService::class)
class FileWeftWebBoot3AutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentApiReadFacade::class)
    fun fileWeftV1DocumentApiReadFacade(documents: DocumentQueryService): DocumentApiReadFacade =
        DocumentApiReadFacade(documents)

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1ApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
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
}
