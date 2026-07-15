package ai.icen.fw.migration.cli

import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.DriverManager
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

abstract class FlowWeftMigrationRealDatabaseContract {
    protected abstract val enabledEnvironmentVariable: String
    protected abstract fun createTarget(namespace: String): MigrationTarget

    @Test
    fun `fresh all-line migrate is idempotent validates and fails closed on corrupt history`() {
        requireEnabledLane()
        withTarget("fresh") { target ->
            val migrate = invoke(target, mode = "migrate", lines = "all", createSchema = target.cliCreatesSchema)
            assertEquals(FlowWeftMigrationExitCode.SUCCESS, migrate.code)
            assertTrue(lineExecuted(migrate, "legacy") > 0, migrate.output)
            assertTrue(lineExecuted(migrate, "workflow") > 0, migrate.output)
            assertSafe(migrate, target)

            target.connection().use { connection ->
                assertTrue(tableExists(connection, target.namespace, "fw_document"))
                assertTrue(tableExists(connection, target.namespace, "fw_wf_instance"))
                assertTrue(tableExists(connection, target.namespace, "fileweft_schema_history"))
                assertTrue(tableExists(connection, target.namespace, "flowweft_workflow_schema_history"))
                assertFalse(tableExists(connection, target.namespace, "flyway_schema_history"))
            }

            val secondMigrate = invoke(target, mode = "migrate", lines = "all", createSchema = false)
            assertEquals(FlowWeftMigrationExitCode.SUCCESS, secondMigrate.code)
            assertEquals(0, lineExecuted(secondMigrate, "legacy"))
            assertEquals(0, lineExecuted(secondMigrate, "workflow"))

            val validate = invoke(target, mode = "validate", lines = "all", createSchema = false)
            assertEquals(FlowWeftMigrationExitCode.SUCCESS, validate.code)
            assertTrue(validate.output.contains("\"migrationsExecuted\":0"))
            assertSafe(validate, target)

            target.connection().use { connection -> corruptWorkflowHistory(connection, target.namespace) }
            val failed = invoke(target, mode = "validate", lines = "all", createSchema = false)
            assertEquals(FlowWeftMigrationExitCode.MIGRATION_FAILED, failed.code)
            assertTrue(failed.error.contains("\"failedLine\":\"workflow\""), failed.error)
            assertSafe(failed, target)
        }
    }

    @Test
    fun `workflow-only fresh install owns only its tables and history`() {
        requireEnabledLane()
        withTarget("workflow") { target ->
            target.ensureNamespace()

            val migrate = invoke(target, mode = "migrate", lines = "workflow", createSchema = false)
            assertEquals(FlowWeftMigrationExitCode.SUCCESS, migrate.code)
            assertTrue(lineExecuted(migrate, "workflow") > 0, migrate.output)
            assertSafe(migrate, target)

            target.connection().use { connection ->
                assertTrue(tableExists(connection, target.namespace, "fw_wf_instance"))
                assertTrue(tableExists(connection, target.namespace, "flowweft_workflow_schema_history"))
                assertFalse(tableExists(connection, target.namespace, "fileweft_schema_history"))
                assertFalse(tableExists(connection, target.namespace, "flyway_schema_history"))
                assertFalse(tableExists(connection, target.namespace, "fw_document"))
                assertFalse(tableExists(connection, target.namespace, "fw_agent_result"))
            }

            val validate = invoke(target, mode = "validate", lines = "workflow", createSchema = false)
            assertEquals(FlowWeftMigrationExitCode.SUCCESS, validate.code)
            assertSafe(validate, target)
        }
    }

    @Test
    fun `legacy 0_0_3 schema upgrades forward before workflow line`() {
        requireEnabledLane()
        withTarget("upgrade") { target ->
            target.ensureNamespace()
            installLegacyVersion29(target)

            target.connection().use { connection ->
                assertTrue(historyHasVersion(connection, target.namespace, "fileweft_schema_history", "029"))
                assertFalse(tableExists(connection, target.namespace, "flowweft_workflow_schema_history"))
            }

            val migrate = invoke(target, mode = "migrate", lines = "all", createSchema = false)
            assertEquals(FlowWeftMigrationExitCode.SUCCESS, migrate.code)
            assertTrue(lineExecuted(migrate, "legacy") > 0, migrate.output)
            assertTrue(lineExecuted(migrate, "workflow") > 0, migrate.output)
            assertSafe(migrate, target)

            target.connection().use { connection ->
                assertTrue(historyHasVersion(connection, target.namespace, "fileweft_schema_history", "029"))
                assertTrue(tableExists(connection, target.namespace, "flowweft_workflow_schema_history"))
                assertTrue(tableExists(connection, target.namespace, "fw_document"))
                assertTrue(tableExists(connection, target.namespace, "fw_wf_instance"))
                assertFalse(tableExists(connection, target.namespace, "flyway_schema_history"))
            }

            val validate = invoke(target, mode = "validate", lines = "all", createSchema = false)
            assertEquals(FlowWeftMigrationExitCode.SUCCESS, validate.code)
            assertSafe(validate, target)
        }
    }

