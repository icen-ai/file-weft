package ai.icen.fw.starter.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimService
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimRepository
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimService
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimRepository
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.ResumableUploadSessionRepository
import ai.icen.fw.application.upload.UploadApplicationService
import ai.icen.fw.application.upload.PresignedUploadCleanupService
import ai.icen.fw.application.upload.PresignedUploadRecoveryService
import ai.icen.fw.application.upload.PresignedUploadService
import ai.icen.fw.application.upload.PresignedUploadSessionRepository
import ai.icen.fw.application.upload.PresignedUploadDiagnosticsRepository
import ai.icen.fw.application.upload.PresignedUploadDiagnosticsService
import ai.icen.fw.application.upload.PresignedUploadDoctorChecker
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.persistence.jdbc.JdbcPresignedUploadSessionRepository
import ai.icen.fw.persistence.jdbc.JdbcPresignedUploadDiagnosticsRepository
import ai.icen.fw.persistence.jdbc.JdbcResumableUploadSessionRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.PresignedUploadStorageAdapter
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.spi.tenant.TenantProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import io.micrometer.core.instrument.MeterRegistry
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
class FileWeftUploadConfiguration {
    private val factories = FileWeftRuntimeFactories()
    @Bean(name = ["fileWeftResumableUploadSessionRepository"])
    @ConditionalOnMissingBean(ResumableUploadSessionRepository::class)
    fun flowWeftJdbcResumableUploadSessionRepository(
        objectMapper: ObjectMapper,
    ): JdbcResumableUploadSessionRepository = JdbcResumableUploadSessionRepository(objectMapper)

    /** Retains the released factory-method ABI for hosts that construct the repository directly. */
    fun fileWeftResumableUploadSessionRepository(
        objectMapper: ObjectMapper,
    ): ResumableUploadSessionRepository = factories.fileWeftResumableUploadSessionRepository(objectMapper)

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.upload", name = ["presigned-enabled"], havingValue = "true")
    @ConditionalOnMissingBean(PresignedUploadSessionRepository::class)
    fun flowWeftPresignedUploadSessionRepository(objectMapper: ObjectMapper): JdbcPresignedUploadSessionRepository =
        JdbcPresignedUploadSessionRepository(objectMapper)

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.upload", name = ["presigned-enabled"], havingValue = "true")
    @ConditionalOnMissingBean(PresignedUploadDiagnosticsRepository::class)
    fun flowWeftPresignedUploadDiagnosticsRepository(): PresignedUploadDiagnosticsRepository =
        JdbcPresignedUploadDiagnosticsRepository()

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.upload", name = ["presigned-enabled"], havingValue = "true")
    @ConditionalOnMissingBean(PresignedUploadDiagnosticsService::class)
    fun flowWeftPresignedUploadDiagnosticsService(
        diagnostics: PresignedUploadDiagnosticsRepository,
        transaction: ApplicationTransaction,
        clock: Clock,
    ): PresignedUploadDiagnosticsService = PresignedUploadDiagnosticsService(diagnostics, transaction, clock)

    @Bean
    @ConditionalOnBean(value = [PresignedUploadDiagnosticsService::class, MeterRegistry::class])
    @ConditionalOnProperty(prefix = "fileweft.worker", name = ["enabled"], havingValue = "true")
    @ConditionalOnMissingBean(FlowWeftPresignedUploadMetricsPublisher::class)
    fun flowWeftPresignedUploadMetricsPublisher(
        diagnostics: PresignedUploadDiagnosticsService,
        meters: MeterRegistry,
    ): FlowWeftPresignedUploadMetricsPublisher = FlowWeftPresignedUploadMetricsPublisher(diagnostics, meters)

    @Bean(name = ["flowWeftPresignedUploadDoctorPlugin"])
    @ConditionalOnProperty(prefix = "fileweft.upload", name = ["presigned-enabled"], havingValue = "true")
    fun flowWeftPresignedUploadDoctorPlugin(diagnostics: PresignedUploadDiagnosticsService): FileWeftPlugin =
        object : FileWeftPlugin {
            private val checker: DoctorChecker = PresignedUploadDoctorChecker(diagnostics)

            override fun id(): String = "flowweft-presigned-upload-doctor"

