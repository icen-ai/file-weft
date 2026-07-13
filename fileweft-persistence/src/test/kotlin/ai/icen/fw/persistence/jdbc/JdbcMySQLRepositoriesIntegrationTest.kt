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
import ai.icen.fw.domain.workflow.WorkflowInstance
import ai.icen.fw.domain.workflow.WorkflowTask
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
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
 * The suite is disabled unless `FILEWEFT_RUN_MYSQL_TESTS=true` and a MySQL 8.0.17+
 * server is reachable via the environment variables below.
 */
class JdbcMySQLRepositoriesIntegrationTest {
    private lateinit var dataSource: DataSource
    private val clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)

    @BeforeEach
    fun prepareDatabase() {
        check(System.getenv("FILEWEFT_RUN_MYSQL_TESTS") == "true") {
            "MySQL integration tests must run only through the fail-closed Gradle task"
        }
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

        val primaryKeyDocument = Document(
            id = Identifier("tenant_id"),
            tenantId = original.tenantId,
            assetId = Identifier("asset-primary-1"),
            documentNumber = "DOC-PRIMARY-1",
            title = "Primary key original",
        )
        transaction.execute { repository.save(primaryKeyDocument) }
        assertFailsWith<SQLException> {
            transaction.execute {
                repository.save(
                    Document(
                        id = primaryKeyDocument.id,
                        // A different tenant bypasses the repository's scoped
                        // UPDATE and exercises the global PRIMARY-key conflict.
                        tenantId = Identifier("tenant-primary-other"),
                        assetId = Identifier("asset-primary-2"),
                        documentNumber = "DOC-PRIMARY-2",
                        title = "Primary key duplicate",
                    ),
                )
            }
        }
    }

    @Test
    fun `keeps tenant spelling and opaque identifier spelling isolated`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcDocumentRepository(clock)
        val tenantDocuments = listOf(
            document("document-tenant-upper", "TenantA", "asset-tenant-upper", "DOC-SHARED", "upper"),
            document("document-tenant-padded", "TenantA ", "asset-tenant-padded", "DOC-SHARED", "padded"),
            document("document-tenant-lower", "tenanta", "asset-tenant-lower", "DOC-SHARED", "lower"),
            document("document-tenant-plain", "TenantCafe", "asset-tenant-plain", "DOC-SHARED", "plain"),
            document("document-tenant-accent", "TenantCafé", "asset-tenant-accent", "DOC-SHARED", "accent"),
        )
        val opaqueIdDocuments = listOf(
            document("Opaque-ID", "tenant-opaque", "asset-opaque-upper", "DOC-OPAQUE-1", "opaque upper"),
            document("opaque-id", "tenant-opaque", "asset-opaque-lower", "DOC-OPAQUE-2", "opaque lower"),
            document("Opaque-ID ", "tenant-opaque", "asset-opaque-padded", "DOC-OPAQUE-3", "opaque padded"),
        )

        transaction.execute {
            (tenantDocuments + opaqueIdDocuments).forEach(repository::save)
        }

        tenantDocuments.forEach { expected ->
            val byId = transaction.execute { repository.findById(expected.tenantId, expected.id) }
            val byNumber = transaction.execute {
                repository.findByDocumentNumber(expected.tenantId, expected.documentNumber)
            }
            assertEquals(expected.id, byId?.id)
            assertEquals(expected.id, byNumber?.id)
            assertEquals(expected.title, byId?.title)
        }
        assertNull(
            transaction.execute {
                repository.findById(Identifier("tenanta"), Identifier("document-tenant-upper"))
            },
        )
        assertNull(
            transaction.execute {
                repository.findById(Identifier("TenantA"), Identifier("document-tenant-padded"))
            },
        )
        assertNull(
            transaction.execute {
                repository.findById(Identifier("TenantCafe"), Identifier("document-tenant-accent"))
            },
        )

        opaqueIdDocuments.forEach { expected ->
            val restored = transaction.execute { repository.findById(expected.tenantId, expected.id) }
            assertEquals(expected.id, restored?.id)
            assertEquals(expected.title, restored?.title)
        }
        assertNull(
            transaction.execute {
                repository.findById(Identifier("tenant-opaque"), Identifier("OPAQUE-ID"))
            },
        )
        assertEquals(8, countRows("fw_document"))
    }

    @Test
    fun `keeps pending workflow uniqueness keys binary through the repository`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val repository = JdbcWorkflowInstanceRepository(clock)
        val pending = listOf(
            workflow("workflow-base", "TenantPending", "DocumentOpaque"),
            workflow("workflow-tenant-case", "tenantpending", "DocumentOpaque"),
            workflow("workflow-document-case", "TenantPending", "documentopaque"),
        )

        transaction.execute { pending.forEach(repository::save) }

        pending.forEach { expected ->
            val restored = transaction.execute {
                repository.findActiveByDocument(expected.tenantId, expected.documentId)
            }
            assertEquals(expected.id, restored?.id)
        }
        val duplicate = assertFailsWith<SQLException> {
            transaction.execute {
                repository.save(workflow("workflow-duplicate", "TenantPending", "DocumentOpaque"))
            }
        }
        assertEquals("23000", duplicate.sqlState)
        assertEquals(1062, duplicate.errorCode)
        assertEquals(3, countRows("fw_workflow_instance"))
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

    private fun document(
        id: String,
        tenantId: String,
        assetId: String,
        documentNumber: String,
        title: String,
    ): Document = Document(
        id = Identifier(id),
        tenantId = Identifier(tenantId),
        assetId = Identifier(assetId),
        documentNumber = documentNumber,
        title = title,
    )

    private fun workflow(id: String, tenantId: String, documentId: String): WorkflowInstance {
        val workflowId = Identifier(id)
        val tenant = Identifier(tenantId)
        return WorkflowInstance(
            id = workflowId,
            tenantId = tenant,
            documentId = Identifier(documentId),
            workflowType = "DOCUMENT_REVIEW",
            tasks = listOf(
                WorkflowTask(
                    id = Identifier("task-$id"),
                    tenantId = tenant,
                    workflowId = workflowId,
                    assigneeId = Identifier("reviewer-$id"),
                ),
            ),
        )
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
