package com.fileweft.starter.boot2

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
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.delivery.DocumentDeliveryOutboxEventHandler
import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.delivery.DocumentDeliveryRemovalPlanner
import com.fileweft.application.delivery.DocumentDeliveryRemovalService
import com.fileweft.application.delivery.DocumentDeliverySyncService
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
import com.fileweft.application.delivery.MapDeliveryConnectorResolver
import com.fileweft.application.delivery.RetryDocumentDeliveryService
import com.fileweft.application.delivery.StaticDocumentDeliveryProfileProvider
import com.fileweft.application.doctor.ConnectorDoctorChecker
import com.fileweft.application.doctor.DoctorApplicationService
import com.fileweft.application.doctor.DoctorReportRepository
import com.fileweft.application.doctor.DocumentDoctorTaskHandler
import com.fileweft.application.doctor.LifecycleDoctorChecker
import com.fileweft.application.doctor.PermissionDoctorChecker
import com.fileweft.application.doctor.StorageDoctorChecker
import com.fileweft.application.doctor.ScheduleDocumentDoctorService
import com.fileweft.application.doctor.TransactionalDoctorChecker
import com.fileweft.application.doctor.UnavailableDoctorChecker
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.offline.RestoreOfflineDocumentService
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.outbox.OutboxProcessingRepository
import com.fileweft.application.outbox.TraceAwareOutboxEventRepository
import com.fileweft.application.outbox.OutboxWorker
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.sync.DocumentPublishOutboxEventHandler
import com.fileweft.application.sync.DocumentSyncService
import com.fileweft.application.sync.SyncRecordRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.task.TaskProcessingRepository
import com.fileweft.application.task.TaskRepository
import com.fileweft.application.task.TaskWorker
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
import com.fileweft.persistence.jdbc.JdbcApplicationTransaction
import com.fileweft.persistence.jdbc.JdbcAgentResultRepository
import com.fileweft.persistence.jdbc.JdbcAuditRecordRepository
import com.fileweft.persistence.jdbc.JdbcDocumentRepository
import com.fileweft.persistence.jdbc.JdbcDoctorReportRepository
import com.fileweft.persistence.jdbc.JdbcDocumentDeliveryTargetRepository
import com.fileweft.persistence.jdbc.JdbcFileAssetRepository
import com.fileweft.persistence.jdbc.JdbcFileObjectRepository
import com.fileweft.persistence.jdbc.JdbcOutboxEventRepository
import com.fileweft.persistence.jdbc.JdbcOutboxProcessingRepository
import com.fileweft.persistence.jdbc.JdbcOperationLogRepository
import com.fileweft.persistence.jdbc.JdbcResumableUploadSessionRepository
import com.fileweft.persistence.jdbc.JdbcSyncRecordRepository
import com.fileweft.persistence.jdbc.JdbcTaskRepository
import com.fileweft.persistence.jdbc.JdbcWorkflowInstanceRepository
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
    @ConditionalOnMissingBean(OperationLogRepository::class)
    fun fileWeftOperationLogRepository(objectMapper: ObjectMapper): OperationLogRepository = JdbcOperationLogRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(DoctorReportRepository::class)
    fun fileWeftDoctorReportRepository(objectMapper: ObjectMapper, clock: Clock): DoctorReportRepository =
        JdbcDoctorReportRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(SyncRecordRepository::class)
    fun fileWeftSyncRecordRepository(clock: Clock): SyncRecordRepository = JdbcSyncRecordRepository(clock)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryTargetRepository::class)
    fun fileWeftDocumentDeliveryTargetRepository(clock: Clock): DocumentDeliveryTargetRepository =
        JdbcDocumentDeliveryTargetRepository(clock)

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository::class)
    fun fileWeftOutboxEventRepository(objectMapper: ObjectMapper, traces: TraceContextProvider): OutboxEventRepository =
        TraceAwareOutboxEventRepository(JdbcOutboxEventRepository(objectMapper), traces)

    @Bean
    @ConditionalOnMissingBean(OutboxProcessingRepository::class)
    fun fileWeftOutboxProcessingRepository(objectMapper: ObjectMapper): OutboxProcessingRepository = JdbcOutboxProcessingRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(value = [TaskRepository::class, TaskProcessingRepository::class])
    fun fileWeftTaskRepository(objectMapper: ObjectMapper, clock: Clock): JdbcTaskRepository = JdbcTaskRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(AgentResultRepository::class)
    fun fileWeftAgentResultRepository(objectMapper: ObjectMapper, clock: Clock): AgentResultRepository =
        JdbcAgentResultRepository(objectMapper, clock)

    @Bean
    @ConditionalOnMissingBean(ResumableUploadSessionRepository::class)
    fun fileWeftResumableUploadSessionRepository(objectMapper: ObjectMapper): ResumableUploadSessionRepository =
        JdbcResumableUploadSessionRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(ConfirmAgentSuggestionService::class)
    fun fileWeftConfirmAgentSuggestionService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        results: AgentResultRepository, identifiers: IdentifierGenerator, transaction: ApplicationTransaction,
        clock: Clock, auditTrail: AuditTrail,
    ): ConfirmAgentSuggestionService =
        ConfirmAgentSuggestionService(tenants, users, authorization, results, identifiers, transaction, clock, auditTrail)

    @Bean
    @ConditionalOnMissingBean(AgentTaskOrchestrator::class)
    fun fileWeftAgentTaskOrchestrator(
        agents: List<com.fileweft.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry, clock: Clock,
    ): AgentTaskOrchestrator = AgentTaskOrchestrator(agents + plugins.agents(), clock)

    @Bean
    @ConditionalOnMissingBean(AgentDoctorChecker::class)
    fun fileWeftAgentDoctorChecker(
        agents: List<com.fileweft.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry,
    ): AgentDoctorChecker = AgentDoctorChecker(agents + plugins.agents())

    @Bean
    @ConditionalOnMissingBean(AgentTaskScheduler::class)
    fun fileWeftAgentTaskScheduler(identifiers: IdentifierGenerator, clock: Clock): AgentTaskScheduler =
        AgentTaskScheduler(identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(AgentTaskHandler::class)
    fun fileWeftAgentTaskHandler(
        orchestrator: AgentTaskOrchestrator, results: AgentResultRepository,
        transaction: ApplicationTransaction, clock: Clock,
    ): AgentTaskHandler = AgentTaskHandler(orchestrator, results, transaction, clock)

    @Bean
    @ConditionalOnMissingBean(AgentTaskOutboxEventHandler::class)
    fun fileWeftAgentTaskOutboxEventHandler(
        triggers: List<com.fileweft.spi.ai.AgentTaskTrigger>, plugins: FileWeftPluginRegistry, scheduler: AgentTaskScheduler,
        tasks: TaskRepository, transaction: ApplicationTransaction,
    ): AgentTaskOutboxEventHandler = AgentTaskOutboxEventHandler(triggers + plugins.agentTaskTriggers(), scheduler, tasks, transaction)

    @Bean
    @ConditionalOnMissingBean(PersistedAgentSuggestionConfirmationService::class)
    fun fileWeftAgentSuggestionConfirmations(
        results: AgentResultRepository, transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator, clock: Clock,
    ): PersistedAgentSuggestionConfirmationService =
        PersistedAgentSuggestionConfirmationService(results, transaction, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(AuditTrail::class)
    fun fileWeftAuditTrail(
        repository: AuditRecordRepository,
        operationLogs: OperationLogRepository,
        traceContextProvider: TraceContextProvider,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): AuditTrail = AuditTrail(repository, identifiers, clock, operationLogs, traceContextProvider)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ConnectorInvocationExecutor::class)
    fun fileWeftConnectorInvocationExecutor(properties: FileWeftProperties): ConnectorInvocationExecutor = ConnectorInvocationExecutor(
        maxConcurrentInvocations = properties.sync.connectorMaxConcurrentInvocations,
        queueCapacity = properties.sync.connectorInvocationQueueCapacity,
    )

    @Bean
    @ConditionalOnMissingBean(ConnectorResiliencePolicy::class)
    fun fileWeftConnectorResiliencePolicy(properties: FileWeftProperties): ConnectorResiliencePolicy = ConnectorResiliencePolicy(
        timeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        failureThreshold = properties.sync.circuitBreakerFailureThreshold,
        circuitOpenDuration = Duration.ofMillis(properties.sync.circuitBreakerOpenDurationMillis),
    )

    @Bean
    @ConditionalOnMissingBean(ConnectorResilienceRegistry::class)
    fun fileWeftConnectorResilienceRegistry(
        policy: ConnectorResiliencePolicy, executor: ConnectorInvocationExecutor, clock: Clock,
    ): ConnectorResilienceRegistry = ConnectorResilienceRegistry(policy, executor, clock)

    @Bean
    @ConditionalOnMissingBean(DeliveryConnectorResolver::class)
    fun fileWeftDeliveryConnectorResolver(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, properties: FileWeftProperties,
        resilience: ConnectorResilienceRegistry,
    ): DeliveryConnectorResolver = MapDeliveryConnectorResolver(
        resilience.protectAll(plugins.mergeConnectors(connectors)),
        properties.sync.connectorName,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryProfileProvider::class)
    fun fileWeftDocumentDeliveryProfiles(properties: FileWeftProperties): DocumentDeliveryProfileProvider {
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
    fun fileWeftDocumentDeliveryPlanner(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ): DocumentDeliveryPlanner = DocumentDeliveryPlanner(profiles, connectors, deliveries, outbox, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryRemovalPlanner::class)
    fun fileWeftDocumentDeliveryRemovalPlanner(
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ): DocumentDeliveryRemovalPlanner = DocumentDeliveryRemovalPlanner(deliveries, outbox, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliverySyncService::class)
    fun fileWeftDocumentDeliverySyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ): DocumentDeliverySyncService = DocumentDeliverySyncService(
        documents, fileObjects, storage, connectors, deliveries, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
        removalPlanner = removalPlanner,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryRemovalService::class)
    fun fileWeftDocumentDeliveryRemovalService(
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
    ): DocumentDeliveryRemovalService = DocumentDeliveryRemovalService(
        connectors, deliveries, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryOutboxEventHandler::class)
    fun fileWeftDocumentDeliveryOutboxEventHandler(
        sync: DocumentDeliverySyncService,
        removal: DocumentDeliveryRemovalService,
    ): DocumentDeliveryOutboxEventHandler = DocumentDeliveryOutboxEventHandler(sync, removal)

    @Bean
    @ConditionalOnMissingBean(RetryDocumentDeliveryService::class)
    fun fileWeftRetryDocumentDeliveryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ): RetryDocumentDeliveryService = RetryDocumentDeliveryService(
        tenants, users, authorization, documents, deliveries, outbox, identifiers, transaction, clock, auditTrail,
    )

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
    @ConditionalOnMissingBean(ResumableUploadService::class)
    fun fileWeftResumableUploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        storage: StorageAdapter, sessions: ResumableUploadSessionRepository,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock,
        properties: FileWeftProperties, metrics: FileWeftMetrics,
    ): ResumableUploadService = ResumableUploadService(
        tenants, users, authorization, storage, sessions, fileObjects, assets, outbox, identifiers, transaction, clock,
        sessionTtl = Duration.ofMillis(properties.upload.resumableSessionTtlMillis),
        metrics = metrics,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentCommandService::class)
    fun fileWeftDocumentCommandService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): DocumentCommandService = DocumentCommandService(tenants, users, authorization, documents, transaction, auditTrail)

    @Bean
    @ConditionalOnMissingBean(DocumentDownloadService::class)
    fun fileWeftDocumentDownloadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): DocumentDownloadService = DocumentDownloadService(
        tenants, users, authorization, documents, fileObjects, storage, transaction, auditTrail,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDraftService::class)
    fun fileWeftDocumentDraftService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        documents: DocumentRepository, fileObjects: FileObjectRepository, assets: FileAssetRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ): DocumentDraftService = DocumentDraftService(
        tenants, users, authorization, storage, documents, fileObjects, assets, identifiers, transaction, auditTrail, metrics,
    )

    @Bean
    @ConditionalOnMissingBean(PublishDocumentService::class)
    fun fileWeftPublishService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, planner: DocumentDeliveryPlanner,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): PublishDocumentService = PublishDocumentService(tenants, users, authorization, documents, planner, transaction, auditTrail)

    @Bean
    @ConditionalOnMissingBean(OfflineDocumentService::class)
    fun fileWeftOfflineService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ): OfflineDocumentService = OfflineDocumentService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    @Bean
    @ConditionalOnMissingBean(RestoreOfflineDocumentService::class)
    fun fileWeftRestoreOfflineDocumentService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): RestoreOfflineDocumentService = RestoreOfflineDocumentService(
        tenants, users, authorization, documents, deliveries, transaction, auditTrail,
    )

    @Bean
    @ConditionalOnMissingBean(ArchiveDocumentService::class)
    fun fileWeftArchiveService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        removalPlanner: DocumentDeliveryRemovalPlanner,
    ): ArchiveDocumentService = ArchiveDocumentService(tenants, users, authorization, documents, transaction, auditTrail, removalPlanner)

    @Bean
    fun fileWeftDefaultDocumentReviewRouteProvider(): DocumentReviewRouteProvider = DefaultDocumentReviewRouteProvider

    @Bean
    @ConditionalOnMissingBean(DocumentReviewRouteResolver::class)
    fun fileWeftDocumentReviewRouteResolver(
        providers: List<DocumentReviewRouteProvider>,
        plugins: FileWeftPluginRegistry,
        properties: FileWeftProperties,
    ): DocumentReviewRouteResolver = DocumentReviewRouteResolver(
        providers = providers + plugins.reviewRouteProviders(),
        defaultRouteId = properties.workflow.defaultReviewRouteId,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentReviewWorkflowService::class)
    fun fileWeftReviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        reviewRoutes: DocumentReviewRouteResolver,
    ): DocumentReviewWorkflowService = DocumentReviewWorkflowService(
        tenants, users, authorization, documents, workflows, planner, identifiers, transaction, auditTrail, reviewRoutes,
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
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter, transaction: ApplicationTransaction,
    ): StorageDoctorChecker = StorageDoctorChecker(documents, fileObjects, storage, transaction)

    @Bean
    @ConditionalOnMissingBean(ConnectorDoctorChecker::class)
    fun fileWeftConnectorDoctorChecker(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, resilience: ConnectorResilienceRegistry,
    ): ConnectorDoctorChecker = ConnectorDoctorChecker(resilience.protectAll(plugins.mergeConnectors(connectors)).values.toList())

    @Bean
    @ConditionalOnMissingBean(DoctorApplicationService::class)
    fun fileWeftDoctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, connector: ConnectorDoctorChecker, agent: AgentDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = DoctorApplicationService(
        tenants,
        permission,
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
    fun fileWeftDocumentDoctorTaskHandler(
        doctor: DoctorApplicationService, reports: DoctorReportRepository,
        transaction: ApplicationTransaction, clock: Clock,
    ): DocumentDoctorTaskHandler = DocumentDoctorTaskHandler(doctor, reports, transaction, clock)

    @Bean
    @ConditionalOnMissingBean(ScheduleDocumentDoctorService::class)
    fun fileWeftScheduleDocumentDoctorService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, tasks: TaskRepository, identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ): ScheduleDocumentDoctorService = ScheduleDocumentDoctorService(
        tenants, users, authorization, documents, tasks, identifiers, transaction, clock, auditTrail,
    )

    @Bean
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentSyncService::class)
    fun fileWeftDocumentSyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connector: FileConnector, properties: FileWeftProperties, resilience: ConnectorResilienceRegistry, records: SyncRecordRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail, metrics: FileWeftMetrics,
    ): DocumentSyncService = DocumentSyncService(
        documents, fileObjects, storage, resilience.protect(properties.sync.connectorName, connector),
        properties.sync.connectorName, records, identifiers, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
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
        handlers: List<OutboxEventHandler>, plugins: FileWeftPluginRegistry, clock: Clock, traces: ObjectProvider<TraceContextScope>,
    ): OutboxWorker = OutboxWorker(repository, transaction, handlers + plugins.outboxEventHandlers(), clock, traceContextScope = traces.getIfAvailable())

    @Bean
    @ConditionalOnBean(TaskProcessingRepository::class)
    @ConditionalOnMissingBean(TaskWorker::class)
    fun fileWeftTaskWorker(
        repository: TaskProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<FileWeftTaskHandler>, plugins: FileWeftPluginRegistry,
        clock: Clock, properties: FileWeftProperties, metrics: FileWeftMetrics,
    ): TaskWorker = TaskWorker(
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
