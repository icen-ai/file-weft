package ai.icen.fw.web.spring.boot3

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.doctor.DocumentDoctorQueryService
import ai.icen.fw.application.doctor.DocumentDoctorTaskQueryService
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentCatalogDoctorService
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentDoctorService
import ai.icen.fw.application.doctor.SystemDoctorService
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.doctor.DoctorApiFacade
import ai.icen.fw.web.spring.boot3.v1.doctor.V1DocumentDoctorController
import ai.icen.fw.web.spring.boot3.v1.doctor.V1SystemDoctorController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/** Optional Spring Boot 3 formal Doctor transport with fail-closed capabilities. */
@AutoConfiguration(afterName = ["ai.icen.fw.starter.boot3.FileWeftAutoConfiguration"])
@ConditionalOnClass(RestController::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class FileWeftWebBoot3DoctorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DoctorApiFacade::class)
    fun fileWeftV1DoctorApiFacade(
        catalogAccesses: ObjectProvider<DocumentCatalogAccessService>,
        documentDoctors: ObjectProvider<DocumentDoctorQueryService>,
        taskQueries: ObjectProvider<DocumentDoctorTaskQueryService>,
        flatSchedulers: ObjectProvider<IdempotentScheduleDocumentDoctorService>,
        catalogSchedulers: ObjectProvider<IdempotentScheduleDocumentCatalogDoctorService>,
        systemDoctors: ObjectProvider<SystemDoctorService>,
    ): DoctorApiFacade = DoctorApiFacade(
        catalogAccessCount = catalogAccesses.allCandidates().size,
        documentDoctors = documentDoctors.allCandidates(),
        taskQueries = taskQueries.allCandidates(),
        flatSchedulers = flatSchedulers.allCandidates(),
        catalogSchedulers = catalogSchedulers.allCandidates(),
        systemDoctors = systemDoctors.allCandidates(),
    )

    @Bean
    @ConditionalOnMissingBean(V1ApiResponseFactory::class)
    fun fileWeftV1DoctorApiResponseFactory(): V1ApiResponseFactory = V1ApiResponseFactory()

    @Bean
    @ConditionalOnMissingBean(V1DocumentDoctorController::class)
    fun fileWeftV1DocumentDoctorController(
        doctor: DoctorApiFacade,
        responses: V1ApiResponseFactory,
        traces: ObjectProvider<TraceContextProvider>,
    ): V1DocumentDoctorController = V1DocumentDoctorController(doctor, responses, traces.getIfUnique())

    @Bean
    @ConditionalOnMissingBean(V1SystemDoctorController::class)
    fun fileWeftV1SystemDoctorController(
        doctor: DoctorApiFacade,
        responses: V1ApiResponseFactory,
        traces: ObjectProvider<TraceContextProvider>,
    ): V1SystemDoctorController = V1SystemDoctorController(doctor, responses, traces.getIfUnique())

    private fun <T> ObjectProvider<T>.allCandidates(): List<T> =
        orderedStream().iterator().asSequence().toList()
}
