package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.application.transaction.ApplicationTransactionState
import ai.icen.fw.persistence.jdbc.dialect.SqlDialect
import ai.icen.fw.persistence.jdbc.dialect.SqlDialects
import java.sql.Connection
import java.util.ArrayDeque
import java.util.logging.Level
import java.util.logging.Logger
import javax.sql.DataSource

class JdbcApplicationTransaction(
    private val dataSource: DataSource,
) : ApplicationTransaction, ApplicationTransactionState {
    override fun <T> execute(action: () -> T): T {
        val existingConnection = JdbcConnectionContext.current(dataSource)
        if (existingConnection != null) {
            // Re-push the matching binding so repositories always use the
            // transaction selected by this call, even across A -> B -> A nesting.
            return JdbcConnectionContext.withConnection(dataSource, existingConnection, action)
        }
        val connection = dataSource.connection
        var originalAutoCommit: Boolean? = null
        var primaryFailure: Throwable? = null
        var commitSucceeded = false
        try {
            originalAutoCommit = connection.autoCommit
            connection.autoCommit = false
            val result = try {
                JdbcConnectionContext.withConnection(dataSource, connection, action)
            } catch (actionFailure: Throwable) {
                throw rollback(connection, actionFailure)
            }
            try {
                connection.commit()
                commitSucceeded = true
            } catch (commitFailure: Throwable) {
                throw ApplicationTransactionOutcomeUnknownException(commitFailure)
            }
            return result
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanup(connection, originalAutoCommit, primaryFailure, commitSucceeded)
        }
    }

    override fun isTransactionActive(): Boolean = JdbcConnectionContext.current(dataSource) != null

    private fun rollback(connection: Connection, actionFailure: Throwable): Throwable = try {
        connection.rollback()
        actionFailure
    } catch (rollbackFailure: Throwable) {
        val outcomeUnknown = actionFailure as? ApplicationTransactionOutcomeUnknownException
            ?: ApplicationTransactionOutcomeUnknownException(actionFailure)
        if (rollbackFailure !== outcomeUnknown) {
            outcomeUnknown.addSuppressed(rollbackFailure)
        }
        outcomeUnknown
    }

    private fun cleanup(
        connection: Connection,
        originalAutoCommit: Boolean?,
        primaryFailure: Throwable?,
        commitSucceeded: Boolean,
    ) {
        if (originalAutoCommit != null && primaryFailure !is ApplicationTransactionOutcomeUnknownException) {
            cleanupStep("restore auto-commit", primaryFailure, commitSucceeded) {
                connection.autoCommit = originalAutoCommit
            }
        }
        cleanupStep("close connection", primaryFailure, commitSucceeded) {
            connection.close()
        }
    }

    private fun cleanupStep(
        operation: String,
        primaryFailure: Throwable?,
        commitSucceeded: Boolean,
        action: () -> Unit,
    ) {
        try {
            action()
        } catch (cleanupFailure: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure)
            } else {
                // A confirmed commit is the business outcome. Connection cleanup is
                // best effort and must not make callers compensate committed data.
                logCleanupFailure(operation, commitSucceeded, cleanupFailure)
            }
        }
    }

    private fun logCleanupFailure(operation: String, commitSucceeded: Boolean, failure: Throwable) {
        try {
            LOGGER.log(
                Level.WARNING,
                "Failed to $operation after JDBC transaction completion (commitSucceeded=$commitSucceeded).",
                failure,
            )
        } catch (_: Throwable) {
            // Logging must never replace the already determined transaction outcome.
        }
    }

    private companion object {
        val LOGGER: Logger = Logger.getLogger(JdbcApplicationTransaction::class.java.name)
    }
}

object JdbcConnectionContext {
    private data class Binding(
        val dataSource: DataSource?,
        val connection: Connection,
    ) {
        val dialect: SqlDialect by lazy { SqlDialects.detect(connection) }
    }

    private val local = ThreadLocal<ArrayDeque<Binding>>()

    fun current(): Connection? = local.get()?.peekLast()?.connection

    /**
     * Returns the connection bound to this exact DataSource instance. DataSource
     * identity is deliberate: different pools may be equal by value while still
     * representing independent transactional resources.
     */
    fun current(dataSource: DataSource): Connection? {
        val iterator = local.get()?.descendingIterator() ?: return null
        while (iterator.hasNext()) {
            val binding = iterator.next()
            if (binding.dataSource === dataSource) return binding.connection
        }
        return null
    }

    fun requireCurrent(): Connection = current()
        ?: throw IllegalStateException("No JDBC transaction connection is bound to the current thread.")

    fun dialect(): SqlDialect? = local.get()?.peekLast()?.dialect

    fun requireDialect(): SqlDialect = dialect()
        ?: throw IllegalStateException("No JDBC transaction connection is bound to the current thread.")

    /**
     * Compatibility binding for repository and lock integration tests that
     * supply a connection directly without an owning DataSource.
     */
    fun <T> withConnection(connection: Connection, action: () -> T): T {
        return withBinding(Binding(null, connection), action)
    }

    fun <T> withConnection(dataSource: DataSource, connection: Connection, action: () -> T): T {
        return withBinding(Binding(dataSource, connection), action)
    }

    private fun <T> withBinding(binding: Binding, action: () -> T): T {
        val stack = local.get() ?: ArrayDeque<Binding>().also(local::set)
        stack.addLast(binding)
        return try {
            action()
        } finally {
            val removed = stack.removeLast()
            check(removed === binding) { "JDBC connection bindings were removed out of order." }
            if (stack.isEmpty()) local.remove()
        }
    }
}
