package ai.icen.fw.persistence.migration

import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.assertFailsWith

class FlywayMigrationRunnerTest {
    private val dataSource = PGSimpleDataSource()

    @Test
    fun `accepts bounded diagnostic-safe PostgreSQL schema identifiers`() {
        FlywayMigrationRunner(dataSource, "public", false)
        FlywayMigrationRunner(dataSource, "文件_存储", false)
        FlywayMigrationRunner(dataSource, "tenant-schema", false)
        FlywayMigrationRunner(dataSource, "a".repeat(63), false)
    }

    @Test
    fun `rejects schema identifiers that are ambiguous unsafe or truncated by PostgreSQL`() {
        listOf(
            "",
            " public",
            "public\u00a0",
            "tenant\nschema",
            "tenant\u200bschema",
            "a".repeat(64),
            "文".repeat(22),
            String(charArrayOf('\uD800')),
        ).forEach { schema ->
            assertFailsWith<IllegalArgumentException> {
                FlywayMigrationRunner(dataSource, schema, false)
            }
        }
    }

    @Test
    fun `requires explicit schema when schema creation is enabled`() {
        assertFailsWith<IllegalArgumentException> {
            FlywayMigrationRunner(dataSource, null, true)
        }
    }
}
