package ai.icen.fw.starter.boot3

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
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.doctor.*
import ai.icen.fw.application.delivery.*
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import ai.icen.fw.application.lifecycle.IdempotentDocumentLifecycleService
import ai.icen.fw.application.metadata.DocumentMetadataService
import ai.icen.fw.application.metadata.DocumentMetadataWriteService
import ai.icen.fw.application.metadata.MetadataSchemaQueryService
import ai.icen.fw.application.offline.OfflineDocumentService
import ai.icen.fw.application.offline.RestoreOfflineDocumentService
import ai.icen.fw.application.outbox.*
import ai.icen.fw.application.publish.PublishDocumentService
import ai.icen.fw.application.sync.*
import ai.icen.fw.application.task.*
import ai.icen.fw.application.transaction.ApplicationTransaction
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
import ai.icen.fw.persistence.jdbc.*
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.task.FileWeftTaskHandler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
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
    fun transaction(dataSource: DataSource): ApplicationTransaction = factories.transaction(dataSource)

    @Bean
    @ConditionalOnMissingBean(DocumentRepository::class)
    fun documents(clock: Clock): DocumentRepository = factories.documents(clock)

    @Bean
    @ConditionalOnMissingBean(DocumentQueryRepository::class)
    fun documentQueries(): DocumentQueryRepository = factories.documentQueries()

    @Bean
    @ConditionalOnMissingBean(DocumentAuditLogQueryRepository::class)
    fun documentAuditLogQueries(): DocumentAuditLogQueryRepository = factories.documentAuditLogQueries()

    @Bean
    @ConditionalOnMissingBean(FileObjectRepository::class)
    fun fileObjects(clock: Clock): FileObjectRepository = factories.fileObjects(clock)

    @Bean
    @ConditionalOnMissingBean(FileAssetRepository::class)
    fun fileAssets(objectMapper: ObjectMapper, clock: Clock): FileAssetRepository = factories.fileAssets(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(AuditRecordRepository::class)
    fun audits(objectMapper: ObjectMapper): AuditRecordRepository = factories.audits(objectMapper)

    @Bean
    @ConditionalOnMissingBean(OperationLogRepository::class)
    fun operationLogs(objectMapper: ObjectMapper): OperationLogRepository = factories.operationLogs(objectMapper)

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository::class)
    fun outboxEvents(objectMapper: ObjectMapper, traces: TraceContextProvider): OutboxEventRepository = factories.outboxEvents(objectMapper, traces)

    @Bean
    @ConditionalOnMissingBean(OutboxProcessingRepository::class)
    fun outboxProcessing(objectMapper: ObjectMapper): JdbcOutboxProcessingRepository = factories.outboxProcessing(objectMapper)

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
    fun outboxBacklogReader(properties: FileWeftProperties): OutboxBacklogReader = factories.outboxBacklogReader(properties)

    @Bean
    @ConditionalOnProperty(
        prefix = "fileweft.outbox",
        name = ["backlog-metrics-enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnMissingBean(OutboxBacklogMetricsPublisher::class)
    fun outboxBacklogMetricsPublisher(
        transaction: ApplicationTransaction,
        reader: ObjectProvider<OutboxBacklogReader>,
        gauges: ObjectProvider<FileWeftGaugeRecorder>,
        clock: Clock,
        properties: FileWeftProperties,
    ): OutboxBacklogMetricsPublisher = factories.outboxBacklogMetricsPublisher(transaction, reader, gauges, clock, properties)

    @Bean
    @ConditionalOnMissingBean(value = [TaskRepository::class, TaskProcessingRepository::class])
    fun tasks(objectMapper: ObjectMapper, clock: Clock): JdbcTaskRepository = factories.tasks(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(RequestIdempotencyRepository::class)
    fun requestIdempotencyRepository(): RequestIdempotencyRepository = factories.requestIdempotencyRepository()

    @Bean
    @ConditionalOnMissingBean(RequestIdempotencyService::class)
    fun requestIdempotencyService(
        repository: RequestIdempotencyRepository,
        transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): RequestIdempotencyService = factories.requestIdempotencyService(repository, transaction, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(MetadataSchemaResolver::class)
    fun metadataSchemaRegistry(
        schemas: ObjectProvider<MetadataSchema>,
        historicalSchemas: ObjectProvider<HistoricalMetadataSchema>,
    ): MetadataSchemaRegistry = factories.metadataSchemaRegistryWithHistory(schemas, historicalSchemas)

    @Bean
    @ConditionalOnMissingBean(MetadataProcessor::class)
    fun metadataProcessor(schemas: MetadataSchemaResolver): MetadataProcessor = factories.metadataProcessor(schemas)

    @Bean
    @ConditionalOnMissingBean(MetadataSchemaQueryService::class)
    fun metadataSchemaQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        schemas: MetadataSchemaResolver,
    ): MetadataSchemaQueryService = factories.metadataSchemaQueryService(tenants, users, authorization, schemas)

    @Bean
    @ConditionalOnMissingBean(DocumentMetadataService::class)
    fun documentMetadataService(
        tenants: TenantProvider,
        schemas: MetadataSchemaResolver,
        processor: MetadataProcessor,
    ): DocumentMetadataService = factories.documentMetadataService(tenants, schemas, processor)

    @Bean
    @ConditionalOnMissingBean(DocumentMetadataWriteService::class)
    fun documentMetadataWriteService(
        drafts: DocumentDraftService,
        metadata: DocumentMetadataService,
        catalogDrafts: ObjectProvider<DocumentCatalogDraftService>,
        catalogMutations: ObjectProvider<DocumentCatalogMutationService>,
    ): DocumentMetadataWriteService =
        factories.documentMetadataWriteService(drafts, metadata, catalogDrafts, catalogMutations)

    @Bean(name = ["documentCatalogAccessService"])
    @ConditionalOnBean(DocumentCatalogProvider::class)
    @ConditionalOnMissingBean(DocumentCatalogAccessService::class)
    fun documentCatalogAccessServiceFromCandidates(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalogs: ObjectProvider<DocumentCatalogProvider>,
    ) = factories.documentCatalogAccessServiceFromCandidates(tenants, users, authorization, catalogs)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogBindingService::class)
    // Nullable by design: keep the existing FileAssetRepository factory ABI while
    // withholding unsafe catalog writes from custom repositories without a real lock.
    fun documentCatalogBindingService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        catalogAccess: DocumentCatalogAccessService,
        documents: DocumentRepository,
        assets: FileAssetRepository,
        transaction: ApplicationTransaction,
        auditTrail: AuditTrail,
    ): DocumentCatalogBindingService? = factories.documentCatalogBindingService(tenants, users, catalogAccess, documents, assets, transaction, auditTrail)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogDraftService::class)
    fun documentCatalogDraftService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
    ) = factories.documentCatalogDraftService(drafts, catalogAccess)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogMutationService::class)
    // See the binding service above: read/create remain available, mutations fail closed.
    fun documentCatalogMutationService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
        assets: FileAssetRepository,
    ): DocumentCatalogMutationService? = factories.documentCatalogMutationService(drafts, catalogAccess, assets)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogLifecycleService::class)
    // Keep lifecycle changes behind the same catalog binding lock required by
    // guarded draft mutations; never downgrade to an ordinary asset read.
    fun documentCatalogLifecycleService(
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
    ): DocumentCatalogLifecycleService? = factories.documentCatalogLifecycleService(commands, workflows, publish, offline, restore, archive, catalogAccesses, documents, assets, transaction)

    @Bean
    @ConditionalOnMissingBean(
        value = [
            DocumentCatalogAccessService::class,
            IdempotentDocumentLifecycleService::class,
            IdempotentDocumentCatalogLifecycleService::class,
        ],
    )
    fun idempotentDocumentLifecycleService(
        commands: DocumentCommandService,
        publish: PublishDocumentService,
        offline: OfflineDocumentService,
        restore: RestoreOfflineDocumentService,
        archive: ArchiveDocumentService,
        idempotency: RequestIdempotencyService,
    ) = factories.idempotentDocumentLifecycleService(commands, publish, offline, restore, archive, idempotency)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(
        value = [IdempotentDocumentLifecycleService::class, IdempotentDocumentCatalogLifecycleService::class],
    )
    fun idempotentDocumentCatalogLifecycleService(
        catalogLifecycles: ObjectProvider<DocumentCatalogLifecycleService>,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentCatalogLifecycleService? = factories.idempotentDocumentCatalogLifecycleService(catalogLifecycles, idempotency)

    @Bean
    @ConditionalOnMissingBean(AuditTrail::class)
    fun auditTrail(
        repository: AuditRecordRepository,
        operationLogs: OperationLogRepository,
        traceContextProvider: TraceContextProvider,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ) = factories.auditTrail(repository, operationLogs, traceContextProvider, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(DocumentQueryService::class)
    fun documentQueryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        queries: DocumentQueryRepository, transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.documentQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean
    @ConditionalOnMissingBean(DocumentAuditLogQueryService::class)
    fun documentAuditLogQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentAuditLogQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.documentAuditLogQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean
    @ConditionalOnMissingBean(DocumentCommandService::class)
    fun documentCommands(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = factories.documentCommands(tenants, users, authorization, documents, transaction, auditTrail)

    @Bean
    @ConditionalOnBean(DocumentFolderReadAccess::class)
    @ConditionalOnMissingBean(DocumentDownloadVisibility::class)
    fun documentDownloadVisibility(
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
        queries: DocumentQueryRepository,
    ): DocumentDownloadVisibility = factories.documentDownloadVisibility(folderReadAccess, queries)

    @Bean
    @ConditionalOnMissingBean(DocumentDownloadService::class)
    fun documentDownloads(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
        visibility: ObjectProvider<DocumentDownloadVisibility>,
    ): DocumentDownloadService = factories.documentDownloads(tenants, users, authorization, documents, fileObjects, storage, transaction, auditTrail, visibility)

    @Bean
    @ConditionalOnMissingBean(DocumentDraftService::class)
    fun documentDraftService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        documents: DocumentRepository, fileObjects: FileObjectRepository, assets: FileAssetRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ) = factories.documentDraftService(tenants, users, authorization, storage, documents, fileObjects, assets, identifiers, transaction, auditTrail, metrics)

    @Bean
    @ConditionalOnMissingBean(PublishDocumentService::class)
    fun publishService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = factories.publishService(tenants, users, authorization, documents, workflows, planner, transaction, auditTrail)

    @Bean
    @ConditionalOnMissingBean(OfflineDocumentService::class)
    fun offlineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ) = factories.offlineService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    @Bean
    @ConditionalOnMissingBean(RestoreOfflineDocumentService::class)
    fun restoreOfflineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = factories.restoreOfflineService(tenants, users, authorization, documents, deliveries, transaction, auditTrail)

    @Bean
    @ConditionalOnMissingBean(ArchiveDocumentService::class)
    fun archiveService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ) = factories.archiveService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    @Bean
    @ConditionalOnBean(TaskProcessingRepository::class)
    @ConditionalOnMissingBean(TaskWorker::class)
    fun taskWorker(
        repository: TaskProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<FileWeftTaskHandler>, plugins: FileWeftPluginRegistry,
        clock: Clock, properties: FileWeftProperties, metrics: FileWeftMetrics,
    ) = factories.taskWorker(repository, transaction, handlers, plugins, clock, properties, metrics)
}
