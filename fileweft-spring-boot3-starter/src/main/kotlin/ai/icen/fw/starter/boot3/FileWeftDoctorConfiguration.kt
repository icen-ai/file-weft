package ai.icen.fw.starter.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.doctor.DocumentDoctorQueryService
import ai.icen.fw.application.doctor.DocumentDoctorTaskQueryRepository
import ai.icen.fw.application.doctor.DocumentDoctorTaskQueryService
import ai.icen.fw.application.doctor.DoctorApplicationService
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentCatalogDoctorService
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentDoctorService
import ai.icen.fw.application.doctor.SystemDoctorService
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetMutationRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentDoctorTaskQueryRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource
import ai.icen.fw.adapter.connector.ConnectorResilienceRegistry
import ai.icen.fw.agent.AgentTaskHandler
import ai.icen.fw.agent.AgentDoctorChecker
import ai.icen.fw.agent.AgentTaskOrchestrator
import ai.icen.fw.agent.AgentTaskOutboxEventHandler
import ai.icen.fw.agent.AgentTaskScheduler
import ai.icen.fw.agent.PersistedAgentSuggestionConfirmationService
import ai.icen.fw.application.agent.AgentResultRepository
import ai.icen.fw.application.agent.ConfirmAgentSuggestionService
import ai.icen.fw.application.doctor.*
import ai.icen.fw.application.delivery.*
import ai.icen.fw.application.outbox.*
import ai.icen.fw.application.sync.*
import ai.icen.fw.application.task.*
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.metadata.api.MetadataProcessor
import ai.icen.fw.metadata.api.MetadataSchemaResolver
import ai.icen.fw.persistence.jdbc.*
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.StorageAdapter
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate

