package ai.icen.fw.starter.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.adapter.connector.ConnectorInvocationExecutor
import ai.icen.fw.adapter.connector.ConnectorResiliencePolicy
import ai.icen.fw.adapter.connector.ConnectorResilienceRegistry
import ai.icen.fw.agent.AgentTaskHandler
import ai.icen.fw.agent.AgentDoctorChecker
import ai.icen.fw.agent.AgentTaskOrchestrator
import ai.icen.fw.agent.AgentTaskOutboxEventHandler
import ai.icen.fw.agent.AgentTaskScheduler
import ai.icen.fw.agent.PersistedAgentSuggestionConfirmationService
import ai.icen.fw.application.agent.AgentResultRepository
import ai.icen.fw.application.agent.ConfirmAgentSuggestionService
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
import ai.icen.fw.application.delivery.DocumentDeliveryOutboxEventHandler
import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalService
import ai.icen.fw.application.delivery.DocumentDeliverySyncService
import ai.icen.fw.application.delivery.DocumentDeliveryTargetRepository
import ai.icen.fw.application.delivery.DocumentDeliveryTargetMutationRepository
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryRepository
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryService
import ai.icen.fw.application.delivery.IdempotentDocumentCatalogDeliveryRecoveryService
import ai.icen.fw.application.delivery.IdempotentDocumentDeliveryRecoveryService
import ai.icen.fw.application.delivery.RetryDocumentDeliveryService
import ai.icen.fw.application.doctor.ConnectorDoctorChecker
import ai.icen.fw.application.doctor.CatalogDoctorChecker
import ai.icen.fw.application.doctor.DeliveryProfileDoctorChecker
import ai.icen.fw.application.doctor.DeploymentSafetyDoctorChecker
import ai.icen.fw.application.doctor.DoctorApplicationService
import ai.icen.fw.application.doctor.DoctorReportRepository
import ai.icen.fw.application.doctor.DocumentDoctorTaskHandler
import ai.icen.fw.application.doctor.LifecycleDoctorChecker
import ai.icen.fw.application.doctor.MetadataDoctorChecker
import ai.icen.fw.application.doctor.PermissionDoctorChecker
import ai.icen.fw.application.doctor.StorageDoctorChecker
import ai.icen.fw.application.doctor.ScheduleDocumentDoctorService
import ai.icen.fw.application.doctor.WorkflowDoctorChecker
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
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.application.outbox.OutboxBacklogMetricsPublisher
import ai.icen.fw.application.outbox.OutboxBacklogReader
import ai.icen.fw.application.outbox.OutboxProcessingRepository
import ai.icen.fw.application.outbox.OutboxWorker
import ai.icen.fw.application.publish.PublishDocumentService
import ai.icen.fw.application.sync.DocumentPublishOutboxEventHandler
import ai.icen.fw.application.sync.DocumentSyncService
import ai.icen.fw.application.sync.SyncRecordRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.task.TaskProcessingRepository
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.task.TaskMutationRepository
import ai.icen.fw.application.task.TaskWorker
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.ResumableUploadSessionRepository
import ai.icen.fw.application.upload.UploadApplicationService
import ai.icen.fw.application.workflow.DocumentReviewRouteResolver
import ai.icen.fw.application.workflow.DocumentReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentReviewWorkflowService
import ai.icen.fw.application.workflow.WorkflowQueryRepository
import ai.icen.fw.application.workflow.WorkflowQueryService
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryRepository
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryService
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
import ai.icen.fw.persistence.jdbc.JdbcDocumentDeliveryTargetRepository
import ai.icen.fw.persistence.jdbc.JdbcOutboxProcessingRepository
import ai.icen.fw.persistence.jdbc.JdbcTaskRepository
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.spi.observability.TraceContextScope
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource
import org.springframework.context.annotation.Import

