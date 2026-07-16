package ai.icen.fw.reliability.persistence.jdbc

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

class ReliabilityJdbcTransactionsTest {
    @Test
    fun `unknown commit closes without resetting an uncertain connection`() {
        val probe = CommitUnknownConnection()
        val transactions = ReliabilityJdbcTransactions(
            SingleConnectionDataSource(probe.connection), ReliabilityJdbcDialect.POSTGRESQL,
        )

        assertFailsWith<ReliabilityJdbcCommitOutcomeUnknownException> {
            transactions.write { _, _ -> "result" }
        }

        assertTrue(probe.commitAttempted)
        assertTrue(probe.closed)
        assertFalse(probe.setterCalledAfterCommitFailure)
    }

    @Test
    fun `local transaction returns before external work can begin`() {
        val probe = CommitUnknownConnection(failCommit = false)
        val transactions = ReliabilityJdbcTransactions(
            SingleConnectionDataSource(probe.connection), ReliabilityJdbcDialect.POSTGRESQL,
        )

        transactions.write { connection, _ ->
            assertFalse(connection.autoCommit)
            assertFalse(probe.closed)
        }

        assertTrue(probe.commitAttempted)
        assertTrue(probe.closed)
    }

    @Test
    fun `connection state read failure still closes the borrowed connection`() {
        val probe = CommitUnknownConnection(failCommit = false, failStateRead = true)
        val transactions = ReliabilityJdbcTransactions(
            SingleConnectionDataSource(probe.connection), ReliabilityJdbcDialect.POSTGRESQL,
        )

        assertFailsWith<SQLException> { transactions.write { _, _ -> "unreachable" } }

        assertFalse(probe.commitAttempted)
        assertTrue(probe.closed)
    }

    @Test
    fun `cleanup failure cannot reverse a successful commit`() {
        val probe = CommitUnknownConnection(failCommit = false, failCleanup = true)
        val transactions = ReliabilityJdbcTransactions(
            SingleConnectionDataSource(probe.connection), ReliabilityJdbcDialect.POSTGRESQL,
        )

        assertEquals("committed", transactions.write { _, _ -> "committed" })

        assertTrue(probe.commitAttempted)
        assertTrue(probe.closed)
    }

    private class CommitUnknownConnection(
        private val failCommit: Boolean = true,
        private val failStateRead: Boolean = false,
        private val failCleanup: Boolean = false,
    ) {
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
                "getAutoCommit" -> if (failStateRead) throw SQLException("simulated state read failure") else autoCommit
                "isReadOnly" -> readOnly
                "getTransactionIsolation" -> isolation
                "setAutoCommit" -> {
                    if (commitAttempted && failCommit) setterCalledAfterCommitFailure = true
                    autoCommit = args[0] as Boolean
                    null
                }
                "setReadOnly" -> {
                    if (commitAttempted && failCommit) setterCalledAfterCommitFailure = true
                    if (commitAttempted && !failCommit && failCleanup) {
                        throw SQLException("simulated cleanup failure")
                    }
                    readOnly = args[0] as Boolean
                    null
                }
                "setTransactionIsolation" -> {
                    if (commitAttempted && failCommit) setterCalledAfterCommitFailure = true
                    isolation = args[0] as Int
                    null
                }
                "commit" -> {
                    commitAttempted = true
                    if (failCommit) throw SQLException("simulated uncertain commit")
                    null
                }
                "rollback" -> null
                "close" -> { closed = true; null }
                "isClosed" -> closed
                "unwrap" -> throw SQLException("not a wrapper")
                "isWrapperFor" -> false
                "toString" -> "ReliabilityCommitUnknownConnection"
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
