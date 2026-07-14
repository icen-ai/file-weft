package ai.icen.fw.starter.boot2

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.idempotency.RequestIdempotencyService
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
import ai.icen.fw.runtime.plugin.FileWeftPluginRegistry
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.beans.factory.ObjectProvider
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
    fun fileWeftWorkflowQueryRepository(): WorkflowQueryRepository = factories.fileWeftWorkflowQueryRepository()

    @Bean
    @ConditionalOnMissingBean(WorkflowDecisionEvidenceQueryRepository::class)
    fun fileWeftWorkflowDecisionEvidenceQueryRepository(): WorkflowDecisionEvidenceQueryRepository = factories.fileWeftWorkflowDecisionEvidenceQueryRepository()

    @Bean
    @ConditionalOnMissingBean(WorkflowInstanceRepository::class)
    fun fileWeftWorkflowRepository(clock: Clock): WorkflowInstanceRepository = factories.fileWeftWorkflowRepository(clock)

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
    ): IdempotentDocumentReviewWorkflowService = factories.fileWeftIdempotentDocumentReviewWorkflowService(reviews, idempotency)

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
    ): IdempotentDocumentCatalogReviewWorkflowService? = factories.fileWeftIdempotentDocumentCatalogReviewWorkflowService(catalogLifecycles, idempotency)

    @Bean
    @ConditionalOnMissingBean(WorkflowQueryService::class)
    fun fileWeftWorkflowQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): WorkflowQueryService = factories.fileWeftWorkflowQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean
    @ConditionalOnMissingBean(WorkflowDecisionEvidenceQueryService::class)
    fun fileWeftWorkflowDecisionEvidenceQueryService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: WorkflowDecisionEvidenceQueryRepository,
        transaction: ApplicationTransaction,
        folderReadAccess: ObjectProvider<DocumentFolderReadAccess>,
    ): WorkflowDecisionEvidenceQueryService = factories.fileWeftWorkflowDecisionEvidenceQueryService(tenants, users, authorization, queries, transaction, folderReadAccess)

    @Bean
    fun fileWeftDefaultDocumentReviewRouteProvider(): DocumentReviewRouteProvider = factories.fileWeftDefaultDocumentReviewRouteProvider()

    @Bean
    @ConditionalOnMissingBean(DocumentReviewRouteResolver::class)
    fun fileWeftDocumentReviewRouteResolver(
        providers: List<DocumentReviewRouteProvider>,
        plugins: FileWeftPluginRegistry,
        properties: FileWeftProperties,
    ): DocumentReviewRouteResolver = factories.fileWeftDocumentReviewRouteResolver(providers, plugins, properties)

    @Bean
    @ConditionalOnMissingBean(DocumentReviewWorkflowService::class)
    fun fileWeftReviewWorkflowService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        documents: DocumentRepository, workflows: WorkflowInstanceRepository, planner: DocumentDeliveryPlanner,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, auditTrail: AuditTrail,
        reviewRoutes: DocumentReviewRouteResolver,
    ): DocumentReviewWorkflowService = factories.fileWeftReviewWorkflowService(tenants, users, authorization, documents, workflows, planner, identifiers, transaction, auditTrail, reviewRoutes)
}
