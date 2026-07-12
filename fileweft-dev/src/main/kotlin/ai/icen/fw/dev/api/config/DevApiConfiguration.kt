package ai.icen.fw.dev.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.adapter.s3.S3StorageAdapter
import ai.icen.fw.adapter.s3.S3StorageConfiguration
import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogDraftService
import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService
import ai.icen.fw.application.outbox.OutboxWorker
import ai.icen.fw.application.task.TaskWorker
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.workflow.DocumentReviewWorkflowService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.dev.api.catalog.DevCatalogDocumentService
import ai.icen.fw.dev.api.agent.DevClassificationAgent
import ai.icen.fw.dev.api.agent.DevPublishClassificationTrigger
import ai.icen.fw.dev.api.catalog.DevCatalogQueryService
import ai.icen.fw.dev.api.catalog.DevDocumentCatalogProvider
import ai.icen.fw.dev.api.connector.DevPlatformConnector
import ai.icen.fw.dev.api.connector.DevPlatformMirrorService
import ai.icen.fw.dev.api.security.DevAuthorizationProvider
import ai.icen.fw.dev.api.security.DevSessionStore
import ai.icen.fw.dev.api.security.DevTraceContextProvider
import ai.icen.fw.dev.api.security.DevTenantProvider
import ai.icen.fw.dev.api.security.DevUserDirectory
import ai.icen.fw.dev.api.security.DevUserRealmProvider
import ai.icen.fw.dev.api.service.DevAccessService
import ai.icen.fw.dev.api.service.DevDocumentQueryService
import ai.icen.fw.dev.api.service.DevAuthService
import ai.icen.fw.dev.api.service.DevOperationsService
import ai.icen.fw.dev.api.service.DevReviewService
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.ai.AgentTaskTrigger
import ai.icen.fw.spi.ai.FileWeftAgent
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.delivery.DocumentDeliveryTargetDefinition
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI

@Configuration(proxyBeanMethods = false)
class DevApiConfiguration {
    @Bean
    fun devClassificationAgent(): FileWeftAgent = DevClassificationAgent()

    @Bean
    fun devPublishClassificationTrigger(): AgentTaskTrigger = DevPublishClassificationTrigger()

    @Bean
    fun devUserDirectory(properties: FileWeftDevProperties): DevUserDirectory = DevUserDirectory(properties)

    @Bean
    fun devSessionStore(): DevSessionStore = DevSessionStore()

    @Bean
    fun devTraceContextProvider(): DevTraceContextProvider = DevTraceContextProvider()

    @Bean
    fun devAuthService(users: DevUserDirectory, sessions: DevSessionStore): DevAuthService = DevAuthService(users, sessions)

    @Bean
    fun devTenantProvider(): TenantProvider = DevTenantProvider()

    @Bean
    fun devUserRealmProvider(users: DevUserDirectory): UserRealmProvider = DevUserRealmProvider(users)

    @Bean
    fun devAuthorizationProvider(): AuthorizationProvider = DevAuthorizationProvider()

