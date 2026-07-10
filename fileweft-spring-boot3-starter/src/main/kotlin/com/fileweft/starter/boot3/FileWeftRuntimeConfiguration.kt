package com.fileweft.starter.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.doctor.*
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.outbox.*
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.sync.*
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.upload.UploadApplicationService
import com.fileweft.application.workflow.DocumentReviewWorkflowService
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.persistence.jdbc.*
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
    fun transaction(dataSource: DataSource): ApplicationTransaction = JdbcApplicationTransaction(dataSource)

    @Bean
    @ConditionalOnMissingBean(DocumentRepository::class)
    fun documents(clock: Clock): DocumentRepository = JdbcDocumentRepository(clock)

    @Bean
    @ConditionalOnMissingBean(FileObjectRepository::class)
    fun fileObjects(clock: Clock): FileObjectRepository = JdbcFileObjectRepository(clock)

    @Bean
    @ConditionalOnMissingBean(FileAssetRepository::class)
    fun fileAssets(objectMapper: ObjectMapper, clock: Clock): FileAssetRepository = JdbcFileAssetRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(WorkflowInstanceRepository::class)
    fun workflows(clock: Clock): WorkflowInstanceRepository = JdbcWorkflowInstanceRepository(clock)

    @Bean
    @ConditionalOnMissingBean(AuditRecordRepository::class)
    fun audits(objectMapper: ObjectMapper): AuditRecordRepository = JdbcAuditRecordRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(SyncRecordRepository::class)
    fun syncRecords(clock: Clock): SyncRecordRepository = JdbcSyncRecordRepository(clock)

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository::class)
    fun outboxEvents(objectMapper: ObjectMapper): OutboxEventRepository = JdbcOutboxEventRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(OutboxProcessingRepository::class)
    fun outboxProcessing(objectMapper: ObjectMapper): OutboxProcessingRepository = JdbcOutboxProcessingRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(AuditTrail::class)
    fun auditTrail(repository: AuditRecordRepository, identifiers: IdentifierGenerator, clock: Clock) = AuditTrail(repository, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(UploadApplicationService::class)
    fun uploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, metrics: FileWeftMetrics,
    ) = UploadApplicationService(tenants, users, authorization, storage, fileObjects, assets, outbox, identifiers, transaction, clock, metrics)

    @Bean
    @ConditionalOnMissingBean(DocumentCommandService::class)
    fun documentCommands(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction,
    ) = DocumentCommandService(tenants, users, authorization, documents, transaction)

    @Bean
    @ConditionalOnMissingBean(DocumentDraftService::class)
    fun documentDraftService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        documents: DocumentRepository, fileObjects: FileObjectRepository, assets: FileAssetRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ) = DocumentDraftService(
        tenants, users, authorization, storage, documents, fileObjects, assets, identifiers, transaction, auditTrail, metrics,
    )

    @Bean
    @ConditionalOnMissingBean(PublishDocumentService::class)
    fun publishService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, outbox: OutboxEventRepository, identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction, clock: Clock,
    ) = PublishDocumentService(tenants, users, authorization, documents, outbox, identifiers, transaction, clock)

    @Bean
    @ConditionalOnMissingBean(OfflineDocumentService::class)
    fun offlineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction,
    ) = OfflineDocumentService(tenants, users, authorization, documents, transaction)

    @Bean
    @ConditionalOnMissingBean(ArchiveDocumentService::class)
    fun archiveService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction,
    ) = ArchiveDocumentService(tenants, users, authorization, documents, transaction)

    @Bean
    @ConditionalOnMissingBean(DocumentReviewWorkflowService::class)
    fun reviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock,
    ) = DocumentReviewWorkflowService(tenants, users, authorization, documents, workflows, outbox, identifiers, transaction, clock)

    @Bean
    @ConditionalOnMissingBean(PermissionDoctorChecker::class)
    fun permissionDoctor(users: UserRealmProvider, authorization: AuthorizationProvider) = PermissionDoctorChecker(users, authorization)

    @Bean
    @ConditionalOnMissingBean(LifecycleDoctorChecker::class)
    fun lifecycleDoctor(documents: DocumentRepository) = LifecycleDoctorChecker(documents)

    @Bean
    @ConditionalOnMissingBean(StorageDoctorChecker::class)
    fun storageDoctor(documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter) =
        StorageDoctorChecker(documents, fileObjects, storage)

    @Bean
    @ConditionalOnMissingBean(ConnectorDoctorChecker::class)
    fun connectorDoctor(connectors: List<FileConnector>) = ConnectorDoctorChecker(connectors)

    @Bean
    @ConditionalOnMissingBean(DoctorApplicationService::class)
    fun doctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, connector: ConnectorDoctorChecker, clock: Clock, metrics: FileWeftMetrics,
    ) = DoctorApplicationService(
        tenants, permission,
        listOf<DoctorChecker>(
            lifecycle, storage, connector,
            UnavailableDoctorChecker("agent", "No agent runtime checker is configured.", "Register an agent DoctorChecker when an agent runtime is enabled."),
        ),
        clock,
        metrics,
    )

    @Bean
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentSyncService::class)
    fun documentSyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connector: FileConnector, properties: FileWeftProperties, records: SyncRecordRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ) = DocumentSyncService(
        documents, fileObjects, storage, connector, properties.sync.connectorName, records, identifiers, transaction,
        auditTrail = auditTrail,
        metrics = metrics,
    )

    @Bean
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentPublishOutboxEventHandler::class)
    fun documentPublishHandler(sync: DocumentSyncService) = DocumentPublishOutboxEventHandler(sync)

    @Bean
    @ConditionalOnMissingBean(OutboxWorker::class)
    fun outboxWorker(
        repository: OutboxProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<OutboxEventHandler>, clock: Clock,
    ) = OutboxWorker(repository, transaction, handlers, clock)
}
