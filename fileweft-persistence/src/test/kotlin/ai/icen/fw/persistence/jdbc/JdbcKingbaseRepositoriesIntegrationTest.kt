package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentPageRequest
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentNumberAlreadyExistsException
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleCommand
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KingbaseES real-database integration tests for the JDBC repository behavior
 * that cannot be established by migration validation alone.
 */
class JdbcKingbaseRepositoriesIntegrationTest {
    private lateinit var dataSource: DataSource
    private lateinit var connectionSettings: ConnectionSettings
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)

    @BeforeEach
    fun prepareDatabase() {
        check(System.getenv("FILEWEFT_RUN_KINGBASE_TESTS") == "true") {
            "Kingbase integration tests must run only through the fail-closed Gradle task"
        }
        Class.forName(System.getenv("FILEWEFT_KINGBASE_DRIVER") ?: "com.kingbase8.Driver")
        connectionSettings = ConnectionSettings(
            url = System.getenv("FILEWEFT_KINGBASE_URL") ?: "jdbc:kingbase8://localhost:54321/fileweft",
            user = System.getenv("FILEWEFT_KINGBASE_USER") ?: "system",
            password = System.getenv("FILEWEFT_KINGBASE_PASSWORD") ?: "kingbase",
            schema = System.getenv("FILEWEFT_KINGBASE_SCHEMA") ?: "public",
        )
        require(connectionSettings.schema.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            "FILEWEFT_KINGBASE_SCHEMA must be an unquoted SQL identifier"
        }

        connectionSettings.rawConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS ${connectionSettings.schema} CASCADE")
                statement.execute("CREATE SCHEMA ${connectionSettings.schema}")
            }
        }
        dataSource = DriverManagerDataSource(connectionSettings)
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun cleanDatabase() {
        if (::connectionSettings.isInitialized) {
            connectionSettings.rawConnection().use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS ${connectionSettings.schema} CASCADE")
                }
            }
        }
    }

    @Test
    fun `reconstructs a document only for its tenant and preserves versions`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcDocumentRepository(clock)
        val document = document()

        transaction.execute { repository.save(document) }
        val restored = transaction.execute { repository.findById(Identifier("tenant-1"), document.id) }
        val leaked = transaction.execute { repository.findById(Identifier("tenant-2"), document.id) }

        requireNotNull(restored)
        assertEquals(document.id, restored.id)
        assertEquals(LifecycleState.PENDING_REVIEW, restored.lifecycleState)
        assertEquals(document.currentVersionId, restored.currentVersionId)
        assertEquals(1, restored.versions.size)
        assertNull(leaked)

        restored.transition(LifecycleCommand.APPROVE)
        transaction.execute { repository.save(restored) }
        val updated = transaction.execute { repository.findById(Identifier("tenant-1"), document.id) }
        requireNotNull(updated)
        assertEquals(LifecycleState.PUBLISHING, updated.lifecycleState)
        assertEquals(1, updated.deliveryGeneration)
    }

    @Test
    fun `finds documents by tenant scoped number and translates duplicate insert conflicts`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcDocumentRepository(clock)
        val original = document()
        transaction.execute { repository.save(original) }

        val found = transaction.execute { repository.findByDocumentNumber(original.tenantId, original.documentNumber) }
        val leaked = transaction.execute { repository.findByDocumentNumber(Identifier("tenant-2"), original.documentNumber) }

        assertEquals(original.id, found?.id)
        assertNull(leaked)
        assertFailsWith<DocumentNumberAlreadyExistsException> {
            transaction.execute {
                repository.save(
                    Document(
                        id = Identifier("document-2"),
                        tenantId = original.tenantId,
                        assetId = Identifier("asset-2"),
                        documentNumber = original.documentNumber,
                        title = "Duplicate",
                    ),
                )
            }
        }
    }

    @Test
    fun `persists outbox payload as json in the active transaction`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcOutboxEventRepository(ObjectMapper())
        transaction.execute {
            repository.append(
                OutboxEvent(
                    id = Identifier("event-1"),
                    tenantId = Identifier("tenant-1"),
                    type = "document.publish.requested",
                    payload = mapOf("documentId" to "document-1"),
                    timestamp = 100,
                ),
            )
        }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT payload_json ->> 'documentId' FROM fw_outbox_event WHERE tenant_id = 'tenant-1'",
                ).use { result ->
                    result.next()
                    assertEquals("document-1", result.getString(1))
                }
            }
        }
    }

    @Test
    fun `persists file metadata and prevents cross tenant asset lookup`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val fileObjects = JdbcFileObjectRepository(clock)
        val assets = JdbcFileAssetRepository(ObjectMapper(), clock)
        val fileObject = FileObject(
            Identifier("file-1"), Identifier("tenant-1"), "contract.pdf", 7,
            "local", "tenant-1/contract.pdf", "application/pdf", "hash-1",
        )
        val asset = FileAsset(
            Identifier("asset-1"), Identifier("tenant-1"), fileObject.id, "DOCUMENT", mapOf("source" to "upload"),
        )

        transaction.execute {
            fileObjects.save(fileObject)
            assets.save(asset)
        }
        val restored = transaction.execute { assets.findById(Identifier("tenant-1"), asset.id) }
        val leaked = transaction.execute { assets.findById(Identifier("tenant-2"), asset.id) }

        requireNotNull(restored)
        assertEquals("upload", restored.metadata["source"])
        assertEquals(fileObject.id, restored.fileObjectId)
        assertNull(leaked)

        val updatedAsset = FileAsset(
            asset.id, asset.tenantId, asset.fileObjectId, asset.assetType, mapOf("source" to "reviewed"),
        )
        transaction.execute { assets.save(updatedAsset) }
        val updated = transaction.execute { assets.findById(Identifier("tenant-1"), asset.id) }
        requireNotNull(updated)
        assertEquals("reviewed", updated.metadata["source"])
        assertEquals(1, countRows("fw_asset"))
    }

    @Test
    fun `queries documents by folder visibility using a Kingbase text array`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val fileObjects = JdbcFileObjectRepository(clock)
        val assets = JdbcFileAssetRepository(ObjectMapper(), clock)
        val documents = JdbcDocumentRepository(clock)
        val queries: DocumentQueryRepository = JdbcDocumentQueryRepository()

        val fileObject = FileObject(
            Identifier("file-1"), Identifier("tenant-1"), "contract.pdf", 7,
            "local", "tenant-1/contract.pdf", "application/pdf", "hash-1",
        )
        val asset = FileAsset(
            Identifier("asset-1"), Identifier("tenant-1"), fileObject.id, "DOCUMENT",
            mapOf("catalog.folder-id" to "contracts"),
        )
        val document = Document(
            id = Identifier("document-1"),
            tenantId = Identifier("tenant-1"),
            assetId = asset.id,
            documentNumber = "DOC-001",
            title = "Contract",
        ).also {
            it.addVersion(DocumentVersion(Identifier("version-1"), it.tenantId, it.id, "1.0", fileObject.id))
            it.transition(LifecycleCommand.SUBMIT)
        }

        transaction.execute {
            fileObjects.save(fileObject)
            assets.save(asset)
            documents.save(document)
        }

        val inScope = transaction.execute {
            queries.findPage(
                Identifier("tenant-1"),
                DocumentPageRequest(limit = 10),
                DocumentFolderReadScope(listOf("contracts")),
            )
        }
        val outOfScope = transaction.execute {
            queries.findPage(
                Identifier("tenant-1"),
                DocumentPageRequest(limit = 10),
                DocumentFolderReadScope(listOf("finance")),
            )
        }

        assertEquals(1, inScope.items.size)
        assertEquals(document.id, inScope.items.single().id)
        assertTrue(outOfScope.items.isEmpty())
    }

    private fun document(): Document {
        val document = Document(
            id = Identifier("document-1"),
            tenantId = Identifier("tenant-1"),
            assetId = Identifier("asset-1"),
            documentNumber = "DOC-001",
            title = "Contract",
        )
        document.addVersion(
            DocumentVersion(Identifier("version-1"), document.tenantId, document.id, "1.0", Identifier("file-1")),
        )
        document.transition(LifecycleCommand.SUBMIT)
        return document
    }

    private fun countRows(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private data class ConnectionSettings(
        val url: String,
        val user: String,
        val password: String,
        val schema: String,
    ) {
        fun rawConnection(): Connection = DriverManager.getConnection(url, user, password)
    }

    private class DriverManagerDataSource(
        private val settings: ConnectionSettings,
    ) : DataSource {
        override fun getConnection(): Connection = configure(settings.rawConnection())

        override fun getConnection(username: String, password: String): Connection =
            configure(DriverManager.getConnection(settings.url, username, password))

        private fun configure(connection: Connection): Connection = connection.apply {
            createStatement().use { statement -> statement.execute("SET search_path TO ${settings.schema}") }
        }

        override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()

        override fun setLogWriter(out: PrintWriter?) = DriverManager.setLogWriter(out)

        override fun setLoginTimeout(seconds: Int) = DriverManager.setLoginTimeout(seconds)

        override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()

        override fun getParentLogger(): Logger = Logger.getLogger("ai.icen.fw.persistence.jdbc.kingbase")

        override fun <T : Any> unwrap(iface: Class<T>): T {
            if (iface.isInstance(this)) return iface.cast(this)
            throw java.sql.SQLException("Not a wrapper for ${iface.name}")
        }

        override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
    }
}