    @Bean
    fun devAccessService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
    ): DevAccessService = DevAccessService(tenants, users, authorization)

    @Bean
    fun devDocumentCatalogProvider(): DocumentCatalogProvider = DevDocumentCatalogProvider()

    @Bean
    fun devCatalogQueryService(
        catalogAccess: DocumentCatalogAccessService,
    ): DevCatalogQueryService = DevCatalogQueryService(catalogAccess)

    @Bean
    fun devCatalogDocumentService(
        catalogDrafts: DocumentCatalogDraftService,
    ): DevCatalogDocumentService = DevCatalogDocumentService(catalogDrafts)

    @Bean
    fun devDocumentQueryService(
        jdbcTemplate: JdbcTemplate,
        access: DevAccessService,
        tenants: TenantProvider,
    ): DevDocumentQueryService = DevDocumentQueryService(jdbcTemplate, access, tenants)

    @Bean(destroyMethod = "close")
    fun devS3StorageAdapter(properties: FileWeftDevProperties): S3StorageAdapter = S3StorageAdapter(
        S3StorageConfiguration(
            endpoint = URI(properties.storage.endpoint),
            region = properties.storage.region,
            accessKey = properties.storage.accessKey,
            secretKey = properties.storage.secretKey,
            bucket = properties.storage.bucket,
        ),
    )

    @Bean
    fun complianceConnector(properties: FileWeftDevProperties, objectMapper: ObjectMapper): FileConnector = DevPlatformConnector(
        baseUrl = URI(properties.platform.baseUrl),
        objectMapper = objectMapper,
        connectTimeoutMillis = properties.platform.connectTimeoutMillis,
        readTimeoutMillis = properties.platform.readTimeoutMillis,
        targetId = "compliance",
        sharedSecret = properties.platform.sharedSecret,
    )

    @Bean("collaborationConnector")
    fun collaborationConnector(properties: FileWeftDevProperties, objectMapper: ObjectMapper): FileConnector = DevPlatformConnector(
        baseUrl = URI(properties.platform.baseUrl),
        objectMapper = objectMapper,
        connectTimeoutMillis = properties.platform.connectTimeoutMillis,
        readTimeoutMillis = properties.platform.readTimeoutMillis,
        targetId = "collaboration",
        sharedSecret = properties.platform.sharedSecret,
    )

    @Bean("searchConnector")
    fun searchConnector(properties: FileWeftDevProperties, objectMapper: ObjectMapper): FileConnector = DevPlatformConnector(
        baseUrl = URI(properties.platform.baseUrl),
        objectMapper = objectMapper,
        connectTimeoutMillis = properties.platform.connectTimeoutMillis,
        readTimeoutMillis = properties.platform.readTimeoutMillis,
        targetId = "search",
        sharedSecret = properties.platform.sharedSecret,
    )

    @Bean
    fun devPlatformMirrorService(
        properties: FileWeftDevProperties,
        objectMapper: ObjectMapper,
        tenants: TenantProvider,
    ): DevPlatformMirrorService = DevPlatformMirrorService(
        tenants,
        URI(properties.platform.baseUrl),
        properties.platform.sharedSecret,
        objectMapper,
        properties.platform.connectTimeoutMillis,
        properties.platform.readTimeoutMillis,
    )

    @Bean
    fun devDocumentDeliveryProfiles(): DocumentDeliveryProfileProvider = object : DocumentDeliveryProfileProvider {
        private val profiles = listOf(
            DocumentDeliveryProfile(
                id = "regulated",
                displayName = "Regulated release",
                targets = listOf(
                    DocumentDeliveryTargetDefinition("compliance", "Compliance archive", "complianceConnector", DeliveryRequirement.REQUIRED, "compliance-ops"),
                    DocumentDeliveryTargetDefinition("collaboration", "Collaboration workspace", "collaborationConnector", DeliveryRequirement.REQUIRED, "workspace-ops"),
                    DocumentDeliveryTargetDefinition("search", "Search index", "searchConnector", DeliveryRequirement.OPTIONAL, "search-ops"),
                ),
            ),
            DocumentDeliveryProfile(
                id = "internal",
                displayName = "Internal workspace",
                targets = listOf(
                    DocumentDeliveryTargetDefinition("collaboration", "Collaboration workspace", "collaborationConnector", DeliveryRequirement.REQUIRED, "workspace-ops"),
                ),
            ),
        )

        override fun listProfiles(tenantId: ai.icen.fw.core.id.Identifier): List<DocumentDeliveryProfile> = profiles

        override fun defaultProfile(tenantId: ai.icen.fw.core.id.Identifier): DocumentDeliveryProfile = profiles.first()
    }

    /** A deterministic seeded reviewer route for the formal submit API. */
    @Bean
    fun devSingleReviewerRoute(): DocumentReviewRouteProvider = object : DocumentReviewRouteProvider {
        override fun id(): String = "single-reviewer"

        override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
            val tenantPrefix = request.tenantId.value
            require(tenantPrefix == "alpha" || tenantPrefix == "beta") {
                "The development reviewer route supports only the seeded alpha and beta tenants."
            }
            return DocumentReviewRoute(
                workflowType = DocumentReviewWorkflowService.REVIEW_WORKFLOW_TYPE,
                tasks = listOf(DocumentReviewRouteTask(Identifier("$tenantPrefix-reviewer"))),
            )
        }
    }

    /** A visible two-person route for exercising parallel review with both seeded tenants. */
    @Bean
    fun devDualControlReviewRoute(): DocumentReviewRouteProvider = object : DocumentReviewRouteProvider {
        override fun id(): String = "dual-control"

        override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
            val tenantPrefix = request.tenantId.value
            require(tenantPrefix == "alpha" || tenantPrefix == "beta") {
                "The development dual-control route supports only the seeded alpha and beta tenants."
            }
            return DocumentReviewRoute(
                workflowType = "DOCUMENT_DUAL_CONTROL",
                tasks = listOf(
                    DocumentReviewRouteTask(Identifier("$tenantPrefix-reviewer")),
                    DocumentReviewRouteTask(Identifier("$tenantPrefix-admin")),
                ),
            )
        }
    }

    @Bean
    fun devBucketInitializer(storage: S3StorageAdapter): ApplicationRunner = ApplicationRunner {
        storage.ensureBucket()
    }

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.dev.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun devOutboxRunner(properties: FileWeftDevProperties, worker: OutboxWorker): DevOutboxRunner =
        DevOutboxRunner(properties.outbox.fixedDelayMillis, properties.outbox.batchSize, worker)

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.dev.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun devTaskRunner(properties: FileWeftDevProperties, worker: TaskWorker): DevTaskRunner =
        DevTaskRunner(properties.task.batchSize, worker)

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.dev.worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun devResumableUploadRunner(
        properties: FileWeftDevProperties,
        uploads: ResumableUploadService,
    ): DevResumableUploadRunner = DevResumableUploadRunner(properties.upload.cleanupBatchSize, uploads::cleanupExpired)

    @Bean
    fun devOperationsService(
        access: DevAccessService,
        worker: OutboxWorker,
        taskWorker: TaskWorker,
    ): DevOperationsService = DevOperationsService(access, worker, taskWorker)

    @Bean
    fun devReviewService(
        access: DevAccessService,
        users: DevUserDirectory,
        tenants: TenantProvider,
        lifecycle: DocumentCatalogLifecycleService,
    ): DevReviewService = DevReviewService(access, users, tenants, lifecycle)
}
