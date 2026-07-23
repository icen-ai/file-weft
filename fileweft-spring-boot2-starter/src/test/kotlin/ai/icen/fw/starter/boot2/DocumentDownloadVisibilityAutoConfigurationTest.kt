package ai.icen.fw.starter.boot2

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.document.DocumentDetailView
import ai.icen.fw.application.document.DocumentDownloadService
import ai.icen.fw.application.document.DocumentDownloadVisibility
import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.document.DocumentPageRequest
import ai.icen.fw.application.document.DocumentPageResult
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.io.InputStream
import java.io.PrintWriter
import java.net.URI
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.time.Duration
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentDownloadVisibilityAutoConfigurationTest {
    @TempDir
    lateinit var storageRoot: Path

    @Test
    fun `keeps the default download service unscoped when no folder access is installed`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, DownloadFixtureConfiguration::class.java)
            .run { context ->
                val downloads = context.getBean(DocumentDownloadService::class.java)

                assertTrue(context.getBeansOfType(DocumentDownloadVisibility::class.java).isEmpty())
                assertNull(privateField(downloads, "downloadVisibility"))
            }
    }

    @Test
    fun `installs catalog visibility and hides an inaccessible document before storage opens`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                DownloadFixtureConfiguration::class.java,
                VisibleCatalogConfiguration::class.java,
            )
            .run { context ->
                val fixture = context.getBean(DownloadFixture::class.java)
                val downloads = context.getBean(DocumentDownloadService::class.java)
                val visibility = context.getBean(DocumentDownloadVisibility::class.java)

                assertSame(visibility, privateField(downloads, "downloadVisibility"))
                assertFailsWith<DocumentNotFoundException> {
                    downloads.download(fixture.hiddenDocumentId)
                }

                assertEquals(listOf("visible"), fixture.queries.lastFolderScope?.folderIds)
                assertEquals(0, fixture.storage.downloadCalls)
            }
    }

    @Test
    fun `preserves a customer supplied download visibility guard`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                DownloadFixtureConfiguration::class.java,
                VisibleCatalogConfiguration::class.java,
                CustomerVisibilityConfiguration::class.java,
            )
            .run { context ->
                val downloads = context.getBean(DocumentDownloadService::class.java)

                assertEquals(1, context.getBeansOfType(DocumentDownloadVisibility::class.java).size)
                assertSame(
                    context.getBean("customerDownloadVisibility"),
                    privateField(downloads, "downloadVisibility"),
                )
            }
    }

    @Test
    fun `fails closed for multiple download visibility guards even when one is primary`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                DownloadFixtureConfiguration::class.java,
                VisibleCatalogConfiguration::class.java,
                MultipleVisibilityConfiguration::class.java,
            )
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
            }
    }

    @Test
    fun `fails closed for multiple folder access candidates even when one is primary`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                DownloadFixtureConfiguration::class.java,
                MultipleFolderAccessConfiguration::class.java,
            )
            .run { context ->
                assertTrue(requireNotNull(context.startupFailure).hasCause(NoUniqueBeanDefinitionException::class.java))
            }
    }

    @Test
    fun `does not replace a customer supplied download service`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                DownloadFixtureConfiguration::class.java,
                VisibleCatalogConfiguration::class.java,
                CustomerDownloadServiceConfiguration::class.java,
            )
            .run { context ->
                val downloads = context.getBean(DocumentDownloadService::class.java)

                assertSame(
                    context.getBean("customerDocumentDownloadService"),
                    downloads,
                )
                assertSame(
                    context.getBean(DocumentDownloadVisibility::class.java),
                    privateField(downloads, "downloadVisibility"),
                )
            }
    }

    private fun contextRunner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileWeftAutoConfiguration::class.java))
        .withPropertyValues(
            "fileweft.default-tenant-enabled=true",
            "fileweft.default-tenant-id=test-tenant",
            "fileweft.storage.local-enabled=true",
            "fileweft.storage.local-root=${storageRoot.toAbsolutePath()}",
        )

    private fun privateField(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
        .get(target)

    private fun Throwable.hasCause(type: Class<out Throwable>): Boolean =
        generateSequence(this) { throwable -> throwable.cause }.any { throwable -> type.isInstance(throwable) }

    @Configuration(proxyBeanMethods = false)
    class DatabaseConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource
    }

    @Configuration(proxyBeanMethods = false)
    class DownloadFixtureConfiguration {
        @Bean
        fun downloadFixture(): DownloadFixture = DownloadFixture()

        @Bean
        fun tenantProvider(fixture: DownloadFixture): TenantProvider = fixture.tenants

        @Bean
        fun userRealmProvider(fixture: DownloadFixture): UserRealmProvider = fixture.users

        @Bean
        fun authorizationProvider(fixture: DownloadFixture): AuthorizationProvider = fixture.authorization

        @Bean
        fun storageAdapter(fixture: DownloadFixture): StorageAdapter = fixture.storage

        @Bean
        fun documentRepository(fixture: DownloadFixture): DocumentRepository = fixture.documents

        @Bean
        fun fileObjectRepository(fixture: DownloadFixture): FileObjectRepository = fixture.files

        @Bean
        fun documentQueryRepository(fixture: DownloadFixture): DocumentQueryRepository = fixture.queries

        @Bean
        fun applicationTransaction(): ApplicationTransaction = DirectTransaction
    }

    @Configuration(proxyBeanMethods = false)
    class VisibleCatalogConfiguration {
        @Bean
        fun visibleCatalogProvider(): DocumentCatalogProvider = object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = listOf(
                DocumentCatalogFolder("visible", null, "Visible folder"),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerVisibilityConfiguration {
        @Bean
        fun customerDownloadVisibility(
            folderReadAccess: DocumentFolderReadAccess,
            queries: DocumentQueryRepository,
        ): DocumentDownloadVisibility = DocumentDownloadVisibility(folderReadAccess, queries)
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleVisibilityConfiguration {
        @Bean
        @Primary
        fun primaryDownloadVisibility(
            folderReadAccess: DocumentFolderReadAccess,
            queries: DocumentQueryRepository,
        ): DocumentDownloadVisibility = DocumentDownloadVisibility(folderReadAccess, queries)

        @Bean
        fun secondaryDownloadVisibility(
            folderReadAccess: DocumentFolderReadAccess,
            queries: DocumentQueryRepository,
        ): DocumentDownloadVisibility = DocumentDownloadVisibility(folderReadAccess, queries)
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleFolderAccessConfiguration {
        @Bean
        @Primary
        fun primaryFolderReadAccess(): DocumentFolderReadAccess = FixedFolderAccess("primary")

        @Bean
        fun secondaryFolderReadAccess(): DocumentFolderReadAccess = FixedFolderAccess("secondary")
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerDownloadServiceConfiguration {
        @Bean
        fun customerDocumentDownloadService(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
            documents: DocumentRepository,
            fileObjects: FileObjectRepository,
            storage: StorageAdapter,
            transaction: ApplicationTransaction,
            auditTrail: AuditTrail,
            visibility: DocumentDownloadVisibility,
        ): DocumentDownloadService = DocumentDownloadService(
            tenants,
            users,
            authorization,
            documents,
            fileObjects,
            storage,
            transaction,
            auditTrail,
            visibility,
        )
    }

    class DownloadFixture {
        val tenantId = Identifier("tenant-1")
        val hiddenDocumentId = Identifier("hidden-document")
        private val versionId = Identifier("version-1")
        private val fileId = Identifier("file-1")
        val storage = RecordingStorage()
        val queries = RecordingQueries()
        val tenants = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(tenantId)
        }
        val users = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "Visibility User")
            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        val authorization = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
        }
        val documents = object : DocumentMutationRepository {
            private val document = Document(
                id = hiddenDocumentId,
                tenantId = tenantId,
                assetId = Identifier("asset-1"),
                documentNumber = "DOC-HIDDEN",
                title = "Hidden document",
                versions = listOf(DocumentVersion(versionId, tenantId, hiddenDocumentId, "1.0", fileId)),
                currentVersionId = versionId,
            )

            override fun findById(tenantId: Identifier, documentId: Identifier): Document? = document.takeIf { candidate ->
                candidate.tenantId == tenantId && candidate.id == documentId
            }

            override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? = findById(tenantId, documentId)

            override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null

            override fun save(document: Document) = Unit
        }
        val files = object : FileObjectRepository {
            override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? = FileObject(
                id = fileObjectId,
                tenantId = tenantId,
                fileName = "hidden.txt",
                contentLength = 6,
                storageType = "memory",
                storagePath = "tenant-1/hidden",
                contentType = "text/plain",
            )

            override fun save(fileObject: FileObject) = Unit
        }
    }

    class RecordingQueries : DocumentQueryRepository {
        var lastFolderScope: DocumentFolderReadScope? = null
            private set

        override fun findDetail(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentDetailView? {
            lastFolderScope = folderReadScope
            // The fixture document belongs to a folder not supplied by the
            // trusted catalog, so visibility must hide it as absent.
            return null
        }

        override fun findPage(
            tenantId: Identifier,
            request: DocumentPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentPageResult = DocumentPageResult(emptyList())
    }

    class RecordingStorage : StorageAdapter {
        var downloadCalls: Int = 0
            private set

        override fun download(location: StorageObjectLocation): StorageDownload {
            downloadCalls++
            throw AssertionError("Hidden downloads must not open storage.")
        }

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = unsupported()
        override fun delete(location: StorageObjectLocation) = unsupported<Unit>()
        override fun exists(location: StorageObjectLocation): Boolean = unsupported()
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = unsupported()
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = unsupported()
        override fun uploadPart(
            upload: MultipartUpload,
            partNumber: Int,
            content: InputStream,
            contentLength: Long,
        ): MultipartPart = unsupported()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = unsupported()
        override fun abortMultipartUpload(upload: MultipartUpload) = unsupported<Unit>()

        private fun <T> unsupported(): T = throw UnsupportedOperationException("Not used by download visibility tests")
    }

    private class FixedFolderAccess(
        private val folderId: String,
    ) : DocumentFolderReadAccess {
        override fun requireFolderForDocumentRead(folderId: String) = Unit

        override fun readableFolderIds(): Set<String> = setOf(this.folderId)
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private object StubDataSource : DataSource {
        override fun getConnection(): Connection = throw UnsupportedOperationException("Test data source")
        override fun getConnection(username: String?, password: String?): Connection =
            throw UnsupportedOperationException("Test data source")
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLFeatureNotSupportedException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
}
