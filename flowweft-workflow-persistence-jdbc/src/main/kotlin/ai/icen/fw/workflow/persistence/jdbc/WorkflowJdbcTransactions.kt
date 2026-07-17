package ai.icen.fw.workflow.persistence.jdbc

import java.sql.Connection
import javax.sql.DataSource

internal class WorkflowJdbcTransactions(
    private val dataSource: DataSource,
    configuredDialect: WorkflowJdbcDialect?,
) {
    private val configuredDialect = configuredDialect

    fun <T> read(action: (Connection, WorkflowJdbcDialect) -> T): T = dataSource.connection.use { connection ->
        action(connection, configuredDialect ?: WorkflowJdbcDialect.detect(connection))
    }

    fun <T> transaction(action: (Connection, WorkflowJdbcDialect) -> T): T {
        val connection = dataSource.connection
        val originalAutoCommit = connection.autoCommit
        val originalReadOnly = connection.isReadOnly
        try {
            connection.autoCommit = false
            connection.isReadOnly = false
            val dialect = configuredDialect ?: WorkflowJdbcDialect.detect(connection)
            val result = try {
                action(connection, dialect)
            } catch (failure: Throwable) {
                rollback(connection, failure)
                throw failure
            }
            // A commit exception is deliberately propagated: callers must classify its outcome as unknown.
            connection.commit()
            return result
        } finally {
            try {
                connection.isReadOnly = originalReadOnly
            } catch (_: Throwable) {
                // The business outcome is already known or represented by the primary failure.
            }
            try {
                connection.autoCommit = originalAutoCommit
            } catch (_: Throwable) {
                // Connection close remains the final cleanup boundary.
            }
            connection.close()
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
