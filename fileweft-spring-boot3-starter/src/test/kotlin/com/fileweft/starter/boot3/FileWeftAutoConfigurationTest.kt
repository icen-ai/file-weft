package com.fileweft.starter.boot3

import com.fileweft.adapter.authorization.DefaultAuthorizationProvider
import com.fileweft.agent.AgentTaskHandler
import com.fileweft.agent.AgentTaskOutboxEventHandler
import com.fileweft.agent.PersistedAgentSuggestionConfirmationService
import com.fileweft.application.agent.AgentResultRepository
import com.fileweft.application.agent.ConfirmAgentSuggestionService
import com.fileweft.adapter.identity.DefaultUserRealmProvider
import com.fileweft.adapter.storage.LocalStorageAdapter
import com.fileweft.adapter.observability.NoOpFileWeftMetrics
import com.fileweft.adapter.micrometer.MicrometerFileWeftMetrics
import com.fileweft.adapter.observability.NoOpTraceContextProvider
import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.doctor.DoctorApplicationService
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.application.outbox.OutboxWorker
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.upload.UploadApplicationService
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.runtime.plugin.FileWeftPluginRegistry
import com.fileweft.spi.plugin.FileWeftPlugin
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.observability.TraceContextProvider
import com.fileweft.spi.observability.TraceContextScope
import com.fileweft.domain.operation.OperationLogRepository
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.spi.authorization.AuthorizationAction
import com.fileweft.spi.authorization.AuthorizationEnvironment
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.authorization.AuthorizationResource
import com.fileweft.spi.authorization.AuthorizationSubject
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.storage.MultipartPart
import com.fileweft.spi.storage.MultipartUpload
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageDownload
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileWeftAutoConfigurationTest {
    @TempDir
    lateinit var storageRoot: Path

    @Test
    fun `binds the default tenant property`() {
        contextRunner().withPropertyValues("fileweft.default-tenant-id=tenant-a").run { context ->
            assertEquals("tenant-a", context.getBean(TenantProvider::class.java).currentTenant().tenantId.value)
        }
    }

    @Test
    fun `does not replace customer extension beans`() {
        contextRunner().withUserConfiguration(CustomerConfiguration::class.java).run { context ->
            assertSame(context.getBean("customerTenantProvider"), context.getBean(TenantProvider::class.java))
            assertSame(context.getBean("customerUserRealmProvider"), context.getBean(UserRealmProvider::class.java))
            assertSame(context.getBean("customerAuthorizationProvider"), context.getBean(AuthorizationProvider::class.java))
            assertSame(context.getBean("customerStorageAdapter"), context.getBean(StorageAdapter::class.java))
        }
    }

    @Test
    fun `defaults to safe identity authorization and local storage adapters`() {
        contextRunner().run { context ->
            assertNull(context.getBean(UserRealmProvider::class.java).currentUser())
            assertTrue(context.getBean(UserRealmProvider::class.java) is DefaultUserRealmProvider)
            val decision = context.getBean(AuthorizationProvider::class.java).authorize(request())
            assertFalse(decision.allowed)
            assertEquals(DefaultAuthorizationProvider.REASON, decision.reason)
            assertTrue(context.getBean(AuthorizationProvider::class.java) is DefaultAuthorizationProvider)
            assertTrue(context.getBean(StorageAdapter::class.java) is LocalStorageAdapter)
            assertTrue(context.getBean(FileWeftMetrics::class.java) is NoOpFileWeftMetrics)
            assertTrue(context.getBean(TraceContextProvider::class.java) is NoOpTraceContextProvider)
            assertTrue(context.getBean(TraceContextScope::class.java) is NoOpTraceContextProvider)
        }
    }

    @Test
    fun `uses plugin storage ahead of the default while retaining plugin diagnostics`() {
        contextRunner().withUserConfiguration(PluginConfiguration::class.java).run { context ->
            assertSame(CustomerStorageAdapter, context.getBean(StorageAdapter::class.java))
            assertTrue(context.getBean(FileWeftPluginRegistry::class.java).plugins().any { it.id() == "test-storage-plugin" })
        }
    }

    @Test
    fun `binds local storage root and makes the default storage usable`() {
        contextRunner().run { context ->
            val storage = context.getBean(StorageAdapter::class.java)
            val stored = storage.upload(
                StorageUploadRequest(Identifier("tenant-a"), "sample.txt", 2, "text/plain"),
                "ok".byteInputStream(),
            )

            assertTrue(storage.accessUrl(stored.location, Duration.ofMinutes(1)).toString().startsWith(storageRoot.toUri().toString()))
            storage.delete(stored.location)
        }
    }

    @Test
    fun `adapts the host Micrometer registry without replacing a customer metrics bean`() {
        contextRunner().withUserConfiguration(MicrometerConfiguration::class.java).run { context ->
            assertTrue(context.getBean(FileWeftMetrics::class.java) is MicrometerFileWeftMetrics)
        }
    }

    @Test
    fun `assembles persistence backed runtime services when a data source is available`() {
        contextRunner().withUserConfiguration(DatabaseConfiguration::class.java).run { context ->
            assertTrue(context.getBean(ApplicationTransaction::class.java) != null)
            assertTrue(context.getBean(DocumentRepository::class.java) != null)
            assertTrue(context.getBean(WorkflowInstanceRepository::class.java) != null)
            assertTrue(context.getBean(UploadApplicationService::class.java) != null)
            assertTrue(context.getBean(DocumentDraftService::class.java) != null)
            assertTrue(context.getBean(DocumentDownloadService::class.java) != null)
            assertTrue(context.getBean(ArchiveDocumentService::class.java) != null)
            assertTrue(context.getBean(DoctorApplicationService::class.java) != null)
            assertTrue(context.getBean(OutboxWorker::class.java) != null)
            assertTrue(context.getBean(OperationLogRepository::class.java) != null)
            assertTrue(context.getBean(AgentResultRepository::class.java) != null)
            assertTrue(context.getBean(ConfirmAgentSuggestionService::class.java) != null)
            assertTrue(context.getBean(AgentTaskHandler::class.java) != null)
            assertTrue(context.getBean(AgentTaskOutboxEventHandler::class.java) != null)
            assertTrue(context.getBean(PersistedAgentSuggestionConfirmationService::class.java) != null)
        }
    }

    @Test
    fun `enables a scheduler only for the explicit worker deployment role`() {
        contextRunner().withUserConfiguration(DatabaseConfiguration::class.java)
            .withPropertyValues("fileweft.worker.enabled=true")
            .run { context ->
                assertTrue(context.getBean(FileWeftWorkerScheduler::class.java) != null)
            }
    }

    @Test
    fun `does not replace a customer document repository when assembling runtime services`() {
        contextRunner().withUserConfiguration(DatabaseWithDocumentRepositoryConfiguration::class.java).run { context ->
            assertSame(context.getBean("customerDocumentRepository"), context.getBean(DocumentRepository::class.java))
        }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileWeftAutoConfiguration::class.java))
        .withPropertyValues("fileweft.storage.local-root=${storageRoot.toAbsolutePath()}")

    private fun request() = AuthorizationRequest(
        AuthorizationSubject(Identifier("user"), "USER"),
        AuthorizationResource(Identifier("document"), "DOCUMENT", Identifier("tenant")),
        AuthorizationAction("document:read"),
        AuthorizationEnvironment(),
    )

    @Configuration(proxyBeanMethods = false)
    class CustomerConfiguration {
        @Bean
        fun customerTenantProvider(): TenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("customer"))
        }

        @Bean
        fun customerUserRealmProvider(): UserRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("customer-user"))
            override fun findUser(userId: Identifier): UserIdentity? = null
        }

        @Bean
        fun customerAuthorizationProvider(): AuthorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
        }

        @Bean
        fun customerStorageAdapter(): StorageAdapter = CustomerStorageAdapter
    }

    @Configuration(proxyBeanMethods = false)
    class DatabaseConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource
    }

    @Configuration(proxyBeanMethods = false)
    class PluginConfiguration {
        @Bean
        fun testStoragePlugin(): FileWeftPlugin = object : FileWeftPlugin {
            override fun id(): String = "test-storage-plugin"
            override fun storageAdapters(): List<StorageAdapter> = listOf(CustomerStorageAdapter)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class MicrometerConfiguration {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Configuration(proxyBeanMethods = false)
    class DatabaseWithDocumentRepositoryConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource

        @Bean
        fun customerDocumentRepository(): DocumentRepository = object : DocumentRepository {
            override fun findById(tenantId: Identifier, documentId: Identifier) = null
            override fun save(document: com.fileweft.domain.document.Document) = Unit
        }
    }

    private object CustomerStorageAdapter : StorageAdapter {
        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = unsupported()
        override fun download(location: StorageObjectLocation): StorageDownload = unsupported()
        override fun delete(location: StorageObjectLocation) = unsupported<Unit>()
        override fun exists(location: StorageObjectLocation): Boolean = unsupported()
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = unsupported()
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = unsupported()
        override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = unsupported()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = unsupported()
        override fun abortMultipartUpload(upload: MultipartUpload) = unsupported<Unit>()

        private fun <T> unsupported(): T = throw UnsupportedOperationException("Test double")
    }

    private object StubDataSource : DataSource {
        override fun getConnection(): Connection = throw UnsupportedOperationException("Test data source")
        override fun getConnection(username: String?, password: String?): Connection = throw UnsupportedOperationException("Test data source")
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
}
