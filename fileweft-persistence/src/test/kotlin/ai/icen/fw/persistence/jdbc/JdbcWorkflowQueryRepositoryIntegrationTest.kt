package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.workflow.DocumentWorkflowPageRequest
import ai.icen.fw.application.workflow.WorkflowTaskPageRequest
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTaskState
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcWorkflowQueryRepositoryIntegrationTest {
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
    fun `pending inbox applies tenant assignment folder and consistency filters before stable keyset paging`() {
        insertDocument("document-visible", "tenant-a", "finance", "PENDING_REVIEW", 10, 50)
        insertWorkflow("workflow-visible", "tenant-a", "document-visible", "PENDING", 100, 110)
        insertTask("task-z", "tenant-a", "workflow-visible", "reviewer-a", "PENDING", 500, 510)
        insertTask("task-y", "tenant-a", "workflow-visible", null, "PENDING", 500, 510)
        insertTask("task-x", "tenant-a", "workflow-visible", "reviewer-other", "PENDING", 500, 510)

        insertDocument("document-hidden", "tenant-a", "operations", "PENDING_REVIEW", 10, 50)
        insertWorkflow("workflow-hidden", "tenant-a", "document-hidden", "PENDING", 100, 110)
        insertTask("task-hidden", "tenant-a", "workflow-hidden", null, "PENDING", 900, 910)

        insertDocument("document-published", "tenant-a", "finance", "PUBLISHED", 10, 50)
        insertWorkflow("workflow-published", "tenant-a", "document-published", "PENDING", 100, 110)
        insertTask("task-published", "tenant-a", "workflow-published", null, "PENDING", 800, 810)

        insertDocument("document-approved", "tenant-a", "finance", "PENDING_REVIEW", 10, 50)
        insertWorkflow("workflow-approved", "tenant-a", "document-approved", "APPROVED", 100, 110)
        insertTask("task-approved-workflow", "tenant-a", "workflow-approved", null, "PENDING", 700, 710)

        insertDocument("document-broken", "tenant-a", "finance", "PENDING_REVIEW", 10, 50)
        insertWorkflow("workflow-broken", "tenant-a", "document-broken", "BROKEN", 100, 110)
        insertTask("task-broken-workflow", "tenant-a", "workflow-broken", null, "PENDING", 700, 710)

        insertDocument("document-corrupt-task", "tenant-a", "finance", "PENDING_REVIEW", 10, 50)
        insertWorkflow("workflow-corrupt-task", "tenant-a", "document-corrupt-task", "PENDING", 100, 110)
        insertTask("task-corrupt-visible", "tenant-a", "workflow-corrupt-task", null, "PENDING", 650, 660)
        insertTask("task-corrupt-sibling", "tenant-a", "workflow-corrupt-task", null, "BROKEN", 640, 650)

        insertDocument("document-bad-time", "tenant-a", "finance", "PENDING_REVIEW", 10, 50)
        insertWorkflow("workflow-bad-time", "tenant-a", "document-bad-time", "PENDING", 100, 110)
        insertTask("task-bad-time", "tenant-a", "workflow-bad-time", null, "PENDING", 600, 599)

        insertDocument("document-other-tenant", "tenant-b", "finance", "PENDING_REVIEW", 10, 50)
        insertWorkflow("workflow-other-tenant", "tenant-b", "document-other-tenant", "PENDING", 100, 110)
        insertTask("task-other-tenant", "tenant-b", "workflow-other-tenant", null, "PENDING", 999, 1_000)

        val repository = JdbcWorkflowQueryRepository()
        val scope = DocumentFolderReadScope(listOf("finance"))

        val first = transaction {
            repository.findPendingTaskPage(
                Identifier("tenant-a"),
                Identifier("reviewer-a"),
                WorkflowTaskPageRequest(limit = 1),
                scope,
            )
        }
        val second = transaction {
            repository.findPendingTaskPage(
                Identifier("tenant-a"),
                Identifier("reviewer-a"),
                WorkflowTaskPageRequest(first.nextCursor, 1),
                scope,
            )
        }

        assertEquals(listOf("task-z"), first.items.map { item -> item.task.id.value })
        assertTrue(first.items.single().task.assignedToCurrentUser)
        assertEquals(500, first.nextCursor?.createdTime)
        assertEquals("task-z", first.nextCursor?.id?.value)
        assertEquals("finance", first.items.single().document.folderId)
        assertEquals(listOf("task-y"), second.items.map { item -> item.task.id.value })
        assertFalse(second.items.single().task.assignedToCurrentUser)
        assertNull(second.nextCursor)
    }

    @Test
    fun `pending inbox treats null scope as unfiltered and empty scope as deny all`() {
        insertDocument("document-finance", "tenant-a", "finance", "PENDING_REVIEW", 10, 50)
        insertWorkflow("workflow-finance", "tenant-a", "document-finance", "PENDING", 100, 110)
        insertTask("task-finance", "tenant-a", "workflow-finance", null, "PENDING", 200, 210)
        insertDocument("document-operations", "tenant-a", "operations", "PENDING_REVIEW", 10, 50)
        insertWorkflow("workflow-operations", "tenant-a", "document-operations", "PENDING", 100, 110)
        insertTask("task-operations", "tenant-a", "workflow-operations", null, "PENDING", 300, 310)
        val repository = JdbcWorkflowQueryRepository()

        val unfiltered = transaction {
            repository.findPendingTaskPage(
                Identifier("tenant-a"),
                Identifier("reviewer-a"),
                WorkflowTaskPageRequest(limit = 10),
                null,
            )
        }
        val denied = transaction {
            repository.findPendingTaskPage(
                Identifier("tenant-a"),
                Identifier("reviewer-a"),
                WorkflowTaskPageRequest(limit = 10),
                DocumentFolderReadScope(emptyList()),
            )
        }

        assertEquals(listOf("task-operations", "task-finance"), unfiltered.items.map { it.task.id.value })
        assertEquals(emptyList(), denied.items)
        assertNull(denied.nextCursor)
    }

    @Test
    fun `document history pages workflows and tasks deterministically while excluding corrupt states`() {
        insertDocument("document-history", "tenant-a", "finance", "PUBLISHED", 10, 900)
        insertWorkflow("workflow-z", "tenant-a", "document-history", "APPROVED", 700, 710)
        insertTask("task-b", "tenant-a", "workflow-z", "reviewer-b", "APPROVED", 705, 706, "secret-b")
        insertTask("task-a", "tenant-a", "workflow-z", null, "APPROVED", 705, 706, "secret-a")
        insertWorkflow("workflow-y", "tenant-a", "document-history", "APPROVED", 700, 710)
        insertTask("task-y", "tenant-a", "workflow-y", null, "APPROVED", 705, 706)
        insertWorkflow("workflow-withdrawn", "tenant-a", "document-history", "WITHDRAWN", 650, 660)
        insertTask("task-withdrawn", "tenant-a", "workflow-withdrawn", "reviewer-a", "PENDING", 655, 656)
        insertWorkflow("workflow-old", "tenant-a", "document-history", "REJECTED", 600, 610)
        insertTask("task-old", "tenant-a", "workflow-old", "reviewer-a", "REJECTED", 605, 606, "private comment")

        insertWorkflow("workflow-broken-state", "tenant-a", "document-history", "BROKEN", 800, 810)
        insertTask("task-broken-state", "tenant-a", "workflow-broken-state", null, "APPROVED", 805, 806)
        insertWorkflow("workflow-broken-task", "tenant-a", "document-history", "APPROVED", 790, 800)
        insertTask("task-broken-task", "tenant-a", "workflow-broken-task", null, "BROKEN", 795, 796)
        insertWorkflow("workflow-inconsistent", "tenant-a", "document-history", "APPROVED", 780, 790)
        insertTask("task-inconsistent", "tenant-a", "workflow-inconsistent", null, "PENDING", 785, 786)
        insertWorkflow("workflow-bad-time", "tenant-a", "document-history", "REJECTED", 770, 760)
        insertTask("task-bad-workflow-time", "tenant-a", "workflow-bad-time", null, "REJECTED", 775, 776)

        val repository = JdbcWorkflowQueryRepository()
        val scope = DocumentFolderReadScope(listOf("finance"))
        val first = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"),
                Identifier("document-history"),
                DocumentWorkflowPageRequest(limit = 1),
                scope,
            )
        }
        val second = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"),
                Identifier("document-history"),
                DocumentWorkflowPageRequest(requireNotNull(first).nextCursor, 1),
                scope,
            )
        }
        val third = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"),
                Identifier("document-history"),
                DocumentWorkflowPageRequest(requireNotNull(second).nextCursor, 1),
                scope,
            )
        }
        val fourth = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"),
                Identifier("document-history"),
                DocumentWorkflowPageRequest(requireNotNull(third).nextCursor, 1),
                scope,
            )
        }
        val firstPage = requireNotNull(first)
        val secondPage = requireNotNull(second)

        assertEquals(listOf("workflow-z"), firstPage.items.map { it.id.value })
        assertEquals(listOf("task-a", "task-b"), firstPage.items.single().tasks.map { it.id.value })
        assertEquals(listOf(WorkflowTaskState.APPROVED, WorkflowTaskState.APPROVED), firstPage.items.single().tasks.map { it.state })
        assertEquals(700, firstPage.nextCursor?.createdTime)
        assertEquals("workflow-z", firstPage.nextCursor?.id?.value)
        assertEquals(listOf("workflow-y"), secondPage.items.map { it.id.value })
        assertEquals(listOf("workflow-withdrawn"), third?.items?.map { it.id.value })
        assertEquals(WorkflowState.WITHDRAWN, third?.items?.single()?.state)
        assertEquals(listOf("task-withdrawn"), third?.items?.single()?.tasks?.map { it.id.value })
        assertEquals(listOf("workflow-old"), fourth?.items?.map { it.id.value })
        assertEquals(WorkflowState.REJECTED, fourth?.items?.single()?.state)
        assertNull(fourth?.nextCursor)
    }

    @Test
    fun `document history sentinel distinguishes hidden documents from visible documents without valid history`() {
        insertDocument("document-empty", "tenant-a", "finance", "DRAFT", 10, 20)
        insertDocument("document-hidden", "tenant-a", "operations", "DRAFT", 10, 20)
        insertDocument("document-corrupt-only", "tenant-a", "finance", "DRAFT", 10, 20)
        insertWorkflow("workflow-corrupt-only", "tenant-a", "document-corrupt-only", "APPROVED", 30, 40)
        insertTask("task-corrupt-only", "tenant-a", "workflow-corrupt-only", null, "PENDING", 31, 32)
        val repository = JdbcWorkflowQueryRepository()
        val finance = DocumentFolderReadScope(listOf("finance"))

        val empty = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"), Identifier("document-empty"), DocumentWorkflowPageRequest(), finance,
            )
        }
        val corruptOnly = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"), Identifier("document-corrupt-only"), DocumentWorkflowPageRequest(), finance,
            )
        }
        val hidden = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"), Identifier("document-hidden"), DocumentWorkflowPageRequest(), finance,
            )
        }
        val denied = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"),
                Identifier("document-empty"),
                DocumentWorkflowPageRequest(),
                DocumentFolderReadScope(emptyList()),
            )
        }
        val crossTenant = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-b"), Identifier("document-empty"), DocumentWorkflowPageRequest(), null,
            )
        }
        val missing = transaction {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"), Identifier("document-missing"), DocumentWorkflowPageRequest(), null,
            )
        }

        assertNotNull(empty)
        assertEquals(emptyList(), empty.items)
        assertNotNull(corruptOnly)
        assertEquals(emptyList(), corruptOnly.items)
        assertNull(hidden)
        assertNull(denied)
        assertNull(crossTenant)
        assertNull(missing)
    }

    @Test
    fun `decision evidence pages immutable actors while preserving legacy unknown tasks and tenant scope`() {
        insertDocument("document-evidence", "tenant-a", "finance", "PUBLISHED", 10, 900)
        insertWorkflow("workflow-new", "tenant-a", "document-evidence", "APPROVED", 700, 710)
        insertTask("task-new-a", "tenant-a", "workflow-new", "reviewer-a", "APPROVED", 705, 706)
        insertTask("task-new-b", "tenant-a", "workflow-new", "reviewer-b", "APPROVED", 705, 707)
        updateDecisionEvidence("task-new-a", "reviewer-a", "审批人甲", 706)
        updateDecisionEvidence("task-new-b", "reviewer-b", "审批人乙", 707)
        insertWorkflow("workflow-legacy", "tenant-a", "document-evidence", "REJECTED", 600, 610)
        insertTask("task-legacy", "tenant-a", "workflow-legacy", "reviewer-old", "REJECTED", 605, 606)

        insertDocument("document-other", "tenant-b", "finance", "PUBLISHED", 10, 900)
        insertWorkflow("workflow-other", "tenant-b", "document-other", "APPROVED", 800, 810)
        insertTask("task-other", "tenant-b", "workflow-other", "reviewer-a", "APPROVED", 805, 806)
        updateDecisionEvidence("task-other", "reviewer-a", "其他租户审批人", 806)

        val repository = JdbcWorkflowQueryRepository()
        val first = transaction {
            repository.findDocumentWorkflowDecisionEvidencePage(
                Identifier("tenant-a"),
                Identifier("document-evidence"),
                DocumentWorkflowPageRequest(limit = 1),
                DocumentFolderReadScope(listOf("finance")),
            )
        }
        val firstPage = requireNotNull(first)
        val second = transaction {
            repository.findDocumentWorkflowDecisionEvidencePage(
                Identifier("tenant-a"),
                Identifier("document-evidence"),
                DocumentWorkflowPageRequest(requireNotNull(firstPage.nextCursor), 1),
                DocumentFolderReadScope(listOf("finance")),
            )
        }

        assertEquals(Identifier("document-evidence"), firstPage.documentId)
        assertEquals(listOf("workflow-new"), firstPage.items.map { workflow -> workflow.id.value })
        assertEquals(
            listOf("reviewer-a", "reviewer-b"),
            firstPage.items.single().tasks.map { task -> task.decisionOperatorId?.value },
        )
        assertEquals(
            listOf("审批人甲", "审批人乙"),
            firstPage.items.single().tasks.map { task -> task.decisionOperatorName },
        )
        assertEquals(listOf(706L, 707L), firstPage.items.single().tasks.map { task -> task.decidedTime })
        val legacyTask = requireNotNull(second).items.single().tasks.single()
        assertEquals("task-legacy", legacyTask.id.value)
        assertNull(legacyTask.decisionOperatorId)
        assertNull(legacyTask.decisionOperatorName)
        assertNull(legacyTask.decidedTime)
        assertNull(second.nextCursor)

        val hidden = transaction {
            repository.findDocumentWorkflowDecisionEvidencePage(
                Identifier("tenant-a"), Identifier("document-evidence"), DocumentWorkflowPageRequest(),
                DocumentFolderReadScope(listOf("operations")),
            )
        }
        val crossTenant = transaction {
            repository.findDocumentWorkflowDecisionEvidencePage(
                Identifier("tenant-b"), Identifier("document-evidence"), DocumentWorkflowPageRequest(), null,
            )
        }
        assertNull(hidden)
        assertNull(crossTenant)
    }

    @Test
    fun `requires the caller bound JDBC transaction`() {
        val repository = JdbcWorkflowQueryRepository()

        assertFailsWith<IllegalStateException> {
            repository.findPendingTaskPage(
                Identifier("tenant-a"), Identifier("reviewer-a"), WorkflowTaskPageRequest(), null,
            )
        }
        assertFailsWith<IllegalStateException> {
            repository.findDocumentWorkflowPage(
                Identifier("tenant-a"), Identifier("document-a"), DocumentWorkflowPageRequest(), null,
            )
        }
        assertFailsWith<IllegalStateException> {
            repository.findDocumentWorkflowDecisionEvidencePage(
                Identifier("tenant-a"), Identifier("document-a"), DocumentWorkflowPageRequest(), null,
            )
        }
    }

    @Test
    fun `V022 provides a valid assignee leading partial index for large tenant inboxes`() {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INBOX_ASSIGNEE_INDEX_SQL).use { statement ->
                statement.setString(1, INBOX_ASSIGNEE_INDEX_NAME)
                statement.executeQuery().use { result ->
                    assertTrue(result.next(), "V022 assignee-leading workflow inbox index is missing.")
                    val definition = result.getString("index_definition")
                        .lowercase()
                        .replace(Regex("\\s+"), " ")
                    assertTrue(result.getBoolean("is_valid"))
                    assertTrue(result.getBoolean("is_ready"))
                    assertTrue(
                        definition.contains("(tenant_id, assignee_id, created_time desc, id desc)"),
                        definition,
                    )
                    assertTrue(definition.contains("include (workflow_id, updated_time)"), definition)
                    assertTrue(definition.contains("where"), definition)
                    assertTrue(definition.contains("task_state"), definition)
                    assertTrue(definition.contains("'pending'"), definition)
                    assertFalse(result.next(), "Workflow inbox index name must resolve to exactly one index.")
                }
            }
        }
    }

    private fun <T> transaction(action: () -> T): T = JdbcApplicationTransaction(dataSource).execute(action)

    private fun insertDocument(
        id: String,
        tenantId: String,
        folderId: String?,
        lifecycleState: String,
        createdTime: Long,
        updatedTime: Long,
    ) {
        val assetId = "asset-$id"
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_ASSET_SQL).use { statement ->
                statement.setString(1, assetId)
                statement.setString(2, tenantId)
                statement.setString(3, "file-$id")
                statement.setString(4, folderId?.let { "{\"catalog.folder-id\":\"$it\"}" } ?: "{}")
                statement.setLong(5, createdTime)
                statement.setLong(6, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
            connection.prepareStatement(INSERT_DOCUMENT_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, assetId)
                statement.setString(4, "DOC-$id")
                statement.setString(5, "Title $id")
                statement.setString(6, lifecycleState)
                statement.setLong(7, createdTime)
                statement.setLong(8, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertWorkflow(
        id: String,
        tenantId: String,
        documentId: String,
        state: String,
        createdTime: Long,
        updatedTime: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_WORKFLOW_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, documentId)
                statement.setString(4, "DOCUMENT_REVIEW")
                statement.setString(5, state)
                statement.setLong(6, createdTime)
                statement.setLong(7, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertTask(
        id: String,
        tenantId: String,
        workflowId: String,
        assigneeId: String?,
        state: String,
        createdTime: Long,
        updatedTime: Long,
        comment: String? = null,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT_TASK_SQL).use { statement ->
                statement.setString(1, id)
                statement.setString(2, tenantId)
                statement.setString(3, workflowId)
                statement.setNullableString(4, assigneeId)
                statement.setString(5, state)
                statement.setNullableString(6, comment)
                statement.setLong(7, createdTime)
                statement.setLong(8, updatedTime)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun updateDecisionEvidence(
        taskId: String,
        operatorId: String,
        operatorName: String,
        decidedTime: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE fw_workflow_task SET decision_operator_id = ?, decision_operator_name = ?, decided_time = ? WHERE id = ?",
            ).use { statement ->
                statement.setString(1, operatorId)
                statement.setString(2, operatorName)
                statement.setLong(3, decidedTime)
                statement.setString(4, taskId)
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
        const val INBOX_ASSIGNEE_INDEX_NAME = "idx_fw_workflow_task_tenant_assignee_pending_inbox"
        const val INBOX_ASSIGNEE_INDEX_SQL = """
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
            VALUES (?, ?, ?, 'DOCUMENT', ?::jsonb, ?, ?)
        """
        const val INSERT_DOCUMENT_SQL = """
            INSERT INTO fw_document(
                id, tenant_id, asset_id, doc_no, title, lifecycle_state, current_version_id, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
        """
        const val INSERT_WORKFLOW_SQL = """
            INSERT INTO fw_workflow_instance(
                id, tenant_id, document_id, workflow_type, state, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """
        const val INSERT_TASK_SQL = """
            INSERT INTO fw_workflow_task(
                id, tenant_id, workflow_id, assignee_id, task_state, comment_text, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """
    }
}
