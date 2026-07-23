package ai.icen.fw.starter.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.adapter.connector.ConnectorInvocationExecutor
import ai.icen.fw.adapter.connector.ConnectorResiliencePolicy
import ai.icen.fw.adapter.connector.ConnectorResilienceRegistry
import ai.icen.fw.adapter.logging.Slf4jFileWeftLogger
import ai.icen.fw.adapter.storage.LocalStorageAdapter
import ai.icen.fw.adapter.tenant.FixedTenantProvider
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
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.ResumableUploadSessionRepository
import ai.icen.fw.application.upload.UploadApplicationService
import ai.icen.fw.application.workflow.DefaultDocumentReviewRouteProvider
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
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileAssetMutationRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.domain.operation.OperationLogRepository
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchema
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.metadata.runtime.DefaultMetadataProcessor
import ai.icen.fw.metadata.runtime.MetadataSchemaRegistry
import ai.icen.fw.metadata.runtime.HistoricalMetadataSchema
import ai.icen.fw.persistence.jdbc.*
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.doctor.DoctorChecker
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
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

/**
 * Hand-rolled construction surface shared by the split `FileWeft*Configuration`
 * classes of this starter.
 *
 * Each configuration class instantiates its own copy instead of injecting a
 * shared bean: the factories carry no runtime state, and keeping them outside
 * the container avoids turning an internal wiring detail into replaceable
 * public SPI surface. This is deliberately not a Spring configuration class —
 * bean definitions, conditions, and bean names live in the starter
 * configurations, while this class only centralizes constructor wiring so the
 * Boot 2 and Boot 3 starters assemble identical runtime graphs.
 */
internal class FileWeftRuntimeFactories {
    fun transaction(dataSource: DataSource): ApplicationTransaction =
        JdbcApplicationTransaction(dataSource, Slf4jFileWeftLogger(JdbcApplicationTransaction::class.java.name))

    fun documents(clock: Clock): DocumentRepository = JdbcDocumentRepository(clock)

    fun documentQueries(): DocumentQueryRepository = JdbcDocumentQueryRepository()

    fun documentAuditLogQueries(): DocumentAuditLogQueryRepository = JdbcDocumentAuditLogQueryRepository()

    fun documentSyncStatusQueries(): DocumentSyncStatusQueryRepository = JdbcDocumentSyncStatusQueryRepository()

    fun workflowQueries(): WorkflowQueryRepository = JdbcWorkflowQueryRepository()

    fun workflowDecisionEvidenceQueries(): WorkflowDecisionEvidenceQueryRepository =
        JdbcWorkflowDecisionEvidenceQueryRepository()

    fun fileObjects(clock: Clock): FileObjectRepository = JdbcFileObjectRepository(clock)

    fun fileAssets(objectMapper: ObjectMapper, clock: Clock): FileAssetRepository =
        JdbcFileAssetRepository(objectMapper, clock)

    fun workflows(clock: Clock): WorkflowInstanceRepository = JdbcWorkflowInstanceRepository(clock)

    fun audits(objectMapper: ObjectMapper): AuditRecordRepository = JdbcAuditRecordRepository(objectMapper)

    fun operationLogs(objectMapper: ObjectMapper): OperationLogRepository = JdbcOperationLogRepository(objectMapper)

    fun doctorReports(objectMapper: ObjectMapper, clock: Clock): DoctorReportRepository = JdbcDoctorReportRepository(objectMapper, clock)

    fun syncRecords(clock: Clock): SyncRecordRepository = JdbcSyncRecordRepository(clock)

    fun documentDeliveryTargets(clock: Clock): JdbcDocumentDeliveryTargetRepository = JdbcDocumentDeliveryTargetRepository(clock)

    fun outboxEvents(objectMapper: ObjectMapper, traces: TraceContextProvider): OutboxEventRepository =
        TraceAwareOutboxEventRepository(JdbcOutboxEventRepository(objectMapper), traces)

    fun outboxProcessing(objectMapper: ObjectMapper): JdbcOutboxProcessingRepository = JdbcOutboxProcessingRepository(objectMapper)

