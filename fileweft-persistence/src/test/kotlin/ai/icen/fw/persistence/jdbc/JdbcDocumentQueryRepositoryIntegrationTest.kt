package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.document.DocumentPageRequest
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JdbcDocumentQueryRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareSchema() {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        dataSource = PGSimpleDataSource().apply {
            setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
            user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
            password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        }
        reset(dataSource.connection)
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `maps one tenant document its folder fallback and safe version file metadata in one detail read`() {
        insertDocument(
            id = "document-a", tenantId = "tenant-a", documentNumber = "DOC-A", title = "Contract",
            lifecycleState = LifecycleState.PUBLISHED, currentVersionId = "version-2", createdTime = 10, updatedTime = 100,
        )
        insertFile("file-1", "tenant-a", "first.pdf", "application/pdf", 11, "sha256:first", 20, 21)
        insertFile("file-2", "tenant-a", "final.pdf", "application/pdf", 22, "sha256:final", 30, 31)
        insertVersion("version-1", "tenant-a", "document-a", "1.0", "file-1", 20, 21)
        insertVersion("version-2", "tenant-a", "document-a", "2.0", "file-2", 30, 31)
        val repository = JdbcDocumentQueryRepository()

        val detail = transaction { repository.findDetail(Identifier("tenant-a"), Identifier("document-a")) }
        val leaked = transaction { repository.findDetail(Identifier("tenant-b"), Identifier("document-a")) }

        requireNotNull(detail)
        assertEquals("document-a", detail.document.id.value)
        assertEquals("DOC-A", detail.document.documentNumber)
        assertEquals("Contract", detail.document.title)
        assertEquals(LifecycleState.PUBLISHED, detail.document.lifecycleState)
        assertEquals("version-2", detail.document.currentVersionId?.value)
        assertEquals("inbox", detail.document.folderId)
        assertEquals(10, detail.document.createdTime)
        assertEquals(100, detail.document.updatedTime)
        assertEquals(listOf("version-1", "version-2"), detail.versions.map { it.id.value })
        assertEquals(listOf("first.pdf", "final.pdf"), detail.versions.map { it.fileName })
        assertEquals(listOf(11L, 22L), detail.versions.map { it.contentLength })
        assertNull(leaked)
    }

    @Test
    fun `paginates by updated time and id without tenant leaks and filters state folder safely`() {
        insertDocument("document-z", "tenant-a", "DOC-Z", "Newest Z", LifecycleState.PUBLISHED, null, 10, 500, "finance")
        insertDocument("document-y", "tenant-a", "DOC-Y", "Newest Y", LifecycleState.PUBLISHED, null, 10, 500, "finance")
        insertDocument("document-x", "tenant-a", "DOC-X", "Newest X", LifecycleState.PUBLISHED, null, 10, 500, "inbox")
        insertDocument("document-old", "tenant-a", "DOC-OLD", "Old", LifecycleState.DRAFT, null, 10, 400, "finance")
        insertDocument("document-other-z", "tenant-b", "DOC-B", "Other tenant", LifecycleState.PUBLISHED, null, 10, 999, "finance")
        val repository = JdbcDocumentQueryRepository()

        val first = transaction { repository.findPage(Identifier("tenant-a"), DocumentPageRequest(limit = 2)) }
        val second = transaction {
            repository.findPage(
                Identifier("tenant-a"),
                DocumentPageRequest(cursor = first.nextCursor, limit = 2),
            )
        }
        val financePublished = transaction {
            repository.findPage(
                Identifier("tenant-a"),
                DocumentPageRequest(limit = 10, lifecycleState = LifecycleState.PUBLISHED, folderId = "finance"),
            )
        }
        val injectedFolder = transaction {
            repository.findPage(
                Identifier("tenant-a"),
                DocumentPageRequest(limit = 10, folderId = "finance' OR '1'='1"),
            )
        }

        assertEquals(listOf("document-z", "document-y"), first.items.map { it.id.value })
        assertEquals(500, first.nextCursor?.updatedTime)
        assertEquals("document-y", first.nextCursor?.id?.value)
        assertEquals(listOf("document-x", "document-old"), second.items.map { it.id.value })
        assertNull(second.nextCursor)
        assertEquals(listOf("document-z", "document-y"), financePublished.items.map { it.id.value })
        assertEquals(listOf("finance", "finance"), financePublished.items.map { it.folderId })
        assertEquals(emptyList(), injectedFolder.items)
    }

    @Test
    fun `constrains document details and unfiltered pages to the trusted catalog folder scope`() {
        insertDocument("document-visible", "tenant-a", "DOC-VISIBLE", "Visible", LifecycleState.PUBLISHED, null, 10, 300, "finance")
        insertDocument("document-hidden", "tenant-a", "DOC-HIDDEN", "Hidden", LifecycleState.PUBLISHED, null, 10, 400, "operations")
        insertDocument("document-inbox", "tenant-a", "DOC-INBOX", "Inbox", LifecycleState.PUBLISHED, null, 10, 500, null)
        val repository = JdbcDocumentQueryRepository()
        val scope = DocumentFolderReadScope(listOf("finance"))

        val visible = transaction {
            repository.findDetail(Identifier("tenant-a"), Identifier("document-visible"), scope)
        }
        val hidden = transaction {
            repository.findDetail(Identifier("tenant-a"), Identifier("document-hidden"), scope)
        }
        val inbox = transaction {
            repository.findDetail(Identifier("tenant-a"), Identifier("document-inbox"), scope)
        }
        val page = transaction {
            repository.findPage(Identifier("tenant-a"), DocumentPageRequest(limit = 10), scope)
        }

        assertEquals("document-visible", visible?.document?.id?.value)
        assertNull(hidden)
        assertNull(inbox)
        assertEquals(listOf("document-visible"), page.items.map { it.id.value })
    }

    @Test
    fun `requires the caller bound JDBC transaction`() {
        val repository = JdbcDocumentQueryRepository()

        assertFailsWith<IllegalStateException> {
            repository.findPage(Identifier("tenant-a"), DocumentPageRequest())
        }
        assertFailsWith<IllegalStateException> {
            repository.findDetail(Identifier("tenant-a"), Identifier("document-a"))
        }
    }

    private fun <T> transaction(action: () -> T): T = JdbcApplicationTransaction(dataSource).execute(action)

    private fun insertDocument(
        id: String,
        tenantId: String,
        documentNumber: String,
        title: String,
        lifecycleState: LifecycleState,
        currentVersionId: String?,
        createdTime: Long,
        updatedTime: Long,
        folderId: String? = null,
    ) {
        val assetId = "asset-$tenantId-$id"
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_ASSET_SQL).use { statement ->
                statement.setString(1, assetId)
                statement.setString(2, tenantId)
                statement.setString(3, "asset-file-$tenantId-$id")
                statement.setString(4, folderJson(folderId))
                statement.setLong(5, createdTime)
                statement.setLong(6, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(INSERT_DOCUMENT_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, assetId)
                statement.setString(4, documentNumber)
                statement.setString(5, title)
                statement.setString(6, lifecycleState.name)
                statement.setNullableString(7, currentVersionId)
                statement.setLong(8, createdTime)
                statement.setLong(9, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertFile(
        id: String,
        tenantId: String,
        fileName: String,
        contentType: String?,
        size: Long,
        contentHash: String?,
        createdTime: Long,
        updatedTime: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_FILE_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, fileName)
                statement.setNullableString(4, contentType)
                statement.setLong(5, size)
                statement.setNullableString(6, contentHash)
                statement.setString(7, "local")
                statement.setString(8, "private/$tenantId/$id")
                statement.setLong(9, createdTime)
                statement.setLong(10, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertVersion(
        id: String,
        tenantId: String,
        documentId: String,
        versionNumber: String,
        fileId: String,
        createdTime: Long,
        updatedTime: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_VERSION_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, documentId)
                statement.setString(4, versionNumber)
                statement.setString(5, fileId)
                statement.setLong(6, createdTime)
                statement.setLong(7, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun folderJson(folderId: String?): String = folderId?.let { "{\"catalog.folder-id\":\"$it\"}" } ?: "{}"

    private fun PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private companion object {
        const val INSERT_ASSET_SQL = """
            INSERT INTO fw_asset(id, tenant_id, file_id, asset_type, metadata_json, created_time, updated_time)
            VALUES (?, ?, ?, 'DOCUMENT', ?::jsonb, ?, ?)
        """
        const val INSERT_DOCUMENT_SQL = """
            INSERT INTO fw_document(
                id, tenant_id, asset_id, doc_no, title, lifecycle_state, current_version_id, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_FILE_SQL = """
            INSERT INTO fw_file_object(
                id, tenant_id, file_name, content_type, file_size, content_hash, storage_type, storage_path, status, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
        """
        const val INSERT_VERSION_SQL = """
            INSERT INTO fw_document_version(
                id, tenant_id, document_id, version_no, file_id, status, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
        """
    }
}
