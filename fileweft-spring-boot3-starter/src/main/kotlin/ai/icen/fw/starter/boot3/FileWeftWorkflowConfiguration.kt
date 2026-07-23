package ai.icen.fw.starter.boot3

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.doctor.*
import ai.icen.fw.application.delivery.*
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.outbox.*
import ai.icen.fw.application.sync.*
import ai.icen.fw.application.task.*
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.workflow.DocumentReviewRouteResolver
import ai.icen.fw.application.workflow.DocumentReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import ai.icen.fw.application.workflow.IdempotentDocumentReviewWorkflowService
import ai.icen.fw.application.workflow.WorkflowQueryRepository
import ai.icen.fw.application.workflow.WorkflowQueryService
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryRepository
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceQueryService
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.persistence.jdbc.*
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
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
class FileWeftWorkflowConfiguration {
    private val factories = FileWeftRuntimeFactories()
    @Bean(name = ["fileWeftWorkflowQueryRepository", "workflowQueries"])
    @ConditionalOnMissingBean(WorkflowQueryRepository::class)
    fun workflowQueries(): WorkflowQueryRepository = factories.workflowQueries()

    @Bean(name = ["fileWeftWorkflowDecisionEvidenceQueryRepository", "workflowDecisionEvidenceQueries"])
    @ConditionalOnMissingBean(WorkflowDecisionEvidenceQueryRepository::class)
    fun workflowDecisionEvidenceQueries(): WorkflowDecisionEvidenceQueryRepository = factories.workflowDecisionEvidenceQueries()

    @Bean(name = ["fileWeftWorkflowRepository", "workflows"])
    @ConditionalOnMissingBean(WorkflowInstanceRepository::class)
    fun workflows(clock: Clock): WorkflowInstanceRepository = factories.workflows(clock)

    @Bean(name = ["fileWeftIdempotentDocumentReviewWorkflowService", "idempotentDocumentReviewWorkflowService"])
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
    ) = factories.idempotentDocumentReviewWorkflowService(reviews, idempotency)

    @Bean(name = ["fileWeftIdempotentDocumentCatalogReviewWorkflowService", "idempotentDocumentCatalogReviewWorkflowService"])
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
    ): IdempotentDocumentCatalogReviewWorkflowService? = factories.idempotentDocumentCatalogReviewWorkflowService(catalogLifecycles, idempotency)

    @Bean(name = ["fileWeftWorkflowQueryService", "workflowQueryService"])
    @ConditionalOnMissingBean(WorkflowQueryService::class)
    fun workflowQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.workflowQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean(name = ["fileWeftWorkflowDecisionEvidenceQueryService", "workflowDecisionEvidenceQueryService"])
    @ConditionalOnMissingBean(WorkflowDecisionEvidenceQueryService::class)
    fun workflowDecisionEvidenceQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowDecisionEvidenceQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.workflowDecisionEvidenceQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean(name = ["fileWeftDefaultDocumentReviewRouteProvider", "defaultDocumentReviewRouteProvider"])
    fun defaultDocumentReviewRouteProvider(): DocumentReviewRouteProvider = factories.defaultDocumentReviewRouteProvider()

    @Bean(name = ["fileWeftDocumentReviewRouteResolver", "documentReviewRouteResolver"])
    @ConditionalOnMissingBean(DocumentReviewRouteResolver::class)
    fun documentReviewRouteResolver(
        providers: List<DocumentReviewRouteProvider>,
        plugins: FileWeftPluginRegistry,
        properties: FileWeftProperties,
    ): DocumentReviewRouteResolver = factories.documentReviewRouteResolver(providers, plugins, properties)

    @Bean(name = ["fileWeftReviewWorkflowService", "reviewWorkflowService"])
    @ConditionalOnMissingBean(DocumentReviewWorkflowService::class)
    fun reviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        reviewRoutes: DocumentReviewRouteResolver,
    ) = factories.reviewWorkflowService(tenants, users, authorization, documents, workflows, planner, identifiers, transaction, auditTrail, reviewRoutes)
}
