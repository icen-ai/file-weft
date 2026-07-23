package ai.icen.fw.starter.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.doctor.*
import ai.icen.fw.application.delivery.*
import ai.icen.fw.application.outbox.*
import ai.icen.fw.application.sync.*
import ai.icen.fw.application.task.*
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.ResumableUploadSessionRepository
import ai.icen.fw.application.upload.UploadApplicationService
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.persistence.jdbc.*
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
class FileWeftUploadConfiguration {
    private val factories = FileWeftRuntimeFactories()
    @Bean(name = ["fileWeftResumableUploadSessionRepository", "resumableUploadSessions"])
    @ConditionalOnMissingBean(ResumableUploadSessionRepository::class)
    fun resumableUploadSessions(objectMapper: ObjectMapper): ResumableUploadSessionRepository = factories.resumableUploadSessions(objectMapper)

    @Bean(name = ["fileWeftUploadService", "uploadService"])
    @ConditionalOnMissingBean(UploadApplicationService::class)
    fun uploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider, storage: StorageAdapter,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock, metrics: FileWeftMetrics,
    ) = factories.uploadService(tenants, users, authorization, storage, fileObjects, assets, outbox, identifiers, transaction, clock, metrics)

    @Bean(name = ["fileWeftResumableUploadService", "resumableUploadService"])
    @ConditionalOnMissingBean(ResumableUploadService::class)
    fun resumableUploadService(
        tenants: TenantProvider, users: UserRealmProvider, authorization: AuthorizationProvider,
        storage: StorageAdapter, sessions: ResumableUploadSessionRepository,
        fileObjects: FileObjectRepository, assets: FileAssetRepository, outbox: OutboxEventRepository,
        identifiers: IdentifierGenerator, transaction: ApplicationTransaction, clock: Clock,
        properties: FileWeftProperties, metrics: FileWeftMetrics,
    ) = factories.resumableUploadService(tenants, users, authorization, storage, sessions, fileObjects, assets, outbox, identifiers, transaction, clock, properties, metrics)
}
