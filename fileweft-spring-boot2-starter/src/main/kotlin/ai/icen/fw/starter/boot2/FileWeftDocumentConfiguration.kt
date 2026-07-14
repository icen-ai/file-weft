package ai.icen.fw.starter.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.archive.ArchiveDocumentService
import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.audit.DocumentAuditLogQueryRepository
import ai.icen.fw.application.audit.DocumentAuditLogQueryService
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogBindingService
import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService
import ai.icen.fw.application.catalog.DocumentCatalogMutationService
import ai.icen.fw.application.document.DocumentCommandService
import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.application.document.DocumentDownloadVisibility
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryTargetRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import ai.icen.fw.application.lifecycle.IdempotentDocumentLifecycleService
import ai.icen.fw.application.metadata.DocumentMetadataService
import ai.icen.fw.application.metadata.DocumentMetadataWriteService
import ai.icen.fw.application.metadata.MetadataSchemaQueryService
import ai.icen.fw.application.offline.OfflineDocumentService
import ai.icen.fw.application.offline.RestoreOfflineDocumentService
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.outbox.OutboxBacklogMetricsPublisher
import ai.icen.fw.application.outbox.OutboxBacklogReader
import ai.icen.fw.application.outbox.OutboxProcessingRepository
import ai.icen.fw.application.publish.PublishDocumentService
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.task.TaskProcessingRepository
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.task.TaskWorker
import ai.icen.fw.application.workflow.DocumentReviewWorkflowService
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.domain.operation.OperationLogRepository
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.metadata.runtime.MetadataSchemaRegistry
import ai.icen.fw.metadata.runtime.HistoricalMetadataSchema
import ai.icen.fw.persistence.jdbc.JdbcOutboxProcessingRepository
import ai.icen.fw.persistence.jdbc.JdbcTaskRepository
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.task.FileWeftTaskHandler
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
class FileWeftDocumentConfiguration {
    private val factories = FileWeftRuntimeFactories()

    @Bean
    @ConditionalOnMissingBean(ApplicationTransaction::class)
    fun fileWeftTransaction(dataSource: DataSource): ApplicationTransaction = factories.fileWeftTransaction(dataSource)

    @Bean
    @ConditionalOnMissingBean(DocumentRepository::class)
    fun fileWeftDocumentRepository(clock: Clock): DocumentRepository = factories.fileWeftDocumentRepository(clock)

    @Bean
    @ConditionalOnMissingBean(DocumentQueryRepository::class)
    fun fileWeftDocumentQueryRepository(): DocumentQueryRepository = factories.fileWeftDocumentQueryRepository()

    @Bean
    @ConditionalOnMissingBean(DocumentAuditLogQueryRepository::class)
    fun fileWeftDocumentAuditLogQueryRepository(): DocumentAuditLogQueryRepository = factories.fileWeftDocumentAuditLogQueryRepository()

    @Bean
    @ConditionalOnMissingBean(FileObjectRepository::class)
    fun fileWeftFileObjectRepository(clock: Clock): FileObjectRepository = factories.fileWeftFileObjectRepository(clock)

