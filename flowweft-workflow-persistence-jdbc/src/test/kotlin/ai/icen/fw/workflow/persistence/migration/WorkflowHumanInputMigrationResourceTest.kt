package ai.icen.fw.workflow.persistence.migration

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkflowHumanInputMigrationResourceTest {
    @Test
    fun `V034 has three-dialect semantic parity and never stores raw html`() {
        val migrations = listOf("postgres", "mysql", "kingbase").associateWith { dialect ->
            val path = "/ai/icen/fw/workflow/db/migration/$dialect/V034__persist_workflow_human_input.sql"
            requireNotNull(javaClass.getResource(path)).readText(Charsets.UTF_8)
        }

        assertEquals(canonical(migrations.getValue("postgres")), canonical(migrations.getValue("mysql")))
        assertEquals(canonical(migrations.getValue("postgres")), canonical(migrations.getValue("kingbase")))
        migrations.forEach { (dialect, sql) ->
            val lower = sql.lowercase()
            assertEquals(REQUIRED_TABLES, TABLE.findAll(sql).map { it.groupValues[1].lowercase() }.toSet(), dialect)
            listOf("raw_html", "html_payload", "rendered_html").forEach { forbidden ->
                assertFalse(lower.contains(forbidden), "$dialect V034 contains forbidden $forbidden")
            }
            listOf(
                "tenant_id", "idempotency_key", "request_digest", "reservation_status",
                "fencing_token", "record_version", "provider_receipt_digest", "receipt_expires_time",
                "form_binding_digest", "canonical_payload_digest", "token_schema_version",
                "token_kind", "token_ordinal", "principal_type", "principal_id", "display_name_snapshot",
            ).forEach { required -> assertTrue(lower.contains(required), "$dialect V034 is missing $required") }
            assertTrue(lower.contains("unique (tenant_id, idempotency_key)"), "$dialect idempotency key is not scoped")
            assertTrue(lower.contains("result_kind in ('form', 'notification')"), "$dialect receipt rule is missing")
            assertTrue(lower.contains("provider_receipt_digest varchar(64) not null"), "$dialect notification receipt is nullable")
        }
    }

    private fun canonical(sql: String): String = sql.lowercase()
        .replace(Regex("varbinary\\((\\d+)\\)")) { match -> "varchar(${match.groupValues[1]})" }
        .replace("longblob", "bytea")
        .replace("longtext", "text")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        val TABLE = Regex("CREATE TABLE\\s+(fw_wf_[a-z_]+)", RegexOption.IGNORE_CASE)
        val REQUIRED_TABLES = setOf(
            "fw_wf_human_input_idem",
            "fw_wf_form_submission_ref",
            "fw_wf_structured_comment",
            "fw_wf_structured_comment_token",
            "fw_wf_mention_notification_result",
        )
    }
}
