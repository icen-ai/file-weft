package ai.icen.fw.workflow.persistence.migration

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkflowMigrationResourceContractTest {
    @Test
    fun `all dialects own the same V030 through V033 workflow table inventory`() {
        val inventories = listOf("postgres", "mysql", "kingbase").associateWith { dialect ->
            (30..33).flatMap { version ->
                val suffix = when (version) {
                    30 -> "create_flowweft_workflow_runtime"
                    31 -> "create_flowweft_workflow_collaboration"
                    32 -> "create_flowweft_workflow_human_collaboration"
                    else -> "fence_flowweft_workflow_effect_jobs"
                }
                val path = "/ai/icen/fw/workflow/db/migration/$dialect/V0${version}__${suffix}.sql"
                val text = requireNotNull(javaClass.getResource(path)).readText(Charsets.UTF_8)
                assertTrue(
                    text.startsWith("--") || text.startsWith("CREATE TABLE"),
                    "$path must start with SQL rather than captured command output",
                )
                assertFalse(text.contains("Exit code:"), "$path contains captured command output")
                assertFalse(text.contains("Wall time:"), "$path contains captured command output")
                assertFalse(text.contains("CREATE TABLE fw_document"), "$path must remain workflow-only")
                assertFalse(text.contains("CREATE TABLE fw_workflow_"), "$path must not recreate legacy workflow tables")
                if (version == 33) {
                    listOf(
                        "record_version", "lease_id", "fencing_token", "claim_request_digest",
                        "organization_authority", "organization_snapshot_revision",
                        "resolution_request_digest", "organization_provider_revision",
                        "organization_snapshot_digest", "organization_snapshot_receipt_digest",
                        "organization_confirmation_revision", "organization_confirmation_snapshot_digest",
                        "organization_confirmation_request_digest", "organization_confirmation_receipt_digest",
                        "fw_wf_effect_result",
                    ).forEach { required ->
                        assertTrue(text.contains(required), "$path is missing $required")
                    }
                }
                TABLE.findAll(text).map { match -> match.groupValues[1] }.toList()
            }.toSet()
        }
        assertEquals(inventories.getValue("postgres"), inventories.getValue("mysql"))
        assertEquals(inventories.getValue("postgres"), inventories.getValue("kingbase"))
        assertTrue(inventories.getValue("postgres").containsAll(REQUIRED_TABLES))
        assertEquals("flowweft_workflow_schema_history", WorkflowFlywayMigrationRunner.HISTORY_TABLE)
    }

    @Test
    fun `official V036 through V038 line owns the same tenant scoped extension inventory`() {
        val expected = mapOf(
            36 to setOf("fw_wf_mention_notification_checkpoint"),
            37 to setOf(
                "fw_wf_cycle_guard_total",
                "fw_wf_cycle_guard_cycle",
                "fw_wf_cycle_guard_receipt",
            ),
            38 to setOf("fw_wf_sla_schedule", "fw_wf_sla_milestone"),
        )
        val suffixes = mapOf(
            36 to "fence_workflow_mention_notification_provider_calls",
            37 to "persist_workflow_cycle_guard",
            38 to "persist_workflow_sla",
        )
        listOf("postgres", "mysql", "kingbase").forEach { dialect ->
            expected.forEach { (version, expectedTables) ->
                val path = "/ai/icen/fw/workflow/db/migration/$dialect/" +
                    "V0${version}__${suffixes.getValue(version)}.sql"
                val sql = requireNotNull(javaClass.getResource(path)).readText(Charsets.UTF_8)
                val lower = sql.lowercase()
                assertEquals(
                    expectedTables,
                    TABLE.findAll(sql).map { it.groupValues[1].lowercase() }.toSet(),
                    "$dialect V0$version table inventory",
                )
                assertEquals(
                    expectedTables.size,
                    Regex("primary key \\(tenant_id, id\\)", RegexOption.IGNORE_CASE).findAll(sql).count(),
                    "$dialect V0$version must tenant-scope every primary key",
                )
                assertFalse(lower.contains("exit code:"), "$path contains captured command output")
                assertFalse(lower.contains("wall time:"), "$path contains captured command output")
            }
            val v36 = resource(dialect, 36, suffixes.getValue(36))
            listOf("operation_request_digest", "provider_request_digest", "fencing_token", "record_version")
                .forEach { assertTrue(v36.contains(it), "$dialect V036 misses $it") }
            val v37 = resource(dialect, 37, suffixes.getValue(37))
            listOf(
                "unique (tenant_id, aggregate_digest)",
                "unique (tenant_id, scope_digest)",
                "unique (tenant_id, idempotency_key)",
            ).forEach { assertTrue(v37.contains(it), "$dialect V037 misses $it") }
            val v38 = resource(dialect, 38, suffixes.getValue(38))
            listOf(
                "unique (tenant_id, schedule_id)",
                "unique (tenant_id, idempotency_key)",
                "unique (tenant_id, instance_id, work_item_id)",
            ).forEach { assertTrue(v38.contains(it), "$dialect V038 misses $it") }
        }
    }

    private fun resource(dialect: String, version: Int, suffix: String): String {
        val path = "/ai/icen/fw/workflow/db/migration/$dialect/V0${version}__$suffix.sql"
        return requireNotNull(javaClass.getResource(path)).readText(Charsets.UTF_8).lowercase()
    }

    private companion object {
        val TABLE = Regex("CREATE TABLE\\s+(fw_wf_[a-z_]+)", RegexOption.IGNORE_CASE)
        val REQUIRED_TABLES = setOf(
            "fw_wf_definition_version", "fw_wf_instance", "fw_wf_token",
            "fw_wf_node_execution", "fw_wf_human_task", "fw_wf_human_candidate",
            "fw_wf_human_decision", "fw_wf_event", "fw_wf_effect", "fw_wf_job",
            "fw_wf_human_collaboration_event",
            "fw_wf_effect_result",
            "fw_wf_idempotency", "fw_wf_timer", "fw_wf_subscription", "fw_wf_incident",
            "fw_wf_form_version", "fw_wf_form_submission", "fw_wf_comment",
            "fw_wf_comment_mention", "fw_wf_notification", "fw_wf_migration_plan",
        )
    }
}
