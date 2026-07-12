package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.audit.DocumentAuditLogPageRequest
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcDocumentAuditLogQueryRepositoryIntegrationTest {
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
    fun `pages the current tenant audit projection and enriches each audit with at most one exact operation trace`() {
        insertDocument("document-a", "tenant-a", "finance")
        insertDocument("document-b", "tenant-b", "finance")
        insertAudit("audit-z", "tenant-a", "document-a", "document:create", 500, "editor-z", "Editor Z")
        insertOperation("audit-z", "tenant-a", "document-a", "document:create", 500, "trace-z")
        insertAudit("audit-y", "tenant-a", "document-a", "document:rename", 500, "editor-y", "编辑者 Y")
        insertAudit("audit-x", "tenant-a", "document-a", "document:publish", 400, null, null)
        insertOperation("audit-x", "tenant-a", "document-a", "document:publish", 400, "trace-x")
        insertOperation("operation-only", "tenant-a", "document-a", "document:download", 900, "trace-only")
        insertAudit("audit-other", "tenant-b", "document-b", "document:create", 999, "other", "Other")

        val repository = JdbcDocumentAuditLogQueryRepository()
        val scope = DocumentFolderReadScope(listOf("finance"))
        val first = transaction {
            repository.findPage(
                Identifier("tenant-a"),
                Identifier("document-a"),
                DocumentAuditLogPageRequest(limit = 1),
                scope,
            )
        }
        val second = transaction {
            repository.findPage(
                Identifier("tenant-a"),
                Identifier("document-a"),
                DocumentAuditLogPageRequest(requireNotNull(first).nextCursor, 1),
                scope,
            )
        }
        val third = transaction {
            repository.findPage(
                Identifier("tenant-a"),
                Identifier("document-a"),
                DocumentAuditLogPageRequest(requireNotNull(second).nextCursor, 1),
                scope,
            )
        }

        assertEquals(listOf("audit-z"), first?.items?.map { it.id.value })
        assertEquals("trace-z", first?.items?.single()?.traceId?.value)
        assertEquals("editor-z", first?.items?.single()?.operatorId?.value)
        assertEquals("Editor Z", first?.items?.single()?.operatorName)
        assertEquals(500, first?.nextCursor?.createdTime)
        assertEquals("audit-z", first?.nextCursor?.id?.value)

        assertEquals(listOf("audit-y"), second?.items?.map { it.id.value })
        assertNull(second?.items?.single()?.traceId)
        assertEquals("编辑者 Y", second?.items?.single()?.operatorName)
        assertEquals("audit-y", second?.nextCursor?.id?.value)

        assertEquals(listOf("audit-x"), third?.items?.map { it.id.value })
        assertEquals("trace-x", third?.items?.single()?.traceId?.value)
        assertNull(third?.items?.single()?.operatorId)
        assertNull(third?.nextCursor)
        assertTrue((first?.items.orEmpty() + second?.items.orEmpty() + third?.items.orEmpty()).none {
            it.id.value == "operation-only" || it.id.value == "audit-other"
        })
    }

    @Test
    fun `keeps keyset continuation stable across concurrent appends and admits only rows older than its boundary`() {
        insertDocument("document-a", "tenant-a", "finance")
        insertAudit("z-initial", "tenant-a", "document-a", "document:create", 500, null, null)
        insertAudit("m-initial", "tenant-a", "document-a", "document:rename", 400, null, null)
        insertAudit("a-initial", "tenant-a", "document-a", "document:publish", 300, null, null)
        val repository = JdbcDocumentAuditLogQueryRepository()

        val first = requireNotNull(transaction {
            repository.findPage(
                Identifier("tenant-a"),
                Identifier("document-a"),
                DocumentAuditLogPageRequest(limit = 1),
                null,
            )
        })
        assertEquals(listOf("z-initial"), first.items.map { it.id.value })

        insertAudit("newer-append", "tenant-a", "document-a", "document:newer", 600, null, null)
        insertAudit("zz-same-time", "tenant-a", "document-a", "document:same-newer", 500, null, null)
        insertAudit("a-same-time", "tenant-a", "document-a", "document:same-older", 500, null, null)
        insertAudit("older-backfill", "tenant-a", "document-a", "document:backfill", 200, null, null)

        val continuedIds = ArrayList<String>()
        var cursor = first.nextCursor
        while (cursor != null) {
            val page = requireNotNull(transaction {
                repository.findPage(
                    Identifier("tenant-a"),
                    Identifier("document-a"),
                    DocumentAuditLogPageRequest(cursor, 1),
                    null,
                )
            })
            continuedIds += page.items.map { it.id.value }
            cursor = page.nextCursor
        }

        val allContinuationIds = first.items.map { it.id.value } + continuedIds
        assertEquals(
            listOf("z-initial", "a-same-time", "m-initial", "a-initial", "older-backfill"),
            allContinuationIds,
        )
        assertEquals(allContinuationIds.size, allContinuationIds.toSet().size)
        assertTrue(listOf("z-initial", "m-initial", "a-initial").all { id -> allContinuationIds.count { it == id } == 1 })
        assertFalse("newer-append" in allContinuationIds)
        assertFalse("zz-same-time" in allContinuationIds)

        val restarted = requireNotNull(transaction {
            repository.findPage(
                Identifier("tenant-a"),
                Identifier("document-a"),
                DocumentAuditLogPageRequest(limit = 10),
                null,
            )
        })
        assertEquals("newer-append", restarted.items.first().id.value)
        assertTrue(restarted.items.map { it.id.value }.contains("zz-same-time"))
    }

    @Test
    fun `does not attach a same-id operation from another tenant or resource`() {
        insertDocument("document-a", "tenant-a", "finance")
        insertAudit("audit-wrong-tenant", "tenant-a", "document-a", "document:create", 300, null, null)
        insertOperation("audit-wrong-tenant", "tenant-b", "document-a", "document:create", 300, "trace-wrong-tenant")
        insertAudit("audit-wrong-resource", "tenant-a", "document-a", "document:rename", 200, null, null)
        insertOperation("audit-wrong-resource", "tenant-a", "document-other", "document:rename", 200, "trace-wrong-resource")

        val result = transaction {
            JdbcDocumentAuditLogQueryRepository().findPage(
                Identifier("tenant-a"),
                Identifier("document-a"),
                DocumentAuditLogPageRequest(limit = 10),
                null,
            )
        }

        assertEquals(listOf("audit-wrong-tenant", "audit-wrong-resource"), result?.items?.map { it.id.value })
        assertTrue(result?.items.orEmpty().all { it.traceId == null })
    }

    @Test
    fun `uses one statement sentinel to distinguish visible empty history from hidden missing and cross-tenant documents`() {
        insertDocument("document-visible", "tenant-a", "finance")
        insertDocument("document-hidden", "tenant-a", "operations")
        val repository = JdbcDocumentAuditLogQueryRepository()
        val finance = DocumentFolderReadScope(listOf("finance"))

        val visible = transaction {
            repository.findPage(
                Identifier("tenant-a"), Identifier("document-visible"), DocumentAuditLogPageRequest(), finance,
            )
        }
        val hidden = transaction {
            repository.findPage(
                Identifier("tenant-a"), Identifier("document-hidden"), DocumentAuditLogPageRequest(), finance,
            )
        }
        val denyAll = transaction {
            repository.findPage(
                Identifier("tenant-a"),
                Identifier("document-visible"),
                DocumentAuditLogPageRequest(),
                DocumentFolderReadScope(emptyList()),
            )
        }
        val crossTenant = transaction {
            repository.findPage(
                Identifier("tenant-b"), Identifier("document-visible"), DocumentAuditLogPageRequest(), null,
            )
        }
        val missing = transaction {
            repository.findPage(
                Identifier("tenant-a"), Identifier("document-missing"), DocumentAuditLogPageRequest(), null,
            )
        }

        assertNotNull(visible)
        assertTrue(visible.items.isEmpty())
        assertNull(visible.nextCursor)
        assertNull(hidden)
        assertNull(denyAll)
        assertNull(crossTenant)
        assertNull(missing)
    }

    @Test
    fun `migration installs a ready keyset index and repository requires a bound transaction`() {
        val index = dataSource.connection.use { connection ->
            connection.prepareStatement(INDEX_SQL).use { statement ->
                statement.setString(1, INDEX_NAME)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    Triple(result.getString("index_definition"), result.getBoolean("is_valid"), result.getBoolean("is_ready"))
                }
            }
        }

        assertTrue(index.first.contains("tenant_id, resource_type, resource_id, created_time DESC, id DESC"))
        assertTrue(index.second)
        assertTrue(index.third)
        assertThrows<IllegalStateException> {
            JdbcDocumentAuditLogQueryRepository().findPage(
                Identifier("tenant-a"), Identifier("document-a"), DocumentAuditLogPageRequest(), null,
            )
        }
    }

    private fun <T> transaction(action: () -> T): T = JdbcApplicationTransaction(dataSource).execute(action)

    private fun insertDocument(id: String, tenantId: String, folderId: String) {
        val assetId = "asset-$id"
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_ASSET_SQL).use { statement ->
                statement.setString(1, assetId)
                statement.setString(2, tenantId)
                statement.setString(3, "file-$id")
                statement.setString(4, "{\"catalog.folder-id\":\"$folderId\"}")
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(INSERT_DOCUMENT_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, assetId)
                statement.setString(4, "DOC-$id")
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertAudit(
        id: String,
        tenantId: String,
        documentId: String,
        action: String,
        createdTime: Long,
        operatorId: String?,
        operatorName: String?,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_AUDIT_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, documentId)
                statement.setString(4, action)
                statement.setNullableString(5, operatorId)
                statement.setNullableString(6, operatorName)
                statement.setLong(7, createdTime)
                statement.setLong(8, createdTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertOperation(
        id: String,
        tenantId: String,
        documentId: String,
        action: String,
        createdTime: Long,
        traceId: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_OPERATION_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, documentId)
                statement.setString(4, action)
                statement.setString(5, traceId)
                statement.setLong(6, createdTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

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
        const val INDEX_NAME = "idx_fw_audit_tenant_resource_time_id"
        const val INDEX_SQL = """
            SELECT pg_get_indexdef(index_row.indexrelid) AS index_definition,
                   index_row.indisvalid AS is_valid,
                   index_row.indisready AS is_ready
            FROM pg_index index_row
            JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
            JOIN pg_namespace index_namespace ON index_namespace.oid = index_class.relnamespace
            WHERE index_namespace.nspname = current_schema()
              AND index_class.relname = ?
        """
        const val INSERT_ASSET_SQL = """
            INSERT INTO fw_asset(id, tenant_id, file_id, asset_type, metadata_json, created_time, updated_time)
            VALUES (?, ?, ?, 'DOCUMENT', ?::jsonb, 1, 1)
        """
        const val INSERT_DOCUMENT_SQL = """
            INSERT INTO fw_document(
                id, tenant_id, asset_id, doc_no, title, lifecycle_state, current_version_id, created_time, updated_time
            ) VALUES (?, ?, ?, ?, 'Title', 'DRAFT', NULL, 1, 1)
        """
        const val INSERT_AUDIT_SQL = """
            INSERT INTO fw_audit_record(
                id, tenant_id, resource_type, resource_id, action, operator_id, operator_name,
                detail_json, created_time, updated_time
            ) VALUES (?, ?, 'DOCUMENT', ?, ?, ?, ?, '{"secret":"must-not-project"}'::jsonb, ?, ?)
        """
        const val INSERT_OPERATION_SQL = """
            INSERT INTO fw_operation_log(
                id, tenant_id, resource_type, resource_id, action, operator_id, operator_name,
                trace_id, detail_json, created_time
            ) VALUES (?, ?, 'DOCUMENT', ?, ?, NULL, NULL, ?, '{"secret":"must-not-project"}'::jsonb, ?)
        """
    }
}