    private fun requireEnabledLane() {
        check(System.getenv(enabledEnvironmentVariable) == "true") {
            "Real migration CLI tests run only through their fail-closed Gradle task."
        }
    }

    private fun withTarget(purpose: String, block: (MigrationTarget) -> Unit) {
        val suffix = System.nanoTime().toString().replace('-', '0')
        createTarget("fw_cli_${purpose}_$suffix").use(block)
    }

    private fun invoke(
        target: MigrationTarget,
        mode: String,
        lines: String,
        createSchema: Boolean,
    ): CliResult {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val arguments = arrayOf(
            "--url=${target.url}",
            "--user=${target.user}",
            "--schema=${target.namespace}",
            "--mode=$mode",
            "--lines=$lines",
            "--create-schema=$createSchema",
        )
        val code = PrintStream(output, true, "UTF-8").use { out ->
            PrintStream(error, true, "UTF-8").use { err ->
                FlowWeftMigrationCli.execute(
                    arguments,
                    mapOf("FLOWWEFT_MIGRATION_JDBC_PASSWORD" to target.password),
                    out,
                    err,
                )
            }
        }
        return CliResult(code, output.toString("UTF-8"), error.toString("UTF-8"))
    }

    private fun lineExecuted(result: CliResult, line: String): Int {
        val match = Regex("\\\"$line\\\":(\\d+)").find(result.output)
        return checkNotNull(match) { "Missing $line result in ${result.output}" }.groupValues[1].toInt()
    }

    private fun assertSafe(result: CliResult, target: MigrationTarget) {
        val text = result.output + result.error
        assertFalse(text.contains(target.password))
        assertFalse(text.contains(target.url))
        assertFalse(text.contains("jdbc:"))
        assertFalse(text.contains("127.0.0.1"))
        assertFalse(text.contains("localhost"))
    }

