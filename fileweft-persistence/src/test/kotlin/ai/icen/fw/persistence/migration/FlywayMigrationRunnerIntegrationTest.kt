package ai.icen.fw.persistence.migration

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.outbox.OutboxLeaseClaim
import ai.icen.fw.application.task.TaskLeaseClaim
import ai.icen.fw.persistence.jdbc.JdbcApplicationTransaction
import ai.icen.fw.persistence.jdbc.JdbcOutboxProcessingRepository
import ai.icen.fw.persistence.jdbc.JdbcTaskRepository
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlywayMigrationRunnerIntegrationTest {
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun prepareSchema() {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        dataSource = PGSimpleDataSource().apply {
            setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
            user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
            password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA public CASCADE")
                statement.execute("CREATE SCHEMA public")
            }
        }
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA public CASCADE")
                    statement.execute("CREATE SCHEMA public")
                }
            }
        }
    }

    @Test
    fun `applies schema migrations for durable runtime recovery idempotency and workflow queries`() {
        val migrations = FlywayMigrationRunner(dataSource).migrate()

        assertEquals(33, migrations)
        dataSource.connection.use { connection ->
            assertTrue(tableExists(connection, "fw_file_object"))
            assertTrue(tableExists(connection, "fw_asset"))
            assertTrue(tableExists(connection, "fw_document"))
            assertTrue(tableExists(connection, "fw_document_version"))
            assertTrue(tableExists(connection, "fw_outbox_event"))
            assertTrue(tableExists(connection, "fw_sync_record"))
            assertTrue(tableExists(connection, "fw_audit_record"))
            assertTrue(tableExists(connection, "fw_workflow_instance"))
            assertTrue(tableExists(connection, "fw_workflow_task"))
            assertTrue(tableExists(connection, "fw_task"))
            assertTrue(tableExists(connection, "fw_doctor_record"))
            assertTrue(tableExists(connection, "fw_operation_log"))
            assertTrue(tableExists(connection, "fw_agent_result"))
            assertTrue(tableExists(connection, "fw_agent_suggestion_confirmation"))
            assertTrue(tableExists(connection, "fw_upload_session"))
            assertTrue(tableExists(connection, "fw_upload_session_part"))
            assertTrue(tableExists(connection, "fw_presigned_upload_session"))
            assertTrue(tableExists(connection, "fw_idempotency_record"))
            assertTrue(tableExists(connection, "fw_secure_deletion_plan"))
            assertTrue(tableExists(connection, "fw_secure_deletion_tombstone"))
            assertTrue(tableExists(connection, "fw_secure_deletion_audit"))
            assertTrue(tableExists(connection, "fw_secure_deletion_receipt"))
            assertTrue(columnExists(connection, "fw_outbox_event", "next_attempt_time"))
            assertTrue(columnExists(connection, "fw_outbox_event", "last_error"))
            assertTrue(columnExists(connection, "fw_audit_record", "operator_name"))
            assertTrue(columnExists(connection, "fw_task", "lease_expire_time"))
            assertTrue(columnExists(connection, "fw_task", "lease_token"))
            assertTrue(columnExists(connection, "fw_operation_log", "trace_id"))
            assertTrue(columnExists(connection, "fw_outbox_event", "trace_id"))
            assertTrue(columnExists(connection, "fw_outbox_event", "lease_owner"))
            assertTrue(columnExists(connection, "fw_outbox_event", "lease_token"))
            assertTrue(columnExists(connection, "fw_outbox_event", "lease_expire_time"))
            assertTrue(columnExists(connection, "fw_agent_result", "result_json"))
            assertTrue(columnExists(connection, "fw_upload_session", "storage_upload_id"))
            assertTrue(columnExists(connection, "fw_upload_session", "owner_id"))
            assertTrue(columnExists(connection, "fw_upload_session", "claimed_idempotency_key_digest"))
            assertTrue(columnExists(connection, "fw_upload_session", "claimed_resource_type"))
            assertTrue(columnExists(connection, "fw_upload_session", "claimed_resource_id"))
            assertTrue(columnExists(connection, "fw_upload_session", "claimed_subresource_id"))
            assertTrue(columnExists(connection, "fw_upload_session", "claimed_by"))
            assertTrue(columnExists(connection, "fw_upload_session", "claimed_time"))
            assertTrue(columnExists(connection, "fw_presigned_upload_session", "claim_token"))
            assertTrue(columnExists(connection, "fw_presigned_upload_session", "cleanup_time"))
            assertTrue(columnExists(connection, "fw_presigned_upload_session", "asset_file_object_id"))
            assertTrue(columnExists(connection, "fw_presigned_upload_session", "asset_claim_key_digest"))
            assertTrue(columnExists(connection, "fw_workflow_task", "decision_operator_id"))
            assertTrue(columnExists(connection, "fw_workflow_task", "decision_operator_name"))
            assertTrue(columnExists(connection, "fw_workflow_task", "decided_time"))
            assertTrue(columnExists(connection, "fw_workflow_instance", "submitted_by"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "removal_status"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "removal_error_message"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "removal_retry_count"))
            assertTrue(columnExists(connection, "fw_document", "delivery_generation"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "delivery_generation"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "current_event_id"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "current_operation"))
            assertTrue(columnExists(connection, "fw_document_delivery_target", "dispatch_sequence"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "tenant_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "key_digest"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "operator_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "action"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "resource_type"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "resource_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "subresource_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "request_fingerprint"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "record_status"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "result_resource_type"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "result_resource_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "result_related_resource_type"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "result_related_resource_id"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "completed_time"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "created_time"))
            assertTrue(columnExists(connection, "fw_idempotency_record", "updated_time"))
            assertFalse(columnExists(connection, "fw_idempotency_record", "idempotency_key"))
            assertFalse(columnExists(connection, "fw_idempotency_record", "result_json"))
            assertFalse(columnExists(connection, "fw_idempotency_record", "expires_at"))
            assertTrue(indexExists(connection, "fw_sync_record", "idx_fw_sync_tenant_document_connector_status"))
            assertTrue(indexExists(connection, "fw_task", "idx_fw_task_tenant_status_updated"))
            assertTrue(indexExists(connection, "fw_workflow_instance", "uq_fw_workflow_instance_tenant_document_pending"))
            assertTrue(indexExists(connection, "fw_outbox_event", "idx_fw_outbox_running_lease_expiry"))
            assertTrue(indexExists(connection, "fw_outbox_event", "idx_fw_outbox_legacy_running_updated"))
            assertTrue(indexExists(connection, "fw_task", "idx_fw_task_running_lease_expiry"))
            assertTrue(indexExists(connection, "fw_task", "idx_fw_task_legacy_running_updated"))
            assertTrue(indexExists(connection, "fw_idempotency_record", "uq_fw_idempotency_tenant_key_digest"))
            assertTrue(indexExists(connection, "fw_idempotency_record", "idx_fw_idempotency_tenant_resource_time"))
            assertTrue(indexExists(connection, "fw_idempotency_record", "idx_fw_idempotency_in_progress_diagnostic"))
            assertTrue(indexExists(connection, "fw_upload_session", "uq_fw_upload_session_tenant_file_asset"))
            assertTrue(indexExists(connection, "fw_workflow_task", "idx_fw_workflow_task_tenant_pending_inbox"))
            assertTrue(indexExists(connection, "fw_workflow_task", "idx_fw_workflow_task_tenant_assignee_pending_inbox"))
            assertTrue(indexExists(connection, "fw_workflow_instance", "idx_fw_workflow_instance_tenant_document_history"))
            assertTrue(indexExists(connection, "fw_workflow_task", "idx_fw_workflow_task_tenant_workflow_history"))
            assertTrue(auditLogKeysetIndexIsExact(connection))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_digests"))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_binding"))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_status"))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_result"))
            assertTrue(constraintExists(connection, "fw_idempotency_record", "ck_fw_idempotency_time"))
            assertTrue(constraintExists(connection, "fw_document_delivery_target", "uq_fw_delivery_current_event"))
            assertTrue(constraintExists(connection, "fw_document_delivery_target", "ck_fw_delivery_dispatch_event_id"))
            assertTrue(constraintExists(connection, "fw_document_delivery_target", "ck_fw_delivery_dispatch_operation"))
            assertTrue(constraintExists(connection, "fw_document_delivery_target", "ck_fw_delivery_dispatch_sequence"))
            assertTrue(constraintExists(connection, "fw_document_delivery_target", "ck_fw_delivery_removal_requires_success"))
            assertTrue(constraintExists(connection, "fw_document_delivery_target", "ck_fw_delivery_dispatch_state"))
            assertTrue(constraintExists(connection, "fw_upload_session", "ck_fw_upload_session_owner_id"))
            assertTrue(constraintExists(connection, "fw_workflow_task", "ck_fw_workflow_task_decision_evidence"))
            assertTrue(constraintExists(connection, "fw_workflow_task", "ck_fw_workflow_task_assignee_id"))
            assertTrue(constraintExists(connection, "fw_workflow_task", "ck_fw_workflow_task_decision_operator_id"))
            assertTrue(constraintExists(connection, "fw_workflow_instance", "ck_fw_workflow_instance_submitted_by"))
            assertTrue(constraintExists(connection, "fw_audit_record", "ck_fw_audit_operator_id"))
            assertTrue(constraintExists(connection, "fw_operation_log", "ck_fw_operation_operator_id"))
            assertTrue(constraintExists(connection, "fw_agent_suggestion_confirmation", "ck_fw_agent_confirmation_operator_id"))
            listOf(
                "fw_audit_record" to "operator_id",
                "fw_operation_log" to "operator_id",
                "fw_agent_suggestion_confirmation" to "confirmed_by",
                "fw_workflow_task" to "assignee_id",
                "fw_workflow_task" to "decision_operator_id",
                "fw_workflow_instance" to "submitted_by",
            ).forEach { (table, column) ->
                assertEquals(256, characterMaximumLength(connection, table, column), "$table.$column")
            }
        }
    }

    @Test
    fun `packaged versioned migration inventory remains complete and isolated`() {
        val packagedScripts = Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
            .table(FlywayMigrationRunner.HISTORY_TABLE)
            .load()
            .info()
            .all()
            .map { it.script }
            .filter { it.startsWith("V") }
            .toSet()

        assertEquals(33, packagedScripts.size)
        assertTrue("V001__create_file_document_outbox.sql" in packagedScripts)
        assertTrue("V025__index_document_audit_log_queries.sql" in packagedScripts)
        assertTrue("V026__persist_workflow_decision_evidence.sql" in packagedScripts)
        assertTrue("V027__stabilize_worker_claim_order.sql" in packagedScripts)
        assertTrue("V028__enforce_binary_identifier_collation.sql" in packagedScripts)
        assertTrue("V029__persist_workflow_submitter.sql" in packagedScripts)
        assertTrue("V033__claim_completed_upload_asset.sql" in packagedScripts)
        assertTrue("V034__create_presigned_upload_session.sql" in packagedScripts)
        assertTrue("V035__claim_presigned_upload_asset.sql" in packagedScripts)
        assertTrue("V036__create_secure_deletion.sql" in packagedScripts)
        dataSource.connection.use { connection ->
            assertFalse(tableExists(connection, FlywayMigrationRunner.HISTORY_TABLE))
        }
    }

    @Test
    fun `V029 preserves unknown legacy submitters and enforces the trusted identity boundary`() {
        val historyTable = "v029_upgrade_history"
        val before = Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
            .table(historyTable)
            .target("028")
            .load()
        assertEquals(28, before.migrate().migrationsExecuted)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO fw_workflow_instance(id, tenant_id, document_id, workflow_type, state, created_time, updated_time) " +
                        "VALUES ('workflow-v029', 'tenant-1', 'document-1', 'DOCUMENT_REVIEW', 'APPROVED', 100, 100)",
                )
            }
        }

        val upgraded = Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
            .table(historyTable)
            .target("029")
            .load()
        assertEquals(1, upgraded.migrate().migrationsExecuted)

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT submitted_by FROM fw_workflow_instance WHERE id = 'workflow-v029'",
                ).use { result ->
                    assertTrue(result.next())
                    assertNull(result.getString("submitted_by"))
                }
            }
            val maximumIdentity = "u".repeat(256)
            connection.prepareStatement(
                "UPDATE fw_workflow_instance SET submitted_by = ? WHERE id = 'workflow-v029'",
            ).use { statement ->
                statement.setString(1, maximumIdentity)
                assertEquals(1, statement.executeUpdate())
            }
            assertFailsWith<SQLException> {
                connection.prepareStatement(
                    "UPDATE fw_workflow_instance SET submitted_by = ? WHERE id = 'workflow-v029'",
                ).use { statement ->
                    statement.setString(1, " unsafe-submitter")
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `V026 upgrades populated identity columns without inventing legacy decision evidence`() {
        val historyTable = "v026_upgrade_history"
        val before = Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
            .table(historyTable)
            .target("025")
            .load()
        assertEquals(25, before.migrate().migrationsExecuted)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO fw_audit_record(id, tenant_id, resource_type, resource_id, action, operator_id, detail_json, created_time, updated_time) " +
                        "VALUES ('audit-v026', 'tenant-1', 'DOCUMENT', 'document-1', 'document:audit', 'legacy-user', '{}'::jsonb, 100, 100)",
                )
                statement.executeUpdate(
                    "INSERT INTO fw_operation_log(id, tenant_id, resource_type, resource_id, action, operator_id, detail_json, created_time) " +
                        "VALUES ('operation-v026', 'tenant-1', 'DOCUMENT', 'document-1', 'document:audit', 'legacy-user', '{}'::jsonb, 100)",
                )
                statement.executeUpdate(
                    "INSERT INTO fw_agent_suggestion_confirmation(id, tenant_id, task_id, suggestion_id, confirmed_by, confirmed_time, created_time, updated_time) " +
                        "VALUES ('confirmation-v026', 'tenant-1', 'agent-task-1', 'suggestion-1', 'legacy-user', 100, 100, 100)",
                )
                statement.executeUpdate(
                    "INSERT INTO fw_workflow_instance(id, tenant_id, document_id, workflow_type, state, created_time, updated_time) " +
                        "VALUES ('workflow-v026', 'tenant-1', 'document-1', 'DOCUMENT_REVIEW', 'APPROVED', 100, 100)",
                )
                statement.executeUpdate(
                    "INSERT INTO fw_workflow_task(id, tenant_id, workflow_id, assignee_id, task_state, comment_text, created_time, updated_time) " +
                        "VALUES ('task-v026', 'tenant-1', 'workflow-v026', 'legacy-user', 'APPROVED', 'legacy decision', 100, 100)",
                )
            }
        }

        val upgraded = Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
            .table(historyTable)
            .target("026")
            .load()
        assertEquals(1, upgraded.migrate().migrationsExecuted)

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT assignee_id, decision_operator_id, decision_operator_name, decided_time " +
                        "FROM fw_workflow_task WHERE id = 'task-v026'",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals("legacy-user", result.getString("assignee_id"))
                    assertNull(result.getString("decision_operator_id"))
                    assertNull(result.getString("decision_operator_name"))
                    assertNull(result.getObject("decided_time"))
                }
            }
            val maximumIdentity = "u".repeat(256)
            connection.prepareStatement(
                "UPDATE fw_workflow_task SET decision_operator_id = ?, decision_operator_name = ?, decided_time = 100 WHERE id = 'task-v026'",
            ).use { statement ->
                statement.setString(1, maximumIdentity)
                statement.setString(2, "升级后审批者")
                assertEquals(1, statement.executeUpdate())
            }
            listOf(99L, 101L).forEach { invalidDecisionTime ->
                assertFailsWith<SQLException>("decided_time=$invalidDecisionTime must stay within task timestamps") {
                    connection.prepareStatement(
                        "UPDATE fw_workflow_task SET decided_time = ? WHERE id = 'task-v026'",
                    ).use { statement ->
                        statement.setLong(1, invalidDecisionTime)
                        statement.executeUpdate()
                    }
                }
            }
            listOf(
                "UPDATE fw_audit_record SET operator_id = ? WHERE id = 'audit-v026'",
                "UPDATE fw_operation_log SET operator_id = ? WHERE id = 'operation-v026'",
                "UPDATE fw_agent_suggestion_confirmation SET confirmed_by = ? WHERE id = 'confirmation-v026'",
                "UPDATE fw_workflow_task SET assignee_id = ? WHERE id = 'task-v026'",
            ).forEach { sql ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, maximumIdentity)
                    assertEquals(1, statement.executeUpdate())
                }
            }
            assertFailsWith<SQLException> {
                connection.prepareStatement(
                    "UPDATE fw_workflow_task SET decision_operator_id = ? WHERE id = 'task-v026'",
                ).use { statement ->
                    statement.setString(1, "u".repeat(257))
                    statement.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `keeps host and FileWeft V001 migrations in independent history tables`() {
        val hostMigrations = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .table("flyway_schema_history")
            .baselineOnMigrate(false)
            .load()
            .migrate()
            .migrationsExecuted

        val fileWeftMigrations = FlywayMigrationRunner(dataSource).migrate()

        assertEquals(1, hostMigrations)
        assertEquals(33, fileWeftMigrations)
        dataSource.connection.use { connection ->
            assertTrue(tableExists(connection, "host_v001_probe"))
            assertTrue(tableExists(connection, "fw_document"))
            assertEquals(1, historyCount(connection, "flyway_schema_history"))
            assertEquals(33, versionedHistoryCount(connection, FlywayMigrationRunner.HISTORY_TABLE))
            assertEquals(
                "V001__create_host_probe.sql",
                historyScript(connection, "flyway_schema_history", "001"),
            )
            assertEquals(
                "V001__create_file_document_outbox.sql",
                historyScript(connection, FlywayMigrationRunner.HISTORY_TABLE, "001"),
            )
            assertEquals(
                "FileWeft namespace initialization",
                historyDescription(connection, FlywayMigrationRunner.HISTORY_TABLE, "0"),
            )
        }
    }

    @Test
    fun `reuses dedicated history without replay and validates the migrated schema`() {
        val runner = FlywayMigrationRunner(dataSource)

        assertEquals(33, runner.migrate())
        assertEquals(0, runner.migrate())
        runner.validate()

        dataSource.connection.use { connection ->
            assertEquals(33, versionedHistoryCount(connection, FlywayMigrationRunner.HISTORY_TABLE))
            assertFalse(tableExists(connection, "flyway_schema_history"))
        }
    }

    @Test
    fun `metadata wildcard decoys cannot impersonate FileWeft history or sentinel tables`() {
        val targetSchema = "fw_metadata_target"
        val similarSchema = "fw0metadata1target"
        createSchema(targetSchema)
        createSchema(similarSchema)
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("CREATE TABLE $targetSchema.fileweft0schema1history(id integer)")
                    statement.execute("CREATE TABLE $targetSchema.flyway0schema1history(id integer)")
                    statement.execute("CREATE TABLE $targetSchema.fw0document(id integer)")
                    statement.execute("CREATE TABLE $similarSchema.fileweft_schema_history(id integer)")
                    statement.execute("CREATE TABLE $similarSchema.flyway_schema_history(id integer)")
                    statement.execute("CREATE TABLE $similarSchema.fw_document(id integer)")
                }
            }

            val targetDataSource = postgresDataSource(targetSchema)
            assertEquals(33, FlywayMigrationRunner(targetDataSource, targetSchema).migrate())
            targetDataSource.connection.use { connection ->
                assertEquals(
                    33,
                    versionedHistoryCount(connection, FlywayMigrationRunner.HISTORY_TABLE, targetSchema),
                )
            }
        } finally {
            dropSchema(targetSchema)
            dropSchema(similarSchema)
        }
    }

    @Test
    fun `migrates an explicit non-default current schema`() {
        val schema = "fw_explicit_schema"
        createSchema(schema)
        try {
            val schemaDataSource = postgresDataSource(schema)

            assertEquals(33, FlywayMigrationRunner(schemaDataSource, schema, false).migrate())
            FlywayMigrationRunner(schemaDataSource, schema, false).validate()

            schemaDataSource.connection.use { connection ->
                assertEquals(schema, currentSchema(connection))
                assertTrue(tableExists(connection, "fw_document", schema))
                assertTrue(tableExists(connection, FlywayMigrationRunner.HISTORY_TABLE, schema))
                assertEquals(33, versionedHistoryCount(connection, FlywayMigrationRunner.HISTORY_TABLE, schema))
            }
        } finally {
            dropSchema(schema)
        }
    }

    @Test
    fun `enforces PostgreSQL identifier byte limits after product detection`() {
        val acceptedSchemas = listOf("a".repeat(63), "文".repeat(21))
        acceptedSchemas.forEach(::createSchema)
        try {
            acceptedSchemas.forEach { schema ->
                val schemaDataSource = postgresDataSource(schema)
                assertEquals(33, FlywayMigrationRunner(schemaDataSource, schema).migrate())
                FlywayMigrationRunner(schemaDataSource, schema).validate()
            }

            listOf("a".repeat(64), "文".repeat(22)).forEach { schema ->
                val failure = assertFailsWith<IllegalArgumentException> {
                    FlywayMigrationRunner(dataSource, schema).migrate()
                }
                assertTrue(failure.message.orEmpty().contains("UTF-8 bytes"))
            }
        } finally {
            acceptedSchemas.forEach(::dropSchema)
        }
    }

    @Test
    fun `creates an explicitly selected missing schema and verifies the DataSource resolves it`() {
        val schema = "fw_created_schema"
        dropSchema(schema)
        try {
            val schemaDataSource = postgresDataSource(schema)
            schemaDataSource.connection.use { connection ->
                assertNull(currentSchema(connection))
            }

            assertEquals(33, FlywayMigrationRunner(schemaDataSource, schema, true).migrate())

            schemaDataSource.connection.use { connection ->
                assertEquals(schema, currentSchema(connection))
                assertTrue(tableExists(connection, "fw_document", schema))
                assertEquals(33, versionedHistoryCount(connection, FlywayMigrationRunner.HISTORY_TABLE, schema))
            }
        } finally {
            dropSchema(schema)
        }
    }

    @Test
    fun `fails closed when configured and current schemas differ`() {
        val schema = "fw_mismatched_schema"
        createSchema(schema)
        try {
            val failure = assertFailsWith<IllegalStateException> {
                FlywayMigrationRunner(dataSource, schema, false).migrate()
            }

            assertTrue(failure.message.orEmpty().contains("schema mismatch"))
            assertTrue(failure.message.orEmpty().contains("'$schema'"))
            assertTrue(failure.message.orEmpty().contains("'public'"))
            dataSource.connection.use { connection ->
                assertFalse(tableExists(connection, FlywayMigrationRunner.HISTORY_TABLE, schema))
                assertFalse(tableExists(connection, "fw_document", schema))
            }
        } finally {
            dropSchema(schema)
        }
    }

    @Test
    fun `validate fails without mutating a schema that has no FileWeft history`() {
        val failure = assertFailsWith<IllegalStateException> {
            FlywayMigrationRunner(dataSource).validate()
        }

        assertTrue(failure.message.orEmpty().contains(FlywayMigrationRunner.HISTORY_TABLE))
        dataSource.connection.use { connection ->
            assertFalse(tableExists(connection, FlywayMigrationRunner.HISTORY_TABLE))
            assertFalse(tableExists(connection, "fw_document"))
        }
    }

    @Test
    fun `validate detects checksum drift in dedicated history`() {
        val runner = FlywayMigrationRunner(dataSource)
        runner.migrate()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(
                    1,
                    statement.executeUpdate(
                        "UPDATE ${FlywayMigrationRunner.HISTORY_TABLE} " +
                            "SET checksum = checksum + 1 " +
                            "WHERE script = 'V001__create_file_document_outbox.sql'",
                    ),
                )
            }
        }

        assertFailsWith<FlywayException> { runner.validate() }
    }

    @Test
    fun `future applied migration fails both validation and migration`() {
        val runner = FlywayMigrationRunner(dataSource)
        assertEquals(33, runner.migrate())
        insertFutureFileWeftHistoryRow()

        assertFailsWith<FlywayException> { runner.validate() }
        assertFailsWith<FlywayException> { runner.migrate() }

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM ${FlywayMigrationRunner.HISTORY_TABLE} " +
                        "WHERE version = '037' AND script = 'V037__future_fileweft_probe.sql' AND success",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `rejects every missing tampered or duplicate namespace marker field`() {
        val mutations = linkedMapOf(
            "missing marker" to
                "DELETE FROM ${FlywayMigrationRunner.HISTORY_TABLE} WHERE version = '0' OR type = 'BASELINE'",
            "installed rank" to
                "UPDATE ${FlywayMigrationRunner.HISTORY_TABLE} SET installed_rank = 99 WHERE type = 'BASELINE'",
            "non-zero version" to
                "UPDATE ${FlywayMigrationRunner.HISTORY_TABLE} SET version = '25' WHERE type = 'BASELINE'",
            "type" to
                "UPDATE ${FlywayMigrationRunner.HISTORY_TABLE} SET type = 'SQL' WHERE version = '0'",
            "description" to
                "UPDATE ${FlywayMigrationRunner.HISTORY_TABLE} SET description = 'tampered' WHERE type = 'BASELINE'",
            "script" to
                "UPDATE ${FlywayMigrationRunner.HISTORY_TABLE} SET script = 'tampered' WHERE type = 'BASELINE'",
            "checksum" to
                "UPDATE ${FlywayMigrationRunner.HISTORY_TABLE} SET checksum = 1 WHERE type = 'BASELINE'",
            "success" to
                "UPDATE ${FlywayMigrationRunner.HISTORY_TABLE} SET success = FALSE WHERE type = 'BASELINE'",
            "duplicate marker" to
                """
                INSERT INTO ${FlywayMigrationRunner.HISTORY_TABLE}(
                    installed_rank, version, description, type, script, checksum,
                    installed_by, execution_time, success
                ) VALUES (
                    99, '0', 'FileWeft namespace initialization', 'BASELINE',
                    'FileWeft namespace initialization', NULL, current_user, 0, TRUE
                )
                """.trimIndent(),
        )

        mutations.forEach { (caseName, mutation) ->
            resetPublicSchema()
            baselineFileWeftNamespace()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(mutation) }
            }

            val runner = FlywayMigrationRunner(dataSource)
            listOf<() -> Unit>(
                { runner.validate() },
                { runner.migrate(); Unit },
            ).forEach { operation ->
                val failure = assertFailsWith<IllegalStateException>(caseName) { operation() }
                assertTrue(
                    failure.message.orEmpty().contains("namespace marker"),
                    "$caseName should be rejected as an invalid namespace marker: ${failure.message}",
                )
            }
            dataSource.connection.use { connection ->
                assertFalse(tableExists(connection, "fw_document"), caseName)
            }
        }
    }

    @Test
    fun `refuses a version 28 baseline without FileWeft business tables`() {
        baselineFileWeftNamespace(version = "28", description = "unsafe adoption")

        val runner = FlywayMigrationRunner(dataSource)
        listOf<() -> Unit>(
            { runner.validate() },
            { runner.migrate(); Unit },
        ).forEach { operation ->
            val failure = assertFailsWith<IllegalStateException> { operation() }
            assertTrue(failure.message.orEmpty().contains("namespace marker"))
        }
        dataSource.connection.use { connection ->
            assertFalse(tableExists(connection, "fw_document"))
            assertEquals(1, historyCount(connection, FlywayMigrationRunner.HISTORY_TABLE))
        }
    }

    @Test
    fun `high concurrency migration calls coordinate without duplicate history`() {
        val concurrency = 8
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(concurrency)
        try {
            val results = (1..concurrency).map {
                executor.submit<Int> {
                    assertTrue(start.await(30, TimeUnit.SECONDS))
                    FlywayMigrationRunner(dataSource).migrate()
                }
            }
            start.countDown()

            val executionCounts = results.map { it.get(2, TimeUnit.MINUTES) }
            assertTrue(executionCounts.all { count -> count in 0..33 })
            // Flyway locks schema-history changes, not the caller for the whole migrate() method.
            // Concurrent callers may therefore split the pending migrations (for example 1 + 24),
            // but every version must be executed exactly once across the complete result set.
            assertEquals(33, executionCounts.sum())
            dataSource.connection.use { connection ->
                assertEquals(33, versionedHistoryCount(connection, FlywayMigrationRunner.HISTORY_TABLE))
                assertTrue(tableExists(connection, "fw_document"))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent migration calls create one missing schema and one namespace history`() {
        val schema = "fw_concurrent_created_schema"
        val concurrency = 8
        dropSchema(schema)
        val schemaDataSource = postgresDataSource(schema)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(concurrency)
        try {
            val results = (1..concurrency).map {
                executor.submit<Int> {
                    assertTrue(start.await(30, TimeUnit.SECONDS))
                    FlywayMigrationRunner(schemaDataSource, schema, true).migrate()
                }
            }
            start.countDown()

            val executionCounts = results.map { it.get(2, TimeUnit.MINUTES) }
            assertTrue(executionCounts.all { count -> count in 0..33 })
            assertEquals(33, executionCounts.sum())
            FlywayMigrationRunner(schemaDataSource, schema, false).validate()
            schemaDataSource.connection.use { connection ->
                assertEquals(schema, currentSchema(connection))
                assertTrue(tableExists(connection, "fw_document", schema))
                assertEquals(33, versionedHistoryCount(connection, FlywayMigrationRunner.HISTORY_TABLE, schema))
            }
        } finally {
            executor.shutdownNow()
            dropSchema(schema)
        }
    }

    @Test
    fun `rechecks a newly appeared valid history before rejecting concurrent sentinel tables`() {
        val firstHistoryProbeCompleted = CountDownLatch(1)
        val resumeFirstRunner = CountDownLatch(1)
        val delayedDataSource = PausingHistoryProbeDataSource(
            dataSource,
            firstHistoryProbeCompleted,
            resumeFirstRunner,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val delayedResult = executor.submit<Int> {
                FlywayMigrationRunner(delayedDataSource).migrate()
            }
            assertTrue(firstHistoryProbeCompleted.await(30, TimeUnit.SECONDS))

            assertEquals(33, FlywayMigrationRunner(dataSource).migrate())
            resumeFirstRunner.countDown()

            assertEquals(0, delayedResult.get(2, TimeUnit.MINUTES))
            dataSource.connection.use { connection ->
                assertTrue(tableExists(connection, "fw_document"))
                assertEquals(33, versionedHistoryCount(connection, FlywayMigrationRunner.HISTORY_TABLE))
            }
        } finally {
            resumeFirstRunner.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `does not swallow a non-concurrent baseline failure when a valid marker appears`() {
        val failingDataSource = NonConcurrentBaselineFailureDataSource(dataSource) {
            baselineFileWeftNamespace()
        }

        val failure = assertFailsWith<FlywayException> {
            FlywayMigrationRunner(failingDataSource).migrate()
        }

        val messages = generateSequence<Throwable>(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        assertTrue(messages.contains("forced non-concurrent bootstrap failure"))
        assertTrue(failingDataSource.failureInjected.get())
        dataSource.connection.use { connection ->
            assertTrue(tableExists(connection, FlywayMigrationRunner.HISTORY_TABLE))
            assertFalse(tableExists(connection, "fw_document"))
        }
    }

    @Test
    fun `refuses legacy FileWeft history without replay repair or baseline`() {
        assertEquals(
            1,
            Flyway.configure()
                .dataSource(dataSource)
                .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
                .table("flyway_schema_history")
                .target("1")
                .baselineOnMigrate(false)
                .load()
                .migrate()
                .migrationsExecuted,
        )

        val failure = assertFailsWith<LegacyFileWeftMigrationHistoryException> {
            FlywayMigrationRunner(dataSource).migrate()
        }

        assertEquals("public", failure.schema)
        assertTrue(failure.message.orEmpty().contains("Refusing to replay, repair, or baseline"))
        dataSource.connection.use { connection ->
            assertEquals(1, historyCount(connection, "flyway_schema_history"))
            assertFalse(tableExists(connection, FlywayMigrationRunner.HISTORY_TABLE))
            assertTrue(tableExists(connection, "fw_document"))
        }
    }

    @Test
    fun `refuses a failed legacy FileWeft V001 row even when no FileWeft table exists`() {
        migrateHostProbe()
        insertLegacyHistoryRow(
            version = "001",
            script = "V001__create_file_document_outbox.sql",
            success = false,
        )

        assertFailsWith<LegacyFileWeftMigrationHistoryException> {
            FlywayMigrationRunner(dataSource).migrate()
        }

        dataSource.connection.use { connection ->
            assertFalse(tableExists(connection, FlywayMigrationRunner.HISTORY_TABLE))
            assertFalse(tableExists(connection, "fw_document"))
            assertTrue(tableExists(connection, "host_v001_probe"))
        }
    }

    @Test
    fun `refuses an isolated legacy FileWeft V024 row without relying on V001 or sentinel tables`() {
        migrateHostProbe()
        insertLegacyHistoryRow(
            version = "024",
            script = "V024__bind_resumable_upload_session_owner.sql",
            success = true,
        )

        assertFailsWith<LegacyFileWeftMigrationHistoryException> {
            FlywayMigrationRunner(dataSource).migrate()
        }

        dataSource.connection.use { connection ->
            assertFalse(tableExists(connection, FlywayMigrationRunner.HISTORY_TABLE))
            assertFalse(tableExists(connection, "fw_upload_session"))
            assertTrue(tableExists(connection, "host_v001_probe"))
        }
    }

    @Test
    fun `refuses to namespace-baseline untracked FileWeft sentinel tables`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE fw_document(id varchar(64) PRIMARY KEY)")
            }
        }

        val failure = assertFailsWith<IllegalStateException> {
            FlywayMigrationRunner(dataSource).migrate()
        }

        assertTrue(failure.message.orEmpty().contains("fw_document"))
        assertTrue(failure.message.orEmpty().contains("without ${FlywayMigrationRunner.HISTORY_TABLE}"))
        dataSource.connection.use { connection ->
            assertFalse(tableExists(connection, FlywayMigrationRunner.HISTORY_TABLE))
        }
    }

    @Test
    fun `makes historical running outbox rows reclaimable after the legacy cutoff with a new token lease`() {
        migrateFileWeftThrough("17")

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_outbox_event(
                    id, tenant_id, event_type, payload_json, event_status, retry_count, next_attempt_time, created_time, updated_time
                ) VALUES (?, ?, ?, '{}'::jsonb, 'RUNNING', 0, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "historical-running-event")
                statement.setString(2, "tenant-1")
                statement.setString(3, "document.publish.requested")
                statement.setLong(4, 9_999)
                statement.setLong(5, 1)
                statement.setLong(6, 1)
                statement.executeUpdate()
            }
        }

        FlywayMigrationRunner(dataSource).migrate()

        val processing = JdbcOutboxProcessingRepository(ObjectMapper())
        val transaction = JdbcApplicationTransaction(dataSource)
        val lease = transaction.execute {
            processing.claimAvailable(1, 100, OutboxLeaseClaim("worker-new", "token-new", 200, 1))
        }.single()

        assertEquals("historical-running-event", lease.event.id.value)
        assertEquals("worker-new", lease.leaseOwner)
        assertEquals("token-new", lease.leaseToken)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT event_status, lease_owner, lease_token, lease_expire_time FROM fw_outbox_event WHERE id = ?",
            ).use { statement ->
                statement.setString(1, "historical-running-event")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("RUNNING", result.getString("event_status"))
                    assertEquals("worker-new", result.getString("lease_owner"))
                    assertEquals("token-new", result.getString("lease_token"))
                    assertEquals(200, result.getLong("lease_expire_time"))
                }
            }
        }
    }

    @Test
    fun `makes historical running task rows reclaimable after the legacy cutoff with a new token lease`() {
        migrateFileWeftThrough("18")

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_task(
                    id, tenant_id, task_type, payload_json, idempotency_key, task_status, retry_count,
                    next_attempt_time, lease_owner, lease_expire_time, created_time, updated_time
                ) VALUES (?, ?, ?, '{}'::jsonb, ?, 'RUNNING', 0, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "historical-running-task")
                statement.setString(2, "tenant-1")
                statement.setString(3, "document.doctor")
                statement.setString(4, "doctor:document-1")
                statement.setLong(5, 9_999)
                statement.setString(6, "old-worker")
                statement.setLong(7, 0)
                statement.setLong(8, 1)
                statement.setLong(9, 1)
                statement.executeUpdate()
            }
        }

        FlywayMigrationRunner(dataSource).migrate()

        val processing = JdbcTaskRepository(ObjectMapper(), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC))
        val transaction = JdbcApplicationTransaction(dataSource)
        val lease = transaction.execute {
            processing.claimAvailable(1, 100, TaskLeaseClaim("worker-new", "token-new", 200, 1))
        }.single()

        assertEquals("historical-running-task", lease.task.id.value)
        assertEquals("worker-new", lease.leaseOwner)
        assertEquals("token-new", lease.leaseToken)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT task_status, lease_owner, lease_token, lease_expire_time FROM fw_task WHERE id = ?",
            ).use { statement ->
                statement.setString(1, "historical-running-task")
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals("RUNNING", result.getString("task_status"))
                    assertEquals("worker-new", result.getString("lease_owner"))
                    assertEquals("token-new", result.getString("lease_token"))
                    assertEquals(200, result.getLong("lease_expire_time"))
                }
            }
        }
    }

    @Test
    fun `refuses to migrate when historical pending workflows are duplicated for one document`() {
        migrateFileWeftThrough("16")

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO fw_workflow_instance(id, tenant_id, document_id, workflow_type, state, created_time, updated_time)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "workflow-1")
                statement.setString(2, "tenant-1")
                statement.setString(3, "document-1")
                statement.setString(4, "DOCUMENT_REVIEW")
                statement.setLong(5, 100)
                statement.setLong(6, 100)
                statement.executeUpdate()
                statement.setString(1, "workflow-2")
                statement.setLong(5, 101)
                statement.setLong(6, 101)
                statement.executeUpdate()
            }
        }

        val failure = assertFailsWith<FlywayException> {
            FlywayMigrationRunner(dataSource).migrate()
        }
        val messages = generateSequence<Throwable>(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        assertTrue(messages.contains("Cannot enforce one PENDING workflow per tenant/document"))
        assertTrue(messages.contains("tenant_id=tenant-1, document_id=document-1, pending_count=2"))
    }

    @Test
    fun `refuses V025 when the expected audit index name has an incompatible definition`() {
        migrateFileWeftThrough("24")

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE INDEX idx_fw_audit_tenant_resource_time_id ON fw_audit_record(tenant_id)",
                )
            }
        }

        val failure = assertFailsWith<FlywayException> {
            FlywayMigrationRunner(dataSource).migrate()
        }
        val messages = generateSequence<Throwable>(failure) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        assertTrue(messages.contains("V025 requires one valid idx_fw_audit_tenant_resource_time_id"))

        dataSource.connection.use { connection ->
            assertFalse(auditLogKeysetIndexIsExact(connection))
            connection.prepareStatement(
                "SELECT COUNT(*) FROM ${FlywayMigrationRunner.HISTORY_TABLE} WHERE version = '25' AND success",
            ).use { statement ->
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    private fun baselineFileWeftNamespace(
        version: String = "0",
        description: String = "FileWeft namespace initialization",
    ) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
            .table(FlywayMigrationRunner.HISTORY_TABLE)
            .baselineOnMigrate(false)
            .baselineVersion(version)
            .baselineDescription(description)
            .load()
            .baseline()
    }

    private fun migrateFileWeftThrough(target: String) {
        baselineFileWeftNamespace()
        Flyway.configure()
            .dataSource(dataSource)
            .locations(FlywayMigrationRunner.migrationLocation(FlywayMigrationRunner.DatabaseProduct.POSTGRESQL))
            .table(FlywayMigrationRunner.HISTORY_TABLE)
            .target(target)
            .baselineOnMigrate(false)
            .load()
            .migrate()
    }

    private fun insertFutureFileWeftHistoryRow() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                assertEquals(
                    1,
                    statement.executeUpdate(
                        """
                        INSERT INTO ${FlywayMigrationRunner.HISTORY_TABLE}(
                            installed_rank, version, description, type, script, checksum,
                            installed_by, execution_time, success
                        )
                        SELECT MAX(installed_rank) + 1, '037', 'future FileWeft probe', 'SQL',
                               'V037__future_fileweft_probe.sql', 300037, current_user, 0, TRUE
                          FROM ${FlywayMigrationRunner.HISTORY_TABLE}
                        """.trimIndent(),
                    ),
                )
            }
        }
    }

    private fun postgresDataSource(currentSchema: String? = null): PGSimpleDataSource = PGSimpleDataSource().apply {
        setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
        user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
        password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        if (currentSchema != null) {
            setCurrentSchema(currentSchema)
        }
    }

    private fun migrateHostProbe() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .table("flyway_schema_history")
            .baselineOnMigrate(false)
            .load()
            .migrate()
    }

    private fun insertLegacyHistoryRow(version: String, script: String, success: Boolean) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO flyway_schema_history(
                    installed_rank, version, description, type, script, checksum,
                    installed_by, execution_time, success
                ) VALUES (2, ?, 'legacy FileWeft probe', 'SQL', ?, NULL, current_user, 0, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, version)
                statement.setString(2, script)
                statement.setBoolean(3, success)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun resetPublicSchema() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA public CASCADE")
                statement.execute("CREATE SCHEMA public")
            }
        }
    }

    private fun createSchema(schema: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                statement.execute("CREATE SCHEMA $schema")
            }
        }
    }

    private fun dropSchema(schema: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            }
        }
    }

    private fun currentSchema(connection: Connection): String? = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT current_schema()").use { result ->
            assertTrue(result.next())
            result.getString(1)
        }
    }

    private fun tableExists(
        connection: Connection,
        tableName: String,
        schema: String = "public",
    ): Boolean = connection.metaData.getTables(null, schema, tableName, arrayOf("TABLE")).use { it.next() }

    private fun historyCount(
        connection: Connection,
        historyTable: String,
        schema: String = "public",
    ): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM $schema.$historyTable WHERE success").use { result ->
            assertTrue(result.next())
            result.getInt(1)
        }
    }

    private fun versionedHistoryCount(
        connection: Connection,
        historyTable: String,
        schema: String = "public",
    ): Int = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT COUNT(*) FROM $schema.$historyTable WHERE success AND version IS NOT NULL AND version <> '0'",
        ).use { result ->
            assertTrue(result.next())
            result.getInt(1)
        }
    }

    private fun historyScript(
        connection: Connection,
        historyTable: String,
        version: String,
    ): String = connection.prepareStatement(
        "SELECT script FROM public.$historyTable WHERE version = ? AND success",
    ).use { statement ->
        statement.setString(1, version)
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getString(1)
        }
    }

    private fun historyDescription(
        connection: Connection,
        historyTable: String,
        version: String,
    ): String = connection.prepareStatement(
        "SELECT description FROM public.$historyTable WHERE version = ? AND success",
    ).use { statement ->
        statement.setString(1, version)
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getString(1)
        }
    }

    private fun columnExists(connection: Connection, tableName: String, columnName: String): Boolean =
        connection.metaData.getColumns(null, "public", tableName, columnName).use { it.next() }

    private fun characterMaximumLength(connection: Connection, tableName: String, columnName: String): Int =
        connection.prepareStatement(
            "SELECT character_maximum_length FROM information_schema.columns " +
                "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
        ).use { statement ->
            statement.setString(1, tableName)
            statement.setString(2, columnName)
            statement.executeQuery().use { result ->
                check(result.next()) { "Missing column $tableName.$columnName." }
                result.getInt(1)
            }
        }

    private fun indexExists(connection: Connection, tableName: String, indexName: String): Boolean =
        connection.metaData.getIndexInfo(null, "public", tableName, false, false).use { indexes ->
            generateSequence { if (indexes.next()) indexes.getString("INDEX_NAME") else null }
                .any { it.equals(indexName, ignoreCase = true) }
        }

    private fun auditLogKeysetIndexIsExact(connection: Connection): Boolean =
        connection.prepareStatement(
            """
            SELECT index_row.indisvalid
               AND index_row.indisready
               AND index_row.indislive
               AND pg_get_indexdef(index_row.indexrelid) = FORMAT(
                    'CREATE INDEX %I ON %I.%I USING btree (tenant_id, resource_type, resource_id, created_time DESC, id DESC)',
                    'idx_fw_audit_tenant_resource_time_id', current_schema(), 'fw_audit_record'
               )
              FROM pg_index index_row
              JOIN pg_class index_class ON index_class.oid = index_row.indexrelid
              JOIN pg_namespace index_namespace ON index_namespace.oid = index_class.relnamespace
             WHERE index_namespace.nspname = current_schema()
               AND index_class.relname = 'idx_fw_audit_tenant_resource_time_id'
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) && !result.next() }
        }

    private fun constraintExists(connection: Connection, tableName: String, constraintName: String): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.table_constraints WHERE table_schema = 'public' AND table_name = ? AND constraint_name = ?",
        ).use { statement ->
            statement.setString(1, tableName)
            statement.setString(2, constraintName)
            statement.executeQuery().use { result -> result.next() }
        }
}

private class PausingHistoryProbeDataSource(
    private val delegate: DataSource,
    private val firstHistoryProbeCompleted: CountDownLatch,
    private val resumeFirstRunner: CountDownLatch,
) : DataSource by delegate {
    private val historyProbeCount = AtomicInteger()

    override fun getConnection(): Connection = wrapConnection(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        wrapConnection(delegate.getConnection(username, password))

    private fun wrapConnection(connection: Connection): Connection = Proxy.newProxyInstance(
        connection.javaClass.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, args ->
        if (method.name == "getMetaData" && method.parameterCount == 0) {
            wrapMetadata(connection.metaData)
        } else {
            invokeDelegate(method, connection, args)
        }
    } as Connection

    private fun wrapMetadata(metadata: DatabaseMetaData): DatabaseMetaData = Proxy.newProxyInstance(
        metadata.javaClass.classLoader,
        arrayOf(DatabaseMetaData::class.java),
    ) { _, method, args ->
        val result = invokeDelegate(method, metadata, args)
        if (
            method.name == "getTables" &&
            args?.getOrNull(2) == metadataPattern(metadata, FlywayMigrationRunner.HISTORY_TABLE) &&
            historyProbeCount.incrementAndGet() == 1
        ) {
            firstHistoryProbeCompleted.countDown()
            check(resumeFirstRunner.await(30, TimeUnit.SECONDS)) {
                "Timed out while forcing the namespace-history bootstrap interleaving"
            }
        }
        result
    } as DatabaseMetaData
}

private class NonConcurrentBaselineFailureDataSource(
    private val delegate: DataSource,
    private val publishValidNamespaceMarker: () -> Unit,
) : DataSource by delegate {
    private val sentinelScanObserved = AtomicBoolean()
    val failureInjected = AtomicBoolean()

    override fun getConnection(): Connection {
        if (sentinelScanObserved.get() && failureInjected.compareAndSet(false, true)) {
            publishValidNamespaceMarker()
            throw SQLException("forced non-concurrent bootstrap failure", "42501")
        }
        return wrapConnection(delegate.connection)
    }

    override fun getConnection(username: String?, password: String?): Connection {
        if (sentinelScanObserved.get() && failureInjected.compareAndSet(false, true)) {
            publishValidNamespaceMarker()
            throw SQLException("forced non-concurrent bootstrap failure", "42501")
        }
        return wrapConnection(delegate.getConnection(username, password))
    }

    private fun wrapConnection(connection: Connection): Connection = Proxy.newProxyInstance(
        connection.javaClass.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, args ->
        if (method.name == "getMetaData" && method.parameterCount == 0) {
            wrapMetadata(connection.metaData)
        } else {
            invokeDelegate(method, connection, args)
        }
    } as Connection

    private fun wrapMetadata(metadata: DatabaseMetaData): DatabaseMetaData = Proxy.newProxyInstance(
        metadata.javaClass.classLoader,
        arrayOf(DatabaseMetaData::class.java),
    ) { _, method, args ->
        val result = invokeDelegate(method, metadata, args)
        if (
            method.name == "getTables" &&
            (args?.getOrNull(2) as? String)?.startsWith(metadataPattern(metadata, "fw_")) == true
        ) {
            sentinelScanObserved.set(true)
        }
        result
    } as DatabaseMetaData
}

private fun metadataPattern(metadata: DatabaseMetaData, value: String): String {
    val escape = metadata.searchStringEscape
    return if (escape.isNullOrEmpty()) value else value
        .replace(escape, escape + escape)
        .replace("_", escape + "_")
        .replace("%", escape + "%")
}

private fun invokeDelegate(method: Method, target: Any, args: Array<out Any?>?): Any? = try {
    method.invoke(target, *(args ?: emptyArray()))
} catch (failure: InvocationTargetException) {
    throw failure.targetException
}
