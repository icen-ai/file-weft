package ai.icen.fw.starter.boot2

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.ResumableUploadSessionRepository
import ai.icen.fw.application.upload.UploadApplicationService
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DataSource::class)
class FileWeftUploadConfiguration {
    private val factories = FileWeftRuntimeFactories()
    @Bean
    @ConditionalOnMissingBean(ResumableUploadSessionRepository::class)
    fun fileWeftResumableUploadSessionRepository(objectMapper: ObjectMapper): ResumableUploadSessionRepository = factories.fileWeftResumableUploadSessionRepository(objectMapper)

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
}
