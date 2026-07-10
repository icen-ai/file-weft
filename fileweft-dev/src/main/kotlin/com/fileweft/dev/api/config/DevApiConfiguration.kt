package com.fileweft.dev.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.adapter.s3.S3StorageAdapter
import com.fileweft.adapter.s3.S3StorageConfiguration
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.outbox.OutboxWorker
import com.fileweft.application.task.TaskWorker
import com.fileweft.application.doctor.DoctorApplicationService
import com.fileweft.application.workflow.DocumentReviewWorkflowService
import com.fileweft.dev.api.catalog.DevCatalogDocumentService
import com.fileweft.dev.api.catalog.DevCatalogQueryService
import com.fileweft.dev.api.catalog.DevDocumentCatalogProvider
import com.fileweft.dev.api.connector.DevPlatformConnector
import com.fileweft.dev.api.security.DevAuthorizationProvider
import com.fileweft.dev.api.security.DevSessionStore
import com.fileweft.dev.api.security.DevTenantProvider
import com.fileweft.dev.api.security.DevUserDirectory
import com.fileweft.dev.api.security.DevUserRealmProvider
import com.fileweft.dev.api.service.DevAccessService
import com.fileweft.dev.api.service.DevDocumentQueryService
import com.fileweft.dev.api.service.DevAuthService
import com.fileweft.dev.api.service.DevOperationsService
import com.fileweft.dev.api.service.DevReviewService
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.tenant.TenantProvider
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import java.net.URI

@Configuration(proxyBeanMethods = false)
class DevApiConfiguration {
    @Bean
    fun devUserDirectory(properties: FileWeftDevProperties): DevUserDirectory = DevUserDirectory(properties)

    @Bean
    fun devSessionStore(): DevSessionStore = DevSessionStore()

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
        catalog: DocumentCatalogProvider,
        access: DevAccessService,
        tenants: TenantProvider,
    ): DevCatalogQueryService = DevCatalogQueryService(catalog, access, tenants)

    @Bean
    fun devCatalogDocumentService(
        drafts: DocumentDraftService,
        catalog: DocumentCatalogProvider,
        tenants: TenantProvider,
    ): DevCatalogDocumentService = DevCatalogDocumentService(drafts, catalog, tenants)

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
    )

    @Bean("collaborationConnector")
    fun collaborationConnector(properties: FileWeftDevProperties, objectMapper: ObjectMapper): FileConnector = DevPlatformConnector(
        baseUrl = URI(properties.platform.baseUrl),
        objectMapper = objectMapper,
        connectTimeoutMillis = properties.platform.connectTimeoutMillis,
        readTimeoutMillis = properties.platform.readTimeoutMillis,
        targetId = "collaboration",
    )

    @Bean("searchConnector")
    fun searchConnector(properties: FileWeftDevProperties, objectMapper: ObjectMapper): FileConnector = DevPlatformConnector(
        baseUrl = URI(properties.platform.baseUrl),
        objectMapper = objectMapper,
        connectTimeoutMillis = properties.platform.connectTimeoutMillis,
        readTimeoutMillis = properties.platform.readTimeoutMillis,
        targetId = "search",
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

    @Bean
    fun devBucketInitializer(storage: S3StorageAdapter): ApplicationRunner = ApplicationRunner {
        storage.ensureBucket()
    }

    @Bean
    fun devOutboxRunner(properties: FileWeftDevProperties, worker: OutboxWorker): DevOutboxRunner =
        DevOutboxRunner(properties.outbox.fixedDelayMillis, properties.outbox.batchSize, worker)

    @Bean
    fun devTaskRunner(properties: FileWeftDevProperties, worker: TaskWorker): DevTaskRunner =
        DevTaskRunner(properties.task.batchSize, worker)

    @Bean
    fun devOperationsService(
        access: DevAccessService,
        worker: OutboxWorker,
        taskWorker: TaskWorker,
        doctor: DoctorApplicationService,
    ): DevOperationsService = DevOperationsService(access, worker, taskWorker, doctor)

    @Bean
    fun devReviewService(
        users: DevUserDirectory,
        tenants: TenantProvider,
        workflows: DocumentReviewWorkflowService,
    ): DevReviewService = DevReviewService(users, tenants, workflows)
}
