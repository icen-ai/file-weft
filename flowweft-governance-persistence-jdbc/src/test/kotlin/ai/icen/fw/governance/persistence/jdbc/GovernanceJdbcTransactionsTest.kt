package ai.icen.fw.governance.persistence.jdbc

import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GovernanceJdbcTransactionsTest {
    @Test
    fun `unknown commit closes without resetting an uncertain connection or retrying`() {
        val probe = CommitUnknownConnection()
        val transactions = GovernanceJdbcTransactions(
            SingleConnectionDataSource(probe.connection), GovernanceJdbcDialect.POSTGRESQL,
        )

        assertFailsWith<GovernanceJdbcCommitOutcomeUnknownException> {
            transactions.transaction { _, _ -> "result" }
        }

        assertTrue(probe.commitAttempted)
        assertTrue(probe.closed)
        assertFalse(probe.setterCalledAfterCommitFailure)
        assertFalse(probe.rollbackCalledAfterCommitFailure)
    }

    @Test
    fun `connection state read failure still closes the borrowed connection`() {
        val probe = CommitUnknownConnection(failCommit = false, failStateRead = true)
        val transactions = GovernanceJdbcTransactions(
            SingleConnectionDataSource(probe.connection), GovernanceJdbcDialect.POSTGRESQL,
        )

        assertFailsWith<SQLException> { transactions.transaction { _, _ -> "unreachable" } }

        assertFalse(probe.commitAttempted)
        assertTrue(probe.closed)
    }

    @Test
    fun `cleanup failure cannot reverse an acknowledged commit`() {
        val probe = CommitUnknownConnection(failCommit = false, failCleanup = true)
        val transactions = GovernanceJdbcTransactions(
            SingleConnectionDataSource(probe.connection), GovernanceJdbcDialect.POSTGRESQL,
        )

        assertEquals("committed", transactions.transaction { _, _ -> "committed" })
        assertTrue(probe.commitAttempted)
        assertTrue(probe.closed)
    }

    @Test
    fun `only transaction-wide rollback signals are retryable`() {
        assertTrue(SQLException("serialization", "40001").isGovernanceRetryableRollback())
        assertTrue(SQLException("deadlock", "40P01").isGovernanceRetryableRollback())
        assertTrue(SQLException("deadlock", "23000", 1213).isGovernanceRetryableRollback())
        assertFalse(SQLException("lock timeout", "HY000", 1205).isGovernanceRetryableRollback())
        assertFalse(SQLException("connection", "08006").isGovernanceRetryableRollback())
    }

    private class CommitUnknownConnection(
        private val failCommit: Boolean = true,
        private val failStateRead: Boolean = false,
        private val failCleanup: Boolean = false,
    ) {
        var commitAttempted = false
        var closed = false
        var setterCalledAfterCommitFailure = false
        var rollbackCalledAfterCommitFailure = false
        private var autoCommit = true
        private var readOnly = false
        private var isolation = Connection.TRANSACTION_READ_COMMITTED

        val connection: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            val args = arguments ?: emptyArray()
            when (method.name) {
                "getAutoCommit" -> if (failStateRead) throw SQLException("simulated state read failure") else autoCommit
                "isReadOnly" -> readOnly
                "getTransactionIsolation" -> isolation
                "setAutoCommit" -> {
                    if (commitAttempted) setterCalledAfterCommitFailure = true
                    autoCommit = args[0] as Boolean
                    null
                }
                "setReadOnly" -> {
                    if (commitAttempted) setterCalledAfterCommitFailure = true
                    if (commitAttempted && !failCommit && failCleanup) {
                        throw SQLException("simulated cleanup failure")
                    }
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
                    if (failCommit) throw SQLException("simulated acknowledgement loss")
                    null
                }
                "rollback" -> {
                    if (commitAttempted) rollbackCalledAfterCommitFailure = true
                    null
                }
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