internal const val FILEWEFT_COMPATIBILITY_PREFIX = "fileweft.compatibility"
internal const val LEGACY_AGENT_AUTOCONFIGURATION_ENABLED = "legacy-agent-autoconfiguration-enabled"

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
@Import(
    FileWeftDocumentConfiguration::class,
    FileWeftUploadConfiguration::class,
    FileWeftWorkflowConfiguration::class,
    FileWeftDeliveryConfiguration::class,
    FileWeftDoctorConfiguration::class,
)
class FileWeftRuntimeConfiguration {
    private val factories = FileWeftRuntimeFactories()

    fun fileWeftTransaction(dataSource: DataSource): ApplicationTransaction = factories.fileWeftTransaction(dataSource)

    fun fileWeftDocumentRepository(clock: Clock): DocumentRepository = factories.fileWeftDocumentRepository(clock)

    fun fileWeftDocumentQueryRepository(): DocumentQueryRepository = factories.fileWeftDocumentQueryRepository()

    fun fileWeftDocumentAuditLogQueryRepository(): DocumentAuditLogQueryRepository = factories.fileWeftDocumentAuditLogQueryRepository()

    fun fileWeftDocumentSyncStatusQueryRepository(): DocumentSyncStatusQueryRepository = factories.fileWeftDocumentSyncStatusQueryRepository()

    fun fileWeftWorkflowQueryRepository(): WorkflowQueryRepository = factories.fileWeftWorkflowQueryRepository()

    fun fileWeftWorkflowDecisionEvidenceQueryRepository(): WorkflowDecisionEvidenceQueryRepository = factories.fileWeftWorkflowDecisionEvidenceQueryRepository()

    fun fileWeftFileObjectRepository(clock: Clock): FileObjectRepository = factories.fileWeftFileObjectRepository(clock)

    fun fileWeftFileAssetRepository(objectMapper: ObjectMapper, clock: Clock): FileAssetRepository = factories.fileWeftFileAssetRepository(objectMapper, clock)

    fun fileWeftWorkflowRepository(clock: Clock): WorkflowInstanceRepository = factories.fileWeftWorkflowRepository(clock)

    fun fileWeftAuditRepository(objectMapper: ObjectMapper): AuditRecordRepository = factories.fileWeftAuditRepository(objectMapper)

    fun fileWeftOperationLogRepository(objectMapper: ObjectMapper): OperationLogRepository = factories.fileWeftOperationLogRepository(objectMapper)

    fun fileWeftDoctorReportRepository(objectMapper: ObjectMapper, clock: Clock): DoctorReportRepository = factories.fileWeftDoctorReportRepository(objectMapper, clock)

    fun fileWeftSyncRecordRepository(clock: Clock): SyncRecordRepository = factories.fileWeftSyncRecordRepository(clock)

    fun fileWeftDocumentDeliveryTargetRepository(clock: Clock): JdbcDocumentDeliveryTargetRepository = factories.fileWeftDocumentDeliveryTargetRepository(clock)

    fun fileWeftOutboxEventRepository(objectMapper: ObjectMapper, traces: TraceContextProvider): OutboxEventRepository = factories.fileWeftOutboxEventRepository(objectMapper, traces)

    fun fileWeftOutboxProcessingRepository(objectMapper: ObjectMapper): JdbcOutboxProcessingRepository = factories.fileWeftOutboxProcessingRepository(objectMapper)

    fun fileWeftOutboxBacklogReader(properties: FileWeftProperties): OutboxBacklogReader = factories.fileWeftOutboxBacklogReader(properties)

    fun fileWeftOutboxBacklogMetricsPublisher(
        transaction: ApplicationTransaction,
        reader: ObjectProvider<OutboxBacklogReader>,
        gauges: ObjectProvider<FileWeftGaugeRecorder>,
        clock: Clock,
        properties: FileWeftProperties,
    ): OutboxBacklogMetricsPublisher = factories.fileWeftOutboxBacklogMetricsPublisher(transaction, reader, gauges, clock, properties)

    fun fileWeftTaskRepository(objectMapper: ObjectMapper, clock: Clock): JdbcTaskRepository = factories.fileWeftTaskRepository(objectMapper, clock)