            override fun doctorCheckers(): List<DoctorChecker> = listOf(checker)
        }

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.upload", name = ["presigned-enabled"], havingValue = "true")
    @ConditionalOnMissingBean(PresignedUploadService::class)
    fun flowWeftPresignedUploadService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        storageCandidates: ObjectProvider<StorageAdapter>,
        sessions: PresignedUploadSessionRepository,
        identifiers: IdentifierGenerator,
        transaction: ApplicationTransaction,
        clock: Clock,
        properties: FileWeftProperties,
    ): PresignedUploadService {
        val upload = validatedPresignedProperties(properties)
        return PresignedUploadService(
            tenantProvider = tenants,
            userRealmProvider = users,
            authorizationProvider = authorization,
            storageAdapter = requiredPresignedStorageAdapter(storageCandidates),
            repository = sessions,
            identifierGenerator = identifiers,
            clock = clock,
            finalizeGrace = Duration.ofMillis(upload.presignedFinalizeGraceMillis),
            claimLease = Duration.ofMillis(upload.presignedClaimLeaseMillis),
            transaction = transaction,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.upload", name = ["presigned-enabled"], havingValue = "true")
    @ConditionalOnBean(
        value = [RequestIdempotencyRepository::class, CompletedPresignedUploadAssetClaimRepository::class],
    )
    @ConditionalOnMissingBean(CompletedPresignedUploadAssetClaimService::class)
    fun flowWeftCompletedPresignedUploadAssetClaimService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        uploads: PresignedUploadService,
        sessions: CompletedPresignedUploadAssetClaimRepository,
        fileObjects: FileObjectRepository,
        fileAssets: FileAssetRepository,
        idempotency: RequestIdempotencyRepository,
        transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator,
        clock: Clock,
    ): CompletedPresignedUploadAssetClaimService = CompletedPresignedUploadAssetClaimService(
        tenantProvider = tenants,
        userRealmProvider = users,
        authorizationProvider = authorization,
        uploads = uploads,
        uploadSessions = sessions,
        fileObjects = fileObjects,
        fileAssets = fileAssets,
        idempotencyRepository = idempotency,
        transaction = transaction,
        identifiers = identifiers,
        clock = clock,
    )

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.upload", name = ["presigned-enabled"], havingValue = "true")
    @ConditionalOnMissingBean(PresignedUploadRecoveryService::class)
    fun flowWeftPresignedUploadRecoveryService(
        sessions: PresignedUploadSessionRepository,
        clock: Clock,
        transaction: ApplicationTransaction,
        properties: FileWeftProperties,
    ): PresignedUploadRecoveryService {
        validatedPresignedProperties(properties)
        return PresignedUploadRecoveryService(sessions, clock, transaction)
    }

    @Bean
    @ConditionalOnProperty(prefix = "fileweft.upload", name = ["presigned-enabled"], havingValue = "true")
    @ConditionalOnMissingBean(PresignedUploadCleanupService::class)
    fun flowWeftPresignedUploadCleanupService(
        sessions: PresignedUploadSessionRepository,
        storageCandidates: ObjectProvider<StorageAdapter>,
        clock: Clock,
        transaction: ApplicationTransaction,
        properties: FileWeftProperties,
    ): PresignedUploadCleanupService {
        validatedPresignedProperties(properties)
        return PresignedUploadCleanupService(
            sessions,
            requiredPresignedStorageAdapter(storageCandidates),
            clock,
            transaction,
        )
    }

    @Bean
    @ConditionalOnMissingBean(UploadApplicationService::class)
    fun fileWeftUploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, metrics: FileWeftMetrics,
    ): UploadApplicationService = factories.fileWeftUploadService(tenants, users, authorization, storage, fileObjects, assets, outbox, identifiers, transaction, clock, metrics)

    @Bean
    @ConditionalOnMissingBean(ResumableUploadService::class)
    fun fileWeftResumableUploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        storage: StorageAdapter, sessions: ResumableUploadSessionRepository,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock,
        properties: FileWeftProperties, metrics: FileWeftMetrics,
    ): ResumableUploadService = factories.fileWeftResumableUploadService(tenants, users, authorization, storage, sessions, fileObjects, assets, outbox, identifiers, transaction, clock, properties, metrics)

    @Bean
    @ConditionalOnBean(
        value = [
            RequestIdempotencyRepository::class,
            DocumentRepository::class,
            CompletedResumableUploadAssetClaimRepository::class,
        ],
    )
    @ConditionalOnMissingBean(CompletedResumableUploadAssetClaimService::class)
    fun fileWeftCompletedUploadAssetClaimService(
        tenants: TenantProvider,
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        sessions: CompletedResumableUploadAssetClaimRepository,
        documents: DocumentRepository,
        fileObjects: FileObjectRepository,
        assets: FileAssetRepository,
        idempotency: RequestIdempotencyRepository,
        transaction: ApplicationTransaction,
        identifiers: IdentifierGenerator,
        clock: Clock,
        auditTrails: ObjectProvider<AuditTrail>,
    ): CompletedResumableUploadAssetClaimService = CompletedResumableUploadAssetClaimService(
        tenantProvider = tenants,
        userRealmProvider = users,
        authorizationProvider = authorization,
        uploadSessions = sessions,
        documents = documents,
        fileObjects = fileObjects,
        fileAssets = assets,
        idempotencyRepository = idempotency,
        transaction = transaction,
        identifiers = identifiers,
        clock = clock,
        auditTrail = auditTrails.getIfUnique(),
    )

    private fun validatedPresignedProperties(properties: FileWeftProperties): FileWeftProperties.UploadProperties =
        properties.upload.also { upload ->
            require(upload.presignedFinalizeGraceMillis > 0) {
                "fileweft.upload.presigned-finalize-grace-millis must be positive."
            }
            require(upload.presignedClaimLeaseMillis > 0) {
                "fileweft.upload.presigned-claim-lease-millis must be positive."
            }
            require(upload.presignedClaimLeaseMillis <= upload.presignedFinalizeGraceMillis) {
                "fileweft.upload.presigned-claim-lease-millis must not exceed the finalize grace."
            }
            require(upload.presignedMaintenanceBatchSize in 1..1_000) {
                "fileweft.upload.presigned-maintenance-batch-size must be between 1 and 1000."
            }
        }

    private fun requiredPresignedStorageAdapter(
        candidates: ObjectProvider<StorageAdapter>,
    ): PresignedUploadStorageAdapter {
        val adapters = candidates.orderedStream().iterator().asSequence().toList()
        require(adapters.size == 1) {
            "Presigned uploads require exactly one selected StorageAdapter; found ${adapters.size}."
        }
        return adapters.single() as? PresignedUploadStorageAdapter
            ?: throw IllegalStateException(
                "The selected StorageAdapter does not provide the PresignedUploadStorageAdapter capability.",
            )
    }
}
