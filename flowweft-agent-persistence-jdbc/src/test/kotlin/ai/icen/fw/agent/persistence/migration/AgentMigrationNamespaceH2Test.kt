package ai.icen.fw.agent.persistence.migration

import org.h2.jdbcx.JdbcDataSource
import org.h2.tools.RunScript
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.util.logging.Logger
import javax.sql.DataSource

class AgentMigrationNamespaceH2Test {
    @Test
    fun `postgres resources install only generic V030 and additive evaluation V031 tables`() {
        val dataSource = h2()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE fw_document_sentinel(id integer)")
                statement.execute("CREATE TABLE fw_wf_sentinel(id integer)")
            }
            run(connection, "V030__create_agent_durable_runtime.sql")
            run(connection, "V031__create_agent_evaluation_runtime.sql")
            val tables = linkedSetOf<String>()
            connection.metaData.getTables(null, null, "%", arrayOf("BASE TABLE", "TABLE")).use { result ->
                while (result.next()) {
                    result.getString("TABLE_NAME")?.lowercase()?.let(tables::add)
                }
            }
            assertTrue("fw_document_sentinel" in tables)
            assertTrue("fw_wf_sentinel" in tables)
            assertEquals(
                setOf(
                    "fw_agent_run",
                    "fw_agent_idempotency",
                    "fw_agent_event",
                    "fw_agent_operation",
                    "fw_agent_evaluation_run",
                    "fw_agent_evaluation_idempotency",
                ),
                tables.filter { table -> table.startsWith("fw_agent_") }.toSet(),
            )
        }
    }

    @Test
    fun `historyless managed sentinel is rejected before Flyway can baseline it`() {
        val delegate = h2()
        delegate.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE fw_agent_run(id varchar(64))")
            }
        }
        val runner = AgentFlywayMigrationRunner(ProductNameDataSource(delegate, "PostgreSQL"), "public")
        val failure = assertThrows(IllegalStateException::class.java) { runner.migrate() }
        assertTrue(failure.message.orEmpty().contains("managed tables already exist"))
    }

    private fun run(connection: Connection, name: String) {
        val path = "/ai/icen/fw/agent/db/migration/postgres/$name"
        InputStreamReader(requireNotNull(javaClass.getResourceAsStream(path)), StandardCharsets.UTF_8).use { reader ->
            RunScript.execute(connection, reader)
        }
    }

    private fun h2(): JdbcDataSource = JdbcDataSource().apply {
        setURL(
            "jdbc:h2:mem:agent-migration-${System.nanoTime()};" +
                "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        )
        user = "sa"
        password = ""
    }

    private class ProductNameDataSource(
        private val delegate: DataSource,
        private val productName: String,
    ) : DataSource {
        override fun getConnection(): Connection = connection(delegate.connection)

        override fun getConnection(username: String, password: String): Connection =
            connection(delegate.getConnection(username, password))

        override fun getLogWriter(): PrintWriter? = delegate.logWriter
        override fun setLogWriter(out: PrintWriter?) { delegate.logWriter = out }
        override fun setLoginTimeout(seconds: Int) { delegate.loginTimeout = seconds }
        override fun getLoginTimeout(): Int = delegate.loginTimeout
        override fun getParentLogger(): Logger = delegate.parentLogger
        override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)
        override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)

        private fun connection(connection: Connection): Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            try {
                if (method.name == "getMetaData" && method.parameterCount == 0) {
                    metadata(connection.metaData)
                } else {
                    method.invoke(connection, *(arguments ?: emptyArray()))
                }
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection

        private fun metadata(metadata: DatabaseMetaData): DatabaseMetaData = Proxy.newProxyInstance(
            DatabaseMetaData::class.java.classLoader,
            arrayOf(DatabaseMetaData::class.java),
        ) { _, method, arguments ->
            try {
                if (method.name == "getDatabaseProductName" && method.parameterCount == 0) {
                    productName
                } else {
                    method.invoke(metadata, *(arguments ?: emptyArray()))
                }
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as DatabaseMetaData
    }
}
