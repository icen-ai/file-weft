package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.transaction.ApplicationTransaction
import java.sql.Connection
import javax.sql.DataSource

class JdbcApplicationTransaction(
    private val dataSource: DataSource,
) : ApplicationTransaction {
    override fun <T> execute(action: () -> T): T {
        if (JdbcConnectionContext.current() != null) {
            return action()
        }
        dataSource.connection.use { connection ->
            val originalAutoCommit = connection.autoCommit
            connection.autoCommit = false
            return try {
                JdbcConnectionContext.withConnection(connection, action).also { connection.commit() }
            } catch (failure: Throwable) {
                rollback(connection, failure)
                throw failure
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        }
    }

    private fun rollback(connection: Connection, failure: Throwable) {
        try {
            connection.rollback()
        } catch (rollbackFailure: Throwable) {
            failure.addSuppressed(rollbackFailure)
        }
    }
}

object JdbcConnectionContext {
    private val local = ThreadLocal<Connection>()

    fun current(): Connection? = local.get()

    fun requireCurrent(): Connection = current()
        ?: throw IllegalStateException("No JDBC transaction connection is bound to the current thread.")

    fun <T> withConnection(connection: Connection, action: () -> T): T {
        local.set(connection)
        return try {
            action()
        } finally {
            local.remove()
        }
    }
}
