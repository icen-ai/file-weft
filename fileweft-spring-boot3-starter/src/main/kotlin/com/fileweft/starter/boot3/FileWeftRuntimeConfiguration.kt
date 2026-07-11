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
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.application.doctor.*
import com.fileweft.application.delivery.*
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.offline.OfflineDocumentService
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
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.domain.operation.OperationLogRepository
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.persistence.jdbc.*
import com.fileweft.runtime.plugin.FileWeftPluginRegistry
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.doctor.DoctorChecker
import com.fileweft.spi.event.OutboxEventHandler
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.spi.observability.TraceContextScope
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.spi.task.FileWeftTaskHandler
import com.fileweft.spi.workflow.DocumentReviewRouteProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.beans.factory.ObjectProvider
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
    fun documentDeliveryTargets(clock: Clock): DocumentDeliveryTargetRepository = JdbcDocumentDeliveryTargetRepository(clock)

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository::class)
    fun outboxEvents(objectMapper: ObjectMapper, traces: TraceContextProvider): OutboxEventRepository =
        TraceAwareOutboxEventRepository(JdbcOutboxEventRepository(objectMapper), traces)

    @Bean
    @ConditionalOnMissingBean(OutboxProcessingRepository::class)
    fun outboxProcessing(objectMapper: ObjectMapper): OutboxProcessingRepository = JdbcOutboxProcessingRepository(objectMapper)

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
    ) = DocumentDeliverySyncService(
        documents, fileObjects, storage, connectors, deliveries, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryRemovalService::class)
    fun documentDeliveryRemovalService(
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
    ) = DocumentDeliveryRemovalService(
        connectors, deliveries, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryOutboxEventHandler::class)
    fun documentDeliveryHandler(sync: DocumentDeliverySyncService, removal: DocumentDeliveryRemovalService) =
        DocumentDeliveryOutboxEventHandler(sync, removal)

    @Bean
    @ConditionalOnMissingBean(RetryDocumentDeliveryService::class)
    fun retryDocumentDeliveryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ) = RetryDocumentDeliveryService(tenants, users, authorization, deliveries, outbox, identifiers, transaction, clock, auditTrail)

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
    @ConditionalOnMissingBean(DocumentCommandService::class)
    fun documentCommands(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = DocumentCommandService(tenants, users, authorization, documents, transaction, auditTrail)

    @Bean
    @ConditionalOnMissingBean(DocumentDownloadService::class)
    fun documentDownloads(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = DocumentDownloadService(tenants, users, authorization, documents, fileObjects, storage, transaction, auditTrail)

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
        documents: DocumentRepository, planner: DocumentDeliveryPlanner,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ) = PublishDocumentService(tenants, users, authorization, documents, planner, transaction, auditTrail)

    @Bean
    @ConditionalOnMissingBean(OfflineDocumentService::class)
    fun offlineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ) = OfflineDocumentService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

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
    @ConditionalOnMissingBean(ConnectorDoctorChecker::class)
    fun connectorDoctor(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, resilience: ConnectorResilienceRegistry,
    ) = ConnectorDoctorChecker(resilience.protectAll(plugins.mergeConnectors(connectors)).values.toList())

    @Bean
    @ConditionalOnMissingBean(DoctorApplicationService::class)
    fun doctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, connector: ConnectorDoctorChecker, agent: AgentDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ) = DoctorApplicationService(
        tenants, permission,
        listOf<DoctorChecker>(
            TransactionalDoctorChecker(lifecycle, transaction),
            storage,
            connector,
            agent,
        ) + plugins.doctorCheckers(),
        clock,
        metrics,
    )

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
    )

    @Bean
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentPublishOutboxEventHandler::class)
    fun documentPublishHandler(sync: DocumentSyncService) = DocumentPublishOutboxEventHandler(sync)

    @Bean
    @ConditionalOnMissingBean(OutboxWorker::class)
    fun outboxWorker(
        repository: OutboxProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<OutboxEventHandler>, plugins: FileWeftPluginRegistry, clock: Clock, traces: ObjectProvider<TraceContextScope>,
    ) = OutboxWorker(repository, transaction, handlers + plugins.outboxEventHandlers(), clock, traceContextScope = traces.getIfAvailable())

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
    )

    private companion object {
        const val DEFAULT_DELIVERY_PROFILE_ID = "default"
        const val DEFAULT_DELIVERY_TARGET_ID = "default"
    }
}
