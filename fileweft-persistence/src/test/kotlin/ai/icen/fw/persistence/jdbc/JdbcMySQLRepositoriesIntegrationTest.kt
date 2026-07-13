package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.mysql.cj.jdbc.MysqlDataSource
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MySQL 8 real-database integration test for the dialect-migrated JDBC repositories.
 *
 * The suite is disabled unless `FILEWEFT_RUN_MYSQL_TESTS=true` and a MySQL 8.0.13+
 * server is reachable via the environment variables below.
 */
class JdbcMySQLRepositoriesIntegrationTest {
    private lateinit var dataSource: DataSource
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)

    @BeforeEach
    fun prepareDatabase() {
        assumeTrue(System.getenv("FILEWEFT_RUN_MYSQL_TESTS") == "true")
        val databaseName = System.getenv("FILEWEFT_MYSQL_DATABASE") ?: "fileweft"
        val adminDataSource = MysqlDataSource().apply {
            setURL(System.getenv("FILEWEFT_MYSQL_ADMIN_URL") ?: "jdbc:mysql://localhost:3306")
            user = System.getenv("FILEWEFT_MYSQL_ADMIN_USER") ?: "root"
            password = System.getenv("FILEWEFT_MYSQL_ADMIN_PASSWORD") ?: ""
        }
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                statement.execute("CREATE DATABASE `$databaseName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci")
            }
        }
        dataSource = MysqlDataSource().apply {
            setURL(System.getenv("FILEWEFT_MYSQL_URL") ?: "jdbc:mysql://localhost:3306/$databaseName")
            user = System.getenv("FILEWEFT_MYSQL_USER") ?: "root"
            password = System.getenv("FILEWEFT_MYSQL_PASSWORD") ?: ""
        }
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun cleanDatabase() {
        if (::dataSource.isInitialized) {
            val databaseName = System.getenv("FILEWEFT_MYSQL_DATABASE") ?: "fileweft"
            val adminDataSource = MysqlDataSource().apply {
                setURL(System.getenv("FILEWEFT_MYSQL_ADMIN_URL") ?: "jdbc:mysql://localhost:3306")
                user = System.getenv("FILEWEFT_MYSQL_ADMIN_USER") ?: "root"
                password = System.getenv("FILEWEFT_MYSQL_ADMIN_PASSWORD") ?: ""
            }
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
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
                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json, '\$.documentId')) FROM fw_outbox_event WHERE tenant_id = 'tenant-1'",
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
    fun `queries documents by folder visibility using json array containment`() {
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
}
