package ai.icen.fw.starter.boot3

import ai.icen.fw.adapter.connector.ConnectorInvocationExecutor
import ai.icen.fw.adapter.connector.ConnectorResiliencePolicy
import ai.icen.fw.adapter.connector.ConnectorResilienceRegistry
import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.doctor.*
import ai.icen.fw.application.delivery.*
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.outbox.*
import ai.icen.fw.application.sync.*
import ai.icen.fw.application.task.*
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.persistence.jdbc.*
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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

/**
 * Bean naming contract: primary names keep the `fileWeft*` prefix shared with the
 * Boot 2 starter so by-name injection survives a Boot 2 to Boot 3 migration. The
 * short names introduced in 0.0.3 stay registered as aliases (the second name in
 * each `@Bean` declaration) so hosts already adapted to 0.0.3 keep resolving the
 * same instances; those aliases are deprecated and will be removed in a future
 * major release.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
class FileWeftDeliveryConfiguration {
    private val factories = FileWeftRuntimeFactories()
    @Bean(name = ["fileWeftDocumentSyncStatusQueryRepository", "documentSyncStatusQueries"])
    @ConditionalOnMissingBean(DocumentSyncStatusQueryRepository::class)
    fun documentSyncStatusQueries(): DocumentSyncStatusQueryRepository = factories.documentSyncStatusQueries()

    @Bean(name = ["fileWeftSyncRecordRepository", "syncRecords"])
    @ConditionalOnMissingBean(SyncRecordRepository::class)
    fun syncRecords(clock: Clock): SyncRecordRepository = factories.syncRecords(clock)

    @Bean(name = ["fileWeftDocumentDeliveryTargetRepository", "documentDeliveryTargets"])
    @ConditionalOnMissingBean(DocumentDeliveryTargetRepository::class)
    fun documentDeliveryTargets(clock: Clock): JdbcDocumentDeliveryTargetRepository = factories.documentDeliveryTargets(clock)

    @Bean(name = ["fileWeftIdempotentDocumentDeliveryRecoveryService", "idempotentDocumentDeliveryRecoveryService"])
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
    ) = factories.idempotentDocumentDeliveryRecoveryService(tenants, users, authorization, documents, deliveries, outboxMutations, outbox, identifiers, clock, idempotency, auditTrail)

    @Bean(name = ["fileWeftIdempotentDocumentCatalogDeliveryRecoveryService", "idempotentDocumentCatalogDeliveryRecoveryService"])
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
    ): IdempotentDocumentCatalogDeliveryRecoveryService? = factories.idempotentDocumentCatalogDeliveryRecoveryService(tenants, users, authorization, documents, assets, deliveries, outboxMutations, outbox, identifiers, transaction, clock, idempotency, auditTrail, catalogAccesses)

    @Bean(name = ["fileWeftConnectorInvocationExecutor", "connectorInvocationExecutor"], destroyMethod = "close")
    @ConditionalOnMissingBean(ConnectorInvocationExecutor::class)
    fun connectorInvocationExecutor(properties: FileWeftProperties) = factories.connectorInvocationExecutor(properties)

    @Bean(name = ["fileWeftConnectorResiliencePolicy", "connectorResiliencePolicy"])
    @ConditionalOnMissingBean(ConnectorResiliencePolicy::class)
    fun connectorResiliencePolicy(properties: FileWeftProperties) = factories.connectorResiliencePolicy(properties)

    @Bean(name = ["fileWeftConnectorResilienceRegistry", "connectorResilienceRegistry"])
    @ConditionalOnMissingBean(ConnectorResilienceRegistry::class)
    fun connectorResilienceRegistry(
        policy: ConnectorResiliencePolicy, executor: ConnectorInvocationExecutor, clock: Clock,
    ) = factories.connectorResilienceRegistry(policy, executor, clock)

    @Bean(name = ["fileWeftDeliveryConnectorResolver", "deliveryConnectorResolver"])
    @ConditionalOnMissingBean(DeliveryConnectorResolver::class)
    fun deliveryConnectorResolver(
        connectors: Map<String, FileConnector>, plugins: FileWeftPluginRegistry, properties: FileWeftProperties,
        resilience: ConnectorResilienceRegistry,
    ): DeliveryConnectorResolver = factories.deliveryConnectorResolver(connectors, plugins, properties, resilience)

    @Bean(name = ["fileWeftDocumentDeliveryProfiles", "documentDeliveryProfiles"])
    @ConditionalOnMissingBean(DocumentDeliveryProfileProvider::class)
    fun documentDeliveryProfiles(properties: FileWeftProperties): DocumentDeliveryProfileProvider = factories.documentDeliveryProfiles(properties)

    @Bean(name = ["fileWeftDocumentDeliveryPlanner", "documentDeliveryPlanner"])
    @ConditionalOnMissingBean(DocumentDeliveryPlanner::class)
    fun documentDeliveryPlanner(
        profiles: DocumentDeliveryProfileProvider, connectors: DeliveryConnectorResolver,
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ) = factories.documentDeliveryPlanner(profiles, connectors, deliveries, outbox, identifiers, clock)

    @Bean(name = ["fileWeftDocumentDeliveryRemovalPlanner", "documentDeliveryRemovalPlanner"])
    @ConditionalOnMissingBean(DocumentDeliveryRemovalPlanner::class)
    fun documentDeliveryRemovalPlanner(
        deliveries: DocumentDeliveryTargetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, clock: Clock,
    ) = factories.documentDeliveryRemovalPlanner(deliveries, outbox, identifiers, clock)

    @Bean(name = ["fileWeftDocumentDeliverySyncService", "documentDeliverySyncService"])
    @ConditionalOnMissingBean(DocumentDeliverySyncService::class)
    fun documentDeliverySyncService(
        documents: DocumentRepository, fileObjects: FileObjectRepository, storage: StorageAdapter,
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        removalPlanner: DocumentDeliveryRemovalPlanner, metrics: FileWeftMetrics,
    ) = factories.documentDeliverySyncService(documents, fileObjects, storage, connectors, deliveries, transaction, auditTrail, properties, removalPlanner, metrics)

    @Bean(name = ["fileWeftDocumentDeliveryRemovalService", "documentDeliveryRemovalService"])
    @ConditionalOnMissingBean(DocumentDeliveryRemovalService::class)
    fun documentDeliveryRemovalService(
        connectors: DeliveryConnectorResolver, deliveries: DocumentDeliveryTargetRepository,
        transaction: ApplicationTransaction, auditTrail: AuditTrail, properties: FileWeftProperties,
        metrics: FileWeftMetrics,
    ) = factories.documentDeliveryRemovalService(connectors, deliveries, transaction, auditTrail, properties, metrics)

    @Bean(name = ["fileWeftDocumentDeliveryOutboxEventHandler", "documentDeliveryHandler"])
    @ConditionalOnMissingBean(DocumentDeliveryOutboxEventHandler::class)
    fun documentDeliveryHandler(
        sync: DocumentDeliverySyncService,
        removal: DocumentDeliveryRemovalService,
        deliveries: DocumentDeliveryTargetRepository,
        outboxMutations: ObjectProvider<OutboxEventMutationRepository>,
        documents: DocumentRepository,
    ): DocumentDeliveryOutboxEventHandler = factories.documentDeliveryHandler(sync, removal, deliveries, outboxMutations, documents)

    @Bean(name = ["fileWeftRetryDocumentDeliveryService", "retryDocumentDeliveryService"])
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
    ) = factories.retryDocumentDeliveryService(tenants, users, authorization, documents, deliveries, outbox, identifiers, transaction, clock, auditTrail)

    @Bean(name = ["fileWeftDocumentSyncStatusQueryService", "documentSyncStatusQueryService"])
    @ConditionalOnMissingBean(DocumentSyncStatusQueryService::class)
    fun documentSyncStatusQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentSyncStatusQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.documentSyncStatusQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean(name = ["fileWeftDocumentSyncService", "documentSyncService"])
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
    ) = factories.documentSyncService(documents, fileObjects, storage, connector, properties, resilience, records, identifiers, transaction, auditTrail, metrics)

    @Bean(name = ["fileWeftDocumentPublishOutboxEventHandler", "documentPublishHandler"])
    @ConditionalOnProperty(
        prefix = "fileweft.sync",
        name = ["legacy-publish-handler-enabled"],
        havingValue = "true",
    )
    @ConditionalOnSingleCandidate(FileConnector::class)
    @ConditionalOnMissingBean(DocumentPublishOutboxEventHandler::class)
    fun documentPublishHandler(sync: DocumentSyncService) = factories.documentPublishHandler(sync)

    @Bean(name = ["fileWeftOutboxWorker", "outboxWorker"])
    @ConditionalOnMissingBean(OutboxWorker::class)
    fun outboxWorker(
        repository: OutboxProcessingRepository, transaction: ApplicationTransaction,
        handlers: List<OutboxEventHandler>, plugins: FileWeftPluginRegistry, clock: Clock, traces: ObjectProvider<TraceContextScope>,
        properties: FileWeftProperties,
    ) = factories.outboxWorker(repository, transaction, handlers, plugins, clock, traces, properties)
}
