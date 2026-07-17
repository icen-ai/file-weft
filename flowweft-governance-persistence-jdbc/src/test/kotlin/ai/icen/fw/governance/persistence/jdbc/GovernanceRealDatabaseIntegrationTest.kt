package ai.icen.fw.governance.persistence.jdbc

import ai.icen.fw.governance.persistence.migration.GovernanceFlywayMigrationRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Locale
import java.util.logging.Logger
import javax.sql.DataSource

abstract class GovernanceRealDatabaseIntegrationContract {
    protected abstract val enabledEnvironmentVariable: String
    protected abstract val driverClassName: String
    protected abstract fun createIsolatedDatabase(namespace: String): IsolatedDatabase

    @Test
    fun `V040 baseline then V041 V042 migration is idempotent and owns the governance namespace`() {
        check(System.getenv(enabledEnvironmentVariable) == "true") {
            "Set $enabledEnvironmentVariable=true before running this isolated integration test."
        }
        Class.forName(driverClassName)
        val namespace = "fw_governance_${java.lang.Long.toUnsignedString(System.nanoTime(), 36)}"
        createIsolatedDatabase(namespace).use { isolated ->
            val runner = GovernanceFlywayMigrationRunner(isolated.dataSource, namespace)
            assertEquals(2, runner.migrate(), "first migrate should apply V041 and V042")
            runner.validate()

            isolated.dataSource.connection.use { connection ->
                val tables = tableNames(connection, namespace)
                val expected = listOf(
                    "fw_governance_deletion_run",
                    "fw_governance_deletion_outbox",
                    "fw_governance_deletion_target_manifest",
                    "fw_governance_deletion_item_operation",
                )
                expected.forEach { table ->
                    assertTrue(tables.contains(table), "migration must create $table")
                }
                val historyExists = connection.metaData.getTables(null, namespace, "%", null).use { result ->
                    generateSequence { if (result.next()) result.getString("TABLE_NAME") else null }
                        .map { it.lowercase(Locale.ROOT) }
                        .toList()
                        .contains(GovernanceFlywayMigrationRunner.HISTORY_TABLE)
                }
                assertTrue(historyExists, "dedicated governance history table must exist")
            }

            assertEquals(0, runner.migrate(), "second migrate must be idempotent and apply nothing")
            runner.validate()
        }
    }

    private fun tableNames(connection: Connection, schema: String): Set<String> {
        val names = linkedSetOf<String>()
        connection.metaData.getTables(null, schema, "%", null).use { result ->
            while (result.next()) {
                val name = result.getString("TABLE_NAME")?.lowercase(Locale.ROOT)
                if (name != null) names += name
            }
        }
        return names
    }
}

class GovernancePostgresIntegrationTest : GovernanceRealDatabaseIntegrationContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_POSTGRES_TESTS"
    override val driverClassName: String = "org.postgresql.Driver"

    override fun createIsolatedDatabase(namespace: String): IsolatedDatabase = createSchemaDatabase(
        environment("FILEWEFT_POSTGRES_URL", "jdbc:postgresql://localhost:5432/fileweft"),
        environment("FILEWEFT_POSTGRES_USER", "fileweft"),
        environment("FILEWEFT_POSTGRES_PASSWORD", "fileweft-dev"),
        namespace,
    )
}

class GovernanceKingbaseIntegrationTest : GovernanceRealDatabaseIntegrationContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_KINGBASE_TESTS"
    override val driverClassName: String = environment("FILEWEFT_KINGBASE_DRIVER", "com.kingbase8.Driver")

    override fun createIsolatedDatabase(namespace: String): IsolatedDatabase = createSchemaDatabase(
        environment("FILEWEFT_KINGBASE_URL", "jdbc:kingbase8://localhost:54321/test"),
        environment("FILEWEFT_KINGBASE_USER", "system"),
        environment("FILEWEFT_KINGBASE_PASSWORD", "kingbase"),
        namespace,
    )
}

class GovernanceMySQLIntegrationTest : GovernanceRealDatabaseIntegrationContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_MYSQL_TESTS"
    override val driverClassName: String = "com.mysql.cj.jdbc.Driver"

    override fun createIsolatedDatabase(namespace: String): IsolatedDatabase {
        val adminUrl = environment(
            "FILEWEFT_MYSQL_ADMIN_URL",
            "jdbc:mysql://localhost:3306?useSSL=false&allowPublicKeyRetrieval=true",
        )
        val user = environment("FILEWEFT_MYSQL_ADMIN_USER", "root")
        val password = environment("FILEWEFT_MYSQL_ADMIN_PASSWORD", "fileweft-dev")
        val admin = DriverManagerDataSource(adminUrl, user, password)
        admin.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE DATABASE $namespace CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin",
                )
            }
        }
        val database = DriverManagerDataSource(mysqlDatabaseUrl(adminUrl, namespace), user, password)
        return IsolatedDatabase(database) {
            admin.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP DATABASE IF EXISTS $namespace")
                }
            }
        }
    }

    private fun mysqlDatabaseUrl(adminUrl: String, database: String): String {
        val queryIndex = adminUrl.indexOf('?')
        val base = if (queryIndex >= 0) adminUrl.substring(0, queryIndex) else adminUrl
        val query = if (queryIndex >= 0) adminUrl.substring(queryIndex) else ""
        val hostEnd = base.indexOf('/', "jdbc:mysql://".length)
        val server = if (hostEnd >= 0) base.substring(0, hostEnd) else base
        return "${server.trimEnd('/')}/$database$query"
    }
}

private fun createSchemaDatabase(
    url: String,
    user: String,
    password: String,
    namespace: String,
): IsolatedDatabase {
    val admin = DriverManagerDataSource(url, user, password)
    admin.connection.use { connection ->
        connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $namespace") }
    }
    val scoped = SchemaDataSource(admin, namespace)
    return IsolatedDatabase(scoped) {
        admin.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute("DROP SCHEMA $namespace CASCADE") }
        }
    }
}

private fun environment(name: String, fallback: String): String =
    System.getenv(name)?.takeIf(String::isNotBlank) ?: fallback

class IsolatedDatabase(
    val dataSource: DataSource,
    private val cleanup: () -> Unit,
) : AutoCloseable {
    override fun close() = cleanup()
}

private class SchemaDataSource(
    private val delegate: DataSource,
    private val schema: String,
) : DataSource by delegate {
    override fun getConnection(): Connection = scoped(delegate.connection)
    override fun getConnection(username: String, password: String): Connection =
        scoped(delegate.getConnection(username, password))

    private fun scoped(connection: Connection): Connection = connection.also { value ->
        value.createStatement().use { statement -> statement.execute("SET search_path TO $schema") }
    }
}

private class DriverManagerDataSource(
    private val url: String,
    private val user: String,
    private val password: String,
) : DataSource {
    override fun getConnection(): Connection = DriverManager.getConnection(url, user, password)
    override fun getConnection(username: String, password: String): Connection =
        DriverManager.getConnection(url, username, password)
    override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()
    override fun setLogWriter(out: PrintWriter?) = DriverManager.setLogWriter(out)
    override fun setLoginTimeout(seconds: Int) = DriverManager.setLoginTimeout(seconds)
    override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()
    override fun getParentLogger(): Logger = Logger.getLogger("flowweft.governance.jdbc")
    override fun <T : Any?> unwrap(iface: Class<T>): T {
        if (iface.isInstance(this)) return iface.cast(this)
        throw SQLException("FlowWeft Governance test DataSource does not wrap ${iface.name}.")
    }
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
