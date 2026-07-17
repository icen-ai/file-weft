package ai.icen.fw.reliability.persistence.jdbc

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

internal class ReliabilityJdbcTransactions(
    private val dataSource: DataSource,
    private val configuredDialect: ReliabilityJdbcDialect?,
) {
    fun <T> read(action: (Connection, ReliabilityJdbcDialect) -> T): T = execute(true, action)
    fun <T> write(action: (Connection, ReliabilityJdbcDialect) -> T): T {
        var retries = 0
        while (true) {
            try {
                return execute(false, action)
            } catch (failure: SQLException) {
                if (retries >= MAX_ROLLBACK_RETRIES || !ReliabilityJdbcFailures.isRetryableRollback(failure)) {
                    throw failure
                }
                retries++
            }
        }
    }

    private fun <T> execute(readOnly: Boolean, action: (Connection, ReliabilityJdbcDialect) -> T): T {
        val connection = dataSource.connection
        var originalAutoCommit: Boolean? = null
        var originalReadOnly: Boolean? = null
        var originalIsolation: Int? = null
        var commitOutcomeUnknown = false
        var committed = false
        var primary: Throwable? = null
        try {
            originalAutoCommit = connection.autoCommit
            originalReadOnly = connection.isReadOnly
            originalIsolation = connection.transactionIsolation
            connection.transactionIsolation = if (readOnly) {
                Connection.TRANSACTION_REPEATABLE_READ
            } else {
                Connection.TRANSACTION_SERIALIZABLE
            }
            connection.isReadOnly = readOnly
            connection.autoCommit = false
            val dialect = configuredDialect ?: ReliabilityJdbcDialect.detect(connection)
            val result = try {
                action(connection, dialect)
            } catch (failure: Throwable) {
                rollback(connection, failure)
                throw failure
            }
            try {
                connection.commit()
            } catch (failure: Throwable) {
                commitOutcomeUnknown = true
                throw ReliabilityJdbcCommitOutcomeUnknownException(failure)
            }
            committed = true
            return result
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            var cleanupFailure = primary
            if (!commitOutcomeUnknown) {
                originalReadOnly?.let { value ->
                    cleanupFailure = cleanup(cleanupFailure) { connection.isReadOnly = value }
                }
                originalIsolation?.let { value ->
                    cleanupFailure = cleanup(cleanupFailure) { connection.transactionIsolation = value }
                }
                originalAutoCommit?.let { value ->
                    cleanupFailure = cleanup(cleanupFailure) { connection.autoCommit = value }
                }
            }
            cleanupFailure = cleanup(cleanupFailure) { connection.close() }
            if (primary == null && cleanupFailure != null) {
                if (!committed) throw cleanupFailure
                LOGGER.warning(
                    "Reliability JDBC committed successfully but connection cleanup failed (${cleanupFailure.javaClass.name}).",
                )
            }
        }
    }

    private inline fun cleanup(primary: Throwable?, action: () -> Unit): Throwable? = try {
        action()
        primary
    } catch (failure: Throwable) {
        if (primary == null) failure else primary.also { it.addSuppressed(failure) }
    }

    private fun rollback(connection: Connection, primary: Throwable) {
        try {
            connection.rollback()
        } catch (rollbackFailure: Throwable) {
            primary.addSuppressed(rollbackFailure)
        }
    }

    companion object {
        private const val MAX_ROLLBACK_RETRIES = 1
        private val LOGGER: Logger = Logger.getLogger(ReliabilityJdbcTransactions::class.java.name)
    }
}

internal class ReliabilityJdbcCommitOutcomeUnknownException(cause: Throwable) :
    SQLException("Reliability JDBC commit outcome is unknown.", cause)

internal class ReliabilityJdbcUniqueConflictException(cause: SQLException) :
    SQLException("Reliability JDBC insert encountered an exact unique-key conflict.", cause)

internal object ReliabilityJdbcFailures {
    fun isRetryableRollback(failure: SQLException): Boolean {
        var current: SQLException? = failure
        while (current != null) {
            if (current.sqlState == "40001" || current.sqlState == "40P01" || current.errorCode == 1213) {
                return true
            }
            current = current.nextException
        }
        return false
    }
}

internal object ReliabilityJdbcDigests {
    fun rowId(domain: String, vararg values: String): String = digest(domain, *values)

    fun digest(domain: String, vararg values: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        update(hash, domain)
        hash.update(ByteBuffer.allocate(4).putInt(values.size).array())
        values.forEach { update(hash, it) }
        return hex(hash.digest())
    }

    fun bytes(value: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(value))

    private fun update(hash: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        hash.update(bytes)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}