/**
 * Formal Doctor application boundaries, kept separate from the legacy diagnostic bean surface.
 *
 * Bean naming contract: primary names keep the `fileWeft*` prefix shared with the
 * Boot 2 starter so by-name injection survives a Boot 2 to Boot 3 migration. The
 * short names introduced in 0.0.3 stay registered as aliases (the second name in
 * each `@Bean` declaration) so hosts already adapted to 0.0.3 keep resolving the
 * same instances; those aliases are deprecated and will be removed in a future
 * major release.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
class FileWeftDoctorConfiguration {
    private val runtimeFactories = FileWeftRuntimeFactories()

    @Bean
    @ConditionalOnMissingBean(DocumentDoctorTaskQueryRepository::class)
    fun documentDoctorTaskQueries(objectMapper: ObjectMapper): DocumentDoctorTaskQueryRepository =
        JdbcDocumentDoctorTaskQueryRepository(objectMapper)

    @Bean
    @ConditionalOnMissingBean(DocumentDoctorQueryService::class)
    fun documentDoctorQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        documents: DocumentQueryRepository,
        transaction: ApplicationTransaction,
        doctor: DoctorApplicationService,
        folderAccesses: ObjectProvider<DocumentFolderReadAccess>,
    ) = DocumentDoctorQueryService(
        tenants,
        users,
        authorization,
        documents,
        transaction,
        doctor,
        singleSecurityCandidateOrNull(folderAccesses, DocumentFolderReadAccess::class.java),
    )

    @Bean
    @ConditionalOnMissingBean(DocumentDoctorTaskQueryService::class)
    fun documentDoctorTaskQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentDoctorTaskQueryRepository,
        transaction: ApplicationTransaction,
        folderAccesses: ObjectProvider<DocumentFolderReadAccess>,
    ) = DocumentDoctorTaskQueryService(
        tenants,
        users,
        authorization,
        queries,
        transaction,
        singleSecurityCandidateOrNull(folderAccesses, DocumentFolderReadAccess::class.java),
    )

    @Bean
    @ConditionalOnMissingBean(SystemDoctorService::class)
    fun systemDoctorService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        doctor: DoctorApplicationService,
    ) = SystemDoctorService(tenants, users, authorization, doctor)

    @Bean
    @ConditionalOnMissingBean(
        value = [
            DocumentCatalogAccessService::class,
            IdempotentScheduleDocumentDoctorService::class,
            IdempotentScheduleDocumentCatalogDoctorService::class,
        ],
    )
    fun idempotentScheduleDocumentDoctorService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        documents: DocumentRepository,
        tasks: TaskRepository,
        identifiers: IdentifierGenerator,
        clock: Clock,
        idempotency: RequestIdempotencyService,
        auditTrail: AuditTrail,
    ) = IdempotentScheduleDocumentDoctorService(
        tenants,
        users,
        authorization,
        runtimeFactories.requireDocumentMutations(documents),
        tasks,
        identifiers,
        clock,
        idempotency,
        auditTrail,
    )

    @Bean
    @ConditionalOnBean(DocumentCatalogAccessService::class)
    @ConditionalOnMissingBean(
        value = [IdempotentScheduleDocumentDoctorService::class, IdempotentScheduleDocumentCatalogDoctorService::class],
    )
    fun idempotentScheduleDocumentCatalogDoctorService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        documents: DocumentRepository,
        assets: FileAssetRepository,
        tasks: TaskRepository,
        identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction,
        clock: Clock,
        idempotency: RequestIdempotencyService,
        auditTrail: AuditTrail,
        catalogAccesses: ObjectProvider<DocumentCatalogAccessService>,
    ): IdempotentScheduleDocumentCatalogDoctorService? {
        val catalogAccess = requiredSecurityCandidate(catalogAccesses, DocumentCatalogAccessService::class.java)
        return if (assets is FileAssetMutationRepository && documents is DocumentMutationRepository) {
            IdempotentScheduleDocumentCatalogDoctorService(
                tenants,
                users,
                authorization,
                documents,
                assets,
                tasks,
                identifiers,
                transaction,
                clock,
                idempotency,
                auditTrail,
                catalogAccess,
            )
        } else {
            null
        }
    }


    @Bean(name = ["fileWeftDoctorReportRepository", "doctorReports"])
    @ConditionalOnMissingBean(DoctorReportRepository::class)
    fun doctorReports(objectMapper: ObjectMapper, clock: Clock): DoctorReportRepository = runtimeFactories.doctorReports(objectMapper, clock)

    @Bean(name = ["fileWeftAgentResultRepository", "agentResults"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(AgentResultRepository::class)
    fun agentResults(objectMapper: ObjectMapper, clock: Clock): AgentResultRepository = runtimeFactories.agentResults(objectMapper, clock)

    @Bean(name = ["fileWeftConfirmAgentSuggestionService", "confirmAgentSuggestionService"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(ConfirmAgentSuggestionService::class)
    fun confirmAgentSuggestionService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        results: AgentResultRepository, identifiers: IdentifierGenerator, transaction: ApplicationTransaction,
        clock: Clock, auditTrail: AuditTrail, tasks: TaskRepository,
    ) = runtimeFactories.confirmAgentSuggestionService(tenants, users, authorization, results, identifiers, transaction, clock, auditTrail, tasks)

    @Bean(name = ["fileWeftAgentTaskOrchestrator", "agentTaskOrchestrator"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(AgentTaskOrchestrator::class)
    fun agentTaskOrchestrator(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry, clock: Clock,
    ) = runtimeFactories.agentTaskOrchestrator(agents, plugins, clock)

    @Bean(name = ["fileWeftAgentDoctorChecker", "agentDoctorChecker"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(AgentDoctorChecker::class)
    fun agentDoctorChecker(
        agents: List<ai.icen.fw.spi.ai.FileWeftAgent>, plugins: FileWeftPluginRegistry,
    ) = runtimeFactories.agentDoctorChecker(agents, plugins)

    @Bean(name = ["fileWeftAgentTaskScheduler", "agentTaskScheduler"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(AgentTaskScheduler::class)
    fun agentTaskScheduler(identifiers: IdentifierGenerator, clock: Clock) = runtimeFactories.agentTaskScheduler(identifiers, clock)

    @Bean(name = ["fileWeftAgentTaskHandler", "agentTaskHandler"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(AgentTaskHandler::class)
    fun agentTaskHandler(
        orchestrator: AgentTaskOrchestrator, results: AgentResultRepository,
        transaction: ApplicationTransaction, clock: Clock,
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): AgentTaskHandler = runtimeFactories.agentTaskHandler(orchestrator, results, transaction, clock, taskMutations)

    @Bean(name = ["fileWeftAgentTaskOutboxEventHandler", "agentTaskOutboxEventHandler"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(AgentTaskOutboxEventHandler::class)
    fun agentTaskOutboxEventHandler(
        triggers: List<ai.icen.fw.spi.ai.AgentTaskTrigger>, plugins: FileWeftPluginRegistry, scheduler: AgentTaskScheduler,
        tasks: TaskRepository, transaction: ApplicationTransaction,
    ) = runtimeFactories.agentTaskOutboxEventHandler(triggers, plugins, scheduler, tasks, transaction)

    @Bean(name = ["fileWeftAgentSuggestionConfirmations", "agentSuggestionConfirmations"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(PersistedAgentSuggestionConfirmationService::class)
    fun agentSuggestionConfirmations(
        results: AgentResultRepository, transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator, clock: Clock, tasks: TaskRepository,
    ) = runtimeFactories.agentSuggestionConfirmations(results, transaction, identifiers, clock, tasks)

    @Bean(name = ["fileWeftDeploymentSafetyDoctorChecker", "deploymentSafetyDoctor"])
    @ConditionalOnMissingBean(DeploymentSafetyDoctorChecker::class)
    fun deploymentSafetyDoctor(
        tenants: TenantProvider,
        storage: StorageAdapter,
    ) = runtimeFactories.deploymentSafetyDoctor(tenants, storage)

    @Bean(name = ["fileWeftPermissionDoctorChecker", "permissionDoctor"])
    @ConditionalOnMissingBean(PermissionDoctorChecker::class)
    fun permissionDoctor(users: UserRealmProvider, authorization: AuthorizationProvider) = runtimeFactories.permissionDoctor(users, authorization)

    @Bean(name = ["fileWeftLifecycleDoctorChecker", "lifecycleDoctor"])
    @ConditionalOnMissingBean(LifecycleDoctorChecker::class)
    fun lifecycleDoctor(documents: DocumentRepository) = runtimeFactories.lifecycleDoctor(documents)

    @Bean(name = ["fileWeftStorageDoctorChecker", "storageDoctor"])
    @ConditionalOnMissingBean(StorageDoctorChecker::class)
    fun storageDoctor(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter, transaction: ApplicationTransaction,
    ) = runtimeFactories.storageDoctor(documents, fileObjects, storage, transaction)

    @Bean(name = ["fileWeftWorkflowDoctorChecker", "workflowDoctor"])
    @ConditionalOnMissingBean(WorkflowDoctorChecker::class)
    fun workflowDoctor(
        documents: DocumentRepository, workflows: WorkflowInstanceRepository,
    ) = runtimeFactories.workflowDoctor(documents, workflows)

    @Bean(name = ["fileWeftMetadataDoctorChecker", "metadataDoctor"])
    @ConditionalOnMissingBean(MetadataDoctorChecker::class)
    fun metadataDoctor(
        documents: DocumentRepository,
        assets: FileAssetRepository,
        schemas: MetadataSchemaResolver,
        processor: MetadataProcessor,
        transaction: ApplicationTransaction,
    ) = runtimeFactories.metadataDoctor(documents, assets, schemas, processor, transaction)

    @Bean(name = ["fileWeftCatalogDoctorChecker", "catalogDoctor"])
    @ConditionalOnSingleCandidate(DocumentCatalogProvider::class)
    @ConditionalOnMissingBean(CatalogDoctorChecker::class)
    fun catalogDoctor(
        documents: DocumentRepository, assets: FileAssetRepository, catalog: DocumentCatalogProvider, transaction: ApplicationTransaction,
    ) = runtimeFactories.catalogDoctor(documents, assets, catalog, transaction)

    @Bean(name = ["fileWeftConnectorDoctorChecker", "connectorDoctor"])
    @ConditionalOnMissingBean(ConnectorDoctorChecker::class)
    fun connectorDoctor(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, resilience: ConnectorResilienceRegistry,
    ) = runtimeFactories.connectorDoctor(connectors, plugins, resilience)

    @Bean(name = ["fileWeftDeliveryProfileDoctorChecker", "deliveryProfileDoctor"])
    @ConditionalOnMissingBean(DeliveryProfileDoctorChecker::class)
    fun deliveryProfileDoctor(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
    ) = runtimeFactories.deliveryProfileDoctor(profiles, connectors)

    @Bean(name = ["fileWeftDoctorService", "doctorService"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "false",
        matchIfMissing = true,
    )
    @ConditionalOnMissingBean(DoctorApplicationService::class)
    fun doctorServiceWithoutLegacyAgent(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker, metadata: MetadataDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = runtimeFactories.doctorServiceWithoutLegacyAgentWithMetadata(tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector, deliveryProfile, metadata, transaction, clock, metrics, plugins)

    @Bean(name = ["fileWeftDoctorService", "doctorService"])
    @ConditionalOnProperty(
        prefix = FILEWEFT_COMPATIBILITY_PREFIX,
        name = [LEGACY_AGENT_AUTOCONFIGURATION_ENABLED],
        havingValue = "true",
    )
    @ConditionalOnMissingBean(DoctorApplicationService::class)
    fun doctorService(
        tenants: TenantProvider, permission: PermissionDoctorChecker, deploymentSafety: DeploymentSafetyDoctorChecker,
        lifecycle: LifecycleDoctorChecker,
        storage: StorageDoctorChecker, workflow: WorkflowDoctorChecker, catalog: ObjectProvider<CatalogDoctorChecker>, connector: ConnectorDoctorChecker,
        deliveryProfile: DeliveryProfileDoctorChecker, metadata: MetadataDoctorChecker,
        agent: AgentDoctorChecker, transaction: ApplicationTransaction,
        clock: Clock, metrics: FileWeftMetrics, plugins: FileWeftPluginRegistry,
    ): DoctorApplicationService = runtimeFactories.doctorServiceWithMetadata(tenants, permission, deploymentSafety, lifecycle, storage, workflow, catalog, connector, deliveryProfile, metadata, agent, transaction, clock, metrics, plugins)

    @Bean(name = ["fileWeftDocumentDoctorTaskHandler", "documentDoctorTaskHandler"])
    @ConditionalOnMissingBean(DocumentDoctorTaskHandler::class)
    fun documentDoctorTaskHandler(
        doctor: DoctorApplicationService, reports: DoctorReportRepository,
        transaction: ApplicationTransaction, clock: Clock,
        taskMutations: ObjectProvider<TaskMutationRepository>,
    ): DocumentDoctorTaskHandler = runtimeFactories.documentDoctorTaskHandler(doctor, reports, transaction, clock, taskMutations)

    @Bean(name = ["fileWeftScheduleDocumentDoctorService", "scheduleDocumentDoctorService"])
    @ConditionalOnMissingBean(ScheduleDocumentDoctorService::class)
    fun scheduleDocumentDoctorService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, tasks: TaskRepository, identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction, clock: Clock, auditTrail: AuditTrail,
    ) = runtimeFactories.scheduleDocumentDoctorService(tenants, users, authorization, documents, tasks, identifiers, transaction, clock, auditTrail)

    private fun <T : Any> singleSecurityCandidateOrNull(
        provider: ObjectProvider<T>,
        type: Class<T>,
    ): T? {
        val candidates = provider.orderedStream().iterator().asSequence().toList()
        require(candidates.size <= 1) {
            "More than one ${type.simpleName} is available; register exactly one security boundary."
        }
        return candidates.singleOrNull()
    }

    private fun <T : Any> requiredSecurityCandidate(
        provider: ObjectProvider<T>,
        type: Class<T>,
    ): T {
        val candidates = provider.orderedStream().iterator().asSequence().toList()
        require(candidates.size == 1) {
            "Exactly one ${type.simpleName} is required for the catalog-aware Doctor boundary."
        }
        return candidates.single()
    }
}
