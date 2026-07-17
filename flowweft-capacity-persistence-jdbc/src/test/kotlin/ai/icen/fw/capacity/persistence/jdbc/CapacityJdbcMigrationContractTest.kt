package ai.icen.fw.capacity.persistence.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CapacityJdbcMigrationContractTest {
    @Test
    fun `all supported dialects ship the same tenant-partitioned V039 table set`() {
        val expectedTables = setOf(
            "fw_capacity_policy",
            "fw_capacity_policy_workload",
            "fw_capacity_policy_limit",
            "fw_capacity_policy_degradation",
            "fw_capacity_policy_snapshot",
            "fw_capacity_state",
            "fw_capacity_measure",
            "fw_capacity_reservation",
            "fw_capacity_idempotency",
            "fw_capacity_outbox",
        )
        CapacityJdbcMigrationDialect.values().forEach { dialect ->
            val resource = javaClass.getResource(dialect.resourcePath)
            assertNotNull(resource, dialect.name)
            val sql = resource.readText(Charsets.UTF_8)
            val tables = Regex("CREATE TABLE\\s+([a-z0-9_]+)", RegexOption.IGNORE_CASE)
                .findAll(sql).map { it.groupValues[1].lowercase() }.toSet()
            assertEquals(expectedTables, tables, dialect.name)
            assertTrue(sql.contains("tenant_id", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("fw_capacity_idempotency", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("fw_capacity_outbox", ignoreCase = true), dialect.name)
            assertFalse(sql.contains("H2", ignoreCase = true), dialect.name)
        }
    }

    @Test
    fun `migration locations remain on the standard FlowWeft workflow history`() {
        CapacityJdbcMigrationDialect.values().forEach { dialect ->
            assertTrue(CapacityJdbcMigrations.location(dialect).contains("workflow/db/migration"))
            assertTrue(CapacityJdbcMigrations.resourcePath(dialect).endsWith("V039__persist_capacity_runtime.sql"))
        }
    }
}
