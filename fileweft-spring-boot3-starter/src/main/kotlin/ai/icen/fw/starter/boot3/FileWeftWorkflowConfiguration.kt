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

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
class FileWeftWorkflowConfiguration {
    private val factories = FileWeftRuntimeFactories()
    @Bean
    @ConditionalOnMissingBean(WorkflowQueryRepository::class)
    fun workflowQueries(): WorkflowQueryRepository = factories.workflowQueries()

    @Bean
    @ConditionalOnMissingBean(WorkflowDecisionEvidenceQueryRepository::class)
    fun workflowDecisionEvidenceQueries(): WorkflowDecisionEvidenceQueryRepository = factories.workflowDecisionEvidenceQueries()

    @Bean
    @ConditionalOnMissingBean(WorkflowInstanceRepository::class)
    fun workflows(clock: Clock): WorkflowInstanceRepository = factories.workflows(clock)

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
    ) = factories.idempotentDocumentReviewWorkflowService(reviews, idempotency)

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
    ): IdempotentDocumentCatalogReviewWorkflowService? = factories.idempotentDocumentCatalogReviewWorkflowService(catalogLifecycles, idempotency)

    @Bean
    @ConditionalOnMissingBean(WorkflowQueryService::class)
    fun workflowQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.workflowQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean
    @ConditionalOnMissingBean(WorkflowDecisionEvidenceQueryService::class)
    fun workflowDecisionEvidenceQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowDecisionEvidenceQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ) = factories.workflowDecisionEvidenceQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean
    fun defaultDocumentReviewRouteProvider(): DocumentReviewRouteProvider = factories.defaultDocumentReviewRouteProvider()

    @Bean
    @ConditionalOnMissingBean(DocumentReviewRouteResolver::class)
    fun documentReviewRouteResolver(
        providers: List<DocumentReviewRouteProvider>,
        plugins: FileWeftPluginRegistry,
        properties: FileWeftProperties,
    ): DocumentReviewRouteResolver = factories.documentReviewRouteResolver(providers, plugins, properties)

    @Bean
    @ConditionalOnMissingBean(DocumentReviewWorkflowService::class)
    fun reviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        reviewRoutes: DocumentReviewRouteResolver,
    ) = factories.reviewWorkflowService(tenants, users, authorization, documents, workflows, planner, identifiers, transaction, auditTrail, reviewRoutes)
}
