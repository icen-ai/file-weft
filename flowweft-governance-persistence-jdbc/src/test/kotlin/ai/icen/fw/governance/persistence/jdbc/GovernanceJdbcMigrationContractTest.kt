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
    fun `all dialects ship the same tenant partitioned V042 target ledger schema`() {
        val expectedTables = setOf(
            "fw_governance_deletion_target_manifest",
            "fw_governance_deletion_item_operation",
        )
        val canonicalSchemas = GovernanceJdbcMigrationDialect.values().associateWith { dialect ->
            val resource = javaClass.getResource(dialect.targetLedgerResourcePath)
            assertNotNull(resource, dialect.name)
            val sql = resource.readText(Charsets.UTF_8)
            val tables = Regex("CREATE TABLE\\s+([a-z0-9_]+)", RegexOption.IGNORE_CASE)
                .findAll(sql).map { it.groupValues[1].lowercase() }.toSet()
            assertEquals(expectedTables, tables, dialect.name)
            listOf(
                "tenant_id",
                "preparation_digest",
                "planning_request_digest",
                "planning_identity_digest",
                "target_reference_digest",
                "manifest_digest",
                "operation_key_digest",
                "item_binding_digest",
                "operation_version",
                "state_digest",
            ).forEach { column -> assertTrue(sql.contains(column, ignoreCase = true), "$column ${dialect.name}") }
            assertTrue(
                sql.contains("OCTET_LENGTH(manifest_memento) BETWEEN 1 AND 4194304", ignoreCase = true),
                dialect.name,
            )
            assertTrue(
                sql.contains("OCTET_LENGTH(operation_memento) BETWEEN 1 AND 4194304", ignoreCase = true),
                dialect.name,
            )
            assertTrue(sql.contains(
                "FOREIGN KEY (tenant_id, preparation_digest)", ignoreCase = true,
            ), dialect.name)
            assertTrue(sql.contains(
                "updated_time = created_time", ignoreCase = true,
            ), dialect.name)
            listOf("prepared", "started", "verified-absent", "outcome-unknown", "permanent-failure")
                .forEach { status -> assertTrue(sql.contains("'$status'"), "$status ${dialect.name}") }
            assertFalse(sql.contains("object_key", ignoreCase = true), dialect.name)
            assertFalse(sql.contains("signed_url", ignoreCase = true), dialect.name)
            assertFalse(sql.contains("token", ignoreCase = true), dialect.name)
            if (dialect == GovernanceJdbcMigrationDialect.MYSQL) {
                assertTrue(sql.contains("tenant_id                   varchar(512) CHARACTER SET utf8mb4 " +
                    "COLLATE utf8mb4_bin", ignoreCase = true))
            }
            canonicalV042(sql)
        }
        assertEquals(1, canonicalSchemas.values.toSet().size, "V042 dialect schemas diverged")
    }

    @Test
    fun `migration stays in the standard history and JDBC SQL retains tenant CAS fences`() {
        GovernanceJdbcMigrationDialect.values().forEach { dialect ->
            assertTrue(GovernanceJdbcMigrations.location(dialect).contains("workflow/db/migration"))
            assertTrue(dialect.resourcePath.endsWith("V041__persist_governance_runtime.sql"))
            assertTrue(dialect.targetLedgerResourcePath.endsWith(
                "V042__persist_governance_deletion_targets.sql",
            ))
            assertEquals(
                listOf(dialect.resourcePath, dialect.targetLedgerResourcePath),
                GovernanceJdbcMigrations.resourcePaths(dialect),
            )
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

        val targetRelative =
            "src/main/kotlin/ai/icen/fw/governance/persistence/jdbc/JdbcGovernanceDeletionTargetLedger.kt"
        val targetSourceFile = assertNotNull(listOf(
            java.io.File(targetRelative),
            java.io.File("flowweft-governance-persistence-jdbc/$targetRelative"),
        ).firstOrNull { it.isFile })
        val targetSource = targetSourceFile.readText(Charsets.UTF_8)
        assertTrue(targetSource.contains("WHERE tenant_id = ? AND preparation_digest = ?"))
        assertTrue(targetSource.contains("WHERE tenant_id = ? AND operation_key_digest = ?"))
        assertTrue(targetSource.contains("AND operation_version = ? AND state_digest = ?"))
        assertTrue(targetSource.contains("operation.binding.bindingDigest == binding.bindingDigest"))
        assertTrue(targetSource.contains("OCTET_LENGTH(manifest_memento) AS manifest_memento_size"))
        assertTrue(targetSource.contains("OCTET_LENGTH(operation_memento) AS operation_memento_size"))
        assertFalse(targetSource.contains("ObjectInputStream"))
        assertFalse(targetSource.contains("Serializable"))
    }

    private fun canonicalV042(sql: String): String = sql
        .replace(
            Regex("\\s+CHARACTER\\s+SET\\s+utf8mb4\\s+COLLATE\\s+utf8mb4_bin", RegexOption.IGNORE_CASE),
            "",
        )
        .replace(Regex("longblob", RegexOption.IGNORE_CASE), "bytea")
        .replace(Regex("\\)\\s+ENGINE\\s*=\\s*InnoDB\\s*;", RegexOption.IGNORE_CASE), ");")
        .replace(Regex("\\s+"), " ")
        .trim()
}
