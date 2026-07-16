package ai.icen.fw.reliability.persistence.migration

import ai.icen.fw.reliability.persistence.jdbc.ReliabilityMySqlDatabaseSupport
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import java.sql.Connection
import java.sql.ResultSet
import java.util.Locale
import javax.sql.DataSource

/** Reliability-only V040+ migration boundary with an isolated history table and resource location. */
class ReliabilityFlywayMigrationRunner @JvmOverloads constructor(
    private val dataSource: DataSource,
    schema: String? = null,
) {
    private val configuredSchema = schema?.let(::safeSchema)

    fun migrate(): Int {
        val target = resolveTarget()
        val flyway = configuredFlyway(target)
        if (!historyExists(target)) {
            requireNoUntrackedOwnedTables(target)
            try {
                flyway.baseline()
            } catch (failure: FlywayException) {
                if (!historyExists(target)) throw failure
            }
        }
        val count = flyway.migrate().migrations.count { migration ->
            migration.category == "Versioned" || migration.category == "Repeatable"
        }
        verifyNamespace(target)
        return count
    }

    fun validate() {
        val target = resolveTarget()
        check(historyExists(target)) {
            "Reliability migration validation requires ${target.schema}.$HISTORY_TABLE; run migrate first."
        }
        val flyway = configuredFlyway(target)
        flyway.validate()
        check(flyway.info().pending().isEmpty()) { "Reliability migration validation found pending V040+ migrations." }
        verifyNamespace(target)
    }

    private fun configuredFlyway(target: DatabaseTarget): Flyway {
        val flywayDataSource = if (target.product == DatabaseProduct.KINGBASE) {
            ReliabilityKingbaseFlywayDataSource(dataSource)
        } else {
            dataSource
        }
        return Flyway.configure()
            .dataSource(flywayDataSource)
            .locations(location(target.product))
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .table(HISTORY_TABLE)
            .defaultSchema(target.schema)
            .schemas(target.schema)
            .createSchemas(false)
            .baselineOnMigrate(false)
            .baselineVersion(BASELINE_VERSION)
            .baselineDescription("FlowWeft Reliability V040 namespace")
            .validateOnMigrate(true)
            .load()
    }

    private fun resolveTarget(): DatabaseTarget = dataSource.connection.use { connection ->
            val product = detectProduct(connection)
            val sql = if (product == DatabaseProduct.MYSQL) "SELECT DATABASE()" else "SELECT current_schema()"
            val current = connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    check(result.next()) { "Reliability DataSource did not expose a current schema." }
                    result.getString(1)?.let(::safeSchema)
                }
            }
            val actual = checkNotNull(current) { "Reliability migration requires a non-null current schema." }
            check(configuredSchema == null || configuredSchema == actual) {
                "Configured Reliability schema '$configuredSchema' does not equal DataSource current schema '$actual'."
            }
            DatabaseTarget(product, actual, if (product == DatabaseProduct.MYSQL) actual else connection.catalog)
        }

    private fun historyExists(target: DatabaseTarget): Boolean = dataSource.connection.use { connection ->
        tableExists(connection, target, HISTORY_TABLE)
    }

    private fun requireNoUntrackedOwnedTables(target: DatabaseTarget) {
        val present = dataSource.connection.use { connection ->
            ownedTables(connection, target).intersect(OWNED_TABLES)
        }
        // Another installer may have created the dedicated history and migrated the namespace
        // after our first history probe. In that case Flyway owns these tables and its lock plus
        // validation remain authoritative; only a still-untracked namespace is rejected.
        check(present.isEmpty() || historyExists(target)) {
            "Reliability migration history is absent but managed tables already exist: ${present.sorted().joinToString()}."
        }
    }

    private fun verifyNamespace(target: DatabaseTarget) {
        dataSource.connection.use { connection ->
            val present = ownedTables(connection, target)
            val missing = OWNED_TABLES - present
            check(missing.isEmpty()) {
                "Reliability migration namespace is incomplete; missing ${missing.sorted().joinToString()}."
            }
            val rows = historyRows(connection, target)
            check(rows.any { row -> row.version == BASELINE_VERSION && row.type == "BASELINE" && row.success }) {
                "Reliability migration history lacks its V039 baseline sentinel."
            }
            rows.forEach { row ->
                check(row.success) { "Reliability migration history contains an unsuccessful row." }
                val numeric = row.version?.toIntOrNull()
                check(numeric == null || numeric >= FIRST_RELIABILITY_VERSION ||
                    numeric == BASELINE_VERSION.toInt() && row.type == "BASELINE"
                ) {
                    "Reliability migration history contains a pre-V040 non-baseline migration."
                }
            }
        }
    }

    private fun ownedTables(connection: Connection, target: DatabaseTarget): Set<String> {
        val tables = linkedSetOf<String>()
        tableResults(connection, target, "%").use { result ->
            while (result.next()) {
                val name = result.getString("TABLE_NAME")?.lowercase(Locale.ROOT)
                if (name != null) tables += name
            }
        }
        return tables
    }

    private fun tableExists(connection: Connection, target: DatabaseTarget, table: String): Boolean {
        val candidates = listOf(table, table.uppercase(Locale.ROOT), table.lowercase(Locale.ROOT))
        return candidates.any { candidate ->
            tableResults(connection, target, candidate).use { result -> result.next() }
        }
    }

    private fun tableResults(connection: Connection, target: DatabaseTarget, pattern: String): ResultSet =
        if (target.product == DatabaseProduct.MYSQL) {
            connection.metaData.getTables(target.catalog, null, pattern, null)
        } else {
            connection.metaData.getTables(target.catalog, target.schema, pattern, null)
        }

    private fun historyRows(connection: Connection, target: DatabaseTarget): List<HistoryRow> {
        val quote = connection.metaData.identifierQuoteString?.trim().orEmpty()
        val table = if (quote.isEmpty()) HISTORY_TABLE else "$quote$HISTORY_TABLE$quote"
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version, type, success FROM $table").use { result ->
                ArrayList<HistoryRow>().also { rows ->
                    while (result.next()) {
                        rows += HistoryRow(
                            result.getString("version"),
                            result.getString("type").uppercase(Locale.ROOT),
                            result.getBoolean("success"),
                        )
                    }
                }
            }
        }
    }

    private fun safeSchema(value: String): String {
        require(SAFE_SCHEMA.matches(value)) { "Reliability migration schema must be a simple non-empty SQL identifier." }
        return value
    }

    enum class DatabaseProduct { POSTGRESQL, MYSQL, KINGBASE }

    private class DatabaseTarget(
        val product: DatabaseProduct,
        val schema: String,
        val catalog: String?,
    )

    private class HistoryRow(val version: String?, val type: String, val success: Boolean)

    companion object {
        const val HISTORY_TABLE: String = "flowweft_reliability_schema_history"
        const val BASELINE_VERSION: String = "39"
        const val FIRST_RELIABILITY_VERSION: Int = 40
        private val SAFE_SCHEMA = Regex("[A-Za-z_][A-Za-z0-9_$]*")
        private val OWNED_TABLES = setOf(
            "fw_reliability_run",
            "fw_reliability_provider_attempt",
            "fw_reliability_provider_receipt",
            "fw_reliability_outbox",
            "fw_reliability_slo_schedule",
            "fw_reliability_slo_evaluation",
        )

        @JvmStatic
        fun detectProduct(connection: Connection): DatabaseProduct {
            val product = connection.metaData.databaseProductName
            return when {
                product.equals("PostgreSQL", ignoreCase = true) -> DatabaseProduct.POSTGRESQL
                product.equals("MySQL", ignoreCase = true) -> {
                    ReliabilityMySqlDatabaseSupport.requireSupported(connection.metaData)
                    DatabaseProduct.MYSQL
                }
                product.startsWith("Kingbase", ignoreCase = true) -> DatabaseProduct.KINGBASE
                else -> throw IllegalStateException(
                    "Unsupported Reliability database '$product'; expected PostgreSQL, MySQL 8 or KingbaseES.",
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