    fun outboxBacklogReader(properties: FileWeftProperties): OutboxBacklogReader =
        JdbcOutboxBacklogReader(properties.outbox.backlogMetricsQueryTimeoutSeconds)

    fun outboxBacklogMetricsPublisher(
        transaction: ApplicationTransaction,
        reader: ObjectProvider<OutboxBacklogReader>,
        gauges: ObjectProvider<FileWeftGaugeRecorder>,
        clock: Clock,
        properties: FileWeftProperties,
    ): OutboxBacklogMetricsPublisher = OutboxBacklogMetricsPublisher(
        transaction = transaction,
        reader = reader.getIfAvailable(),
        gauges = gauges.getIfAvailable(),
        clock = clock,
        samplingInterval = Duration.ofMillis(properties.outbox.backlogMetricsIntervalMillis),
        legacyRunningGrace = Duration.ofMillis(properties.outbox.legacyRunningGraceMillis),
    )

    fun tasks(objectMapper: ObjectMapper, clock: Clock): JdbcTaskRepository = JdbcTaskRepository(objectMapper, clock)

    fun agentResults(objectMapper: ObjectMapper, clock: Clock): AgentResultRepository = JdbcAgentResultRepository(objectMapper, clock)

    fun resumableUploadSessions(objectMapper: ObjectMapper): ResumableUploadSessionRepository =
        JdbcResumableUploadSessionRepository(objectMapper)

    fun requestIdempotencyRepository(): RequestIdempotencyRepository = JdbcRequestIdempotencyRepository()

    fun requestIdempotencyService(
        repository: RequestIdempotencyRepository,
        transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): RequestIdempotencyService = RequestIdempotencyService(repository, transaction, identifiers, clock)

    /**
     * Invalid host schemas fail here with MetadataSchemaConfigurationException
     * (cause retained) while the bean factory is creating the registry, so the
     * failure aborts startup with the full cause chain and needs no additional
     * reporting on this path.
     */
    fun metadataSchemaRegistry(schemas: ObjectProvider<MetadataSchema>): MetadataSchemaRegistry =
        MetadataSchemaRegistry(schemas.orderedStream().iterator().asSequence().toList())

    fun metadataSchemaRegistryWithHistory(
        schemas: ObjectProvider<MetadataSchema>,
        historicalSchemas: ObjectProvider<HistoricalMetadataSchema>,
    ): MetadataSchemaRegistry = MetadataSchemaRegistry(
        schemas.orderedStream().iterator().asSequence().toList(),
        historicalSchemas.orderedStream().iterator().asSequence().map { contribution -> contribution.schema }.toList(),
    )

    fun metadataProcessor(schemas: MetadataSchemaResolver): MetadataProcessor = DefaultMetadataProcessor(schemas)

