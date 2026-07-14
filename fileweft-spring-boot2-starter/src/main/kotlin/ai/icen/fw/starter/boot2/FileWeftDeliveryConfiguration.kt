package ai.icen.fw.starter.boot2

import ai.icen.fw.adapter.connector.ConnectorInvocationExecutor
import ai.icen.fw.adapter.connector.ConnectorResiliencePolicy
import ai.icen.fw.adapter.connector.ConnectorResilienceRegistry
import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.document.DocumentFolderReadAccess
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
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.application.outbox.OutboxProcessingRepository
import ai.icen.fw.application.outbox.OutboxWorker
import ai.icen.fw.application.sync.DocumentPublishOutboxEventHandler
import ai.icen.fw.application.sync.DocumentSyncService
import ai.icen.fw.application.sync.SyncRecordRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentDeliveryTargetRepository
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.observability.TraceContextScope
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
class FileWeftDeliveryConfiguration {
    private val factories = FileWeftRuntimeFactories()
    @Bean
    @ConditionalOnMissingBean(DocumentSyncStatusQueryRepository::class)
    fun fileWeftDocumentSyncStatusQueryRepository(): DocumentSyncStatusQueryRepository = factories.fileWeftDocumentSyncStatusQueryRepository()

    @Bean
    @ConditionalOnMissingBean(SyncRecordRepository::class)
    fun fileWeftSyncRecordRepository(clock: Clock): SyncRecordRepository = factories.fileWeftSyncRecordRepository(clock)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryTargetRepository::class)
    fun fileWeftDocumentDeliveryTargetRepository(clock: Clock): JdbcDocumentDeliveryTargetRepository = factories.fileWeftDocumentDeliveryTargetRepository(clock)

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
    ): IdempotentDocumentDeliveryRecoveryService = factories.fileWeftIdempotentDocumentDeliveryRecoveryService(tenants, users, authorization, documents, deliveries, outboxMutations, outbox, identifiers, clock, idempotency, auditTrail)

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
    ): IdempotentDocumentCatalogDeliveryRecoveryService? = factories.fileWeftIdempotentDocumentCatalogDeliveryRecoveryService(tenants, users, authorization, documents, assets, deliveries, outboxMutations, outbox, identifiers, transaction, clock, idempotency, auditTrail, catalogAccesses)

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ConnectorInvocationExecutor::class)
    fun fileWeftConnectorInvocationExecutor(properties: FileWeftProperties): ConnectorInvocationExecutor = factories.fileWeftConnectorInvocationExecutor(properties)

    @Bean
    @ConditionalOnMissingBean(ConnectorResiliencePolicy::class)
    fun fileWeftConnectorResiliencePolicy(properties: FileWeftProperties): ConnectorResiliencePolicy = factories.fileWeftConnectorResiliencePolicy(properties)

    @Bean
    @ConditionalOnMissingBean(ConnectorResilienceRegistry::class)
    fun fileWeftConnectorResilienceRegistry(
        policy: ConnectorResiliencePolicy, executor: ConnectorInvocationExecutor, clock: Clock,
    ): ConnectorResilienceRegistry = factories.fileWeftConnectorResilienceRegistry(policy, executor, clock)

    @Bean
    @ConditionalOnMissingBean(DeliveryConnectorResolver::class)
    fun fileWeftDeliveryConnectorResolver(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, properties: FileWeftProperties,
        resilience: ConnectorResilienceRegistry,
    ): DeliveryConnectorResolver = factories.fileWeftDeliveryConnectorResolver(connectors, plugins, properties, resilience)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryProfileProvider::class)
    fun fileWeftDocumentDeliveryProfiles(properties: FileWeftProperties): DocumentDeliveryProfileProvider = factories.fileWeftDocumentDeliveryProfiles(properties)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryPlanner::class)
    fun fileWeftDocumentDeliveryPlanner(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ): DocumentDeliveryPlanner = factories.fileWeftDocumentDeliveryPlanner(profiles, connectors, deliveries, outbox, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryRemovalPlanner::class)
    fun fileWeftDocumentDeliveryRemovalPlanner(
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ): DocumentDeliveryRemovalPlanner = factories.fileWeftDocumentDeliveryRemovalPlanner(deliveries, outbox, identifiers, clock)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliverySyncService::class)
    fun fileWeftDocumentDeliverySyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        removalPlanner: DocumentDeliveryRemovalPlanner, metrics: FileWeftMetrics,
    ): DocumentDeliverySyncService = factories.fileWeftDocumentDeliverySyncService(documents, fileObjects, storage, connectors, deliveries, transaction, auditTrail, properties, removalPlanner, metrics)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryRemovalService::class)
    fun fileWeftDocumentDeliveryRemovalService(
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        metrics: FileWeftMetrics,
    ): DocumentDeliveryRemovalService = factories.fileWeftDocumentDeliveryRemovalService(connectors, deliveries, transaction, auditTrail, properties, metrics)

    @Bean
    @ConditionalOnMissingBean(DocumentDeliveryOutboxEventHandler::class)
    fun fileWeftDocumentDeliveryOutboxEventHandler(
        sync: DocumentDeliverySyncService,
        removal: DocumentDeliveryRemovalService,
        deliveries: DocumentDeliveryTargetRepository,
        outboxMutations: ObjectProvider<OutboxEventMutationRepository>,
        documents: DocumentRepository,
    ): DocumentDeliveryOutboxEventHandler = factories.fileWeftDocumentDeliveryOutboxEventHandler(sync, removal, deliveries, outboxMutations, documents)

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
    ): RetryDocumentDeliveryService = factories.fileWeftRetryDocumentDeliveryService(tenants, users, authorization, documents, deliveries, outbox, identifiers, transaction, clock, auditTrail)

    @Bean
    @ConditionalOnMissingBean(DocumentSyncStatusQueryService::class)
    fun fileWeftDocumentSyncStatusQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentSyncStatusQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): DocumentSyncStatusQueryService = factories.fileWeftDocumentSyncStatusQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

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
    ): DocumentSyncService = factories.fileWeftDocumentSyncService(documents, fileObjects, storage, connector, properties, resilience, records, identifiers, transaction, auditTrail, metrics)

    @Bean
    @ConditionalOnProperty(
        prefix = "fileweft.sync",
        name = ["legacy-publish-handler-enabled"],
        havingValue = "true",
    )
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentPublishOutboxEventHandler::class)
    fun fileWeftDocumentPublishOutboxEventHandler(sync: DocumentSyncService): DocumentPublishOutboxEventHandler = factories.fileWeftDocumentPublishOutboxEventHandler(sync)

    @Bean
    @ConditionalOnMissingBean(OutboxWorker::class)
    fun fileWeftOutboxWorker(
        repository: OutboxProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<OutboxEventHandler>, plugins: FileWeftPluginRegistry, clock: Clock, traces: ObjectProvider<TraceContextScope>,
        properties: FileWeftProperties,
    ): OutboxWorker = factories.fileWeftOutboxWorker(repository, transaction, handlers, plugins, clock, traces, properties)
}
