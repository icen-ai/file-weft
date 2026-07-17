package ai.icen.fw.agent.persistence.jdbc

import ai.icen.fw.agent.persistence.migration.AgentFlywayMigrationRunner
import ai.icen.fw.agent.runtime.AgentRunCreateStatus
import ai.icen.fw.agent.runtime.AgentRunKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.sql.DataSource

abstract class AgentRealDatabaseIntegrationContract {
    protected abstract val enabledEnvironmentVariable: String
    protected abstract val driverClassName: String
    protected abstract fun createIsolatedDatabase(namespace: String): IsolatedDatabase

    @Test
    fun `concurrent V029 baseline then V030 V031 migration and both stores survive exact unicode identities`() {
        check(System.getenv(enabledEnvironmentVariable) == "true") {
            "Set $enabledEnvironmentVariable=true before running this isolated integration test."
        }
        Class.forName(driverClassName)
        val namespace = "fw_agent_${java.lang.Long.toUnsignedString(System.nanoTime(), 36)}"
        createIsolatedDatabase(namespace).use { isolated ->
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val migrations = (1..2).map {
                    executor.submit<Int> {
                        check(start.await(10, TimeUnit.SECONDS))
                        AgentFlywayMigrationRunner(isolated.dataSource, namespace).migrate()
                    }
                }
                start.countDown()
                migrations.forEach { future -> future.get(60, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
            AgentFlywayMigrationRunner(isolated.dataSource, namespace).validate()

            val generic = JdbcAgentDurableRunStore(isolated.dataSource)
            val upper = AgentDurableJdbcTestFixture.initial(
                runId = "运行-A",
                tenantId = "实库租户-A",
                idempotencyKey = "实库幂等-A",
            )
            val lower = AgentDurableJdbcTestFixture.initial(
                runId = "运行-a",
                tenantId = "实库租户-a",
                idempotencyKey = "实库幂等-a",
            )
            assertSame(AgentRunCreateStatus.CREATED, generic.create(upper).status)
            assertSame(AgentRunCreateStatus.CREATED, generic.create(lower).status)
            assertEquals("运行-A", generic.load(AgentRunKey(upper.state.tenantId, upper.state.runId))?.runId?.value)
            assertEquals("运行-a", generic.load(AgentRunKey(lower.state.tenantId, lower.state.runId))?.runId?.value)

            val evaluationStore = JdbcAgentEvaluationDurableStore(isolated.dataSource)
            val evaluation = AgentEvaluationJdbcTestFixture.initial(
                "评测-A",
                "实库评测租户-A",
                "评测幂等-A",
            )
            assertNotNull(evaluationStore.create(evaluation).state)
            assertEquals("评测-A", evaluationStore.load(evaluation.key())?.evaluationId?.value)
        }
    }
}

class AgentPostgresIntegrationTest : AgentRealDatabaseIntegrationContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_POSTGRES_TESTS"
    override val driverClassName: String = "org.postgresql.Driver"

    override fun createIsolatedDatabase(namespace: String): IsolatedDatabase = createSchemaDatabase(
        environment("FILEWEFT_POSTGRES_URL", "jdbc:postgresql://localhost:5432/fileweft"),
        environment("FILEWEFT_POSTGRES_USER", "fileweft"),
        environment("FILEWEFT_POSTGRES_PASSWORD", "fileweft-dev"),
        namespace,
    )
}

class AgentKingbaseIntegrationTest : AgentRealDatabaseIntegrationContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_KINGBASE_TESTS"
    override val driverClassName: String = environment("FILEWEFT_KINGBASE_DRIVER", "com.kingbase8.Driver")

    override fun createIsolatedDatabase(namespace: String): IsolatedDatabase = createSchemaDatabase(
        environment("FILEWEFT_KINGBASE_URL", "jdbc:kingbase8://localhost:54321/test"),
        environment("FILEWEFT_KINGBASE_USER", "system"),
        environment("FILEWEFT_KINGBASE_PASSWORD", "kingbase"),
        namespace,
    )
}

class AgentMySQLIntegrationTest : AgentRealDatabaseIntegrationContract() {
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
    override fun getParentLogger(): Logger = Logger.getLogger("flowweft.agent.jdbc")
    override fun <T : Any?> unwrap(iface: Class<T>): T {
        if (iface.isInstance(this)) return iface.cast(this)
        throw SQLException("FlowWeft Agent test DataSource does not wrap ${iface.name}.")
    }
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
