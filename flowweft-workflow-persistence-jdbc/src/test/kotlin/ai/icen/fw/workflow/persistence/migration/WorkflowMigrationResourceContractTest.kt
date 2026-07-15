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
