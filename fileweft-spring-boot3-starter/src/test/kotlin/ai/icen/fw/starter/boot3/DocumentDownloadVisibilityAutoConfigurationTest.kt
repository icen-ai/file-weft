package ai.icen.fw.starter.boot3

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
import ai.icen.fw.application.document.DocumentQueryService
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
import java.io.ByteArrayInputStream
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentDownloadVisibilityAutoConfigurationTest {
    @TempDir
    lateinit var storageRoot: Path

    @Test
    fun `keeps the default download service unscoped when catalog access is absent`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java)
            .run { context ->
                assertTrue(context.getBeansOfType(DocumentFolderReadAccess::class.java).isEmpty())
                assertTrue(context.getBeansOfType(DocumentDownloadVisibility::class.java).isEmpty())
                assertNull(privateField(context.getBean(DocumentDownloadService::class.java), "downloadVisibility"))
            }
    }

    @Test
    fun `single catalog access installs visibility and hides an out of scope download before storage`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, HiddenCatalogDownloadConfiguration::class.java)
            .run { context ->
                val visibility = context.getBean(DocumentDownloadVisibility::class.java)
                val downloads = context.getBean(DocumentDownloadService::class.java)
                val queries = context.getBean(HiddenDocumentQueries::class.java)
                val files = context.getBean(CountingFileObjects::class.java)
                val storage = context.getBean(CountingStorage::class.java)

                assertSame(visibility, privateField(downloads, "downloadVisibility"))
                assertFailsWith<DocumentNotFoundException> {
                    downloads.download(DOCUMENT_ID)
                }
                assertEquals(listOf("visible-folder"), queries.lastScope?.folderIds)
                assertEquals(1, queries.detailCalls)
                assertEquals(0, files.reads)
                assertEquals(0, storage.downloads)
            }
    }

    @Test
    fun `keeps a customer visibility and injects it into the default download service`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                SingleFolderAccessConfiguration::class.java,
                CustomerVisibilityConfiguration::class.java,
            )
            .run { context ->
                val customer = context.getBean("customerDocumentDownloadVisibility")

                assertSame(customer, context.getBean(DocumentDownloadVisibility::class.java))
                assertSame(
                    customer,
                    privateField(context.getBean(DocumentDownloadService::class.java), "downloadVisibility"),
                )
                assertEquals(1, context.getBeansOfType(DocumentDownloadVisibility::class.java).size)
            }
    }

    @Test
    fun `fails startup instead of dropping visibility when visibility candidates are ambiguous`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, MultipleVisibilityConfiguration::class.java)
            .run { context ->
                assertHasNoUniqueBeanFailure(context.startupFailure, DocumentDownloadVisibility::class.java)
            }
    }

    @Test
    fun `fails startup for multiple visibility guards even when one is primary`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, PrimaryVisibilityConfiguration::class.java)
            .run { context ->
                assertHasNoUniqueBeanFailure(context.startupFailure, DocumentDownloadVisibility::class.java)
            }
    }

    @Test
    fun `fails startup instead of falling back to unscoped downloads when folder access is ambiguous`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, MultipleFolderAccessConfiguration::class.java)
            .run { context ->
                assertHasNoUniqueBeanFailure(context.startupFailure, DocumentFolderReadAccess::class.java)
            }
    }

    @Test
    fun `fails startup for multiple folder access guards even when one is primary`() {
        contextRunner()
            .withUserConfiguration(DatabaseConfiguration::class.java, PrimaryFolderAccessConfiguration::class.java)
            .run { context ->
                assertHasNoUniqueBeanFailure(context.startupFailure, DocumentFolderReadAccess::class.java)
            }
    }

    @Test
    fun `does not replace a customer download service when folder visibility is available`() {
        contextRunner()
            .withUserConfiguration(
                DatabaseConfiguration::class.java,
                SingleFolderAccessConfiguration::class.java,
                CustomerDownloadServiceConfiguration::class.java,
            )
            .run { context ->
                val customer = context.getBean("customerDocumentDownloadService")
                val visibility = context.getBean(DocumentDownloadVisibility::class.java)

                assertSame(customer, context.getBean(DocumentDownloadService::class.java))
                assertEquals(1, context.getBeansOfType(DocumentDownloadService::class.java).size)
                assertSame(visibility, privateField(customer, "downloadVisibility"))
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

    private fun assertHasNoUniqueBeanFailure(failure: Throwable?, beanType: Class<*>) {
        val startupFailure = assertNotNull(failure)
        val ambiguity = generateSequence(startupFailure) { current -> current.cause }
            .filterIsInstance<NoUniqueBeanDefinitionException>()
            .firstOrNull()
        assertNotNull(ambiguity)
        assertEquals(beanType, ambiguity.beanType)
    }

    @Configuration(proxyBeanMethods = false)
    class DatabaseConfiguration {
        @Bean
        fun dataSource(): DataSource = StubDataSource
    }

    @Configuration(proxyBeanMethods = false)
    class HiddenCatalogDownloadConfiguration {
        @Bean
        fun tenantProvider(): TenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
        }

        @Bean
        fun userRealmProvider(): UserRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(USER_ID, "Visibility test user")
            override fun findUser(userId: Identifier): UserIdentity? = null
        }

        @Bean
        fun authorizationProvider(): AuthorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
        }

        @Bean
        fun applicationTransaction(): ApplicationTransaction = DirectTransaction

        @Bean
        fun documentRepository() = VisibleDocumentRepository()

        @Bean
        fun fileObjectRepository() = CountingFileObjects()

        @Bean
        fun documentQueryRepository() = HiddenDocumentQueries()

        @Bean
        fun storageAdapter() = CountingStorage()

        @Bean
        fun documentCatalogProvider(): DocumentCatalogProvider = object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
                listOf(DocumentCatalogFolder("visible-folder", null, "Visible folder"))
        }
    }

    @Configuration(proxyBeanMethods = false)
    class SingleFolderAccessConfiguration {
        @Bean
        fun folderReadAccess(): DocumentFolderReadAccess = PermissiveFolderReadAccess
    }

    @Configuration(proxyBeanMethods = false)
    class CustomerVisibilityConfiguration {
        @Bean
        fun customerDocumentDownloadVisibility(
            folderReadAccess: DocumentFolderReadAccess,
            queries: DocumentQueryRepository,
        ): DocumentDownloadVisibility = DocumentDownloadVisibility(folderReadAccess, queries)
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleVisibilityConfiguration {
        @Bean
        fun firstDocumentDownloadVisibility(queries: DocumentQueryRepository): DocumentDownloadVisibility =
            DocumentDownloadVisibility(PermissiveFolderReadAccess, queries)

        @Bean
        fun secondDocumentDownloadVisibility(queries: DocumentQueryRepository): DocumentDownloadVisibility =
            DocumentDownloadVisibility(PermissiveFolderReadAccess, queries)
    }

    @Configuration(proxyBeanMethods = false)
    class PrimaryVisibilityConfiguration {
        @Bean
        @Primary
        fun primaryDocumentDownloadVisibility(queries: DocumentQueryRepository): DocumentDownloadVisibility =
            DocumentDownloadVisibility(PermissiveFolderReadAccess, queries)

        @Bean
        fun secondaryDocumentDownloadVisibility(queries: DocumentQueryRepository): DocumentDownloadVisibility =
            DocumentDownloadVisibility(PermissiveFolderReadAccess, queries)
    }

    @Configuration(proxyBeanMethods = false)
    class MultipleFolderAccessConfiguration {
        @Bean
        fun firstFolderReadAccess(): DocumentFolderReadAccess = PermissiveFolderReadAccess

        @Bean
        fun secondFolderReadAccess(): DocumentFolderReadAccess = object : DocumentFolderReadAccess {
            override fun requireFolderForDocumentRead(folderId: String) = Unit
            override fun readableFolderIds(): Set<String> = setOf("second-folder")
        }

        // Keep the test focused on the visibility factory rather than the
        // independently optional folder access on DocumentQueryService.
        @Bean
        fun customerDocumentQueryService(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
            queries: DocumentQueryRepository,
            transaction: ApplicationTransaction,
        ): DocumentQueryService = DocumentQueryService(
            tenants,
            users,
            authorization,
            queries,
            transaction,
        )
    }

    @Configuration(proxyBeanMethods = false)
    class PrimaryFolderAccessConfiguration {
        @Bean
        @Primary
        fun primaryFolderReadAccess(): DocumentFolderReadAccess = PermissiveFolderReadAccess

        @Bean
        fun secondaryFolderReadAccess(): DocumentFolderReadAccess = object : DocumentFolderReadAccess {
            override fun requireFolderForDocumentRead(folderId: String) = Unit
            override fun readableFolderIds(): Set<String> = setOf("secondary-folder")
        }

        @Bean
        fun customerDocumentQueryService(
            tenants: TenantProvider,
            users: UserRealmProvider,
            authorization: AuthorizationProvider,
            queries: DocumentQueryRepository,
            transaction: ApplicationTransaction,
        ): DocumentQueryService = DocumentQueryService(
            tenants,
            users,
            authorization,
            queries,
            transaction,
        )
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

    class VisibleDocumentRepository : DocumentMutationRepository {
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? = findById(tenantId, documentId)
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            DOCUMENT.takeIf { document -> document.tenantId == tenantId && document.id == documentId }

        override fun save(document: Document) = Unit
    }

    class HiddenDocumentQueries : DocumentQueryRepository {
        var detailCalls: Int = 0
            private set
        var lastScope: DocumentFolderReadScope? = null
            private set

        override fun findDetail(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentDetailView? {
            detailCalls++
            lastScope = folderReadScope
            return null
        }

        override fun findPage(
            tenantId: Identifier,
            request: DocumentPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentPageResult = DocumentPageResult(emptyList())
    }

    class CountingFileObjects : FileObjectRepository {
        var reads: Int = 0
            private set

        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? {
            reads++
            return FileObject(
                fileObjectId,
                tenantId,
                "hidden.txt",
                7,
                "memory",
                "tenant-a/hidden-object",
                "text/plain",
            )
        }

        override fun save(fileObject: FileObject) = Unit
    }

    class CountingStorage : StorageAdapter {
        var downloads: Int = 0
            private set

        override fun download(location: StorageObjectLocation): StorageDownload {
            downloads++
            return StorageDownload(ByteArrayInputStream("content".toByteArray()), 7, "text/plain")
        }

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = unsupported()
        override fun delete(location: StorageObjectLocation) = Unit
        override fun exists(location: StorageObjectLocation): Boolean = true
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI("memory://content")
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = unsupported()
        override fun uploadPart(
            upload: MultipartUpload,
            partNumber: Int,
            content: InputStream,
            contentLength: Long,
        ): MultipartPart = unsupported()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = unsupported()
        override fun abortMultipartUpload(upload: MultipartUpload) = Unit

        private fun <T> unsupported(): T = throw UnsupportedOperationException("Test double")
    }

    private object PermissiveFolderReadAccess : DocumentFolderReadAccess {
        override fun requireFolderForDocumentRead(folderId: String) = Unit
        override fun readableFolderIds(): Set<String> = setOf("visible-folder")
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

    private companion object {
        val TENANT_ID = Identifier("tenant-a")
        val USER_ID = Identifier("user-a")
        val DOCUMENT_ID = Identifier("document-a")
        val DOCUMENT = Document(
            id = DOCUMENT_ID,
            tenantId = TENANT_ID,
            assetId = Identifier("asset-a"),
            documentNumber = "DOC-A",
            title = "Hidden document",
            versions = listOf(
                DocumentVersion(
                    id = Identifier("version-a"),
                    tenantId = TENANT_ID,
                    documentId = DOCUMENT_ID,
                    versionNumber = "1.0",
                    fileObjectId = Identifier("file-a"),
                ),
            ),
            currentVersionId = Identifier("version-a"),
        )
    }
}
