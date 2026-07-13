package ai.icen.fw.persistence.migration

import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import kotlin.test.assertFailsWith

class FlywayMigrationRunnerTest {
    private val dataSource = PGSimpleDataSource()

    @Test
    fun `construction accepts diagnostic-safe identifiers without opening the database`() {
        FlywayMigrationRunner(dataSource, "public", false)
        FlywayMigrationRunner(dataSource, "文件_存储", false)
        FlywayMigrationRunner(dataSource, "tenant-schema", false)
        FlywayMigrationRunner(dataSource, "a".repeat(65), false)
        FlywayMigrationRunner(dataSource, "文".repeat(64), false)
    }

    @Test
    fun `construction rejects schema identifiers that are ambiguous or unsafe`() {
        listOf(
            "",
            " public",
            "public\u00a0",
            "tenant\nschema",
            "tenant\u200bschema",
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
