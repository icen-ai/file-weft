package ai.icen.fw.capacity.persistence.jdbc

import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapacityJdbcTransactionsTest {
    @Test
    fun `unknown commit closes without issuing state-reset calls on uncertain connection`() {
        val probe = CommitUnknownConnection()
        val transactions = CapacityJdbcTransactions(SingleConnectionDataSource(probe.connection), CapacityJdbcDialect.POSTGRESQL)

        assertFailsWith<CapacityJdbcCommitOutcomeUnknownException> {
            transactions.transaction { _, _ -> "result" }
        }

        assertTrue(probe.commitAttempted)
        assertTrue(probe.closed)
        assertFalse(probe.setterCalledAfterCommitFailure)
    }

    private class CommitUnknownConnection {
        var commitAttempted = false
        var closed = false
        var setterCalledAfterCommitFailure = false
        private var autoCommit = true
        private var readOnly = false
        private var isolation = Connection.TRANSACTION_READ_COMMITTED

        val connection: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            val args = arguments ?: emptyArray()
            when (method.name) {
                "getAutoCommit" -> autoCommit
                "isReadOnly" -> readOnly
                "getTransactionIsolation" -> isolation
                "setAutoCommit" -> {
                    if (commitAttempted) setterCalledAfterCommitFailure = true
                    autoCommit = args[0] as Boolean
                    null
                }
                "setReadOnly" -> {
                    if (commitAttempted) setterCalledAfterCommitFailure = true
                    readOnly = args[0] as Boolean
                    null
                }
                "setTransactionIsolation" -> {
                    if (commitAttempted) setterCalledAfterCommitFailure = true
                    isolation = args[0] as Int
                    null
                }
                "commit" -> {
                    commitAttempted = true
                    throw SQLException("simulated uncertain commit")
                }
                "rollback" -> null
                "close" -> { closed = true; null }
                "isClosed" -> closed
                "unwrap" -> throw SQLException("not a wrapper")
                "isWrapperFor" -> false
                "toString" -> "CommitUnknownConnection"
                else -> primitiveDefault(method.returnType)
            }
        } as Connection
    }

    private class SingleConnectionDataSource(private val connection: Connection) : DataSource {
        override fun getConnection(): Connection = connection
        override fun getConnection(username: String?, password: String?): Connection = connection
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    companion object {
        private fun primitiveDefault(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            else -> null
        }
    }
}