    private fun tableExists(connection: Connection, namespace: String, table: String): Boolean = try {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT 1 FROM ${qualified(connection, namespace, table)} WHERE 1 = 0").use {
                true
            }
        }
    } catch (_: SQLException) {
        false
    }

    private fun historyHasVersion(
        connection: Connection,
        namespace: String,
        history: String,
        version: String,
    ): Boolean = connection.prepareStatement(
        "SELECT COUNT(*) FROM ${qualified(connection, namespace, history)} WHERE version = ? AND success = ?",
    ).use { statement ->
        statement.setString(1, version)
        statement.setBoolean(2, true)
        statement.executeQuery().use { rows ->
            rows.next() && rows.getInt(1) == 1
        }
    }

    private fun corruptWorkflowHistory(connection: Connection, namespace: String) {
        val history = qualified(connection, namespace, "flowweft_workflow_schema_history")
        connection.createStatement().use { statement ->
            val changed = statement.executeUpdate(
                "UPDATE $history SET checksum = 0 WHERE script = 'V030__create_flowweft_workflow_runtime.sql'",
            )
            check(changed == 1) { "Expected exactly one workflow V030 history row." }
        }
    }

    private fun installLegacyVersion29(target: MigrationTarget) {
        val rawDataSource = TestDriverManagerDataSource(target.url, target.user, target.password)
        val product = target.connection().use { connection ->
            FlywayMigrationRunner.detectDatabaseProduct(connection)
        }
        val dataSource = if (product == FlywayMigrationRunner.DatabaseProduct.KINGBASE) {
            PostgreSqlMetadataDataSource(rawDataSource)
        } else {
            rawDataSource
        }
        val configuration = Class.forName("org.flywaydb.core.Flyway")
            .getMethod("configure")
            .invoke(null)
        configure(configuration, "dataSource", DataSource::class.java, dataSource)
        configure(
            configuration,
            "locations",
            Array<String>::class.java,
            arrayOf(FlywayMigrationRunner.migrationLocation(product)),
        )
        configure(configuration, "failOnMissingLocations", Boolean::class.javaPrimitiveType!!, true)
        configure(configuration, "validateMigrationNaming", Boolean::class.javaPrimitiveType!!, true)
        configure(configuration, "table", String::class.java, FlywayMigrationRunner.HISTORY_TABLE)
        configure(configuration, "defaultSchema", String::class.java, target.namespace)
        configure(configuration, "schemas", Array<String>::class.java, arrayOf(target.namespace))
        configure(configuration, "createSchemas", Boolean::class.javaPrimitiveType!!, false)
        configure(configuration, "baselineOnMigrate", Boolean::class.javaPrimitiveType!!, false)
        configure(configuration, "baselineVersion", String::class.java, "0")
        configure(configuration, "baselineDescription", String::class.java, "FileWeft namespace initialization")
        configure(configuration, "target", String::class.java, "29")
        configure(configuration, "validateOnMigrate", Boolean::class.javaPrimitiveType!!, true)

        val flyway = configuration.javaClass.getMethod("load").invoke(configuration)
        flyway.javaClass.getMethod("baseline").invoke(flyway)
        flyway.javaClass.getMethod("migrate").invoke(flyway)
    }

    private fun configure(configuration: Any, name: String, type: Class<*>, value: Any) {
        configuration.javaClass.getMethod(name, type).invoke(configuration, value)
    }

    private fun qualified(connection: Connection, namespace: String, table: String): String {
        val quote = connection.metaData.identifierQuoteString.trim().ifEmpty { "\"" }
        fun quoted(value: String): String = quote + value.replace(quote, quote + quote) + quote
        return "${quoted(namespace)}.${quoted(table)}"
    }

    protected fun environment(name: String, fallback: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank) ?: fallback

    protected class MigrationTarget(
        val namespace: String,
        val url: String,
        val user: String,
        val password: String,
        val cliCreatesSchema: Boolean,
        private val namespaceCreator: () -> Unit,
        private val cleanup: () -> Unit,
    ) : AutoCloseable {
        private var namespaceCreated: Boolean = !cliCreatesSchema

        fun ensureNamespace() {
            if (!namespaceCreated) {
                namespaceCreator()
                namespaceCreated = true
            }
        }

        fun connection(): Connection = DriverManager.getConnection(url, user, password)
        override fun close() = cleanup()
    }

    private class CliResult(val code: Int, val output: String, val error: String)
}

class FlowWeftMigrationPostgresIntegrationTest : FlowWeftMigrationRealDatabaseContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_POSTGRES_TESTS"

    override fun createTarget(namespace: String): MigrationTarget {
        Class.forName("org.postgresql.Driver")
        val adminUrl = environment("FILEWEFT_POSTGRES_URL", "jdbc:postgresql://127.0.0.1:5432/fileweft")
        val user = environment("FILEWEFT_POSTGRES_USER", "fileweft")
        val password = environment("FILEWEFT_POSTGRES_PASSWORD", "fileweft-dev")
        val separator = if ('?' in adminUrl) '&' else '?'
        val targetUrl = "$adminUrl${separator}currentSchema=$namespace"
        val create = {
            DriverManager.getConnection(adminUrl, user, password).use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA $namespace") }
            }
            Unit
        }
        return MigrationTarget(namespace, targetUrl, user, password, true, create) {
            DriverManager.getConnection(adminUrl, user, password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $namespace CASCADE") }
            }
        }
    }
}

class FlowWeftMigrationKingbaseIntegrationTest : FlowWeftMigrationRealDatabaseContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_KINGBASE_TESTS"

    override fun createTarget(namespace: String): MigrationTarget {
        Class.forName(environment("FILEWEFT_KINGBASE_DRIVER", "com.kingbase8.Driver"))
        val adminUrl = environment("FILEWEFT_KINGBASE_URL", "jdbc:kingbase8://127.0.0.1:54321/test")
        val user = environment("FILEWEFT_KINGBASE_USER", "system")
        val password = environment("FILEWEFT_KINGBASE_PASSWORD", "kingbase")
        val separator = if ('?' in adminUrl) '&' else '?'
        val targetUrl = "$adminUrl${separator}currentSchema=$namespace"
        val create = {
            DriverManager.getConnection(adminUrl, user, password).use { connection ->
                connection.createStatement().use { it.execute("CREATE SCHEMA $namespace") }
            }
            Unit
        }
        return MigrationTarget(namespace, targetUrl, user, password, true, create) {
            DriverManager.getConnection(adminUrl, user, password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $namespace CASCADE") }
            }
        }
    }
}

