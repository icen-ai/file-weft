package ai.icen.fw.workflow.sla.persistence.jdbc

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowSlaJdbcMigrationResourceTest {
    @Test
    fun `all production dialects own the same V038 logical schema`() {
        WorkflowSlaJdbcMigrationDialect.values().forEach { dialect ->
            val sql = checkNotNull(javaClass.getResourceAsStream(dialect.resourcePath)).use {
                String(it.readBytes(), StandardCharsets.UTF_8).lowercase()
            }
            listOf(
                "create table fw_wf_sla_schedule",
                "create table fw_wf_sla_milestone",
                "unique (tenant_id, schedule_id)",
                "unique (tenant_id, idempotency_key)",
                "unique (tenant_id, instance_id, work_item_id)",
                "fence_sequence",
                "lease_fencing_token",
                "action_request_digest",
                "receipt_digest",
                "last_operation_digest",
                "schedule_content_digest",
                "milestone_content_digest",
                "created_time",
                "updated_time",
            ).forEach { required -> assertTrue(sql.contains(required), "${dialect.name} misses $required") }
            assertFalse(sql.contains("fw_workflow_task"))
            assertFalse(sql.contains("v001"))
            assertFalse(sql.contains("v029"))
            assertEquals("038", dialect.resourcePath.substringAfter("/V").substringBefore("__"))
        }
    }
}
