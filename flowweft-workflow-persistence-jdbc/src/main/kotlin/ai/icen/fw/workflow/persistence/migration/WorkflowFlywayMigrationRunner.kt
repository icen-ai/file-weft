package ai.icen.fw.workflow.persistence.migration

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.sql.Connection
import java.util.Locale
import javax.sql.DataSource

/**
 * Workflow-only migrate/validate boundary. It scans only Workflow-owned V030+ resources and uses
 * [HISTORY_TABLE], so installing this JAR cannot create or mutate legacy file tables/history.
 */
class WorkflowFlywayMigrationRunner @JvmOverloads constructor(
    private val dataSource: DataSource,
    schema: String? = null,
) {
    private val configuredSchema = schema?.let(::safeSchema)

    fun migrate(): Int {
        val target = resolveSchema()
        val flyway = configuredFlyway(target)
        if (!historyExists(target)) {
            try {
                flyway.baseline()
            } catch (failure: FlywayException) {
                // A concurrent installer may have created the exact dedicated history first.
                if (!historyExists(target)) throw failure
            }
        }
        val result = flyway.migrate()
        return result.migrations.count { migration ->
            migration.category == "Versioned" || migration.category == "Repeatable"
        }
    }

    fun validate() {
        val target = resolveSchema()
        check(historyExists(target)) {
            "Workflow migration validation requires $target.$HISTORY_TABLE; run migrate first."
        }
        val flyway = configuredFlyway(target)
        flyway.validate()
        check(flyway.info().pending().isEmpty()) {
            "Workflow migration validation found pending V030+ migrations."
        }
    }

    private fun configuredFlyway(schema: String): Flyway {
        val product = dataSource.connection.use(::detectProduct)
        val flywayDataSource = if (product == DatabaseProduct.KINGBASE) {
            WorkflowKingbaseFlywayDataSource(dataSource)
        } else {
            dataSource
        }
        return Flyway.configure()
            .dataSource(flywayDataSource)
            .locations(location(product))
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .table(HISTORY_TABLE)
            .defaultSchema(schema)
            .schemas(schema)
            .createSchemas(false)
            .baselineOnMigrate(false)
            .baselineVersion("29")
            .baselineDescription("FlowWeft Workflow V030 namespace")
            .validateOnMigrate(true)
            .load()
    }

    private fun resolveSchema(): String {
        val current = dataSource.connection.use { connection ->
            val product = detectProduct(connection)
            val sql = if (product == DatabaseProduct.MYSQL) "SELECT DATABASE()" else "SELECT current_schema()"
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next()) { "Workflow DataSource did not expose a current schema." }
                    result.getString(1)?.let(::safeSchema)
                }
            }
        }
        val actual = checkNotNull(current) { "Workflow migration requires a non-null current schema." }
        check(configuredSchema == null || configuredSchema == actual) {
            "Configured workflow schema '$configuredSchema' does not equal DataSource current schema '$actual'."
        }
        return actual
    }

    private fun historyExists(schema: String): Boolean = dataSource.connection.use { connection ->
        val candidates = listOf(schema, schema.uppercase(Locale.ROOT), schema.lowercase(Locale.ROOT))
        candidates.any { candidate ->
            connection.metaData.getTables(null, candidate, HISTORY_TABLE, arrayOf("TABLE")).use { it.next() }
        }
    }

    private fun safeSchema(value: String): String {
        require(SAFE_SCHEMA.matches(value)) {
            "Workflow migration schema must be a simple non-empty SQL identifier."
        }
        return value
    }

    enum class DatabaseProduct { POSTGRESQL, MYSQL, KINGBASE }

    companion object {
        const val HISTORY_TABLE: String = "flowweft_workflow_schema_history"
        private val SAFE_SCHEMA = Regex("[A-Za-z_][A-Za-z0-9_$]*")

        @JvmStatic
        fun detectProduct(connection: Connection): DatabaseProduct {
            val product = connection.metaData.databaseProductName
            return when {
                product.equals("PostgreSQL", ignoreCase = true) -> DatabaseProduct.POSTGRESQL
                product.equals("MySQL", ignoreCase = true) -> {
                    val major = connection.metaData.databaseMajorVersion
                    check(major >= 8) { "FlowWeft Workflow requires MySQL 8 or newer." }
                    DatabaseProduct.MYSQL
                }
                product.startsWith("Kingbase", ignoreCase = true) -> DatabaseProduct.KINGBASE
                else -> throw IllegalStateException(
                    "Unsupported workflow database '$product'; expected PostgreSQL, MySQL 8 or KingbaseES.",
                )
            }
        }

        @JvmStatic
        fun location(product: DatabaseProduct): String = when (product) {
            DatabaseProduct.POSTGRESQL -> "classpath:ai/icen/fw/workflow/db/migration/postgres"
            DatabaseProduct.MYSQL -> "classpath:ai/icen/fw/workflow/db/migration/mysql"
            DatabaseProduct.KINGBASE -> "classpath:ai/icen/fw/workflow/db/migration/kingbase"
        }
    }
}
