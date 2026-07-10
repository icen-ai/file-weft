package com.fileweft.starter.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.doctor.ConnectorDoctorChecker
import com.fileweft.application.doctor.DoctorApplicationService
import com.fileweft.application.doctor.LifecycleDoctorChecker
import com.fileweft.application.doctor.PermissionDoctorChecker
import com.fileweft.application.doctor.StorageDoctorChecker
import com.fileweft.application.doctor.UnavailableDoctorChecker
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.outbox.OutboxProcessingRepository
import com.fileweft.application.outbox.OutboxWorker
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.sync.DocumentPublishOutboxEventHandler
import com.fileweft.application.sync.DocumentSyncService
import com.fileweft.application.sync.SyncRecordRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.upload.UploadApplicationService
import com.fileweft.application.workflow.DocumentReviewWorkflowService
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.persistence.jdbc.JdbcApplicationTransaction
import com.fileweft.persistence.jdbc.JdbcAuditRecordRepository
import com.fileweft.persistence.jdbc.JdbcDocumentRepository
import com.fileweft.persistence.jdbc.JdbcFileAssetRepository
import com.fileweft.persistence.jdbc.JdbcFileObjectRepository
import com.fileweft.persistence.jdbc.JdbcOutboxEventRepository
import com.fileweft.persistence.jdbc.JdbcOutboxProcessingRepository
import com.fileweft.persistence.jdbc.JdbcSyncRecordRepository
import com.fileweft.persistence.jdbc.JdbcWorkflowInstanceRepository
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.doctor.DoctorChecker
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.tenant.TenantProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
class FileWeftRuntimeConfiguration {
    @Bean
    @ConditionalOnMissingBean(ApplicationTransaction::class)
    fun fileWeftTransaction(dataSource: DataSource): ApplicationTransaction = JdbcApplicationTransaction(dataSource)

    @Bean
    @ConditionalOnMissingBean(DocumentRepository::class)
    fun fileWeftDocumentRepository(clock: Clock): DocumentRepository = JdbcDocumentRepository(clock)

    @Bean
    @ConditionalOnMissingBean(FileObjectRepository::class)
    fun fileWeftFileObjectRepository(clock: Clock): FileObjectRepository = JdbcFileObjectRepository(clock)

    @Bean
    @ConditionalOnMissingBean(FileAssetRepository::class)
    fun fileWeftFileAssetRepository(objectMapper: ObjectMapper, clock: Clock): FileAssetRepository = JdbcFileAssetRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(WorkflowInstanceRepository::class)
    fun fileWeftWorkflowRepository(clock: Clock): WorkflowInstanceRepository = JdbcWorkflowInstanceRepository(clock)

    @Bean
    @ConditionalOnMissingBean(AuditRecordRepository::class)
    fun fileWeftAuditRepository(objectMapper: ObjectMapper): AuditRecordRepository = JdbcAuditRecordRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(SyncRecordRepository::class)
    fun fileWeftSyncRecordRepository(clock: Clock): SyncRecordRepository = JdbcSyncRecordRepository(clock)

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository::class)
    fun fileWeftOutboxEventRepository(objectMapper: ObjectMapper): OutboxEventRepository = JdbcOutboxEventRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(OutboxProcessingRepository::class)
    fun fileWeftOutboxProcessingRepository(objectMapper: ObjectMapper): OutboxProcessingRepository = JdbcOutboxProcessingRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(AuditTrail::class)
    fun fileWeftAuditTrail(repository: AuditRecordRepository, identifiers: IdentifierGenerator, clock: Clock): AuditTrail =
        AuditTrail(repository, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(UploadApplicationService::class)
    fun fileWeftUploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, metrics: FileWeftMetrics,
    ): UploadApplicationService = UploadApplicationService(
        tenants, users, authorization, storage, fileObjects, assets, outbox, identifiers, transaction, clock, metrics,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentCommandService::class)
    fun fileWeftDocumentCommandService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction,
    ): DocumentCommandService = DocumentCommandService(tenants, users, authorization, documents, transaction)

    @Bean
    @ConditionalOnMissingBean(PublishDocumentService::class)
    fun fileWeftPublishService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, outbox: OutboxEventRepository, identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction, clock: Clock,
    ): PublishDocumentService = PublishDocumentService(tenants, users, authorization, documents, outbox, identifiers, transaction, clock)

    @Bean
    @ConditionalOnMissingBean(OfflineDocumentService::class)
    fun fileWeftOfflineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction,
    ): OfflineDocumentService = OfflineDocumentService(tenants, users, authorization, documents, transaction)

    @Bean
    @ConditionalOnMissingBean(ArchiveDocumentService::class)
    fun fileWeftArchiveService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction,
    ): ArchiveDocumentService = ArchiveDocumentService(tenants, users, authorization, documents, transaction)

    @Bean
    @ConditionalOnMissingBean(DocumentReviewWorkflowService::class)
    fun fileWeftReviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock,
    ): DocumentReviewWorkflowService = DocumentReviewWorkflowService(
        tenants, users, authorization, documents, workflows, outbox, identifiers, transaction, clock,
    )

    @Bean
    @ConditionalOnMissingBean(PermissionDoctorChecker::class)
    fun fileWeftPermissionDoctorChecker(users: UserRealmProvider, authorization: AuthorizationProvider): PermissionDoctorChecker =
        PermissionDoctorChecker(users, authorization)

    @Bean
    @ConditionalOnMissingBean(LifecycleDoctorChecker::class)
    fun fileWeftLifecycleDoctorChecker(documents: DocumentRepository): LifecycleDoctorChecker = LifecycleDoctorChecker(documents)

    @Bean
    @ConditionalOnMissingBean(StorageDoctorChecker::class)
    fun fileWeftStorageDoctorChecker(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
    ): StorageDoctorChecker = StorageDoctorChecker(documents, fileObjects, storage)

    @Bean
    @ConditionalOnMissingBean(ConnectorDoctorChecker::class)
    fun fileWeftConnectorDoctorChecker(connectors: List<FileConnector>): ConnectorDoctorChecker = ConnectorDoctorChecker(connectors)

    @Bean
    @ConditionalOnMissingBean(DoctorApplicationService::class)
    fun fileWeftDoctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, connector: ConnectorDoctorChecker, clock: Clock, metrics: FileWeftMetrics,
    ): DoctorApplicationService = DoctorApplicationService(
        tenants,
        permission,
        listOf<DoctorChecker>(
            lifecycle,
            storage,
            connector,
            UnavailableDoctorChecker("agent", "No agent runtime checker is configured.", "Register an agent DoctorChecker when an agent runtime is enabled."),
        ),
        clock,
        metrics,
    )

    @Bean
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentSyncService::class)
    fun fileWeftDocumentSyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connector: FileConnector, properties: FileWeftProperties, records: SyncRecordRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ): DocumentSyncService = DocumentSyncService(
        documents, fileObjects, storage, connector, properties.sync.connectorName, records, identifiers, transaction,
        auditTrail = auditTrail,
        metrics = metrics,
    )

    @Bean
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentPublishOutboxEventHandler::class)
    fun fileWeftDocumentPublishOutboxEventHandler(sync: DocumentSyncService): DocumentPublishOutboxEventHandler =
        DocumentPublishOutboxEventHandler(sync)

    @Bean
    @ConditionalOnMissingBean(OutboxWorker::class)
    fun fileWeftOutboxWorker(
        repository: OutboxProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<OutboxEventHandler>, clock: Clock,
    ): OutboxWorker = OutboxWorker(repository, transaction, handlers, clock)
}
