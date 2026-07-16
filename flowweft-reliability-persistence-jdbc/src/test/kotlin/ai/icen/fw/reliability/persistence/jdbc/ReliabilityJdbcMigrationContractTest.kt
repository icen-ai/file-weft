package ai.icen.fw.reliability.persistence.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReliabilityJdbcMigrationContractTest {
    @Test
    fun `all production dialects ship the same tenant-partitioned V040 tables`() {
        val expected = setOf(
            "fw_reliability_run",
            "fw_reliability_provider_attempt",
            "fw_reliability_provider_receipt",
            "fw_reliability_outbox",
            "fw_reliability_slo_schedule",
            "fw_reliability_slo_evaluation",
        )
        ReliabilityJdbcMigrationDialect.values().forEach { dialect ->
            val resource = javaClass.getResource(dialect.resourcePath)
            assertNotNull(resource, dialect.name)
            val sql = resource.readText(Charsets.UTF_8)
            val tables = Regex("CREATE TABLE\\s+([a-z0-9_]+)", RegexOption.IGNORE_CASE)
                .findAll(sql).map { it.groupValues[1].lowercase() }.toSet()
            assertEquals(expected, tables, dialect.name)
            assertTrue(sql.contains("tenant_id", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("idempotency_digest", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("next_fencing_token", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("state_memento_digest", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("PRIMARY KEY (tenant_id, id)", ignoreCase = true), dialect.name)
            assertFalse(sql.contains("PRIMARY KEY (id)", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("OCTET_LENGTH(state_memento) BETWEEN 1 AND 8388608", true), dialect.name)
            assertFalse(sql.contains("H2", ignoreCase = true), dialect.name)
        }
    }

    @Test
    fun `migration remains in the standard workflow history after capacity V039`() {
        ReliabilityJdbcMigrationDialect.values().forEach { dialect ->
            assertTrue(ReliabilityJdbcMigrations.location(dialect).contains("workflow/db/migration"))
            assertTrue(
                ReliabilityJdbcMigrations.resourcePath(dialect)
                    .endsWith("V040__persist_reliability_runtime.sql"),
            )
        }
    }

    @Test
    fun `provider attempts receipts and SLO evaluations are append-only evidence`() {
        ReliabilityJdbcMigrationDialect.values().forEach { dialect ->
            val sql = requireNotNull(javaClass.getResource(dialect.resourcePath)).readText(Charsets.UTF_8)
            assertTrue(sql.contains("UNIQUE (tenant_id, attempt_digest)"), dialect.name)
            assertTrue(sql.contains("UNIQUE (tenant_id, evidence_kind, evidence_digest)"), dialect.name)
            assertTrue(sql.contains("UNIQUE (tenant_id, evaluation_digest, alert_digest)"), dialect.name)
            assertTrue(sql.contains("(tenant_id, run_id)"), dialect.name)
            assertTrue(sql.contains("(tenant_id, schedule_id, evaluated_time)"), dialect.name)
        }
    }

    @Test
    fun `mysql identity comparison is binary and its composite key remains bounded`() {
        val sql = requireNotNull(
            javaClass.getResource(ReliabilityJdbcMigrationDialect.MYSQL.resourcePath),
        ).readText(Charsets.UTF_8)
        assertTrue(sql.contains("tenant_id VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"))
        assertTrue(sql.contains("id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin"))
        assertFalse(sql.contains("ON DUPLICATE KEY", ignoreCase = true))
    }
}
