package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiDownloadFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentContentController
import ai.icen.fw.web.spring.boot3.v1.document.V1DocumentContentFailureHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

/** Optional Boot 3 MVC transport for authorized v1 document content streams. */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
@AutoConfigureAfter(
    FileWeftWebBoot3AutoConfiguration::class,
    FileWeftWebBoot3WriteAutoConfiguration::class,
)
@ConditionalOnClass(RestController::class, StreamingResponseBody::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(DocumentDownloadService::class)
class FileWeftWebBoot3ContentAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentApiDownloadFacade::class)
    fun fileWeftV1DocumentApiDownloadFacade(downloads: DocumentDownloadService): DocumentApiDownloadFacade =
        DocumentApiDownloadFacade(downloads)

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1ContentApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(V1DocumentContentController::class)
    fun fileWeftV1DocumentContentController(
        documents: DocumentApiDownloadFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1DocumentContentController = V1DocumentContentController(
        documents = documents,
        responses = responses,
        traceContextProvider = traceContexts.getIfUnique(),
    )

    @Bean
    @ConditionalOnMissingBean(V1DocumentContentFailureHandler::class)
    fun fileWeftV1DocumentContentFailureHandler(): V1DocumentContentFailureHandler =
        V1DocumentContentFailureHandler()
}
