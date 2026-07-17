package ai.icen.fw.workflow.persistence.migration

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkflowNotificationMigrationResourceTest {
    @Test
    fun `V035 has three dialect parity and explicit durable delivery boundaries`() {
        val migrations = listOf("postgres", "mysql", "kingbase").associateWith { dialect ->
            val path = "/ai/icen/fw/workflow/db/migration/$dialect/V035__persist_workflow_notification_lifecycle.sql"
            requireNotNull(javaClass.getResource(path)).readText(Charsets.UTF_8)
        }

        assertEquals(canonical(migrations.getValue("postgres")), canonical(migrations.getValue("mysql")))
        assertEquals(canonical(migrations.getValue("postgres")), canonical(migrations.getValue("kingbase")))
        migrations.forEach { (dialect, sql) ->
            val lower = sql.lowercase()
            assertEquals(REQUIRED_TABLES, TABLE.findAll(sql).map { it.groupValues[1].lowercase() }.toSet(), dialect)
            listOf(
                "unique (tenant_id, origin_idempotency_key)",
                "unique (tenant_id, deduplication_key)",
                "record_version", "fencing_token", "lease_expires_time", "provider_request_digest",
                "provider_evidence_payload", "provider_receipt_digest", "delivery_digest",
                "outcome_evidence_digest", "last_mutation_digest", "authorization_evidence_digest",
                "outcome-unknown", "transient-bounce", "permanent-bounce",
            ).forEach { required -> assertTrue(lower.contains(required), "$dialect V035 is missing $required") }
            listOf("authorization_header", "bearer_token", "cookie_value", "provider_secret", "endpoint_url").forEach {
                forbidden -> assertFalse(lower.contains(forbidden), "$dialect V035 persists forbidden $forbidden")
            }
        }
    }

    private fun canonical(sql: String): String = sql.lowercase()
        .replace(Regex("varbinary\\((\\d+)\\)")) { match -> "varchar(${match.groupValues[1]})" }
        .replace("longblob", "bytea")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        val TABLE = Regex("CREATE TABLE\\s+(fw_wf_[a-z_]+)", RegexOption.IGNORE_CASE)
        val REQUIRED_TABLES = setOf(
            "fw_wf_notification_batch",
            "fw_wf_notification_envelope",
            "fw_wf_notification_delivery_report",
        )
    }
}
