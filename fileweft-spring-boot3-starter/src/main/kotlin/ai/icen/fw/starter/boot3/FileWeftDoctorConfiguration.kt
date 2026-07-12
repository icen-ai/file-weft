package ai.icen.fw.starter.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.doctor.DocumentDoctorQueryService
import ai.icen.fw.application.doctor.DocumentDoctorTaskQueryRepository
import ai.icen.fw.application.doctor.DocumentDoctorTaskQueryService
import ai.icen.fw.application.doctor.DoctorApplicationService
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentCatalogDoctorService
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentDoctorService
import ai.icen.fw.application.doctor.SystemDoctorService
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetMutationRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentDoctorTaskQueryRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

/** Formal Doctor application boundaries, kept separate from the legacy diagnostic bean surface. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
class FileWeftDoctorConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentDoctorTaskQueryRepository::class)
    fun documentDoctorTaskQueries(objectMapper: ObjectMapper): DocumentDoctorTaskQueryRepository =
        JdbcDocumentDoctorTaskQueryRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(DocumentDoctorQueryService::class)
    fun documentDoctorQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        documents: DocumentQueryRepository,
        transaction: ApplicationTransaction,
        doctor: DoctorApplicationService,
        folderAccesses: ObjectProvider<DocumentFolderReadAccess>,
    ) = DocumentDoctorQueryService(
        tenants,
        users,
        authorization,
        documents,
        transaction,
        doctor,
        singleSecurityCandidateOrNull(folderAccesses, DocumentFolderReadAccess::class.java),
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDoctorTaskQueryService::class)
    fun documentDoctorTaskQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentDoctorTaskQueryRepository,
        transaction: ApplicationTransaction,
        folderAccesses: ObjectProvider<DocumentFolderReadAccess>,
    ) = DocumentDoctorTaskQueryService(
        tenants,
        users,
        authorization,
        queries,
        transaction,
        singleSecurityCandidateOrNull(folderAccesses, DocumentFolderReadAccess::class.java),
    )

    @Bean
    @ConditionalOnMissingBean(SystemDoctorService::class)
    fun systemDoctorService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        doctor: DoctorApplicationService,
    ) = SystemDoctorService(tenants, users, authorization, doctor)

    @Bean
    @ConditionalOnMissingBean(
        value = [
            DocumentCatalogAccessService::class,
            IdempotentScheduleDocumentDoctorService::class,
            IdempotentScheduleDocumentCatalogDoctorService::class,
        ],
    )
    fun idempotentScheduleDocumentDoctorService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        documents: DocumentRepository,
        tasks: TaskRepository,
        identifiers: IdentifierGenerator,
        clock: Clock,
        idempotency: RequestIdempotencyService,
        auditTrail: AuditTrail,
    ) = IdempotentScheduleDocumentDoctorService(
        tenants,
        users,
        authorization,
        documents,
        tasks,
        identifiers,
        clock,
        idempotency,
        auditTrail,
    )

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(
        value = [IdempotentScheduleDocumentDoctorService::class, IdempotentScheduleDocumentCatalogDoctorService::class],
    )
    fun idempotentScheduleDocumentCatalogDoctorService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        documents: DocumentRepository,
        assets: FileAssetRepository,
        tasks: TaskRepository,
        identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction,
        clock: Clock,
        idempotency: RequestIdempotencyService,
        auditTrail: AuditTrail,
        catalogAccesses: ObjectProvider<DocumentCatalogAccessService>,
    ): IdempotentScheduleDocumentCatalogDoctorService? {
        val catalogAccess = requiredSecurityCandidate(catalogAccesses, DocumentCatalogAccessService::class.java)
        return if (assets is FileAssetMutationRepository) {
            IdempotentScheduleDocumentCatalogDoctorService(
                tenants,
                users,
                authorization,
                documents,
                assets,
                tasks,
                identifiers,
                transaction,
                clock,
                idempotency,
                auditTrail,
                catalogAccess,
            )
        } else {
            null
        }
    }

    private fun <T : Any> singleSecurityCandidateOrNull(
        provider: ObjectProvider<T>,
        type: Class<T>,
    ): T? {
        val candidates = provider.orderedStream().iterator().asSequence().toList()
        require(candidates.size <= 1) {
            "More than one ${type.simpleName} is available; register exactly one security boundary."
        }
        return candidates.singleOrNull()
    }

    private fun <T : Any> requiredSecurityCandidate(
        provider: ObjectProvider<T>,
        type: Class<T>,
    ): T {
        val candidates = provider.orderedStream().iterator().asSequence().toList()
        require(candidates.size == 1) {
            "Exactly one ${type.simpleName} is required for the catalog-aware Doctor boundary."
        }
        return candidates.single()
    }
}
