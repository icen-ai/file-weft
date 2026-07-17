package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.PresignedUploadService
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimService
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiFacade
import ai.icen.fw.web.runtime.v1.upload.PresignedUploadApiFacade
import ai.icen.fw.web.runtime.v1.document.CompletedUploadDocumentApiFacade
import ai.icen.fw.web.spring.boot3.v1.document.V1CompletedUploadDocumentController
import ai.icen.fw.web.spring.boot3.v1.upload.V1ResumableUploadController
import ai.icen.fw.web.spring.boot3.v1.upload.V1ResumableUploadRequestFailureHandler
import ai.icen.fw.web.spring.boot3.v1.upload.V1PresignedUploadController
import ai.icen.fw.web.spring.boot3.v1.upload.V1PresignedUploadRequestFailureHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/** Optional Spring Boot 3 MVC transport for the formal resumable-upload resource. */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
@AutoConfigureAfter(FileWeftWebBoot3AutoConfiguration::class)
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot3ResumableUploadAutoConfiguration {
    @Bean
    @ConditionalOnBean(value = [PresignedUploadService::class, CompletedPresignedUploadAssetClaimService::class])
    @ConditionalOnMissingBean(PresignedUploadApiFacade::class)
    fun flowWeftV1PresignedUploadApiFacade(
        uploads: PresignedUploadService,
        claims: CompletedPresignedUploadAssetClaimService,
    ): PresignedUploadApiFacade = PresignedUploadApiFacade(uploads, claims)

    @Bean
    @ConditionalOnBean(ResumableUploadService::class)
    @ConditionalOnMissingBean(ResumableUploadApiFacade::class)
    fun fileWeftV1ResumableUploadApiFacade(
        uploads: ResumableUploadService,
    ): ResumableUploadApiFacade = ResumableUploadApiFacade(uploads)

    @Bean
    @ConditionalOnBean(CompletedResumableUploadAssetClaimService::class)
    @ConditionalOnMissingBean(CompletedUploadDocumentApiFacade::class)
    fun fileWeftV1CompletedUploadDocumentApiFacade(
        claims: CompletedResumableUploadAssetClaimService,
    ): CompletedUploadDocumentApiFacade = CompletedUploadDocumentApiFacade(claims)

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1ResumableUploadApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnBean(PresignedUploadService::class)
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun flowWeftV1PresignedUploadApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnBean(PresignedUploadApiFacade::class)
    @ConditionalOnMissingBean(V1PresignedUploadController::class)
    fun flowWeftV1PresignedUploadController(
        uploads: PresignedUploadApiFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1PresignedUploadController = V1PresignedUploadController(
        uploads = uploads,
        responses = responses,
        traceContextProvider = traceContexts.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(PresignedUploadApiFacade::class)
    @ConditionalOnMissingBean(V1PresignedUploadRequestFailureHandler::class)
    fun flowWeftV1PresignedUploadRequestFailureHandler(
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1PresignedUploadRequestFailureHandler = V1PresignedUploadRequestFailureHandler(
        responses = responses,
        traceContextProvider = traceContexts.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(ResumableUploadApiFacade::class)
    @ConditionalOnMissingBean(V1ResumableUploadController::class)
    fun fileWeftV1ResumableUploadController(
        uploads: ResumableUploadApiFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1ResumableUploadController = V1ResumableUploadController(
        uploads = uploads,
        responses = responses,
        traceContextProvider = traceContexts.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(CompletedUploadDocumentApiFacade::class)
    @ConditionalOnMissingBean(V1CompletedUploadDocumentController::class)
    fun fileWeftV1CompletedUploadDocumentController(
        claims: CompletedUploadDocumentApiFacade,
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1CompletedUploadDocumentController = V1CompletedUploadDocumentController(
        claims = claims,
        responses = responses,
        traceContextProvider = traceContexts.getIfUnique(),
    )

    @Bean
    @ConditionalOnBean(ResumableUploadApiFacade::class)
    @ConditionalOnMissingBean(V1ResumableUploadRequestFailureHandler::class)
    fun fileWeftV1ResumableUploadRequestFailureHandler(
        responses: V1ApiResponseFactory,
        traceContexts: ObjectProvider<TraceContextProvider>,
    ): V1ResumableUploadRequestFailureHandler = V1ResumableUploadRequestFailureHandler(
        responses = responses,
        traceContextProvider = traceContexts.getIfUnique(),
    )
}
