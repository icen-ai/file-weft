package ai.icen.fw.governance.persistence.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GovernanceJdbcMigrationContractTest {
    @Test
    fun `all dialects ship the same tenant partitioned V041 schema`() {
        val expectedTables = setOf(
            "fw_governance_deletion_run",
            "fw_governance_deletion_outbox",
        )
        GovernanceJdbcMigrationDialect.values().forEach { dialect ->
            val resource = javaClass.getResource(dialect.resourcePath)
            assertNotNull(resource, dialect.name)
            val sql = resource.readText(Charsets.UTF_8)
            val tables = Regex("CREATE TABLE\\s+([a-z0-9_]+)", RegexOption.IGNORE_CASE)
                .findAll(sql).map { it.groupValues[1].lowercase() }.toSet()
            assertEquals(expectedTables, tables, dialect.name)
            assertTrue(sql.contains("tenant_id", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("plan_id_digest", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("record_id_digest", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("idempotency_digest", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("fencing_token", ignoreCase = true), dialect.name)
            assertTrue(sql.contains("run_memento_digest", ignoreCase = true), dialect.name)
            assertTrue(
                sql.contains("OCTET_LENGTH(run_memento) BETWEEN 1 AND 4194304", ignoreCase = true),
                dialect.name,
            )
            if (dialect == GovernanceJdbcMigrationDialect.MYSQL) {
                assertTrue(sql.contains("tenant_id               varchar(512) CHARACTER SET utf8mb4 " +
                    "COLLATE utf8mb4_bin", ignoreCase = true))
            }
            assertFalse(sql.contains("H2", ignoreCase = true), dialect.name)
        }
    }

    @Test
    fun `migration stays in the standard history and JDBC SQL retains tenant CAS fences`() {
        GovernanceJdbcMigrationDialect.values().forEach { dialect ->
            assertTrue(GovernanceJdbcMigrations.location(dialect).contains("workflow/db/migration"))
            assertTrue(dialect.resourcePath.endsWith("V041__persist_governance_runtime.sql"))
        }

        val relative = "src/main/kotlin/ai/icen/fw/governance/persistence/jdbc/JdbcGovernancePersistence.kt"
        val sourceFile = assertNotNull(listOf(
            java.io.File(relative),
            java.io.File("flowweft-governance-persistence-jdbc/$relative"),
        ).firstOrNull { it.isFile })
        val source = sourceFile.readText(Charsets.UTF_8)
        assertTrue(source.contains("WHERE tenant_id = ? AND plan_id_digest = ?"))
        assertTrue(source.contains("AND fencing_token = ?"))
        assertTrue(source.contains("AND record_digest = ? AND state_digest = ? AND run_version = ?"))
        assertTrue(source.contains("OCTET_LENGTH(run_memento) AS run_memento_size"))
        assertTrue(source.contains("current.idempotencyDigest != candidateIdempotencyDigest"))
        assertTrue(source.contains("sameImmutableBinding(current.run, candidate)"))
        assertTrue(source.contains("getBinaryStream(\"run_memento\")"))
        assertFalse(source.contains("ObjectInputStream"))
        assertFalse(source.contains("Serializable"))
    }
}
