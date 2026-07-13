package ai.icen.fw.web.spring.boot2

import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiFacade
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/** Optional Boot 2 servlet transport for the formal resumable-upload resource. */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot2.FileWeftAutoConfiguration"])
@AutoConfigureAfter(FileWeftWebBoot2AutoConfiguration::class)
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot2ResumableUploadAutoConfiguration {
    @Bean
    @ConditionalOnBean(ResumableUploadService::class)
    @ConditionalOnMissingBean(ResumableUploadApiFacade::class)
    fun fileWeftV1ResumableUploadApiFacade(
        uploads: ResumableUploadService,
    ): ResumableUploadApiFacade = ResumableUploadApiFacade(uploads)

    @Bean
    @ConditionalOnBean(ResumableUploadApiFacade::class)
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1ResumableUploadApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnBean(ResumableUploadApiFacade::class)
    @ConditionalOnMissingBean(V1ResumableUploadController::class)
    fun fileWeftV1ResumableUploadController(
        uploads: ResumableUploadApiFacade,
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): V1ResumableUploadController = V1ResumableUploadController(
        uploads = uploads,
        responses = responses,
        traceContextProvider = traceContextProviders.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(ResumableUploadApiFacade::class)
    @ConditionalOnMissingBean(V1ResumableUploadRequestFailureHandler::class)
    fun fileWeftV1ResumableUploadRequestFailureHandler(
        responses: V1ApiResponseFactory,
        traceContextProviders: ObjectProvider<TraceContextProvider>,
    ): V1ResumableUploadRequestFailureHandler = V1ResumableUploadRequestFailureHandler(
        responses = responses,
        traceContextProvider = traceContextProviders.getIfUnique(),
    )
}