class FlowWeftMigrationMySQLIntegrationTest : FlowWeftMigrationRealDatabaseContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_MYSQL_TESTS"

    override fun createTarget(namespace: String): MigrationTarget {
        Class.forName("com.mysql.cj.jdbc.Driver")
        val adminUrl = environment(
            "FILEWEFT_MYSQL_ADMIN_URL",
            "jdbc:mysql://127.0.0.1:3306?useSSL=false&allowPublicKeyRetrieval=true",
        )
        val user = environment("FILEWEFT_MYSQL_ADMIN_USER", "root")
        val password = environment("FILEWEFT_MYSQL_ADMIN_PASSWORD", "fileweft-dev")
        DriverManager.getConnection(adminUrl, user, password).use { connection ->
            connection.createStatement().use {
                it.execute("CREATE DATABASE $namespace CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin")
            }
        }
        val query = adminUrl.indexOf('?')
        val base = if (query >= 0) adminUrl.substring(0, query) else adminUrl
        val suffix = if (query >= 0) adminUrl.substring(query) else ""
        val slash = base.indexOf('/', "jdbc:mysql://".length)
        val server = if (slash >= 0) base.substring(0, slash) else base
        val targetUrl = "${server.trimEnd('/')}/$namespace$suffix"
        return MigrationTarget(namespace, targetUrl, user, password, false, {}) {
            DriverManager.getConnection(adminUrl, user, password).use { connection ->
                connection.createStatement().use { it.execute("DROP DATABASE IF EXISTS $namespace") }
            }
        }
    }
}

private class TestDriverManagerDataSource(
    private val url: String,
    private val user: String,
    private val password: String,
) : DataSource {
    override fun getConnection(): Connection = DriverManager.getConnection(url, user, password)
    override fun getConnection(username: String, password: String): Connection =
        DriverManager.getConnection(url, username, password)
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = Logger.getLogger("ai.icen.fw.migration.cli.test")
    override fun <T : Any?> unwrap(iface: Class<T>): T {
        if (iface.isInstance(this)) return iface.cast(this)
        throw SQLException("Not a wrapper")
    }
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}

private class PostgreSqlMetadataDataSource(private val delegate: DataSource) : DataSource {
    override fun getConnection(): Connection = delegate.connection.asPostgreSql()
    override fun getConnection(username: String, password: String): Connection =
        delegate.getConnection(username, password).asPostgreSql()
    override fun getLogWriter(): PrintWriter? = delegate.logWriter
    override fun setLogWriter(out: PrintWriter?) { delegate.logWriter = out }
    override fun setLoginTimeout(seconds: Int) { delegate.loginTimeout = seconds }
    override fun getLoginTimeout(): Int = delegate.loginTimeout
    override fun getParentLogger(): Logger = delegate.parentLogger
    override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)
    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)

    private fun Connection.asPostgreSql(): Connection {
        val connection = this
        return Proxy.newProxyInstance(
            PostgreSqlMetadataDataSource::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            if (method.name == "getMetaData" && method.parameterCount == 0) {
                connection.metaData.asPostgreSql()
            } else {
                method.invokeUnwrapping(connection, arguments)
            }
        } as Connection
    }

    private fun DatabaseMetaData.asPostgreSql(): DatabaseMetaData {
        val metadata = this
        return Proxy.newProxyInstance(
            PostgreSqlMetadataDataSource::class.java.classLoader,
            arrayOf(DatabaseMetaData::class.java),
        ) { _, method, arguments ->
            if (method.name == "getDatabaseProductName" && method.parameterCount == 0) {
                "PostgreSQL"
            } else {
                method.invokeUnwrapping(metadata, arguments)
            }
        } as DatabaseMetaData
    }

    private fun Method.invokeUnwrapping(target: Any, arguments: Array<out Any?>?): Any? = try {
        invoke(target, *(arguments ?: emptyArray()))
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}
