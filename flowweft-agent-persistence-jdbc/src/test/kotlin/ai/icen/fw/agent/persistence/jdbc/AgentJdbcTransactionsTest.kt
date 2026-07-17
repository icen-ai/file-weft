package ai.icen.fw.agent.persistence.jdbc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource

class AgentJdbcTransactionsTest {
    @Test
    fun `commit outcome unknown closes without rollback setter or second implicit commit`() {
        val commitCalls = AtomicInteger()
        val rollbackCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        val autoCommitWrites = arrayListOf<Boolean>()
        val readOnlyWrites = arrayListOf<Boolean>()
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getAutoCommit" -> true
                "isReadOnly" -> true
                "setAutoCommit" -> {
                    autoCommitWrites += arguments!![0] as Boolean
                    null
                }
                "setReadOnly" -> {
                    readOnlyWrites += arguments!![0] as Boolean
                    null
                }
                "commit" -> {
                    commitCalls.incrementAndGet()
                    throw SQLException("simulated outcome unknown")
                }
                "rollback" -> {
                    rollbackCalls.incrementAndGet()
                    null
                }
                "close" -> {
                    closeCalls.incrementAndGet()
                    null
                }
                "isClosed" -> false
                "unwrap" -> throw SQLException("not wrapped")
                "isWrapperFor" -> false
                "toString" -> "OutcomeUnknownConnection"
                else -> defaultValue(method.returnType)
            }
        } as Connection
        val transactions = AgentJdbcTransactions(SingleConnectionDataSource(connection), AgentJdbcDialect.POSTGRESQL)

        assertThrows(SQLException::class.java) {
            transactions.transaction { _, _ -> "prepared" }
        }

        assertEquals(1, commitCalls.get())
        assertEquals(0, rollbackCalls.get())
        assertEquals(1, closeCalls.get())
        assertEquals(listOf(false), autoCommitWrites)
        assertEquals(listOf(false), readOnlyWrites)
    }

    private class SingleConnectionDataSource(private val connection: Connection) : DataSource {
        override fun getConnection(): Connection = connection
        override fun getConnection(username: String, password: String): Connection = connection
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getLogger("flowweft.agent.jdbc.test")
        override fun <T : Any?> unwrap(iface: Class<T>): T = throw SQLException("not wrapped")
        override fun isWrapperFor(iface: Class<*>): Boolean = false
    }

    private companion object {
        fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }
}
