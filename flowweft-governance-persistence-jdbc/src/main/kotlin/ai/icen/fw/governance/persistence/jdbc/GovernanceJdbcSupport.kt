package ai.icen.fw.governance.persistence.jdbc

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource

internal class GovernanceJdbcTransactions(
    private val dataSource: DataSource,
    private val configuredDialect: GovernanceJdbcDialect?,
) {
    fun <T> read(action: (Connection, GovernanceJdbcDialect) -> T): T = execute(true, action)

    fun <T> transaction(action: (Connection, GovernanceJdbcDialect) -> T): T {
        var retries = 0
        while (true) {
            try {
                return execute(false, action)
            } catch (failure: SQLException) {
                if (retries >= MAX_ROLLBACK_RETRIES || !failure.isGovernanceRetryableRollback()) throw failure
                retries++
            }
        }
    }

    private fun <T> execute(readOnly: Boolean, action: (Connection, GovernanceJdbcDialect) -> T): T {
        val connection = dataSource.connection
        var originalAutoCommit: Boolean? = null
        var originalReadOnly: Boolean? = null
        var originalIsolation: Int? = null
        var commitOutcomeUnknown = false
        var committed = false
        var primaryFailure: Throwable? = null
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
            val dialect = configuredDialect ?: GovernanceJdbcDialect.detect(connection)
            val result = try {
                action(connection, dialect)
            } catch (failure: Throwable) {
                rollback(connection, failure)
                throw failure
            }
            try {
                connection.commit()
            } catch (failure: Throwable) {
                commitOutcomeUnknown = !readOnly
                if (readOnly) throw failure
                throw GovernanceJdbcCommitOutcomeUnknownException(failure)
            }
            committed = true
            return result
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure = primaryFailure
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
            if (primaryFailure == null && cleanupFailure != null) {
                if (!committed) throw cleanupFailure
                LOGGER.warning(
                    "Governance JDBC committed successfully but connection cleanup failed " +
                        "(${cleanupFailure.javaClass.name}).",
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
        private val LOGGER: Logger = Logger.getLogger(GovernanceJdbcTransactions::class.java.name)
    }
}

internal class GovernanceJdbcCommitOutcomeUnknownException(cause: Throwable) :
    IllegalStateException("Governance JDBC commit outcome is unknown.", cause)

internal object GovernanceJdbcDigests {
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

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
}

internal object GovernanceJdbcValues {
    private const val MAX_ID_UTF8_BYTES = 512

    fun id(value: String): String {
        require(value.length in 1..MAX_ID_UTF8_BYTES) { "Governance JDBC identifier is invalid." }
        val encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        require(encoded.remaining() <= MAX_ID_UTF8_BYTES) {
            "Governance JDBC identifier is invalid."
        }
        return value
    }
}

internal fun SQLException.isGovernanceUniqueViolation(): Boolean =
    sqlState == "23505" || errorCode == 1062

internal fun SQLException.isGovernanceRetryableRollback(): Boolean {
    var current: SQLException? = this
    while (current != null) {
        if (current.sqlState == "40001" || current.sqlState == "40P01" || current.errorCode == 1213) return true
        current = current.nextException
    }
    return false
}
