package ai.icen.fw.workflow.sla.persistence.jdbc

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

internal class WorkflowSlaJdbcTransactions(
    private val dataSource: DataSource,
    private val configuredDialect: WorkflowSlaJdbcDialect?,
) {
    fun <T> read(action: (Connection, WorkflowSlaJdbcDialect) -> T): T = dataSource.connection.use { connection ->
        action(connection, resolveDialect(connection))
    }

    fun <T> transaction(action: (Connection, WorkflowSlaJdbcDialect) -> T): T {
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
            // Commit failures are ambiguous until an exact operation digest is reread.
            connection.commit()
            return result
        } finally {
            try {
                connection.isReadOnly = originalReadOnly
            } catch (_: Throwable) {
                // The primary outcome has already been retained.
            }
            try {
                connection.autoCommit = originalAutoCommit
            } catch (_: Throwable) {
                // Closing the connection remains the cleanup boundary.
            }
            connection.close()
        }
    }

    private fun resolveDialect(connection: Connection): WorkflowSlaJdbcDialect {
        val product = connection.metaData.databaseProductName
        if (product.equals("H2", ignoreCase = true)) {
            return checkNotNull(configuredDialect) {
                "H2 is supported only as an explicitly configured Workflow SLA test dialect."
            }
        }
        val detected = WorkflowSlaJdbcDialect.detect(connection)
        check(configuredDialect == null || configuredDialect == detected) {
            "Configured Workflow SLA dialect does not match the connected database product."
        }
        return detected
    }

    private fun rollback(connection: Connection, primary: Throwable) {
        try {
            connection.rollback()
        } catch (rollbackFailure: Throwable) {
            primary.addSuppressed(rollbackFailure)
        }
    }
}

internal object WorkflowSlaJdbcDigests {
    fun rowId(domain: String, vararg values: String): String = digest(domain, *values)

    fun operation(domain: String, vararg values: Any?): String {
        val normalized = values.map { value ->
            when (value) {
                null -> null
                is String -> value
                is Number -> value.toString()
                else -> throw IllegalArgumentException("Workflow SLA JDBC digest input is unsupported.")
            }
        }.toTypedArray()
        return digest(domain, *normalized)
    }

    private fun digest(domain: String, vararg values: String?): String {
        val hash = MessageDigest.getInstance("SHA-256")
        update(hash, domain)
        hash.update(ByteBuffer.allocate(4).putInt(values.size).array())
        values.forEach { value ->
            if (value == null) {
                hash.update(0.toByte())
            } else {
                hash.update(1.toByte())
                update(hash, value)
            }
        }
        return hash.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun update(hash: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        hash.update(bytes)
    }
}
