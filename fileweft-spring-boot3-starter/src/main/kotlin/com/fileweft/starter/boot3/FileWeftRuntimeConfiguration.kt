package com.fileweft.starter.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.adapter.connector.ConnectorInvocationExecutor
import com.fileweft.adapter.connector.ConnectorResiliencePolicy
import com.fileweft.adapter.connector.ConnectorResilienceRegistry
import com.fileweft.agent.AgentTaskHandler
import com.fileweft.agent.AgentDoctorChecker
import com.fileweft.agent.AgentTaskOrchestrator
import com.fileweft.agent.AgentTaskOutboxEventHandler
import com.fileweft.agent.AgentTaskScheduler
import com.fileweft.agent.PersistedAgentSuggestionConfirmationService
import com.fileweft.application.agent.AgentResultRepository
import com.fileweft.application.agent.ConfirmAgentSuggestionService
import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.catalog.DocumentCatalogBindingService
import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.application.catalog.DocumentCatalogLifecycleService
import com.fileweft.application.catalog.DocumentCatalogMutationService
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.application.document.DocumentDownloadVisibility
import com.fileweft.application.document.DocumentFolderReadAccess
import com.fileweft.application.document.DocumentQueryRepository
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.application.doctor.*
import com.fileweft.application.delivery.*
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.idempotency.RequestIdempotencyRepository
import com.fileweft.application.idempotency.RequestIdempotencyService
import com.fileweft.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import com.fileweft.application.lifecycle.IdempotentDocumentLifecycleService
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.offline.RestoreOfflineDocumentService
import com.fileweft.application.outbox.*
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.sync.*
import com.fileweft.application.task.*
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.upload.ResumableUploadService
import com.fileweft.application.upload.ResumableUploadSessionRepository
import com.fileweft.application.upload.UploadApplicationService
import com.fileweft.application.workflow.DefaultDocumentReviewRouteProvider
import com.fileweft.application.workflow.DocumentReviewRouteResolver
import com.fileweft.application.workflow.DocumentReviewWorkflowService
import com.fileweft.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import com.fileweft.application.workflow.IdempotentDocumentReviewWorkflowService
import com.fileweft.application.workflow.WorkflowQueryRepository
import com.fileweft.application.workflow.WorkflowQueryService
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.file.FileAssetMutationRepository
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.domain.operation.OperationLogRepository
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.persistence.jdbc.*
import com.fileweft.runtime.plugin.FileWeftPluginRegistry
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.doctor.DoctorChecker
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.observability.FileWeftGaugeRecorder
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.spi.observability.TraceContextScope
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.spi.task.FileWeftTaskHandler
import com.fileweft.spi.workflow.DocumentReviewRouteProvider
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.UUID
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
    @ConditionalOnMissingBean(DocumentQueryRepository::class)
    fun documentQueries(): DocumentQueryRepository = JdbcDocumentQueryRepository()

    @Bean
    @ConditionalOnMissingBean(DocumentSyncStatusQueryRepository::class)
    fun documentSyncStatusQueries(): DocumentSyncStatusQueryRepository = JdbcDocumentSyncStatusQueryRepository()

    @Bean
    @ConditionalOnMissingBean(WorkflowQueryRepository::class)
    fun workflowQueries(): WorkflowQueryRepository = JdbcWorkflowQueryRepository()

    @Bean
    @ConditionalOnMissingBean(FileObjectRepository::class)
    fun fileObjects(clock: Clock): FileObjectRepository = JdbcFileObjectRepository(clock)

    @Bean
    @ConditionalOnMissingBean(FileAssetRepository::class)
    fun fileAssets(objectMapper: ObjectMapper, clock: Clock): FileAssetRepository =
        JdbcFileAssetRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(WorkflowInstanceRepository::class)
    fun workflows(clock: Clock): WorkflowInstanceRepository = JdbcWorkflowInstanceRepository(clock)

    @Bean
    @ConditionalOnMissingBean(AuditRecordRepository::class)
    fun audits(objectMapper: ObjectMapper): AuditRecordRepository = JdbcAuditRecordRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(OperationLogRepository::class)
    fun operationLogs(objectMapper: ObjectMapper): OperationLogRepository = JdbcOperationLogRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(DoctorReportRepository::class)
    fun doctorReports(objectMapper: ObjectMapper, clock: Clock): DoctorReportRepository = JdbcDoctorReportRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(SyncRecordRepository::class)
    fun syncRecords(clock: Clock): SyncRecordRepository = JdbcSyncRecordRepository(clock)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryTargetRepository::class)
    fun documentDeliveryTargets(clock: Clock): JdbcDocumentDeliveryTargetRepository = JdbcDocumentDeliveryTargetRepository(clock)

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository::class)
    fun outboxEvents(objectMapper: ObjectMapper, traces: TraceContextProvider): OutboxEventRepository =
        TraceAwareOutboxEventRepository(JdbcOutboxEventRepository(objectMapper), traces)

    @Bean
    @ConditionalOnMissingBean(OutboxProcessingRepository::class)
    fun outboxProcessing(objectMapper: ObjectMapper): JdbcOutboxProcessingRepository = JdbcOutboxProcessingRepository(objectMapper)

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
    fun outboxBacklogReader(properties: FileWeftProperties): OutboxBacklogReader =
        JdbcOutboxBacklogReader(properties.outbox.backlogMetricsQueryTimeoutSeconds)

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
    ): OutboxBacklogMetricsPublisher = OutboxBacklogMetricsPublisher(
        transaction = transaction,
        reader = reader.getIfAvailable(),
        gauges = gauges.getIfAvailable(),
        clock = clock,
        samplingInterval = Duration.ofMillis(properties.outbox.backlogMetricsIntervalMillis),
        legacyRunningGrace = Duration.ofMillis(properties.outbox.legacyRunningGraceMillis),
    )

    @Bean
    @ConditionalOnMissingBean(value = [TaskRepository::class, TaskProcessingRepository::class])
    fun tasks(objectMapper: ObjectMapper, clock: Clock): JdbcTaskRepository = JdbcTaskRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(AgentResultRepository::class)
    fun agentResults(objectMapper: ObjectMapper, clock: Clock): AgentResultRepository = JdbcAgentResultRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(ResumableUploadSessionRepository::class)
    fun resumableUploadSessions(objectMapper: ObjectMapper): ResumableUploadSessionRepository =
        JdbcResumableUploadSessionRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(RequestIdempotencyRepository::class)
    fun requestIdempotencyRepository(): RequestIdempotencyRepository = JdbcRequestIdempotencyRepository()

    @Bean
    @ConditionalOnMissingBean(RequestIdempotencyService::class)
    fun requestIdempotencyService(
        repository: RequestIdempotencyRepository,
        transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): RequestIdempotencyService = RequestIdempotencyService(repository, transaction, identifiers, clock)

    @Bean(name = ["documentCatalogAccessService"])
    @ConditionalOnBean(DocumentCatalogProvider::class)
    @ConditionalOnMissingBean(DocumentCatalogAccessService::class)
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

    /** Retains the original factory ABI for hosts that invoked this configuration directly. */
    @Deprecated("Use the ObjectProvider-backed auto-configuration factory.")
    fun documentCatalogAccessService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalog: DocumentCatalogProvider,
    ) = DocumentCatalogAccessService(tenants, users, authorization, catalog)

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
    ): DocumentCatalogBindingService? = if (assets is FileAssetMutationRepository) {
        DocumentCatalogBindingService(tenants, users, catalogAccess, documents, assets, transaction, auditTrail)
    } else {
        null
    }

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogDraftService::class)
    fun documentCatalogDraftService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
    ) = DocumentCatalogDraftService(drafts, catalogAccess)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogMutationService::class)
    // See the binding service above: read/create remain available, mutations fail closed.
    fun documentCatalogMutationService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
        assets: FileAssetRepository,
    ): DocumentCatalogMutationService? =
        if (assets is FileAssetMutationRepository) DocumentCatalogMutationService(drafts, catalogAccess) else null

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
    ) = IdempotentDocumentLifecycleService(commands, publish, offline, restore, archive, idempotency)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(
        value = [IdempotentDocumentLifecycleService::class, IdempotentDocumentCatalogLifecycleService::class],
    )
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

    @Bean
    @ConditionalOnBean(value = [DocumentDeliveryTargetMutationRepository::class, OutboxEventMutationRepository::class])
    @ConditionalOnMissingBean(
        value = [
            DocumentCatalogAccessService::class,
            IdempotentDocumentDeliveryRecoveryService::class,
            IdempotentDocumentCatalogDeliveryRecoveryService::class,
        ],
    )
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
        tenants, users, authorization, documents, deliveries, outboxMutations, outbox,
        identifiers, clock, idempotency, auditTrail,
    )

    @Bean
    @ConditionalOnBean(
        value = [
            DocumentCatalogAccessService::class,
            DocumentDeliveryTargetMutationRepository::class,
            OutboxEventMutationRepository::class,
        ],
    )
    @ConditionalOnMissingBean(
        value = [IdempotentDocumentDeliveryRecoveryService::class, IdempotentDocumentCatalogDeliveryRecoveryService::class],
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
        if (assets is FileAssetMutationRepository) {
            IdempotentDocumentCatalogDeliveryRecoveryService(
                tenants, users, authorization, documents, assets, deliveries, outboxMutations, outbox,
                identifiers, transaction, clock, idempotency, auditTrail,
                requiredSecurityCandidate(catalogAccesses, DocumentCatalogAccessService::class.java),
            )
        } else {
            null
        }

    @Bean
    @ConditionalOnMissingBean(
        value = [
            DocumentCatalogAccessService::class,
            IdempotentDocumentReviewWorkflowService::class,
            IdempotentDocumentCatalogReviewWorkflowService::class,
        ],
    )
    fun idempotentDocumentReviewWorkflowService(
        reviews: DocumentReviewWorkflowService,
        idempotency: RequestIdempotencyService,
    ) = IdempotentDocumentReviewWorkflowService(reviews, idempotency)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(
        value = [
            IdempotentDocumentReviewWorkflowService::class,
            IdempotentDocumentCatalogReviewWorkflowService::class,
        ],
    )
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

    @Bean
    @ConditionalOnMissingBean(ConfirmAgentSuggestionService::class)
    fun confirmAgentSuggestionService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        results: AgentResultRepository, identifiers: IdentifierGenerator, transaction: ApplicationTransaction,
        clock: Clock, auditTrail: AuditTrail,
    ) = ConfirmAgentSuggestionService(tenants, users, authorization, results, identifiers, transaction, clock, auditTrail)

    @Bean
    @ConditionalOnMissingBean(AgentTaskOrchestrator::class)
    fun agentTaskOrchestrator(
        agents: List<com.fileweft.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry, clock: Clock,
    ) = AgentTaskOrchestrator(agents + plugins.agents(), clock)

    @Bean
    @ConditionalOnMissingBean(AgentDoctorChecker::class)
    fun agentDoctorChecker(
        agents: List<com.fileweft.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry,
    ) = AgentDoctorChecker(agents + plugins.agents())

    @Bean
    @ConditionalOnMissingBean(AgentTaskScheduler::class)
    fun agentTaskScheduler(identifiers: IdentifierGenerator, clock: Clock) = AgentTaskScheduler(identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(AgentTaskHandler::class)
    fun agentTaskHandler(
        orchestrator: AgentTaskOrchestrator, results: AgentResultRepository,
        transaction: ApplicationTransaction, clock: Clock,
    ) = AgentTaskHandler(orchestrator, results, transaction, clock)

    @Bean
    @ConditionalOnMissingBean(AgentTaskOutboxEventHandler::class)
    fun agentTaskOutboxEventHandler(
        triggers: List<com.fileweft.spi.ai.AgentTaskTrigger>, plugins: FileWeftPluginRegistry, scheduler: AgentTaskScheduler,
        tasks: TaskRepository, transaction: ApplicationTransaction,
    ) = AgentTaskOutboxEventHandler(triggers + plugins.agentTaskTriggers(), scheduler, tasks, transaction)

    @Bean
    @ConditionalOnMissingBean(PersistedAgentSuggestionConfirmationService::class)
    fun agentSuggestionConfirmations(
        results: AgentResultRepository, transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator, clock: Clock,
    ) = PersistedAgentSuggestionConfirmationService(results, transaction, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(AuditTrail::class)
    fun auditTrail(
        repository: AuditRecordRepository,
        operationLogs: OperationLogRepository,
        traceContextProvider: TraceContextProvider,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ) = AuditTrail(repository, identifiers, clock, operationLogs, traceContextProvider)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ConnectorInvocationExecutor::class)
    fun connectorInvocationExecutor(properties: FileWeftProperties) = ConnectorInvocationExecutor(
        maxConcurrentInvocations = properties.sync.connectorMaxConcurrentInvocations,
        queueCapacity = properties.sync.connectorInvocationQueueCapacity,
    )

    @Bean
    @ConditionalOnMissingBean(ConnectorResiliencePolicy::class)
    fun connectorResiliencePolicy(properties: FileWeftProperties) = ConnectorResiliencePolicy(
        timeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        failureThreshold = properties.sync.circuitBreakerFailureThreshold,
        circuitOpenDuration = Duration.ofMillis(properties.sync.circuitBreakerOpenDurationMillis),
    )

    @Bean
    @ConditionalOnMissingBean(ConnectorResilienceRegistry::class)
    fun connectorResilienceRegistry(
        policy: ConnectorResiliencePolicy, executor: ConnectorInvocationExecutor, clock: Clock,
    ) = ConnectorResilienceRegistry(policy, executor, clock)

    @Bean
    @ConditionalOnMissingBean(DeliveryConnectorResolver::class)
    fun deliveryConnectorResolver(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, properties: FileWeftProperties,
        resilience: ConnectorResilienceRegistry,
    ): DeliveryConnectorResolver = MapDeliveryConnectorResolver(
        resilience.protectAll(plugins.mergeConnectors(connectors)),
        properties.sync.connectorName,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryProfileProvider::class)
    fun documentDeliveryProfiles(properties: FileWeftProperties): DocumentDeliveryProfileProvider {
        val configured = properties.sync.profiles.map { profile ->
            DocumentDeliveryProfile(
                id = profile.id,
                displayName = profile.displayName,
                targets = profile.targets.map { target ->
                    com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition(
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
                        com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition(
                            id = DEFAULT_DELIVERY_TARGET_ID,
                            displayName = "Default downstream",
                            connectorId = properties.sync.connectorName,
                            requirement = DeliveryRequirement.REQUIRED,
                        ),
                    ),
                ),
            )
        }
        val defaultProfileId = properties.sync.defaultProfileId.takeIf { configured.any { profile -> profile.id == it } }
        return StaticDocumentDeliveryProfileProvider(configured, defaultProfileId)
    }

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryPlanner::class)
    fun documentDeliveryPlanner(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ) = DocumentDeliveryPlanner(profiles, connectors, deliveries, outbox, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryRemovalPlanner::class)
    fun documentDeliveryRemovalPlanner(
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ) = DocumentDeliveryRemovalPlanner(deliveries, outbox, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliverySyncService::class)
    fun documentDeliverySyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        removalPlanner: DocumentDeliveryRemovalPlanner, metrics: FileWeftMetrics,
    ) = DocumentDeliverySyncService(
        documents, fileObjects, storage, connectors, deliveries, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
        removalPlanner = removalPlanner,
        metrics = metrics,
        sourceAccessUrlTtl = Duration.ofMillis(properties.sync.sourceAccessUrlTtlMillis),
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryRemovalService::class)
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

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryOutboxEventHandler::class)
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
        return DocumentDeliveryOutboxEventHandler(sync, removal, mutations, documents)
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "fileweft.sync",
        name = ["legacy-delivery-retry-enabled"],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(RetryDocumentDeliveryService::class)
    fun retryDocumentDeliveryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ) = RetryDocumentDeliveryService(tenants, users, authorization, documents, deliveries, outbox, identifiers, transaction, clock, auditTrail)

    @Bean
    @ConditionalOnMissingBean(UploadApplicationService::class)
    fun uploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, metrics: FileWeftMetrics,
    ) = UploadApplicationService(tenants, users, authorization, storage, fileObjects, assets, outbox, identifiers, transaction, clock, metrics)

    @Bean
    @ConditionalOnMissingBean(ResumableUploadService::class)
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

    @Bean
    @ConditionalOnMissingBean(DocumentQueryService::class)
    fun documentQueryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        queries: DocumentQueryRepository, transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = DocumentQueryService(
        tenants, users, authorization, queries, transaction, folderReadAccess.getIfAvailable(),
    )

    @Bean
    @ConditionalOnMissingBean(DocumentSyncStatusQueryService::class)
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

    @Bean
    @ConditionalOnMissingBean(WorkflowQueryService::class)
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

    @Bean
    @ConditionalOnMissingBean(DocumentCommandService::class)
    fun documentCommands(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = DocumentCommandService(tenants, users, authorization, documents, transaction, auditTrail)

    @Bean
    @ConditionalOnBean(DocumentFolderReadAccess::class)
    @ConditionalOnMissingBean(DocumentDownloadVisibility::class)
    fun documentDownloadVisibility(
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
        queries: DocumentQueryRepository,
    ): DocumentDownloadVisibility {
        val access = singleSecurityCandidateOrNull(folderReadAccess, DocumentFolderReadAccess::class.java)
            ?: throw NoSuchBeanDefinitionException(DocumentFolderReadAccess::class.java)
        return DocumentDownloadVisibility(access, queries)
    }

    @Bean
    @ConditionalOnMissingBean(DocumentDownloadService::class)
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
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = PublishDocumentService(tenants, users, authorization, documents, planner, transaction, auditTrail, workflows)

    @Bean
    @ConditionalOnMissingBean(OfflineDocumentService::class)
    fun offlineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ) = OfflineDocumentService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    @Bean
    @ConditionalOnMissingBean(RestoreOfflineDocumentService::class)
    fun restoreOfflineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = RestoreOfflineDocumentService(tenants, users, authorization, documents, deliveries, transaction, auditTrail)

    @Bean
    @ConditionalOnMissingBean(ArchiveDocumentService::class)
    fun archiveService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ) = ArchiveDocumentService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    @Bean
    fun defaultDocumentReviewRouteProvider(): DocumentReviewRouteProvider = DefaultDocumentReviewRouteProvider

    @Bean
    @ConditionalOnMissingBean(DocumentReviewRouteResolver::class)
    fun documentReviewRouteResolver(
        providers: List<DocumentReviewRouteProvider>,
        plugins: FileWeftPluginRegistry,
        properties: FileWeftProperties,
    ): DocumentReviewRouteResolver = DocumentReviewRouteResolver(
        providers = providers + plugins.reviewRouteProviders(),
        defaultRouteId = properties.workflow.defaultReviewRouteId,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentReviewWorkflowService::class)
    fun reviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        reviewRoutes: DocumentReviewRouteResolver,
    ) = DocumentReviewWorkflowService(
        tenants, users, authorization, documents, workflows, planner, identifiers, transaction, auditTrail, reviewRoutes,
    )

    @Bean
    @ConditionalOnMissingBean(PermissionDoctorChecker::class)
    fun permissionDoctor(users: UserRealmProvider, authorization: AuthorizationProvider) = PermissionDoctorChecker(users, authorization)

    @Bean
    @ConditionalOnMissingBean(LifecycleDoctorChecker::class)
    fun lifecycleDoctor(documents: DocumentRepository) = LifecycleDoctorChecker(documents)

    @Bean
    @ConditionalOnMissingBean(StorageDoctorChecker::class)
    fun storageDoctor(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter, transaction: ApplicationTransaction,
    ) = StorageDoctorChecker(documents, fileObjects, storage, transaction)

    @Bean
    @ConditionalOnMissingBean(WorkflowDoctorChecker::class)
    fun workflowDoctor(
        documents: DocumentRepository, workflows: WorkflowInstanceRepository,
    ) = WorkflowDoctorChecker(documents, workflows)

    @Bean
    @ConditionalOnSingleCandidate(DocumentCatalogProvider::class)
    @ConditionalOnMissingBean(CatalogDoctorChecker::class)
    fun catalogDoctor(
        documents: DocumentRepository, assets: FileAssetRepository, catalog: DocumentCatalogProvider, transaction: ApplicationTransaction,
    ) = CatalogDoctorChecker(documents, assets, catalog, transaction)

    @Bean
    @ConditionalOnMissingBean(ConnectorDoctorChecker::class)
    fun connectorDoctor(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, resilience: ConnectorResilienceRegistry,
    ) = ConnectorDoctorChecker(resilience.protectAll(plugins.mergeConnectors(connectors)).values.toList())

    @Bean
    @ConditionalOnMissingBean(DeliveryProfileDoctorChecker::class)
    fun deliveryProfileDoctor(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
    ) = DeliveryProfileDoctorChecker(profiles, connectors)

    @Bean
    @ConditionalOnMissingBean(DoctorApplicationService::class)
    fun doctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker,
        agent: AgentDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService {
        val catalogChecker = catalog.getIfAvailable()
        return DoctorApplicationService(
            tenants, permission,
            listOf<DoctorChecker>(
                TransactionalDoctorChecker(lifecycle, transaction),
                TransactionalDoctorChecker(workflow, transaction),
                storage,
            ) + listOfNotNull(catalogChecker) + listOf(
                deliveryProfile,
                connector,
                agent,
            ) + plugins.doctorCheckers(),
            clock,
            metrics,
        )
    }

    @Bean
    @ConditionalOnMissingBean(DocumentDoctorTaskHandler::class)
    fun documentDoctorTaskHandler(
        doctor: DoctorApplicationService, reports: DoctorReportRepository,
        transaction: ApplicationTransaction, clock: Clock,
    ) = DocumentDoctorTaskHandler(doctor, reports, transaction, clock)

    @Bean
    @ConditionalOnMissingBean(ScheduleDocumentDoctorService::class)
    fun scheduleDocumentDoctorService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, tasks: TaskRepository, identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ) = ScheduleDocumentDoctorService(
        tenants, users, authorization, documents, tasks, identifiers, transaction, clock, auditTrail,
    )

    @Bean
    @ConditionalOnProperty(
        prefix = "fileweft.sync",
        name = ["legacy-publish-handler-enabled"],
        havingValue = "true",
    )
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentSyncService::class)
    fun documentSyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connector: FileConnector, properties: FileWeftProperties, resilience: ConnectorResilienceRegistry, records: SyncRecordRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ) = DocumentSyncService(
        documents, fileObjects, storage, resilience.protect(properties.sync.connectorName, connector),
        properties.sync.connectorName, records, identifiers, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
        metrics = metrics,
        sourceAccessUrlTtl = Duration.ofMillis(properties.sync.sourceAccessUrlTtlMillis),
    )

    @Bean
    @ConditionalOnProperty(
        prefix = "fileweft.sync",
        name = ["legacy-publish-handler-enabled"],
        havingValue = "true",
    )
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentPublishOutboxEventHandler::class)
    fun documentPublishHandler(sync: DocumentSyncService) = DocumentPublishOutboxEventHandler(sync)

    @Bean
    @ConditionalOnMissingBean(OutboxWorker::class)
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
    )

    @Bean
    @ConditionalOnBean(TaskProcessingRepository::class)
    @ConditionalOnMissingBean(TaskWorker::class)
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
