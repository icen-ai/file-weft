package ai.icen.fw.persistence.jdbc

import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JdbcApplicationTransactionTest {
    @Test
    fun `logs a cleanup failure with the committed outcome through the injected logger`() {
        val closeFailure = SQLException("close failed")
        val logger = RecordingLogger()
        val transaction = JdbcApplicationTransaction(
            StubDataSource(stubConnection(closeFailure = closeFailure)),
            logger,
        )

        val result = transaction.execute { "committed" }

        assertEquals("committed", result)
        val event = logger.events.single()
        assertEquals("error", event.level)
        assertEquals(
            "Failed to close connection after JDBC transaction completion (commitSucceeded=true).",
            event.message,
        )
        assertSame(closeFailure, event.throwable)
    }

    @Test
    fun `logs an auto commit restore failure through the injected logger`() {
        val restoreFailure = SQLException("restore failed")
        val logger = RecordingLogger()
        val transaction = JdbcApplicationTransaction(
            StubDataSource(stubConnection(autoCommitRestoreFailure = restoreFailure)),
            logger,
        )

        transaction.execute { "committed" }

        val event = logger.events.single()
        assertEquals("error", event.level)
        assertEquals(
            "Failed to restore auto-commit after JDBC transaction completion (commitSucceeded=true).",
            event.message,
        )
        assertSame(restoreFailure, event.throwable)
    }

    @Test
    fun `a logger that throws never changes the committed transaction outcome`() {
        val transaction = JdbcApplicationTransaction(
            StubDataSource(stubConnection(closeFailure = SQLException("close failed"))),
            ThrowingLogger,
        )

        assertEquals("committed", transaction.execute { "committed" })
    }

    @Test
    fun `the compatibility constructor stays silent and keeps the committed outcome`() {
        val transaction = JdbcApplicationTransaction(
            StubDataSource(stubConnection(closeFailure = SQLException("close failed"))),
        )

        assertEquals("committed", transaction.execute { "committed" })
    }

    @Test
    fun `cleanup failures stay suppressed on the primary failure instead of being logged`() {
        val logger = RecordingLogger()
        val transaction = JdbcApplicationTransaction(
            StubDataSource(stubConnection(closeFailure = SQLException("close failed"))),
            logger,
        )
        val primary = IllegalStateException("action failed")

        val failure = kotlin.test.assertFailsWith<IllegalStateException> {
            transaction.execute<Unit> { throw primary }
        }

        assertSame(primary, failure)
        assertTrue(failure.suppressed.any { it is SQLException && it.message == "close failed" })
        assertTrue(logger.events.isEmpty())
    }

    private class RecordingLogger : FileWeftLogger {
        data class Event(val level: String, val message: String, val throwable: Throwable?)

        val events = mutableListOf<Event>()

        override fun info(message: String, context: LogContext) {
            events += Event("info", message, null)
        }

        override fun warn(message: String, context: LogContext) {
            events += Event("warn", message, null)
        }

        override fun error(message: String, throwable: Throwable?, context: LogContext) {
            events += Event("error", message, throwable)
        }

        override fun debug(message: String, context: LogContext) {
            events += Event("debug", message, null)
        }
    }

    private object ThrowingLogger : FileWeftLogger {
        override fun info(message: String, context: LogContext) = throw RuntimeException("logging offline")
        override fun warn(message: String, context: LogContext) = throw RuntimeException("logging offline")
        override fun error(message: String, throwable: Throwable?, context: LogContext) =
            throw RuntimeException("logging offline")

        override fun debug(message: String, context: LogContext) = throw RuntimeException("logging offline")
    }

    private class StubDataSource(private val connection: Connection) : DataSource {
        override fun getConnection(): Connection = connection
        override fun getConnection(username: String?, password: String?): Connection = connection
        override fun getLogWriter(): PrintWriter = throw UnsupportedOperationException()
        override fun setLogWriter(out: PrintWriter?): Unit = throw UnsupportedOperationException()
        override fun setLoginTimeout(seconds: Int): Unit = throw UnsupportedOperationException()
        override fun getLoginTimeout(): Int = throw UnsupportedOperationException()
        override fun getParentLogger(): java.util.logging.Logger = throw UnsupportedOperationException()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = throw UnsupportedOperationException()
    }

    private fun stubConnection(
        closeFailure: SQLException? = null,
        autoCommitRestoreFailure: SQLException? = null,
    ): Connection {
        val autoCommit = AtomicBoolean(true)
        val closed = AtomicBoolean(false)
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "getAutoCommit" -> autoCommit.get()
                "setAutoCommit" -> {
                    val value = args[0] as Boolean
                    if (value && !autoCommit.get() && autoCommitRestoreFailure != null) {
                        throw autoCommitRestoreFailure
                    }
                    autoCommit.set(value)
                    null
                }
                "commit" -> null
                "rollback" -> null
                "close" -> {
                    if (closeFailure != null) throw closeFailure
                    closed.set(true)
                    null
                }
                "isClosed" -> closed.get()
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        return Proxy.newProxyInstance(
            JdbcApplicationTransactionTest::class.java.classLoader,
            arrayOf(Connection::class.java),
            handler,
        ) as Connection
    }
}
