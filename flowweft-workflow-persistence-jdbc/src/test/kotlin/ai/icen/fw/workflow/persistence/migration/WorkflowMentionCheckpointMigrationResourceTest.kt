package ai.icen.fw.workflow.persistence.migration

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkflowMentionCheckpointMigrationResourceTest {
    @Test
    fun `V036 has three dialect parity and stores only provider call evidence`() {
        val migrations = listOf("postgres", "mysql", "kingbase").associateWith { dialect ->
            val path = "/ai/icen/fw/workflow/db/migration/$dialect/" +
                "V036__fence_workflow_mention_notification_provider_calls.sql"
            requireNotNull(javaClass.getResource(path)).readText(Charsets.UTF_8)
        }
        assertEquals(canonical(migrations.getValue("postgres")), canonical(migrations.getValue("mysql")))
        assertEquals(canonical(migrations.getValue("postgres")), canonical(migrations.getValue("kingbase")))
        migrations.forEach { (dialect, sql) ->
            val lower = sql.lowercase()
            listOf(
                "fw_wf_mention_notification_checkpoint",
                "primary key (tenant_id, id)",
                "unique (tenant_id, idempotency_key)",
                "operation_request_digest",
                "provider_request_digest",
                "lease_id",
                "fencing_token",
                "record_version",
                "provider-call-started",
                "outcome-unknown",
                "not-sent",
                "terminal-failure",
            ).forEach { required -> assertTrue(lower.contains(required), "$dialect V036 is missing $required") }
            listOf(
                "raw_html",
                "rendered_html",
                "comment_text",
                "authorization_header",
                "bearer_token",
                "provider_secret",
            ).forEach { forbidden -> assertFalse(lower.contains(forbidden), "$dialect V036 stores $forbidden") }
        }
        val mysql = migrations.getValue("mysql").lowercase()
        listOf("id varbinary(64)", "tenant_id varbinary(512)", "idempotency_key varbinary(512)", "lease_id varbinary(512)")
            .forEach { required ->
                assertTrue(mysql.contains(required), "MySQL V036 must keep composite identifiers within its key limit: $required")
            }
    }

    private fun canonical(sql: String): String = sql.lowercase()
        .replace(Regex("varbinary\\((\\d+)\\)")) { match -> "varchar(${match.groupValues[1]})" }
        .replace(Regex("\\s+"), " ")
        .trim()
}