    fun fileWeftAgentResultRepository(objectMapper: ObjectMapper, clock: Clock): AgentResultRepository = factories.fileWeftAgentResultRepository(objectMapper, clock)

    fun fileWeftResumableUploadSessionRepository(objectMapper: ObjectMapper): ResumableUploadSessionRepository = factories.fileWeftResumableUploadSessionRepository(objectMapper)

    fun fileWeftRequestIdempotencyRepository(): RequestIdempotencyRepository = factories.fileWeftRequestIdempotencyRepository()

    fun fileWeftRequestIdempotencyService(
        repository: RequestIdempotencyRepository,
        transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): RequestIdempotencyService = factories.fileWeftRequestIdempotencyService(repository, transaction, identifiers, clock)

    fun fileWeftMetadataSchemaRegistry(schemas: ObjectProvider<MetadataSchema>): MetadataSchemaRegistry = factories.fileWeftMetadataSchemaRegistry(schemas)

    fun fileWeftMetadataProcessor(schemas: MetadataSchemaResolver): MetadataProcessor = factories.fileWeftMetadataProcessor(schemas)

    fun fileWeftMetadataSchemaQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        schemas: MetadataSchemaResolver,
    ): MetadataSchemaQueryService =
        factories.fileWeftMetadataSchemaQueryService(tenants, users, authorization, schemas)

    fun fileWeftDocumentMetadataService(
        tenants: TenantProvider,
        schemas: MetadataSchemaResolver,
        processor: MetadataProcessor,
    ): DocumentMetadataService = factories.fileWeftDocumentMetadataService(tenants, schemas, processor)

    fun fileWeftDocumentMetadataWriteService(
        drafts: DocumentDraftService,
        metadata: DocumentMetadataService,
        catalogDrafts: ObjectProvider<DocumentCatalogDraftService>,
        catalogMutations: ObjectProvider<DocumentCatalogMutationService>,
    ): DocumentMetadataWriteService =
        factories.fileWeftDocumentMetadataWriteService(drafts, metadata, catalogDrafts, catalogMutations)

    fun fileWeftDocumentCatalogAccessServiceFromCandidates(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalogs: ObjectProvider<DocumentCatalogProvider>,
    ): DocumentCatalogAccessService = factories.fileWeftDocumentCatalogAccessServiceFromCandidates(tenants, users, authorization, catalogs)

    /** Retains the original factory ABI for hosts that invoked this configuration directly. */
    @Deprecated("Use the ObjectProvider-backed auto-configuration factory.")
    fun fileWeftDocumentCatalogAccessService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalog: DocumentCatalogProvider,
    ): DocumentCatalogAccessService = factories.fileWeftDocumentCatalogAccessService(tenants, users, authorization, catalog)

    fun fileWeftDocumentCatalogBindingService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        catalogAccess: DocumentCatalogAccessService,
        documents: DocumentRepository,
        assets: FileAssetRepository,
        transaction: ApplicationTransaction,
        auditTrail: AuditTrail,
    ): DocumentCatalogBindingService? = factories.fileWeftDocumentCatalogBindingService(tenants, users, catalogAccess, documents, assets, transaction, auditTrail)

    fun fileWeftDocumentCatalogDraftService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
    ): DocumentCatalogDraftService = factories.fileWeftDocumentCatalogDraftService(drafts, catalogAccess)

    fun fileWeftDocumentCatalogMutationService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
        assets: FileAssetRepository,
    ): DocumentCatalogMutationService? = factories.fileWeftDocumentCatalogMutationService(drafts, catalogAccess, assets)

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

    fun fileWeftIdempotentDocumentLifecycleService(
        commands: DocumentCommandService,
        publish: PublishDocumentService,
        offline: OfflineDocumentService,
        restore: RestoreOfflineDocumentService,
        archive: ArchiveDocumentService,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentLifecycleService = factories.fileWeftIdempotentDocumentLifecycleService(commands, publish, offline, restore, archive, idempotency)

    fun fileWeftIdempotentDocumentCatalogLifecycleService(
        catalogLifecycles: ObjectProvider<DocumentCatalogLifecycleService>,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentCatalogLifecycleService? = factories.fileWeftIdempotentDocumentCatalogLifecycleService(catalogLifecycles, idempotency)

    fun fileWeftIdempotentDocumentDeliveryRecoveryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        documents: DocumentRepository,
        deliveries: DocumentDeliveryTargetMutationRepository,
        outboxMutations: OutboxEventMutationRepository,
        outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator,
        clock: Clock,
        idempotency: RequestIdempotencyService,
        auditTrail: AuditTrail,
    ): IdempotentDocumentDeliveryRecoveryService = factories.fileWeftIdempotentDocumentDeliveryRecoveryService(tenants, users, authorization, documents, deliveries, outboxMutations, outbox, identifiers, clock, idempotency, auditTrail)

    fun fileWeftIdempotentDocumentCatalogDeliveryRecoveryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        documents: DocumentRepository,
        assets: FileAssetRepository,
        deliveries: DocumentDeliveryTargetMutationRepository,
        outboxMutations: OutboxEventMutationRepository,
        outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction,
        clock: Clock,
        idempotency: RequestIdempotencyService,
        auditTrail: AuditTrail,
        catalogAccesses: ObjectProvider<DocumentCatalogAccessService>,
    ): IdempotentDocumentCatalogDeliveryRecoveryService? = factories.fileWeftIdempotentDocumentCatalogDeliveryRecoveryService(tenants, users, authorization, documents, assets, deliveries, outboxMutations, outbox, identifiers, transaction, clock, idempotency, auditTrail, catalogAccesses)

    fun fileWeftIdempotentDocumentReviewWorkflowService(
        reviews: DocumentReviewWorkflowService,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentReviewWorkflowService = factories.fileWeftIdempotentDocumentReviewWorkflowService(reviews, idempotency)

    fun fileWeftIdempotentDocumentCatalogReviewWorkflowService(
        catalogLifecycles: ObjectProvider<DocumentCatalogLifecycleService>,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentCatalogReviewWorkflowService? = factories.fileWeftIdempotentDocumentCatalogReviewWorkflowService(catalogLifecycles, idempotency)

    fun fileWeftConfirmAgentSuggestionService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        results: AgentResultRepository, identifiers: IdentifierGenerator, transaction: ApplicationTransaction,
        clock: Clock, auditTrail: AuditTrail, tasks: TaskRepository,
    ): ConfirmAgentSuggestionService = factories.fileWeftConfirmAgentSuggestionService(tenants, users, authorization, results, identifiers, transaction, clock, auditTrail, tasks)

    fun fileWeftAgentTaskOrchestrator(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry, clock: Clock,
    ): AgentTaskOrchestrator = factories.fileWeftAgentTaskOrchestrator(agents, plugins, clock)

    fun fileWeftAgentDoctorChecker(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry,
    ): AgentDoctorChecker = factories.fileWeftAgentDoctorChecker(agents, plugins)

    fun fileWeftAgentTaskScheduler(identifiers: IdentifierGenerator, clock: Clock): AgentTaskScheduler = factories.fileWeftAgentTaskScheduler(identifiers, clock)

    fun fileWeftAgentTaskHandler(
        orchestrator: AgentTaskOrchestrator, results: AgentResultRepository,
        transaction: ApplicationTransaction, clock: Clock,
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): AgentTaskHandler = factories.fileWeftAgentTaskHandler(orchestrator, results, transaction, clock, taskMutations)

    fun fileWeftAgentTaskOutboxEventHandler(
        triggers: List<ai.icen.fw.spi.ai.AgentTaskTrigger>, plugins: FileWeftPluginRegistry, scheduler: AgentTaskScheduler,
        tasks: TaskRepository, transaction: ApplicationTransaction,
    ): AgentTaskOutboxEventHandler = factories.fileWeftAgentTaskOutboxEventHandler(triggers, plugins, scheduler, tasks, transaction)

    fun fileWeftAgentSuggestionConfirmations(
        results: AgentResultRepository, transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator, clock: Clock, tasks: TaskRepository,
    ): PersistedAgentSuggestionConfirmationService = factories.fileWeftAgentSuggestionConfirmations(results, transaction, identifiers, clock, tasks)

    fun fileWeftAuditTrail(
        repository: AuditRecordRepository,
        operationLogs: OperationLogRepository,
        traceContextProvider: TraceContextProvider,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): AuditTrail = factories.fileWeftAuditTrail(repository, operationLogs, traceContextProvider, identifiers, clock)

    fun fileWeftConnectorInvocationExecutor(properties: FileWeftProperties): ConnectorInvocationExecutor = factories.fileWeftConnectorInvocationExecutor(properties)

    fun fileWeftConnectorResiliencePolicy(properties: FileWeftProperties): ConnectorResiliencePolicy = factories.fileWeftConnectorResiliencePolicy(properties)

    fun fileWeftConnectorResilienceRegistry(
        policy: ConnectorResiliencePolicy, executor: ConnectorInvocationExecutor, clock: Clock,
    ): ConnectorResilienceRegistry = factories.fileWeftConnectorResilienceRegistry(policy, executor, clock)

    fun fileWeftDeliveryConnectorResolver(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, properties: FileWeftProperties,
        resilience: ConnectorResilienceRegistry,
    ): DeliveryConnectorResolver = factories.fileWeftDeliveryConnectorResolver(connectors, plugins, properties, resilience)

    fun fileWeftDocumentDeliveryProfiles(properties: FileWeftProperties): DocumentDeliveryProfileProvider = factories.fileWeftDocumentDeliveryProfiles(properties)

    fun fileWeftDocumentDeliveryPlanner(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ): DocumentDeliveryPlanner = factories.fileWeftDocumentDeliveryPlanner(profiles, connectors, deliveries, outbox, identifiers, clock)

    fun fileWeftDocumentDeliveryRemovalPlanner(
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ): DocumentDeliveryRemovalPlanner = factories.fileWeftDocumentDeliveryRemovalPlanner(deliveries, outbox, identifiers, clock)

    fun fileWeftDocumentDeliverySyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        removalPlanner: DocumentDeliveryRemovalPlanner, metrics: FileWeftMetrics,
    ): DocumentDeliverySyncService = factories.fileWeftDocumentDeliverySyncService(documents, fileObjects, storage, connectors, deliveries, transaction, auditTrail, properties, removalPlanner, metrics)

    fun fileWeftDocumentDeliveryRemovalService(
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        metrics: FileWeftMetrics,
    ): DocumentDeliveryRemovalService = factories.fileWeftDocumentDeliveryRemovalService(connectors, deliveries, transaction, auditTrail, properties, metrics)

    fun fileWeftDocumentDeliveryOutboxEventHandler(
        sync: DocumentDeliverySyncService,
        removal: DocumentDeliveryRemovalService,
        deliveries: DocumentDeliveryTargetRepository,
        outboxMutations: ObjectProvider<OutboxEventMutationRepository>,
        documents: DocumentRepository,
    ): DocumentDeliveryOutboxEventHandler = factories.fileWeftDocumentDeliveryOutboxEventHandler(sync, removal, deliveries, outboxMutations, documents)

    fun fileWeftRetryDocumentDeliveryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ): RetryDocumentDeliveryService = factories.fileWeftRetryDocumentDeliveryService(tenants, users, authorization, documents, deliveries, outbox, identifiers, transaction, clock, auditTrail)

    fun fileWeftUploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, metrics: FileWeftMetrics,
    ): UploadApplicationService = factories.fileWeftUploadService(tenants, users, authorization, storage, fileObjects, assets, outbox, identifiers, transaction, clock, metrics)

    fun fileWeftResumableUploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        storage: StorageAdapter, sessions: ResumableUploadSessionRepository,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock,
        properties: FileWeftProperties, metrics: FileWeftMetrics,
    ): ResumableUploadService = factories.fileWeftResumableUploadService(tenants, users, authorization, storage, sessions, fileObjects, assets, outbox, identifiers, transaction, clock, properties, metrics)

    fun fileWeftDocumentQueryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        queries: DocumentQueryRepository, transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): DocumentQueryService = factories.fileWeftDocumentQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun fileWeftDocumentAuditLogQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentAuditLogQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): DocumentAuditLogQueryService = factories.fileWeftDocumentAuditLogQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun fileWeftDocumentSyncStatusQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentSyncStatusQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): DocumentSyncStatusQueryService = factories.fileWeftDocumentSyncStatusQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun fileWeftWorkflowQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): WorkflowQueryService = factories.fileWeftWorkflowQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun fileWeftWorkflowDecisionEvidenceQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowDecisionEvidenceQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): WorkflowDecisionEvidenceQueryService = factories.fileWeftWorkflowDecisionEvidenceQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun fileWeftDocumentCommandService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): DocumentCommandService = factories.fileWeftDocumentCommandService(tenants, users, authorization, documents, transaction, auditTrail)

    fun fileWeftDocumentDownloadVisibility(
        folderReadAccesses: ObjectProvider<DocumentFolderReadAccess>,
        queries: DocumentQueryRepository,
    ): DocumentDownloadVisibility = factories.fileWeftDocumentDownloadVisibility(folderReadAccesses, queries)

    fun fileWeftDocumentDownloadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
        visibility: ObjectProvider<DocumentDownloadVisibility>,
    ): DocumentDownloadService = factories.fileWeftDocumentDownloadService(tenants, users, authorization, documents, fileObjects, storage, transaction, auditTrail, visibility)

    fun fileWeftDocumentDraftService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        documents: DocumentRepository, fileObjects: FileObjectRepository, assets: FileAssetRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ): DocumentDraftService = factories.fileWeftDocumentDraftService(tenants, users, authorization, storage, documents, fileObjects, assets, identifiers, transaction, auditTrail, metrics)

    fun fileWeftPublishService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): PublishDocumentService = factories.fileWeftPublishService(tenants, users, authorization, documents, workflows, planner, transaction, auditTrail)

    fun fileWeftOfflineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ): OfflineDocumentService = factories.fileWeftOfflineService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    fun fileWeftRestoreOfflineDocumentService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): RestoreOfflineDocumentService = factories.fileWeftRestoreOfflineDocumentService(tenants, users, authorization, documents, deliveries, transaction, auditTrail)

    fun fileWeftArchiveService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ): ArchiveDocumentService = factories.fileWeftArchiveService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    fun fileWeftDefaultDocumentReviewRouteProvider(): DocumentReviewRouteProvider = factories.fileWeftDefaultDocumentReviewRouteProvider()

    fun fileWeftDocumentReviewRouteResolver(
        providers: List<DocumentReviewRouteProvider>,
        plugins: FileWeftPluginRegistry,
        properties: FileWeftProperties,
    ): DocumentReviewRouteResolver = factories.fileWeftDocumentReviewRouteResolver(providers, plugins, properties)

    fun fileWeftReviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        reviewRoutes: DocumentReviewRouteResolver,
    ): DocumentReviewWorkflowService = factories.fileWeftReviewWorkflowService(tenants, users, authorization, documents, workflows, planner, identifiers, transaction, auditTrail, reviewRoutes)

    fun fileWeftDeploymentSafetyDoctorChecker(
        tenants: TenantProvider,
        storage: StorageAdapter,
    ): DeploymentSafetyDoctorChecker = factories.fileWeftDeploymentSafetyDoctorChecker(tenants, storage)

    fun fileWeftPermissionDoctorChecker(users: UserRealmProvider, authorization: AuthorizationProvider): PermissionDoctorChecker = factories.fileWeftPermissionDoctorChecker(users, authorization)

    fun fileWeftLifecycleDoctorChecker(documents: DocumentRepository): LifecycleDoctorChecker = factories.fileWeftLifecycleDoctorChecker(documents)

    fun fileWeftStorageDoctorChecker(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter, transaction: ApplicationTransaction,
    ): StorageDoctorChecker = factories.fileWeftStorageDoctorChecker(documents, fileObjects, storage, transaction)

    fun fileWeftWorkflowDoctorChecker(
        documents: DocumentRepository, workflows: WorkflowInstanceRepository,
    ): WorkflowDoctorChecker = factories.fileWeftWorkflowDoctorChecker(documents, workflows)

    fun fileWeftMetadataDoctorChecker(
        documents: DocumentRepository,
        assets: FileAssetRepository,
        schemas: MetadataSchemaResolver,
        processor: MetadataProcessor,
        transaction: ApplicationTransaction,
    ): MetadataDoctorChecker = factories.fileWeftMetadataDoctorChecker(documents, assets, schemas, processor, transaction)

    fun fileWeftCatalogDoctorChecker(
        documents: DocumentRepository, assets: FileAssetRepository, catalog: DocumentCatalogProvider, transaction: ApplicationTransaction,
    ): CatalogDoctorChecker = factories.fileWeftCatalogDoctorChecker(documents, assets, catalog, transaction)

    fun fileWeftConnectorDoctorChecker(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, resilience: ConnectorResilienceRegistry,
    ): ConnectorDoctorChecker = factories.fileWeftConnectorDoctorChecker(connectors, plugins, resilience)

    fun fileWeftDeliveryProfileDoctorChecker(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
    ): DeliveryProfileDoctorChecker = factories.fileWeftDeliveryProfileDoctorChecker(profiles, connectors)

    fun fileWeftDoctorServiceWithoutLegacyAgent(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = factories.fileWeftDoctorServiceWithoutLegacyAgent(tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector, deliveryProfile, transaction, clock, metrics, plugins)

    fun fileWeftDoctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker,
        agent: AgentDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = factories.fileWeftDoctorService(tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector, deliveryProfile, agent, transaction, clock, metrics, plugins)

    fun fileWeftDocumentDoctorTaskHandler(
        doctor: DoctorApplicationService, reports: DoctorReportRepository,
        transaction: ApplicationTransaction, clock: Clock,
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): DocumentDoctorTaskHandler = factories.fileWeftDocumentDoctorTaskHandler(doctor, reports, transaction, clock, taskMutations)

    fun fileWeftScheduleDocumentDoctorService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, tasks: TaskRepository, identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ): ScheduleDocumentDoctorService = factories.fileWeftScheduleDocumentDoctorService(tenants, users, authorization, documents, tasks, identifiers, transaction, clock, auditTrail)

    fun fileWeftDocumentSyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connector: FileConnector, properties: FileWeftProperties, resilience: ConnectorResilienceRegistry, records: SyncRecordRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ): DocumentSyncService = factories.fileWeftDocumentSyncService(documents, fileObjects, storage, connector, properties, resilience, records, identifiers, transaction, auditTrail, metrics)

    fun fileWeftDocumentPublishOutboxEventHandler(sync: DocumentSyncService): DocumentPublishOutboxEventHandler = factories.fileWeftDocumentPublishOutboxEventHandler(sync)

    fun fileWeftOutboxWorker(
        repository: OutboxProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<OutboxEventHandler>, plugins: FileWeftPluginRegistry, clock: Clock, traces: ObjectProvider<TraceContextScope>,
        properties: FileWeftProperties,
    ): OutboxWorker = factories.fileWeftOutboxWorker(repository, transaction, handlers, plugins, clock, traces, properties)

    fun fileWeftTaskWorker(
        repository: TaskProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<FileWeftTaskHandler>, plugins: FileWeftPluginRegistry,
        clock: Clock, properties: FileWeftProperties, metrics: FileWeftMetrics,
    ): TaskWorker = factories.fileWeftTaskWorker(repository, transaction, handlers, plugins, clock, properties, metrics)
}
