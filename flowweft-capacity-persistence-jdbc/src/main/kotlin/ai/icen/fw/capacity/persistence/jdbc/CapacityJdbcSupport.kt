package ai.icen.fw.capacity.persistence.jdbc

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

internal class CapacityJdbcTransactions(
    private val dataSource: DataSource,
    private val configuredDialect: CapacityJdbcDialect?,
) {
    fun <T> read(action: (Connection, CapacityJdbcDialect) -> T): T = execute(true, action)

    fun <T> transaction(action: (Connection, CapacityJdbcDialect) -> T): T = execute(false, action)

    private fun <T> execute(readOnly: Boolean, action: (Connection, CapacityJdbcDialect) -> T): T {
        val connection = dataSource.connection
        val originalAutoCommit = connection.autoCommit
        val originalReadOnly = connection.isReadOnly
        val originalIsolation = connection.transactionIsolation
        var outcomeUnknown = false
        var primaryFailure: Throwable? = null
        try {
            connection.transactionIsolation = if (readOnly) {
                Connection.TRANSACTION_REPEATABLE_READ
            } else {
                Connection.TRANSACTION_SERIALIZABLE
            }
            connection.isReadOnly = readOnly
            connection.autoCommit = false
            val dialect = configuredDialect ?: CapacityJdbcDialect.detect(connection)
            val result = try {
                action(connection, dialect)
            } catch (failure: Throwable) {
                rollback(connection, failure)
                throw failure
            }
            try {
                connection.commit()
            } catch (failure: Throwable) {
                outcomeUnknown = true
                throw CapacityJdbcCommitOutcomeUnknownException(failure)
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

    private inline fun cleanup(primary: Throwable?, action: () -> Unit): Throwable? = try {
        action()
        primary
    } catch (failure: Throwable) {
        if (primary != null) {
            primary.addSuppressed(failure)
            primary
        } else {
            failure
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

internal class CapacityJdbcCommitOutcomeUnknownException(cause: Throwable) :
    SQLException("Capacity JDBC commit outcome is unknown.", cause)

internal object CapacityJdbcDigests {
    fun rowId(domain: String, vararg values: String): String = digest(domain, *values)

    fun digest(domain: String, vararg values: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        update(hash, domain)
        hash.update(ByteBuffer.allocate(4).putInt(values.size).array())
        values.forEach { value -> update(hash, value) }
        return hash.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    fun bytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun update(hash: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        hash.update(bytes)
    }
}

internal fun SQLException.isIntegrityViolation(): Boolean = sqlState?.startsWith("23") == true
