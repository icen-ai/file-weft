package ai.icen.fw.starter.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.adapter.connector.ConnectorInvocationExecutor
import ai.icen.fw.adapter.connector.ConnectorResiliencePolicy
import ai.icen.fw.adapter.connector.ConnectorResilienceRegistry
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
import ai.icen.fw.application.delivery.MapDeliveryConnectorResolver
import ai.icen.fw.application.delivery.RetryDocumentDeliveryService
import ai.icen.fw.application.delivery.StaticDocumentDeliveryProfileProvider
import ai.icen.fw.application.doctor.ConnectorDoctorChecker
import ai.icen.fw.application.doctor.CatalogDoctorChecker
import ai.icen.fw.application.doctor.DeliveryProfileDoctorChecker
import ai.icen.fw.application.doctor.DeploymentSafetyDoctorChecker
import ai.icen.fw.application.doctor.DoctorApplicationService
import ai.icen.fw.application.doctor.DoctorReportRepository
import ai.icen.fw.application.doctor.DocumentDoctorTaskHandler
import ai.icen.fw.application.doctor.LifecycleDoctorChecker
import ai.icen.fw.application.doctor.PermissionDoctorChecker
import ai.icen.fw.application.doctor.StorageDoctorChecker
import ai.icen.fw.application.doctor.ScheduleDocumentDoctorService
import ai.icen.fw.application.doctor.TransactionalDoctorChecker
import ai.icen.fw.application.doctor.UnavailableDoctorChecker
import ai.icen.fw.application.doctor.WorkflowDoctorChecker
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import ai.icen.fw.application.lifecycle.IdempotentDocumentLifecycleService
import ai.icen.fw.application.offline.OfflineDocumentService
import ai.icen.fw.application.offline.RestoreOfflineDocumentService
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.application.outbox.OutboxBacklogMetricsPublisher
import ai.icen.fw.application.outbox.OutboxBacklogReader
import ai.icen.fw.application.outbox.OutboxProcessingRepository
import ai.icen.fw.application.outbox.TraceAwareOutboxEventRepository
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
import ai.icen.fw.application.workflow.DefaultDocumentReviewRouteProvider
import ai.icen.fw.application.workflow.DocumentReviewRouteResolver
import ai.icen.fw.application.workflow.DocumentReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentReviewWorkflowService
import ai.icen.fw.application.workflow.WorkflowQueryRepository
import ai.icen.fw.application.workflow.WorkflowQueryService
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetMutationRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.domain.operation.OperationLogRepository
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.persistence.jdbc.JdbcApplicationTransaction
import ai.icen.fw.persistence.jdbc.JdbcAgentResultRepository
import ai.icen.fw.persistence.jdbc.JdbcAuditRecordRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentQueryRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentSyncStatusQueryRepository
import ai.icen.fw.persistence.jdbc.JdbcDoctorReportRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentDeliveryTargetRepository
import ai.icen.fw.persistence.jdbc.JdbcFileAssetRepository
import ai.icen.fw.persistence.jdbc.JdbcFileObjectRepository
import ai.icen.fw.persistence.jdbc.JdbcOutboxEventRepository
import ai.icen.fw.persistence.jdbc.JdbcOutboxBacklogReader
import ai.icen.fw.persistence.jdbc.JdbcOutboxProcessingRepository
import ai.icen.fw.persistence.jdbc.JdbcOperationLogRepository
import ai.icen.fw.persistence.jdbc.JdbcRequestIdempotencyRepository
import ai.icen.fw.persistence.jdbc.JdbcResumableUploadSessionRepository
import ai.icen.fw.persistence.jdbc.JdbcSyncRecordRepository
import ai.icen.fw.persistence.jdbc.JdbcTaskRepository
import ai.icen.fw.persistence.jdbc.JdbcWorkflowInstanceRepository
import ai.icen.fw.persistence.jdbc.JdbcWorkflowQueryRepository
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
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
    @ConditionalOnMissingBean(DocumentQueryRepository::class)
    fun fileWeftDocumentQueryRepository(): DocumentQueryRepository = JdbcDocumentQueryRepository()

    @Bean
    @ConditionalOnMissingBean(DocumentSyncStatusQueryRepository::class)
    fun fileWeftDocumentSyncStatusQueryRepository(): DocumentSyncStatusQueryRepository =
        JdbcDocumentSyncStatusQueryRepository()

    @Bean
    @ConditionalOnMissingBean(WorkflowQueryRepository::class)
    fun fileWeftWorkflowQueryRepository(): WorkflowQueryRepository = JdbcWorkflowQueryRepository()

    @Bean
    @ConditionalOnMissingBean(FileObjectRepository::class)
    fun fileWeftFileObjectRepository(clock: Clock): FileObjectRepository = JdbcFileObjectRepository(clock)

    @Bean
    @ConditionalOnMissingBean(FileAssetRepository::class)
    fun fileWeftFileAssetRepository(objectMapper: ObjectMapper, clock: Clock): FileAssetRepository =
        JdbcFileAssetRepository(objectMapper, clock)

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
    fun fileWeftDocumentDeliveryTargetRepository(clock: Clock): JdbcDocumentDeliveryTargetRepository =
        JdbcDocumentDeliveryTargetRepository(clock)

    @Bean
    @ConditionalOnMissingBean(OutboxEventRepository::class)
    fun fileWeftOutboxEventRepository(objectMapper: ObjectMapper, traces: TraceContextProvider): OutboxEventRepository =
        TraceAwareOutboxEventRepository(JdbcOutboxEventRepository(objectMapper), traces)

    @Bean
    @ConditionalOnMissingBean(OutboxProcessingRepository::class)
    fun fileWeftOutboxProcessingRepository(objectMapper: ObjectMapper): JdbcOutboxProcessingRepository = JdbcOutboxProcessingRepository(objectMapper)

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
    fun fileWeftOutboxBacklogReader(properties: FileWeftProperties): OutboxBacklogReader =
        JdbcOutboxBacklogReader(properties.outbox.backlogMetricsQueryTimeoutSeconds)

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
    @ConditionalOnMissingBean(RequestIdempotencyRepository::class)
    fun fileWeftRequestIdempotencyRepository(): RequestIdempotencyRepository =
        JdbcRequestIdempotencyRepository()

    @Bean
    @ConditionalOnMissingBean(RequestIdempotencyService::class)
    fun fileWeftRequestIdempotencyService(
        repository: RequestIdempotencyRepository,
        transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): RequestIdempotencyService = RequestIdempotencyService(repository, transaction, identifiers, clock)

    @Bean(name = ["fileWeftDocumentCatalogAccessService"])
    @ConditionalOnBean(DocumentCatalogProvider::class)
    @ConditionalOnMissingBean(DocumentCatalogAccessService::class)
    fun fileWeftDocumentCatalogAccessServiceFromCandidates(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalogs: ObjectProvider<DocumentCatalogProvider>,
    ): DocumentCatalogAccessService = DocumentCatalogAccessService(
        tenants,
        users,
        authorization,
        requiredSecurityCandidate(catalogs, DocumentCatalogProvider::class.java),
    )

    /** Retains the original factory ABI for hosts that invoked this configuration directly. */
    @Deprecated("Use the ObjectProvider-backed auto-configuration factory.")
    fun fileWeftDocumentCatalogAccessService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        catalog: DocumentCatalogProvider,
    ): DocumentCatalogAccessService = DocumentCatalogAccessService(tenants, users, authorization, catalog)

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
    ): DocumentCatalogBindingService? = if (assets is FileAssetMutationRepository) {
        DocumentCatalogBindingService(
            tenants,
            users,
            catalogAccess,
            documents,
            assets,
            transaction,
            auditTrail,
        )
    } else {
        null
    }

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogDraftService::class)
    fun fileWeftDocumentCatalogDraftService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
    ): DocumentCatalogDraftService = DocumentCatalogDraftService(drafts, catalogAccess)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(DocumentCatalogMutationService::class)
    // See the binding service above: read/create remain available, mutations fail closed.
    fun fileWeftDocumentCatalogMutationService(
        drafts: DocumentDraftService,
        catalogAccess: DocumentCatalogAccessService,
        assets: FileAssetRepository,
    ): DocumentCatalogMutationService? =
        if (assets is FileAssetMutationRepository) DocumentCatalogMutationService(drafts, catalogAccess) else null

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
    fun fileWeftIdempotentDocumentLifecycleService(
        commands: DocumentCommandService,
        publish: PublishDocumentService,
        offline: OfflineDocumentService,
        restore: RestoreOfflineDocumentService,
        archive: ArchiveDocumentService,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentLifecycleService = IdempotentDocumentLifecycleService(
        commands,
        publish,
        offline,
        restore,
        archive,
        idempotency,
    )

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(
        value = [IdempotentDocumentLifecycleService::class, IdempotentDocumentCatalogLifecycleService::class],
    )
    fun fileWeftIdempotentDocumentCatalogLifecycleService(
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
    ): IdempotentDocumentDeliveryRecoveryService = IdempotentDocumentDeliveryRecoveryService(
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
    fun fileWeftIdempotentDocumentReviewWorkflowService(
        reviews: DocumentReviewWorkflowService,
        idempotency: RequestIdempotencyService,
    ): IdempotentDocumentReviewWorkflowService =
        IdempotentDocumentReviewWorkflowService(reviews, idempotency)

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(
        value = [
            IdempotentDocumentReviewWorkflowService::class,
            IdempotentDocumentCatalogReviewWorkflowService::class,
        ],
    )
    fun fileWeftIdempotentDocumentCatalogReviewWorkflowService(
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
    fun fileWeftConfirmAgentSuggestionService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        results: AgentResultRepository, identifiers: IdentifierGenerator, transaction: ApplicationTransaction,
        clock: Clock, auditTrail: AuditTrail, tasks: TaskRepository,
    ): ConfirmAgentSuggestionService =
        ConfirmAgentSuggestionService(
            tenants, users, authorization, results, identifiers, transaction, clock, auditTrail, tasks,
        )

    @Bean
    @ConditionalOnMissingBean(AgentTaskOrchestrator::class)
    fun fileWeftAgentTaskOrchestrator(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry, clock: Clock,
    ): AgentTaskOrchestrator = AgentTaskOrchestrator(agents + plugins.agents(), clock)

    @Bean
    @ConditionalOnMissingBean(AgentDoctorChecker::class)
    fun fileWeftAgentDoctorChecker(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry,
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
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): AgentTaskHandler {
        val mutations = taskMutations.getIfUnique() ?: throw IllegalStateException(
            "Exactly one TaskMutationRepository is required for fenced task projections.",
        )
        return AgentTaskHandler(orchestrator, results, transaction, clock, mutations)
    }

    @Bean
    @ConditionalOnMissingBean(AgentTaskOutboxEventHandler::class)
    fun fileWeftAgentTaskOutboxEventHandler(
        triggers: List<ai.icen.fw.spi.ai.AgentTaskTrigger>, plugins: FileWeftPluginRegistry, scheduler: AgentTaskScheduler,
        tasks: TaskRepository, transaction: ApplicationTransaction,
    ): AgentTaskOutboxEventHandler = AgentTaskOutboxEventHandler(triggers + plugins.agentTaskTriggers(), scheduler, tasks, transaction)

    @Bean
    @ConditionalOnMissingBean(PersistedAgentSuggestionConfirmationService::class)
    fun fileWeftAgentSuggestionConfirmations(
        results: AgentResultRepository, transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator, clock: Clock, tasks: TaskRepository,
    ): PersistedAgentSuggestionConfirmationService =
        PersistedAgentSuggestionConfirmationService(results, transaction, identifiers, clock, tasks)

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
        removalPlanner: DocumentDeliveryRemovalPlanner, metrics: FileWeftMetrics,
    ): DocumentDeliverySyncService = DocumentDeliverySyncService(
        documents, fileObjects, storage, connectors, deliveries, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
        removalPlanner = removalPlanner,
        metrics = metrics,
        sourceAccessUrlTtl = Duration.ofMillis(properties.sync.sourceAccessUrlTtlMillis),
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryRemovalService::class)
    fun fileWeftDocumentDeliveryRemovalService(
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        metrics: FileWeftMetrics,
    ): DocumentDeliveryRemovalService = DocumentDeliveryRemovalService(
        connectors, deliveries, transaction,
        connectorTimeout = Duration.ofMillis(properties.sync.connectorTimeoutMillis),
        auditTrail = auditTrail,
        metrics = metrics,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryOutboxEventHandler::class)
    fun fileWeftDocumentDeliveryOutboxEventHandler(
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
    @ConditionalOnMissingBean(DocumentQueryService::class)
    fun fileWeftDocumentQueryService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        queries: DocumentQueryRepository, transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): DocumentQueryService = DocumentQueryService(
        tenants, users, authorization, queries, transaction, folderReadAccess.getIfAvailable(),
    )

    @Bean
    @ConditionalOnMissingBean(DocumentSyncStatusQueryService::class)
    fun fileWeftDocumentSyncStatusQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentSyncStatusQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): DocumentSyncStatusQueryService = DocumentSyncStatusQueryService(
        tenants,
        users,
        authorization,
        queries,
        transaction,
        singleSecurityCandidateOrNull(folderReadAccess, DocumentFolderReadAccess::class.java),
    )

    @Bean
    @ConditionalOnMissingBean(WorkflowQueryService::class)
    fun fileWeftWorkflowQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): WorkflowQueryService = WorkflowQueryService(
        tenants,
        users,
        authorization,
        queries,
        transaction,
        singleSecurityCandidateOrNull(folderReadAccess, DocumentFolderReadAccess::class.java),
    )

    @Bean
    @ConditionalOnMissingBean(DocumentCommandService::class)
    fun fileWeftDocumentCommandService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): DocumentCommandService = DocumentCommandService(tenants, users, authorization, documents, transaction, auditTrail)

    @Bean
    @ConditionalOnBean(DocumentFolderReadAccess::class)
    @ConditionalOnMissingBean(DocumentDownloadVisibility::class)
    fun fileWeftDocumentDownloadVisibility(
        folderReadAccesses: ObjectProvider<DocumentFolderReadAccess>,
        queries: DocumentQueryRepository,
    ): DocumentDownloadVisibility = DocumentDownloadVisibility(
        requiredDownloadFolderAccess(folderReadAccesses),
        queries,
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDownloadService::class)
    fun fileWeftDocumentDownloadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
        visibility: ObjectProvider<DocumentDownloadVisibility>,
    ): DocumentDownloadService {
        // Do not let @Primary select one guard from several candidates: doing
        // so could silently drop another folder policy and widen downloads.
        val downloadVisibility = optionalDownloadVisibility(visibility)
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

    private fun requiredDownloadFolderAccess(
        accesses: ObjectProvider<DocumentFolderReadAccess>,
    ): DocumentFolderReadAccess {
        val candidates = accesses.stream().iterator().asSequence().toList()
        if (candidates.size != 1) {
            throw NoUniqueBeanDefinitionException(
                DocumentFolderReadAccess::class.java,
                candidates.size,
                "FileWeft download visibility requires exactly one DocumentFolderReadAccess.",
            )
        }
        return candidates.single()
    }

    private fun optionalDownloadVisibility(
        visibility: ObjectProvider<DocumentDownloadVisibility>,
    ): DocumentDownloadVisibility? {
        val candidates = visibility.stream().iterator().asSequence().toList()
        if (candidates.size > 1) {
            throw NoUniqueBeanDefinitionException(
                DocumentDownloadVisibility::class.java,
                candidates.size,
                "FileWeft download service requires at most one DocumentDownloadVisibility.",
            )
        }
        return candidates.singleOrNull()
    }

    private fun <T : Any> requiredSecurityCandidate(
        provider: ObjectProvider<T>,
        beanType: Class<T>,
    ): T = singleSecurityCandidateOrNull(provider, beanType)
        ?: throw NoSuchBeanDefinitionException(beanType)

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
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        transaction: ApplicationTransaction, auditTrail: AuditTrail,
    ): PublishDocumentService = PublishDocumentService(tenants, users, authorization, documents, planner, transaction, auditTrail, workflows)

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
    @ConditionalOnMissingBean(DeploymentSafetyDoctorChecker::class)
    fun fileWeftDeploymentSafetyDoctorChecker(
        tenants: TenantProvider,
        storage: StorageAdapter,
    ): DeploymentSafetyDoctorChecker = DeploymentSafetyDoctorChecker(
        fixedTenantProviderActive = tenants is FixedTenantProvider,
        localStorageAdapterActive = storage is LocalStorageAdapter,
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
    @ConditionalOnMissingBean(WorkflowDoctorChecker::class)
    fun fileWeftWorkflowDoctorChecker(
        documents: DocumentRepository, workflows: WorkflowInstanceRepository,
    ): WorkflowDoctorChecker = WorkflowDoctorChecker(documents, workflows)

    @Bean
    @ConditionalOnSingleCandidate(DocumentCatalogProvider::class)
    @ConditionalOnMissingBean(CatalogDoctorChecker::class)
    fun fileWeftCatalogDoctorChecker(
        documents: DocumentRepository, assets: FileAssetRepository, catalog: DocumentCatalogProvider, transaction: ApplicationTransaction,
    ): CatalogDoctorChecker = CatalogDoctorChecker(documents, assets, catalog, transaction)

    @Bean
    @ConditionalOnMissingBean(ConnectorDoctorChecker::class)
    fun fileWeftConnectorDoctorChecker(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, resilience: ConnectorResilienceRegistry,
    ): ConnectorDoctorChecker = ConnectorDoctorChecker(resilience.protectAll(plugins.mergeConnectors(connectors)).values.toList())

    @Bean
    @ConditionalOnMissingBean(DeliveryProfileDoctorChecker::class)
    fun fileWeftDeliveryProfileDoctorChecker(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
    ): DeliveryProfileDoctorChecker = DeliveryProfileDoctorChecker(profiles, connectors)

    @Bean
    @ConditionalOnMissingBean(DoctorApplicationService::class)
    fun fileWeftDoctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker,
        agent: AgentDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService {
        val catalogChecker = catalog.getIfAvailable()
        return DoctorApplicationService(
            tenants,
            permission,
            listOf<DoctorChecker>(
                deploymentSafety,
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
    fun fileWeftDocumentDoctorTaskHandler(
        doctor: DoctorApplicationService, reports: DoctorReportRepository,
        transaction: ApplicationTransaction, clock: Clock,
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): DocumentDoctorTaskHandler {
        val mutations = taskMutations.getIfUnique() ?: throw IllegalStateException(
            "Exactly one TaskMutationRepository is required for fenced task projections.",
        )
        return DocumentDoctorTaskHandler(doctor, reports, transaction, clock, mutations)
    }

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
    @ConditionalOnProperty(
        prefix = "fileweft.sync",
        name = ["legacy-publish-handler-enabled"],
        havingValue = "true",
    )
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
    fun fileWeftDocumentPublishOutboxEventHandler(sync: DocumentSyncService): DocumentPublishOutboxEventHandler =
        DocumentPublishOutboxEventHandler(sync)

    @Bean
    @ConditionalOnMissingBean(OutboxWorker::class)
    fun fileWeftOutboxWorker(
        repository: OutboxProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<OutboxEventHandler>, plugins: FileWeftPluginRegistry, clock: Clock, traces: ObjectProvider<TraceContextScope>,
        properties: FileWeftProperties,
    ): OutboxWorker = OutboxWorker(
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
        legacyRunningGrace = Duration.ofMillis(properties.task.legacyRunningGraceMillis),
    )

    private companion object {
        const val DEFAULT_DELIVERY_PROFILE_ID = "default"
        const val DEFAULT_DELIVERY_TARGET_ID = "default"
    }
}