    @Bean
    @ConditionalOnMissingBean(FileAssetRepository::class)
    fun fileWeftFileAssetRepository(objectMapper: ObjectMapper, clock: Clock): FileAssetRepository = factories.fileWeftFileAssetRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(AuditRecordRepository::class)
    fun fileWeftAuditRepository(objectMapper: ObjectMapper): AuditRecordRepository = factories.fileWeftAuditRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(OperationLogRepository::class)
    fun fileWeftOperationLogRepository(objectMapper: ObjectMapper): OperationLogRepository = factories.fileWeftOperationLogRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository::class)
    fun fileWeftOutboxEventRepository(objectMapper: ObjectMapper, traces: TraceContextProvider): OutboxEventRepository = factories.fileWeftOutboxEventRepository(objectMapper, traces)

    @Bean
    @ConditionalOnMissingBean(OutboxProcessingRepository::class)
    fun fileWeftOutboxProcessingRepository(objectMapper: ObjectMapper): JdbcOutboxProcessingRepository = factories.fileWeftOutboxProcessingRepository(objectMapper)

    /**
     * Global backlog inspection is deliberately separate from processing so customers can
     * replace the query with a partition-aware operational projection without changing workers.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "fileweft.outbox",
        name = ["backlog-metrics-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnMissingBean(OutboxBacklogReader::class)
    fun fileWeftOutboxBacklogReader(properties: FileWeftProperties): OutboxBacklogReader = factories.fileWeftOutboxBacklogReader(properties)

    @Bean
    @ConditionalOnProperty(
        prefix = "fileweft.outbox",
        name = ["backlog-metrics-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnMissingBean(OutboxBacklogMetricsPublisher::class)
    fun fileWeftOutboxBacklogMetricsPublisher(
        transaction: ApplicationTransaction,
        reader: ObjectProvider<OutboxBacklogReader>,
        gauges: ObjectProvider<FileWeftGaugeRecorder>,
        clock: Clock,
        properties: FileWeftProperties,
    ): OutboxBacklogMetricsPublisher = factories.fileWeftOutboxBacklogMetricsPublisher(transaction, reader, gauges, clock, properties)

    @Bean
    @ConditionalOnMissingBean(value = [TaskRepository::class, TaskProcessingRepository::class])
    fun fileWeftTaskRepository(objectMapper: ObjectMapper, clock: Clock): JdbcTaskRepository = factories.fileWeftTaskRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(RequestIdempotencyRepository::class)
    fun fileWeftRequestIdempotencyRepository(): RequestIdempotencyRepository = factories.fileWeftRequestIdempotencyRepository()

    @Bean
    @ConditionalOnMissingBean(RequestIdempotencyService::class)
    fun fileWeftRequestIdempotencyService(
        repository: RequestIdempotencyRepository,
        transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): RequestIdempotencyService = factories.fileWeftRequestIdempotencyService(repository, transaction, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(MetadataSchemaResolver::class)
    fun fileWeftMetadataSchemaRegistry(
        schemas: ObjectProvider<MetadataSchema>,
        historicalSchemas: ObjectProvider<HistoricalMetadataSchema>,
    ): MetadataSchemaRegistry = factories.fileWeftMetadataSchemaRegistryWithHistory(schemas, historicalSchemas)

    @Bean
    @ConditionalOnMissingBean(MetadataProcessor::class)
    fun fileWeftMetadataProcessor(schemas: MetadataSchemaResolver): MetadataProcessor = factories.fileWeftMetadataProcessor(schemas)

    @Bean
    @ConditionalOnMissingBean(MetadataSchemaQueryService::class)
    fun fileWeftMetadataSchemaQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        schemas: MetadataSchemaResolver,
    ): MetadataSchemaQueryService =
        factories.fileWeftMetadataSchemaQueryService(tenants, users, authorization, schemas)

    @Bean
    @ConditionalOnMissingBean(DocumentMetadataService::class)
    fun fileWeftDocumentMetadataService(
        tenants: TenantProvider,
        schemas: MetadataSchemaResolver,
        processor: MetadataProcessor,
    ): DocumentMetadataService = factories.fileWeftDocumentMetadataService(tenants, schemas, processor)

    @Bean
    @ConditionalOnMissingBean(DocumentMetadataWriteService::class)
    fun fileWeftDocumentMetadataWriteService(
        drafts: DocumentDraftService,
        metadata: DocumentMetadataService,
        catalogDrafts: ObjectProvider<DocumentCatalogDraftService>,
        catalogMutations: ObjectProvider<DocumentCatalogMutationService>,
    ): DocumentMetadataWriteService =
        factories.fileWeftDocumentMetadataWriteService(drafts, metadata, catalogDrafts, catalogMutations)

    @Bean(name = ["fileWeftDocumentCatalogAccessService"])
    @ConditionalOnBean(DocumentCatalogProvider::class)
    @ConditionalOnMissingBean(DocumentCatalogAccessService::class)
    fun fileWeftDocumentCatalogAccessServiceFromCandidates(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalogs: ObjectProvider<DocumentCatalogProvider>,
    ): DocumentCatalogAccessService = factories.fileWeftDocumentCatalogAccessServiceFromCandidates(tenants, users, authorization, catalogs)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogBindingService::class)
    // Nullable by design: keep the existing FileAssetRepository factory ABI while
    // withholding unsafe catalog writes from custom repositories without a real lock.
    fun fileWeftDocumentCatalogBindingService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        catalogAccess: DocumentCatalogAccessService,
        documents: DocumentRepository,
        assets: FileAssetRepository,
        transaction: ApplicationTransaction,
        auditTrail: AuditTrail,
    ): DocumentCatalogBindingService? = factories.fileWeftDocumentCatalogBindingService(tenants, users, catalogAccess, documents, assets, transaction, auditTrail)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogDraftService::class)
    fun fileWeftDocumentCatalogDraftService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
    ): DocumentCatalogDraftService = factories.fileWeftDocumentCatalogDraftService(drafts, catalogAccess)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogMutationService::class)
    // See the binding service above: read/create remain available, mutations fail closed.
    fun fileWeftDocumentCatalogMutationService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
        assets: FileAssetRepository,
    ): DocumentCatalogMutationService? = factories.fileWeftDocumentCatalogMutationService(drafts, catalogAccess, assets)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogLifecycleService::class)
    // Lifecycle commands must preserve the same locked catalog binding used by
    // draft mutations; a plain FileAssetRepository cannot provide that proof.
    fun fileWeftDocumentCatalogLifecycleService(
        commands: DocumentCommandService,
        workflows: DocumentReviewWorkflowService,
        publish: PublishDocumentService,
        offline: OfflineDocumentService,
        restore: RestoreOfflineDocumentService,
        archive: ArchiveDocumentService,
        catalogAccesses: ObjectProvider<DocumentCatalogAccessService>,
        documents: DocumentRepository,
        assets: FileAssetRepository,
        transaction: ApplicationTransaction,
    ): DocumentCatalogLifecycleService? = factories.fileWeftDocumentCatalogLifecycleService(commands, workflows, publish, offline, restore, archive, catalogAccesses, documents, assets, transaction)

    @Bean
    @ConditionalOnMissingBean(
        value = [
            DocumentCatalogAccessService::class,
            IdempotentDocumentLifecycleService::class,
            IdempotentDocumentCatalogLifecycleService::class,
        ],
    )
    fun fileWeftIdempotentDocumentLifecycleService(
        commands: DocumentCommandService,
        publish: PublishDocumentService,
        offline: OfflineDocumentService,
        restore: RestoreOfflineDocumentService,
        archive: ArchiveDocumentService,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentLifecycleService = factories.fileWeftIdempotentDocumentLifecycleService(commands, publish, offline, restore, archive, idempotency)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(
        value = [IdempotentDocumentLifecycleService::class, IdempotentDocumentCatalogLifecycleService::class],
    )
    fun fileWeftIdempotentDocumentCatalogLifecycleService(
        catalogLifecycles: ObjectProvider<DocumentCatalogLifecycleService>,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentCatalogLifecycleService? = factories.fileWeftIdempotentDocumentCatalogLifecycleService(catalogLifecycles, idempotency)

    @Bean
    @ConditionalOnMissingBean(AuditTrail::class)
    fun fileWeftAuditTrail(
        repository: AuditRecordRepository,
        operationLogs: OperationLogRepository,
        traceContextProvider: TraceContextProvider,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): AuditTrail = factories.fileWeftAuditTrail(repository, operationLogs, traceContextProvider, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(DocumentQueryService::class)
    fun fileWeftDocumentQueryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        queries: DocumentQueryRepository, transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): DocumentQueryService = factories.fileWeftDocumentQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean
    @ConditionalOnMissingBean(DocumentAuditLogQueryService::class)
    fun fileWeftDocumentAuditLogQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentAuditLogQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): DocumentAuditLogQueryService = factories.fileWeftDocumentAuditLogQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean
    @ConditionalOnMissingBean(DocumentCommandService::class)
    fun fileWeftDocumentCommandService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): DocumentCommandService = factories.fileWeftDocumentCommandService(tenants, users, authorization, documents, transaction, auditTrail)

    @Bean
    @ConditionalOnBean(DocumentFolderReadAccess::class)
    @ConditionalOnMissingBean(DocumentDownloadVisibility::class)
    fun fileWeftDocumentDownloadVisibility(
        folderReadAccesses: ObjectProvider<DocumentFolderReadAccess>,
        queries: DocumentQueryRepository,
    ): DocumentDownloadVisibility = factories.fileWeftDocumentDownloadVisibility(folderReadAccesses, queries)

    @Bean
    @ConditionalOnMissingBean(DocumentDownloadService::class)
    fun fileWeftDocumentDownloadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
        visibility: ObjectProvider<DocumentDownloadVisibility>,
    ): DocumentDownloadService = factories.fileWeftDocumentDownloadService(tenants, users, authorization, documents, fileObjects, storage, transaction, auditTrail, visibility)

    @Bean
    @ConditionalOnMissingBean(DocumentDraftService::class)
    fun fileWeftDocumentDraftService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        documents: DocumentRepository, fileObjects: FileObjectRepository, assets: FileAssetRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ): DocumentDraftService = factories.fileWeftDocumentDraftService(tenants, users, authorization, storage, documents, fileObjects, assets, identifiers, transaction, auditTrail, metrics)

    @Bean
    @ConditionalOnMissingBean(PublishDocumentService::class)
    fun fileWeftPublishService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): PublishDocumentService = factories.fileWeftPublishService(tenants, users, authorization, documents, workflows, planner, transaction, auditTrail)

    @Bean
    @ConditionalOnMissingBean(OfflineDocumentService::class)
    fun fileWeftOfflineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ): OfflineDocumentService = factories.fileWeftOfflineService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    @Bean
    @ConditionalOnMissingBean(RestoreOfflineDocumentService::class)
    fun fileWeftRestoreOfflineDocumentService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): RestoreOfflineDocumentService = factories.fileWeftRestoreOfflineDocumentService(tenants, users, authorization, documents, deliveries, transaction, auditTrail)

    @Bean
    @ConditionalOnMissingBean(ArchiveDocumentService::class)
    fun fileWeftArchiveService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ): ArchiveDocumentService = factories.fileWeftArchiveService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    @Bean
    @ConditionalOnBean(TaskProcessingRepository::class)
    @ConditionalOnMissingBean(TaskWorker::class)
    fun fileWeftTaskWorker(
        repository: TaskProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<FileWeftTaskHandler>, plugins: FileWeftPluginRegistry,
        clock: Clock, properties: FileWeftProperties, metrics: FileWeftMetrics,
    ): TaskWorker = factories.fileWeftTaskWorker(repository, transaction, handlers, plugins, clock, properties, metrics)
}