    fun metadataSchemaQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        schemas: MetadataSchemaResolver,
    ): MetadataSchemaQueryService = MetadataSchemaQueryService(tenants, users, authorization, schemas)

    fun documentMetadataService(
        tenants: TenantProvider,
        schemas: MetadataSchemaResolver,
        processor: MetadataProcessor,
    ): DocumentMetadataService = DocumentMetadataService(
        tenants,
        schemas,
        processor,
        Slf4jFileWeftLogger(DocumentMetadataService::class.java.name),
    )

    fun documentMetadataWriteService(
        drafts: DocumentDraftService,
        metadata: DocumentMetadataService,
        catalogDrafts: ObjectProvider<DocumentCatalogDraftService>,
        catalogMutations: ObjectProvider<DocumentCatalogMutationService>,
    ): DocumentMetadataWriteService = DocumentMetadataWriteService(
        drafts,
        metadata,
        singleSecurityCandidateOrNull(catalogDrafts, DocumentCatalogDraftService::class.java),
        singleSecurityCandidateOrNull(catalogMutations, DocumentCatalogMutationService::class.java),
    )

    fun documentCatalogAccessServiceFromCandidates(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalogs: ObjectProvider<DocumentCatalogProvider>,
    ) = DocumentCatalogAccessService(
        tenants,
        users,
        authorization,
        requiredSecurityCandidate(catalogs, DocumentCatalogProvider::class.java),
    )

    fun documentCatalogAccessService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalog: DocumentCatalogProvider,
    ) = DocumentCatalogAccessService(tenants, users, authorization, catalog)

    fun documentCatalogBindingService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        catalogAccess: DocumentCatalogAccessService,
        documents: DocumentRepository,
        assets: FileAssetRepository,
        transaction: ApplicationTransaction,
        auditTrail: AuditTrail,
    ): DocumentCatalogBindingService? = if (assets is FileAssetMutationRepository && documents is DocumentMutationRepository) {
        DocumentCatalogBindingService(tenants, users, catalogAccess, documents, assets, transaction, auditTrail)
    } else {
        null
    }

    fun documentCatalogDraftService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
    ) = DocumentCatalogDraftService(drafts, catalogAccess)

    fun documentCatalogMutationService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
        assets: FileAssetRepository,
    ): DocumentCatalogMutationService? =
        if (assets is FileAssetMutationRepository) DocumentCatalogMutationService(drafts, catalogAccess) else null

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
    ): DocumentCatalogLifecycleService? {
        val catalogAccess = requiredSecurityCandidate(catalogAccesses, DocumentCatalogAccessService::class.java)
        return if (assets is FileAssetMutationRepository) {
            DocumentCatalogLifecycleService(
                commands,
                workflows,
                publish,
                offline,
                restore,
                archive,
                catalogAccess,
                documents,
                assets,
                transaction,
            )
        } else {
            null
        }
    }

    fun idempotentDocumentLifecycleService(
        commands: DocumentCommandService,
        publish: PublishDocumentService,
        offline: OfflineDocumentService,
        restore: RestoreOfflineDocumentService,
        archive: ArchiveDocumentService,
        idempotency: RequestIdempotencyService,
    ) = IdempotentDocumentLifecycleService(commands, publish, offline, restore, archive, idempotency)

    fun idempotentDocumentCatalogLifecycleService(
        catalogLifecycles: ObjectProvider<DocumentCatalogLifecycleService>,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentCatalogLifecycleService? {
        if (!catalogLifecycles.stream().findAny().isPresent) {
            return null
        }
        return IdempotentDocumentCatalogLifecycleService(
            requiredSecurityCandidate(catalogLifecycles, DocumentCatalogLifecycleService::class.java),
            idempotency,
        )
    }

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
    ) = IdempotentDocumentDeliveryRecoveryService(
        tenants, users, authorization, requireDocumentMutations(documents), deliveries, outboxMutations, outbox,
        identifiers, clock, idempotency, auditTrail,
    )

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
    ): IdempotentDocumentCatalogDeliveryRecoveryService? =
        if (assets is FileAssetMutationRepository && documents is DocumentMutationRepository) {
            IdempotentDocumentCatalogDeliveryRecoveryService(
                tenants, users, authorization, documents, assets, deliveries, outboxMutations, outbox,
                identifiers, transaction, clock, idempotency, auditTrail,
                requiredSecurityCandidate(catalogAccesses, DocumentCatalogAccessService::class.java),
            )
        } else {
            null
        }

    fun idempotentDocumentReviewWorkflowService(
        reviews: DocumentReviewWorkflowService,
        idempotency: RequestIdempotencyService,
    ) = IdempotentDocumentReviewWorkflowService(reviews, idempotency)

    fun idempotentDocumentCatalogReviewWorkflowService(
        catalogLifecycles: ObjectProvider<DocumentCatalogLifecycleService>,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentCatalogReviewWorkflowService? {
        if (!catalogLifecycles.stream().findAny().isPresent) {
            return null
        }
        return IdempotentDocumentCatalogReviewWorkflowService(
            requiredSecurityCandidate(catalogLifecycles, DocumentCatalogLifecycleService::class.java),
            idempotency,
        )
    }

    fun confirmAgentSuggestionService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        results: AgentResultRepository, identifiers: IdentifierGenerator, transaction: ApplicationTransaction,
        clock: Clock, auditTrail: AuditTrail, tasks: TaskRepository,
    ) = ConfirmAgentSuggestionService(
        tenants, users, authorization, results, identifiers, transaction, clock, auditTrail, tasks,
    )

    fun agentTaskOrchestrator(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry, clock: Clock,
    ) = AgentTaskOrchestrator(agents + plugins.agents(), clock)

    fun agentDoctorChecker(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry,
    ) = AgentDoctorChecker(agents + plugins.agents())

    fun agentTaskScheduler(identifiers: IdentifierGenerator, clock: Clock) = AgentTaskScheduler(identifiers, clock)

    fun agentTaskHandler(
        orchestrator: AgentTaskOrchestrator, results: AgentResultRepository,
        transaction: ApplicationTransaction, clock: Clock,
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): AgentTaskHandler {
        val mutations = taskMutations.getIfUnique() ?: throw IllegalStateException(
            "Exactly one TaskMutationRepository is required for fenced task projections.",
        )
        return AgentTaskHandler(orchestrator, results, transaction, clock, mutations)
    }

    fun agentTaskOutboxEventHandler(
        triggers: List<ai.icen.fw.spi.ai.AgentTaskTrigger>, plugins: FileWeftPluginRegistry, scheduler: AgentTaskScheduler,
        tasks: TaskRepository, transaction: ApplicationTransaction,
    ) = AgentTaskOutboxEventHandler(triggers + plugins.agentTaskTriggers(), scheduler, tasks, transaction)

    fun agentSuggestionConfirmations(
        results: AgentResultRepository, transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator, clock: Clock, tasks: TaskRepository,
    ) = PersistedAgentSuggestionConfirmationService(results, transaction, identifiers, clock, tasks)

    fun auditTrail(
        repository: AuditRecordRepository,
        operationLogs: OperationLogRepository,
        traceContextProvider: TraceContextProvider,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ) = AuditTrail(repository, identifiers, clock, operationLogs, traceContextProvider)

    fun connectorInvocationExecutor(properties: FileWeftProperties) = ConnectorInvocationExecutor(
        maxConcurrentInvocations = properties.sync.connectorMaxConcurrentInvocations,
        queueCapacity = properties.sync.connectorInvocationQueueCapacity,
    )

    fun connectorResiliencePolicy(properties: FileWeftProperties) = ConnectorResiliencePolicy(
        timeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        failureThreshold = properties.sync.circuitBreakerFailureThreshold,
        circuitOpenDuration = Duration.ofMillis(properties.sync.circuitBreakerOpenDurationMillis),
    )

    fun connectorResilienceRegistry(
        policy: ConnectorResiliencePolicy, executor: ConnectorInvocationExecutor, clock: Clock,
    ) = ConnectorResilienceRegistry(
        policy,
        executor,
        clock,
        Slf4jFileWeftLogger("ai.icen.fw.adapter.connector.ResilientFileConnector"),
    )

    fun deliveryConnectorResolver(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, properties: FileWeftProperties,
        resilience: ConnectorResilienceRegistry,
    ): DeliveryConnectorResolver = MapDeliveryConnectorResolver(
        resilience.protectAll(plugins.mergeConnectors(connectors)),
        properties.sync.connectorName,
    )

    fun documentDeliveryProfiles(properties: FileWeftProperties): DocumentDeliveryProfileProvider {
        val configured = properties.sync.profiles.map { profile ->
            DocumentDeliveryProfile(
                id = profile.id,
                displayName = profile.displayName,
                targets = profile.targets.map { target ->
                    ai.icen.fw.spi.delivery.DocumentDeliveryTargetDefinition(
                        id = target.id,
                        displayName = target.displayName,
                        connectorId = target.connectorId,
                        requirement = if (target.required) DeliveryRequirement.REQUIRED else DeliveryRequirement.OPTIONAL,
                        ownerRef = target.ownerRef,
                    )
                },
            )
        }.ifEmpty {
            listOf(
                DocumentDeliveryProfile(
                    id = DEFAULT_DELIVERY_PROFILE_ID,
                    displayName = "Default delivery",
                    targets = listOf(
                        ai.icen.fw.spi.delivery.DocumentDeliveryTargetDefinition(
                            id = DEFAULT_DELIVERY_TARGET_ID,
                            displayName = "Default downstream",
                            connectorId = properties.sync.connectorName,
                            requirement = DeliveryRequirement.REQUIRED,
                        ),
                    ),
                ),
            )
        }
        val requestedDefaultProfileId = properties.sync.defaultProfileId
        val defaultProfileId = requestedDefaultProfileId.takeIf { requested ->
            configured.any { profile -> profile.id == requested }
        }
        require(defaultProfileId != null || requestedDefaultProfileId == DEFAULT_DELIVERY_PROFILE_ID) {
            "fileweft.sync.default-profile-id '$requestedDefaultProfileId' does not match a configured delivery " +
                "profile. Available profile ids: ${configured.joinToString { profile -> profile.id }}."
        }
        return StaticDocumentDeliveryProfileProvider(configured, defaultProfileId)
    }

    fun documentDeliveryPlanner(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ) = DocumentDeliveryPlanner(profiles, connectors, deliveries, outbox, identifiers, clock)

    fun documentDeliveryRemovalPlanner(
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ) = DocumentDeliveryRemovalPlanner(deliveries, outbox, identifiers, clock)

    fun documentDeliverySyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        removalPlanner: DocumentDeliveryRemovalPlanner, metrics: FileWeftMetrics,
    ) = DocumentDeliverySyncService(
        requireDocumentMutations(documents), fileObjects, storage, connectors, deliveries, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
        removalPlanner = removalPlanner,
        metrics = metrics,
        sourceAccessUrlTtl = Duration.ofMillis(properties.sync.sourceAccessUrlTtlMillis),
    )

    fun documentDeliveryRemovalService(
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        metrics: FileWeftMetrics,
    ) = DocumentDeliveryRemovalService(
        connectors, deliveries, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
        metrics = metrics,
    )

    fun documentDeliveryHandler(
        sync: DocumentDeliverySyncService,
        removal: DocumentDeliveryRemovalService,
        deliveries: DocumentDeliveryTargetRepository,
        outboxMutations: ObjectProvider<OutboxEventMutationRepository>,
        documents: DocumentRepository,
    ): DocumentDeliveryOutboxEventHandler {
        if (deliveries !is DocumentDeliveryTargetMutationRepository) {
            throw IllegalStateException(
                "DocumentDeliveryTargetRepository must also implement DocumentDeliveryTargetMutationRepository for fenced delivery processing.",
            )
        }
        val mutations = outboxMutations.getIfUnique() ?: throw IllegalStateException(
            "Exactly one OutboxEventMutationRepository is required for fenced delivery processing.",
        )
        return DocumentDeliveryOutboxEventHandler(sync, removal, mutations, requireDocumentMutations(documents))
    }

    fun retryDocumentDeliveryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ) = RetryDocumentDeliveryService(tenants, users, authorization, documents, deliveries, outbox, identifiers, transaction, clock, auditTrail)

    fun uploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, metrics: FileWeftMetrics,
    ) = UploadApplicationService(tenants, users, authorization, storage, fileObjects, assets, outbox, identifiers, transaction, clock, metrics)

    fun resumableUploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        storage: StorageAdapter, sessions: ResumableUploadSessionRepository,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock,
        properties: FileWeftProperties, metrics: FileWeftMetrics,
    ) = ResumableUploadService(
        tenants, users, authorization, storage, sessions, fileObjects, assets, outbox, identifiers, transaction, clock,
        sessionTtl = Duration.ofMillis(properties.upload.resumableSessionTtlMillis),
        metrics = metrics,
    )

    fun documentQueryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        queries: DocumentQueryRepository, transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = DocumentQueryService(
        tenants, users, authorization, queries, transaction, folderReadAccess.getIfAvailable(),
    )

    fun documentAuditLogQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentAuditLogQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = DocumentAuditLogQueryService(
        tenants,
        users,
        authorization,
        queries,
        transaction,
        singleSecurityCandidateOrNull(folderReadAccess, DocumentFolderReadAccess::class.java),
    )

    fun documentSyncStatusQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentSyncStatusQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = DocumentSyncStatusQueryService(
        tenants,
        users,
        authorization,
        queries,
        transaction,
        singleSecurityCandidateOrNull(folderReadAccess, DocumentFolderReadAccess::class.java),
    )

    fun workflowQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = WorkflowQueryService(
        tenants,
        users,
        authorization,
        queries,
        transaction,
        singleSecurityCandidateOrNull(folderReadAccess, DocumentFolderReadAccess::class.java),
    )

    fun workflowDecisionEvidenceQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowDecisionEvidenceQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = WorkflowDecisionEvidenceQueryService(
        tenants,
        users,
        authorization,
        queries,
        transaction,
        singleSecurityCandidateOrNull(folderReadAccess, DocumentFolderReadAccess::class.java),
    )

    fun documentCommands(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = DocumentCommandService(tenants, users, authorization, requireDocumentMutations(documents), transaction, auditTrail)

    fun documentDownloadVisibility(
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
        queries: DocumentQueryRepository,
    ): DocumentDownloadVisibility {
        val access = singleSecurityCandidateOrNull(folderReadAccess, DocumentFolderReadAccess::class.java)
            ?: throw NoSuchBeanDefinitionException(DocumentFolderReadAccess::class.java)
        return DocumentDownloadVisibility(access, queries)
    }

    fun documentDownloads(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
        visibility: ObjectProvider<DocumentDownloadVisibility>,
    ): DocumentDownloadService {
        // Do not let @Primary select among security guards. More than one
        // candidate is a configuration error, not an ordering preference.
        val downloadVisibility = singleSecurityCandidateOrNull(visibility, DocumentDownloadVisibility::class.java)
        return if (downloadVisibility == null) {
            DocumentDownloadService(
                tenants, users, authorization, documents, fileObjects, storage, transaction, auditTrail,
            )
        } else {
            DocumentDownloadService(
                tenants, users, authorization, documents, fileObjects, storage, transaction, auditTrail,
                downloadVisibility,
            )
        }
    }

    private fun <T : Any> singleSecurityCandidateOrNull(
        provider: ObjectProvider<T>,
        beanType: Class<T>,
    ): T? {
        val candidates = provider.stream().iterator().asSequence().toList()
        if (candidates.size > 1) {
            throw NoUniqueBeanDefinitionException(
                beanType,
                candidates.size,
                "Exactly one ${beanType.simpleName} security boundary may be configured.",
            )
        }
        return candidates.singleOrNull()
    }

    private fun <T : Any> requiredSecurityCandidate(
        provider: ObjectProvider<T>,
        beanType: Class<T>,
    ): T = singleSecurityCandidateOrNull(provider, beanType)
        ?: throw NoSuchBeanDefinitionException(beanType)

    fun documentDraftService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        documents: DocumentRepository, fileObjects: FileObjectRepository, assets: FileAssetRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ) = DocumentDraftService(
        tenants, users, authorization, storage, requireDocumentMutations(documents), fileObjects, assets, identifiers, transaction, auditTrail, metrics,
    )

    fun publishService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = PublishDocumentService(tenants, users, authorization, requireDocumentMutations(documents), planner, transaction, auditTrail, workflows)

    fun offlineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ) = OfflineDocumentService(tenants, users, authorization, requireDocumentMutations(documents), transaction, auditTrail, removalPlanner)

    fun restoreOfflineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = RestoreOfflineDocumentService(tenants, users, authorization, requireDocumentMutations(documents), deliveries, transaction, auditTrail)

    fun archiveService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ) = ArchiveDocumentService(tenants, users, authorization, requireDocumentMutations(documents), transaction, auditTrail, removalPlanner)

    fun defaultDocumentReviewRouteProvider(): DocumentReviewRouteProvider = DefaultDocumentReviewRouteProvider

    fun documentReviewRouteResolver(
        providers: List<DocumentReviewRouteProvider>,
        plugins: FileWeftPluginRegistry,
        properties: FileWeftProperties,
    ): DocumentReviewRouteResolver = DocumentReviewRouteResolver(
        providers = providers + plugins.reviewRouteProviders(),
        defaultRouteId = properties.workflow.defaultReviewRouteId,
    )

    fun reviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        reviewRoutes: DocumentReviewRouteResolver,
    ) = DocumentReviewWorkflowService(
        tenants, users, authorization, requireDocumentMutations(documents), workflows, planner, identifiers, transaction, auditTrail, reviewRoutes,
    )

    fun deploymentSafetyDoctor(
        tenants: TenantProvider,
        storage: StorageAdapter,
    ) = DeploymentSafetyDoctorChecker(
        fixedTenantProviderActive = tenants is FixedTenantProvider,
        localStorageAdapterActive = storage is LocalStorageAdapter,
    )

    fun permissionDoctor(users: UserRealmProvider, authorization: AuthorizationProvider) = PermissionDoctorChecker(users, authorization)

    fun lifecycleDoctor(documents: DocumentRepository) = LifecycleDoctorChecker(documents)

    fun storageDoctor(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter, transaction: ApplicationTransaction,
    ) = StorageDoctorChecker(documents, fileObjects, storage, transaction)

    fun workflowDoctor(
        documents: DocumentRepository, workflows: WorkflowInstanceRepository,
    ) = WorkflowDoctorChecker(documents, workflows)

    fun metadataDoctor(
        documents: DocumentRepository,
        assets: FileAssetRepository,
        schemas: MetadataSchemaResolver,
        processor: MetadataProcessor,
        transaction: ApplicationTransaction,
    ) = MetadataDoctorChecker(documents, assets, schemas, processor, transaction)

    fun catalogDoctor(
        documents: DocumentRepository, assets: FileAssetRepository, catalog: DocumentCatalogProvider, transaction: ApplicationTransaction,
    ) = CatalogDoctorChecker(documents, assets, catalog, transaction)

    fun connectorDoctor(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, resilience: ConnectorResilienceRegistry,
    ) = ConnectorDoctorChecker(resilience.protectAll(plugins.mergeConnectors(connectors)).values.toList())

    fun deliveryProfileDoctor(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
    ) = DeliveryProfileDoctorChecker(profiles, connectors)

    fun doctorServiceWithoutLegacyAgent(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = createDoctorApplicationService(
        tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector,
        deliveryProfile, null, null, transaction, clock, metrics, plugins,
    )

    fun doctorServiceWithoutLegacyAgentWithMetadata(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker, metadata: MetadataDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = createDoctorApplicationService(
        tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector,
        deliveryProfile, metadata, null, transaction, clock, metrics, plugins,
    )

    fun doctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker,
        agent: AgentDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = createDoctorApplicationService(
        tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector,
        deliveryProfile, null, agent, transaction, clock, metrics, plugins,
    )

    fun doctorServiceWithMetadata(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker, metadata: MetadataDoctorChecker,
        agent: AgentDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = createDoctorApplicationService(
        tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector,
        deliveryProfile, metadata, agent, transaction, clock, metrics, plugins,
    )

    private fun createDoctorApplicationService(
        tenants: TenantProvider,
        permission: PermissionDoctorChecker,
        deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker,
        workflow: WorkflowDoctorChecker,
        catalog: ObjectProvider<CatalogDoctorChecker>,
        connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker,
        metadata: MetadataDoctorChecker?,
        agent: AgentDoctorChecker?,
        transaction: ApplicationTransaction,
        clock: Clock,
        metrics: FileWeftMetrics,
        plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService {
        val catalogChecker = catalog.getIfAvailable()
        // Every registered checker — built-in or plugin-contributed — runs with a
        // bounded time budget so a stalled check cannot pin a diagnosis request.
        // The permission gate stays on the caller thread because it guards access
        // to the check list and performs no slow I/O.
        val builtInCheckers = listOf<DoctorChecker>(
            deploymentSafety,
            TransactionalDoctorChecker(lifecycle, transaction),
            TransactionalDoctorChecker(workflow, transaction),
            storage,
        ) + listOfNotNull(catalogChecker) + listOf(
            deliveryProfile,
            connector,
        ) + listOfNotNull<DoctorChecker>(metadata, agent)
        return DoctorApplicationService(
            tenants, permission,
            (builtInCheckers + plugins.doctorCheckers()).map { checker -> TimeoutDoctorChecker(checker) },
            clock,
            metrics,
        )
    }

    /**
     * Document write paths require the mutation capability at assembly time so a
     * read-only DocumentRepository fails fast instead of silently disabling the
     * document-number uniqueness check or throwing from a former default method
     * at request time.
     */
    internal fun requireDocumentMutations(documents: DocumentRepository): DocumentMutationRepository =
        documents as? DocumentMutationRepository
            ?: throw IllegalStateException(
                "DocumentRepository must also implement DocumentMutationRepository for safe document writes.",
            )

    fun documentDoctorTaskHandler(
        doctor: DoctorApplicationService, reports: DoctorReportRepository,
        transaction: ApplicationTransaction, clock: Clock,
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): DocumentDoctorTaskHandler {
        val mutations = taskMutations.getIfUnique() ?: throw IllegalStateException(
            "Exactly one TaskMutationRepository is required for fenced task projections.",
        )
        return DocumentDoctorTaskHandler(doctor, reports, transaction, clock, mutations)
    }

    fun scheduleDocumentDoctorService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, tasks: TaskRepository, identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ) = ScheduleDocumentDoctorService(
        tenants, users, authorization, documents, tasks, identifiers, transaction, clock, auditTrail,
    )

    fun documentSyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connector: FileConnector, properties: FileWeftProperties, resilience: ConnectorResilienceRegistry, records: SyncRecordRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ) = DocumentSyncService(
        requireDocumentMutations(documents), fileObjects, storage, resilience.protect(properties.sync.connectorName, connector),
        properties.sync.connectorName, records, identifiers, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
        metrics = metrics,
        sourceAccessUrlTtl = Duration.ofMillis(properties.sync.sourceAccessUrlTtlMillis),
    )

    fun documentPublishHandler(sync: DocumentSyncService) = DocumentPublishOutboxEventHandler(sync)

    fun outboxWorker(
        repository: OutboxProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<OutboxEventHandler>, plugins: FileWeftPluginRegistry, clock: Clock, traces: ObjectProvider<TraceContextScope>,
        properties: FileWeftProperties,
    ) = OutboxWorker(
        repository = repository,
        transaction = transaction,
        handlers = handlers + plugins.outboxEventHandlers(),
        clock = clock,
        traceContextScope = traces.getIfAvailable(),
        workerId = properties.outbox.workerId?.takeIf { it.isNotBlank() } ?: "fileweft-outbox-${UUID.randomUUID()}",
        leaseDuration = Duration.ofMillis(properties.outbox.leaseDurationMillis),
        legacyRunningGrace = Duration.ofMillis(properties.outbox.legacyRunningGraceMillis),
        logger = Slf4jFileWeftLogger(OutboxWorker::class.java.name),
    )

    fun taskWorker(
        repository: TaskProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<FileWeftTaskHandler>, plugins: FileWeftPluginRegistry,
        clock: Clock, properties: FileWeftProperties, metrics: FileWeftMetrics,
    ) = TaskWorker(
        repository = repository,
        transaction = transaction,
        handlers = handlers + plugins.taskHandlers(),
        clock = clock,
        workerId = properties.task.workerId?.takeIf { it.isNotBlank() } ?: "fileweft-${UUID.randomUUID()}",
        maxAttempts = properties.task.maxAttempts,
        initialRetryDelay = Duration.ofMillis(properties.task.initialRetryDelayMillis),
        maxRetryDelay = Duration.ofMillis(properties.task.maxRetryDelayMillis),
        leaseDuration = Duration.ofMillis(properties.task.leaseDurationMillis),
        metrics = metrics,
        legacyRunningGrace = Duration.ofMillis(properties.task.legacyRunningGraceMillis),
    )

    private companion object {
        const val DEFAULT_DELIVERY_PROFILE_ID = "default"
        const val DEFAULT_DELIVERY_TARGET_ID = "default"
    }
}
