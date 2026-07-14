package ai.icen.fw.starter.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.adapter.connector.ConnectorInvocationExecutor
import ai.icen.fw.adapter.connector.ConnectorResiliencePolicy
import ai.icen.fw.adapter.connector.ConnectorResilienceRegistry
import ai.icen.fw.agent.AgentTaskHandler
import ai.icen.fw.agent.AgentDoctorChecker
import ai.icen.fw.agent.AgentTaskOrchestrator
import ai.icen.fw.agent.AgentTaskScheduler
import ai.icen.fw.application.agent.AgentResultRepository
import ai.icen.fw.application.archive.ArchiveDocumentService
import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.audit.DocumentAuditLogQueryRepository
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
import ai.icen.fw.application.doctor.*
import ai.icen.fw.application.delivery.*
import ai.icen.fw.application.document.DocumentDraftService
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.lifecycle.IdempotentDocumentCatalogLifecycleService
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
import ai.icen.fw.application.upload.ResumableUploadSessionRepository
import ai.icen.fw.application.workflow.DocumentReviewRouteResolver
import ai.icen.fw.application.workflow.DocumentReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import ai.icen.fw.application.workflow.WorkflowQueryRepository
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryRepository
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
import ai.icen.fw.persistence.jdbc.*
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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
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

    fun transaction(dataSource: DataSource): ApplicationTransaction = factories.transaction(dataSource)

    fun documents(clock: Clock): DocumentRepository = factories.documents(clock)

    fun documentQueries(): DocumentQueryRepository = factories.documentQueries()

    fun documentAuditLogQueries(): DocumentAuditLogQueryRepository = factories.documentAuditLogQueries()

    fun documentSyncStatusQueries(): DocumentSyncStatusQueryRepository = factories.documentSyncStatusQueries()

    fun workflowQueries(): WorkflowQueryRepository = factories.workflowQueries()

    fun workflowDecisionEvidenceQueries(): WorkflowDecisionEvidenceQueryRepository = factories.workflowDecisionEvidenceQueries()

    fun fileObjects(clock: Clock): FileObjectRepository = factories.fileObjects(clock)

    fun fileAssets(objectMapper: ObjectMapper, clock: Clock): FileAssetRepository = factories.fileAssets(objectMapper, clock)

    fun workflows(clock: Clock): WorkflowInstanceRepository = factories.workflows(clock)

    fun audits(objectMapper: ObjectMapper): AuditRecordRepository = factories.audits(objectMapper)

    fun operationLogs(objectMapper: ObjectMapper): OperationLogRepository = factories.operationLogs(objectMapper)

    fun doctorReports(objectMapper: ObjectMapper, clock: Clock): DoctorReportRepository = factories.doctorReports(objectMapper, clock)

    fun syncRecords(clock: Clock): SyncRecordRepository = factories.syncRecords(clock)

    fun documentDeliveryTargets(clock: Clock): JdbcDocumentDeliveryTargetRepository = factories.documentDeliveryTargets(clock)

    fun outboxEvents(objectMapper: ObjectMapper, traces: TraceContextProvider): OutboxEventRepository = factories.outboxEvents(objectMapper, traces)

    fun outboxProcessing(objectMapper: ObjectMapper): JdbcOutboxProcessingRepository = factories.outboxProcessing(objectMapper)

    fun outboxBacklogReader(properties: FileWeftProperties): OutboxBacklogReader = factories.outboxBacklogReader(properties)

    fun outboxBacklogMetricsPublisher(
        transaction: ApplicationTransaction,
        reader: ObjectProvider<OutboxBacklogReader>,
        gauges: ObjectProvider<FileWeftGaugeRecorder>,
        clock: Clock,
        properties: FileWeftProperties,
    ): OutboxBacklogMetricsPublisher = factories.outboxBacklogMetricsPublisher(transaction, reader, gauges, clock, properties)

    fun tasks(objectMapper: ObjectMapper, clock: Clock): JdbcTaskRepository = factories.tasks(objectMapper, clock)

    fun agentResults(objectMapper: ObjectMapper, clock: Clock): AgentResultRepository = factories.agentResults(objectMapper, clock)

    fun resumableUploadSessions(objectMapper: ObjectMapper): ResumableUploadSessionRepository = factories.resumableUploadSessions(objectMapper)

    fun requestIdempotencyRepository(): RequestIdempotencyRepository = factories.requestIdempotencyRepository()

    fun requestIdempotencyService(
        repository: RequestIdempotencyRepository,
        transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): RequestIdempotencyService = factories.requestIdempotencyService(repository, transaction, identifiers, clock)

    fun metadataSchemaRegistry(schemas: ObjectProvider<MetadataSchema>): MetadataSchemaRegistry = factories.metadataSchemaRegistry(schemas)

    fun metadataProcessor(schemas: MetadataSchemaResolver): MetadataProcessor = factories.metadataProcessor(schemas)

    fun metadataSchemaQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        schemas: MetadataSchemaResolver,
    ): MetadataSchemaQueryService = factories.metadataSchemaQueryService(tenants, users, authorization, schemas)

    fun documentMetadataService(
        tenants: TenantProvider,
        schemas: MetadataSchemaResolver,
        processor: MetadataProcessor,
    ): DocumentMetadataService = factories.documentMetadataService(tenants, schemas, processor)

    fun documentMetadataWriteService(
        drafts: DocumentDraftService,
        metadata: DocumentMetadataService,
        catalogDrafts: ObjectProvider<DocumentCatalogDraftService>,
        catalogMutations: ObjectProvider<DocumentCatalogMutationService>,
    ): DocumentMetadataWriteService =
        factories.documentMetadataWriteService(drafts, metadata, catalogDrafts, catalogMutations)

    fun documentCatalogAccessServiceFromCandidates(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalogs: ObjectProvider<DocumentCatalogProvider>,
    ) = factories.documentCatalogAccessServiceFromCandidates(tenants, users, authorization, catalogs)

    /** Retains the original factory ABI for hosts that invoked this configuration directly. */
    @Deprecated("Use the ObjectProvider-backed auto-configuration factory.")
    fun documentCatalogAccessService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalog: DocumentCatalogProvider,
    ) = factories.documentCatalogAccessService(tenants, users, authorization, catalog)

    fun documentCatalogBindingService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        catalogAccess: DocumentCatalogAccessService,
        documents: DocumentRepository,
        assets: FileAssetRepository,
        transaction: ApplicationTransaction,
        auditTrail: AuditTrail,
    ): DocumentCatalogBindingService? = factories.documentCatalogBindingService(tenants, users, catalogAccess, documents, assets, transaction, auditTrail)

    fun documentCatalogDraftService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
    ) = factories.documentCatalogDraftService(drafts, catalogAccess)

    fun documentCatalogMutationService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
        assets: FileAssetRepository,
    ): DocumentCatalogMutationService? = factories.documentCatalogMutationService(drafts, catalogAccess, assets)

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

    fun idempotentDocumentLifecycleService(
        commands: DocumentCommandService,
        publish: PublishDocumentService,
        offline: OfflineDocumentService,
        restore: RestoreOfflineDocumentService,
        archive: ArchiveDocumentService,
        idempotency: RequestIdempotencyService,
    ) = factories.idempotentDocumentLifecycleService(commands, publish, offline, restore, archive, idempotency)

    fun idempotentDocumentCatalogLifecycleService(
        catalogLifecycles: ObjectProvider<DocumentCatalogLifecycleService>,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentCatalogLifecycleService? = factories.idempotentDocumentCatalogLifecycleService(catalogLifecycles, idempotency)

    fun idempotentDocumentDeliveryRecoveryService(
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
    ) = factories.idempotentDocumentDeliveryRecoveryService(tenants, users, authorization, documents, deliveries, outboxMutations, outbox, identifiers, clock, idempotency, auditTrail)

    fun idempotentDocumentCatalogDeliveryRecoveryService(
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
    ): IdempotentDocumentCatalogDeliveryRecoveryService? = factories.idempotentDocumentCatalogDeliveryRecoveryService(tenants, users, authorization, documents, assets, deliveries, outboxMutations, outbox, identifiers, transaction, clock, idempotency, auditTrail, catalogAccesses)

    fun idempotentDocumentReviewWorkflowService(
        reviews: DocumentReviewWorkflowService,
        idempotency: RequestIdempotencyService,
    ) = factories.idempotentDocumentReviewWorkflowService(reviews, idempotency)

    fun idempotentDocumentCatalogReviewWorkflowService(
        catalogLifecycles: ObjectProvider<DocumentCatalogLifecycleService>,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentCatalogReviewWorkflowService? = factories.idempotentDocumentCatalogReviewWorkflowService(catalogLifecycles, idempotency)

    fun confirmAgentSuggestionService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        results: AgentResultRepository, identifiers: IdentifierGenerator, transaction: ApplicationTransaction,
        clock: Clock, auditTrail: AuditTrail, tasks: TaskRepository,
    ) = factories.confirmAgentSuggestionService(tenants, users, authorization, results, identifiers, transaction, clock, auditTrail, tasks)

    fun agentTaskOrchestrator(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry, clock: Clock,
    ) = factories.agentTaskOrchestrator(agents, plugins, clock)

    fun agentDoctorChecker(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry,
    ) = factories.agentDoctorChecker(agents, plugins)

    fun agentTaskScheduler(identifiers: IdentifierGenerator, clock: Clock) = factories.agentTaskScheduler(identifiers, clock)

    fun agentTaskHandler(
        orchestrator: AgentTaskOrchestrator, results: AgentResultRepository,
        transaction: ApplicationTransaction, clock: Clock,
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): AgentTaskHandler = factories.agentTaskHandler(orchestrator, results, transaction, clock, taskMutations)

    fun agentTaskOutboxEventHandler(
        triggers: List<ai.icen.fw.spi.ai.AgentTaskTrigger>, plugins: FileWeftPluginRegistry, scheduler: AgentTaskScheduler,
        tasks: TaskRepository, transaction: ApplicationTransaction,
    ) = factories.agentTaskOutboxEventHandler(triggers, plugins, scheduler, tasks, transaction)

    fun agentSuggestionConfirmations(
        results: AgentResultRepository, transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator, clock: Clock, tasks: TaskRepository,
    ) = factories.agentSuggestionConfirmations(results, transaction, identifiers, clock, tasks)

    fun auditTrail(
        repository: AuditRecordRepository,
        operationLogs: OperationLogRepository,
        traceContextProvider: TraceContextProvider,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ) = factories.auditTrail(repository, operationLogs, traceContextProvider, identifiers, clock)

    fun connectorInvocationExecutor(properties: FileWeftProperties) = factories.connectorInvocationExecutor(properties)

    fun connectorResiliencePolicy(properties: FileWeftProperties) = factories.connectorResiliencePolicy(properties)

    fun connectorResilienceRegistry(
        policy: ConnectorResiliencePolicy, executor: ConnectorInvocationExecutor, clock: Clock,
    ) = factories.connectorResilienceRegistry(policy, executor, clock)

    fun deliveryConnectorResolver(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, properties: FileWeftProperties,
        resilience: ConnectorResilienceRegistry,
    ): DeliveryConnectorResolver = factories.deliveryConnectorResolver(connectors, plugins, properties, resilience)

    fun documentDeliveryProfiles(properties: FileWeftProperties): DocumentDeliveryProfileProvider = factories.documentDeliveryProfiles(properties)

    fun documentDeliveryPlanner(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ) = factories.documentDeliveryPlanner(profiles, connectors, deliveries, outbox, identifiers, clock)

    fun documentDeliveryRemovalPlanner(
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ) = factories.documentDeliveryRemovalPlanner(deliveries, outbox, identifiers, clock)

    fun documentDeliverySyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        removalPlanner: DocumentDeliveryRemovalPlanner, metrics: FileWeftMetrics,
    ) = factories.documentDeliverySyncService(documents, fileObjects, storage, connectors, deliveries, transaction, auditTrail, properties, removalPlanner, metrics)

    fun documentDeliveryRemovalService(
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        metrics: FileWeftMetrics,
    ) = factories.documentDeliveryRemovalService(connectors, deliveries, transaction, auditTrail, properties, metrics)

    fun documentDeliveryHandler(
        sync: DocumentDeliverySyncService,
        removal: DocumentDeliveryRemovalService,
        deliveries: DocumentDeliveryTargetRepository,
        outboxMutations: ObjectProvider<OutboxEventMutationRepository>,
        documents: DocumentRepository,
    ): DocumentDeliveryOutboxEventHandler = factories.documentDeliveryHandler(sync, removal, deliveries, outboxMutations, documents)

    fun retryDocumentDeliveryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ) = factories.retryDocumentDeliveryService(tenants, users, authorization, documents, deliveries, outbox, identifiers, transaction, clock, auditTrail)

    fun uploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, metrics: FileWeftMetrics,
    ) = factories.uploadService(tenants, users, authorization, storage, fileObjects, assets, outbox, identifiers, transaction, clock, metrics)

    fun resumableUploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        storage: StorageAdapter, sessions: ResumableUploadSessionRepository,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock,
        properties: FileWeftProperties, metrics: FileWeftMetrics,
    ) = factories.resumableUploadService(tenants, users, authorization, storage, sessions, fileObjects, assets, outbox, identifiers, transaction, clock, properties, metrics)

    fun documentQueryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        queries: DocumentQueryRepository, transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.documentQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun documentAuditLogQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentAuditLogQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.documentAuditLogQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun documentSyncStatusQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentSyncStatusQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.documentSyncStatusQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun workflowQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.workflowQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun workflowDecisionEvidenceQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowDecisionEvidenceQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.workflowDecisionEvidenceQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    fun documentCommands(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = factories.documentCommands(tenants, users, authorization, documents, transaction, auditTrail)

    fun documentDownloadVisibility(
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
        queries: DocumentQueryRepository,
    ): DocumentDownloadVisibility = factories.documentDownloadVisibility(folderReadAccess, queries)

    fun documentDownloads(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
        visibility: ObjectProvider<DocumentDownloadVisibility>,
    ): DocumentDownloadService = factories.documentDownloads(tenants, users, authorization, documents, fileObjects, storage, transaction, auditTrail, visibility)

    fun documentDraftService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        documents: DocumentRepository, fileObjects: FileObjectRepository, assets: FileAssetRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ) = factories.documentDraftService(tenants, users, authorization, storage, documents, fileObjects, assets, identifiers, transaction, auditTrail, metrics)

    fun publishService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = factories.publishService(tenants, users, authorization, documents, workflows, planner, transaction, auditTrail)

    fun offlineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ) = factories.offlineService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    fun restoreOfflineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = factories.restoreOfflineService(tenants, users, authorization, documents, deliveries, transaction, auditTrail)

    fun archiveService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ) = factories.archiveService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    fun defaultDocumentReviewRouteProvider(): DocumentReviewRouteProvider = factories.defaultDocumentReviewRouteProvider()

    fun documentReviewRouteResolver(
        providers: List<DocumentReviewRouteProvider>,
        plugins: FileWeftPluginRegistry,
        properties: FileWeftProperties,
    ): DocumentReviewRouteResolver = factories.documentReviewRouteResolver(providers, plugins, properties)

    fun reviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        reviewRoutes: DocumentReviewRouteResolver,
    ) = factories.reviewWorkflowService(tenants, users, authorization, documents, workflows, planner, identifiers, transaction, auditTrail, reviewRoutes)

    fun deploymentSafetyDoctor(
        tenants: TenantProvider,
        storage: StorageAdapter,
    ) = factories.deploymentSafetyDoctor(tenants, storage)

    fun permissionDoctor(users: UserRealmProvider, authorization: AuthorizationProvider) = factories.permissionDoctor(users, authorization)

    fun lifecycleDoctor(documents: DocumentRepository) = factories.lifecycleDoctor(documents)

    fun storageDoctor(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter, transaction: ApplicationTransaction,
    ) = factories.storageDoctor(documents, fileObjects, storage, transaction)

    fun workflowDoctor(
        documents: DocumentRepository, workflows: WorkflowInstanceRepository,
    ) = factories.workflowDoctor(documents, workflows)

    fun metadataDoctor(
        documents: DocumentRepository,
        assets: FileAssetRepository,
        schemas: MetadataSchemaResolver,
        processor: MetadataProcessor,
        transaction: ApplicationTransaction,
    ) = factories.metadataDoctor(documents, assets, schemas, processor, transaction)

    fun catalogDoctor(
        documents: DocumentRepository, assets: FileAssetRepository, catalog: DocumentCatalogProvider, transaction: ApplicationTransaction,
    ) = factories.catalogDoctor(documents, assets, catalog, transaction)

    fun connectorDoctor(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, resilience: ConnectorResilienceRegistry,
    ) = factories.connectorDoctor(connectors, plugins, resilience)

    fun deliveryProfileDoctor(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
    ) = factories.deliveryProfileDoctor(profiles, connectors)

    fun doctorServiceWithoutLegacyAgent(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = factories.doctorServiceWithoutLegacyAgent(tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector, deliveryProfile, transaction, clock, metrics, plugins)

    fun doctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker,
        agent: AgentDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = factories.doctorService(tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector, deliveryProfile, agent, transaction, clock, metrics, plugins)

    fun documentDoctorTaskHandler(
        doctor: DoctorApplicationService, reports: DoctorReportRepository,
        transaction: ApplicationTransaction, clock: Clock,
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): DocumentDoctorTaskHandler = factories.documentDoctorTaskHandler(doctor, reports, transaction, clock, taskMutations)

    fun scheduleDocumentDoctorService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, tasks: TaskRepository, identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ) = factories.scheduleDocumentDoctorService(tenants, users, authorization, documents, tasks, identifiers, transaction, clock, auditTrail)

    fun documentSyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connector: FileConnector, properties: FileWeftProperties, resilience: ConnectorResilienceRegistry, records: SyncRecordRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ) = factories.documentSyncService(documents, fileObjects, storage, connector, properties, resilience, records, identifiers, transaction, auditTrail, metrics)

    fun documentPublishHandler(sync: DocumentSyncService) = factories.documentPublishHandler(sync)

    fun outboxWorker(
        repository: OutboxProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<OutboxEventHandler>, plugins: FileWeftPluginRegistry, clock: Clock, traces: ObjectProvider<TraceContextScope>,
        properties: FileWeftProperties,
    ) = factories.outboxWorker(repository, transaction, handlers, plugins, clock, traces, properties)

    fun taskWorker(
        repository: TaskProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<FileWeftTaskHandler>, plugins: FileWeftPluginRegistry,
        clock: Clock, properties: FileWeftProperties, metrics: FileWeftMetrics,
    ) = factories.taskWorker(repository, transaction, handlers, plugins, clock, properties, metrics)
}
