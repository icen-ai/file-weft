package com.fileweft.web.spring.boot3

import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.doctor.DocumentDoctorQueryService
import com.fileweft.application.doctor.DocumentDoctorTaskQueryService
import com.fileweft.application.doctor.IdempotentScheduleDocumentCatalogDoctorService
import com.fileweft.application.doctor.IdempotentScheduleDocumentDoctorService
import com.fileweft.application.doctor.SystemDoctorService
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.doctor.DoctorApiFacade
import com.fileweft.web.spring.boot3.v1.doctor.V1DocumentDoctorController
import com.fileweft.web.spring.boot3.v1.doctor.V1SystemDoctorController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/** Optional Spring Boot 3 formal Doctor transport with fail-closed capabilities. */
@AutoConfiguration(afterName = ["com.fileweft.starter.boot3.FileWeftAutoConfiguration"])
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
