package ai.icen.fw.workflow.notification.persistence.jdbc

import java.sql.Connection
import javax.sql.DataSource

internal class WorkflowNotificationJdbcTransactions(
    private val dataSource: DataSource,
    configuredDialect: WorkflowNotificationJdbcDialect?,
) {
    private val configuredDialect = configuredDialect

    fun <T> read(action: (Connection, WorkflowNotificationJdbcDialect) -> T): T =
        dataSource.connection.use { connection ->
            action(connection, resolveDialect(connection))
        }

    fun <T> transaction(action: (Connection, WorkflowNotificationJdbcDialect) -> T): T {
        val connection = dataSource.connection
        val originalAutoCommit = connection.autoCommit
        val originalReadOnly = connection.isReadOnly
        try {
            connection.autoCommit = false
            connection.isReadOnly = false
            val dialect = resolveDialect(connection)
            val result = try {
                action(connection, dialect)
            } catch (failure: Throwable) {
                rollback(connection, failure)
                throw failure
            }
            // A commit exception is outcome-unknown. Propagate it; callers must reconcile it.
            connection.commit()
            return result
        } finally {
            try {
                connection.isReadOnly = originalReadOnly
            } catch (_: Throwable) {
                // The primary result/failure must not be hidden by connection cleanup.
            }
            try {
                connection.autoCommit = originalAutoCommit
            } catch (_: Throwable) {
                // Closing the connection is the final safe cleanup boundary.
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

    private fun resolveDialect(connection: Connection): WorkflowNotificationJdbcDialect {
        val configured = configuredDialect ?: return WorkflowNotificationJdbcDialect.detect(connection)
        val product = connection.metaData.databaseProductName
        // H2 is accepted only as an explicitly configured compatibility-test database.
        if (!product.equals("H2", ignoreCase = true)) {
            val actual = WorkflowNotificationJdbcDialect.detect(connection)
            check(actual == configured) {
                "Configured Workflow notification dialect does not match the connected database."
            }
        }
        return configured
    }
}
