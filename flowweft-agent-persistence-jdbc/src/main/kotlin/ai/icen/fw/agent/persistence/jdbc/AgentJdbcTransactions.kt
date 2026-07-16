package ai.icen.fw.agent.persistence.jdbc

import java.sql.Connection
import javax.sql.DataSource

internal class AgentJdbcTransactions(
    private val dataSource: DataSource,
    private val configuredDialect: AgentJdbcDialect?,
) {
    fun <T> read(action: (Connection, AgentJdbcDialect) -> T): T {
        val connection = dataSource.connection
        val originalAutoCommit = connection.autoCommit
        val originalReadOnly = connection.isReadOnly
        val originalIsolation = connection.transactionIsolation
        var outcomeUnknown = false
        var primaryFailure: Throwable? = null
        try {
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true
            connection.autoCommit = false
            val result = try {
                action(connection, configuredDialect ?: AgentJdbcDialect.detect(connection))
            } catch (failure: Throwable) {
                rollback(connection, failure)
                throw failure
            }
            try {
                connection.commit()
            } catch (failure: Throwable) {
                outcomeUnknown = true
                throw failure
            }
            return result
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure = primaryFailure
            if (!outcomeUnknown) {
                cleanupFailure = cleanup(cleanupFailure) { connection.isReadOnly = originalReadOnly }
                cleanupFailure = cleanup(cleanupFailure) { connection.transactionIsolation = originalIsolation }
                cleanupFailure = cleanup(cleanupFailure) { connection.autoCommit = originalAutoCommit }
            }
            cleanupFailure = cleanup(cleanupFailure) { connection.close() }
            if (primaryFailure == null && cleanupFailure != null) throw cleanupFailure
        }
    }

    fun <T> transaction(action: (Connection, AgentJdbcDialect) -> T): T {
        val connection = dataSource.connection
        val originalAutoCommit = connection.autoCommit
        val originalReadOnly = connection.isReadOnly
        var outcomeUnknown = false
        var primaryFailure: Throwable? = null
        try {
            connection.autoCommit = false
            connection.isReadOnly = false
            val result = try {
                action(connection, configuredDialect ?: AgentJdbcDialect.detect(connection))
            } catch (failure: Throwable) {
                rollback(connection, failure)
                throw failure
            }
            // A commit exception has an unknown outcome and is deliberately propagated unchanged.
            try {
                connection.commit()
            } catch (failure: Throwable) {
                outcomeUnknown = true
                throw failure
            }
            return result
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // Never execute JDBC setters after an uncertain commit: enabling auto-commit may issue
            // a second implicit commit on some drivers. Closing is the only safe cleanup boundary.
            var cleanupFailure = primaryFailure
            if (!outcomeUnknown) {
                cleanupFailure = cleanup(cleanupFailure) { connection.isReadOnly = originalReadOnly }
                cleanupFailure = cleanup(cleanupFailure) { connection.autoCommit = originalAutoCommit }
            }
            cleanupFailure = cleanup(cleanupFailure) { connection.close() }
            if (primaryFailure == null && cleanupFailure != null) throw cleanupFailure
        }
    }

    private inline fun cleanup(primary: Throwable?, action: () -> Unit): Throwable? {
        try {
            action()
            return primary
        } catch (cleanupFailure: Throwable) {
            if (primary != null) {
                primary.addSuppressed(cleanupFailure)
                return primary
            }
            return cleanupFailure
        }
    }

    private fun rollback(connection: Connection, primary: Throwable) {
        try {
            connection.rollback()
        } catch (rollbackFailure: Throwable) {
            primary.addSuppressed(rollbackFailure)
        }
    }
}
