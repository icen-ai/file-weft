package com.fileweft.dev.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.adapter.s3.S3StorageAdapter
import com.fileweft.adapter.s3.S3StorageConfiguration
import com.fileweft.application.catalog.DocumentCatalogAccessService
import com.fileweft.application.catalog.DocumentCatalogDraftService
import com.fileweft.application.catalog.DocumentCatalogLifecycleService
import com.fileweft.application.outbox.OutboxWorker
import com.fileweft.application.task.TaskWorker
import com.fileweft.application.upload.ResumableUploadService
import com.fileweft.application.doctor.DoctorApplicationService
import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.catalog.DevCatalogDocumentService
import com.fileweft.dev.api.agent.DevClassificationAgent
import com.fileweft.dev.api.agent.DevPublishClassificationTrigger
import com.fileweft.dev.api.catalog.DevCatalogQueryService
import com.fileweft.dev.api.catalog.DevDocumentCatalogProvider
import com.fileweft.dev.api.connector.DevPlatformConnector
import com.fileweft.dev.api.connector.DevPlatformMirrorService
import com.fileweft.dev.api.security.DevAuthorizationProvider
import com.fileweft.dev.api.security.DevSessionStore
import com.fileweft.dev.api.security.DevTraceContextProvider
import com.fileweft.dev.api.security.DevTenantProvider
import com.fileweft.dev.api.security.DevUserDirectory
import com.fileweft.dev.api.security.DevUserRealmProvider
import com.fileweft.dev.api.service.DevAccessService
import com.fileweft.dev.api.service.DevDocumentQueryService
import com.fileweft.dev.api.service.DevAuthService
import com.fileweft.dev.api.service.DevOperationsService
import com.fileweft.dev.api.service.DevReviewService
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.ai.AgentTaskTrigger
import com.fileweft.spi.ai.FileWeftAgent
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.spi.workflow.DocumentReviewRoute
import com.fileweft.spi.workflow.DocumentReviewRouteProvider
import com.fileweft.spi.workflow.DocumentReviewRouteRequest
import com.fileweft.spi.workflow.DocumentReviewRouteTask
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

        override fun listProfiles(tenantId: com.fileweft.core.id.Identifier): List<DocumentDeliveryProfile> = profiles

        override fun defaultProfile(tenantId: com.fileweft.core.id.Identifier): DocumentDeliveryProfile = profiles.first()
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
        doctor: DoctorApplicationService,
    ): DevOperationsService = DevOperationsService(access, worker, taskWorker, doctor)

    @Bean
    fun devReviewService(
        access: DevAccessService,
        users: DevUserDirectory,
        tenants: TenantProvider,
        lifecycle: DocumentCatalogLifecycleService,
    ): DevReviewService = DevReviewService(access, users, tenants, lifecycle)
}
