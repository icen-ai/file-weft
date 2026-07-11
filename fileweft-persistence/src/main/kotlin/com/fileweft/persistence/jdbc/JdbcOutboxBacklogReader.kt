package com.fileweft.persistence.jdbc

import com.fileweft.application.outbox.OutboxBacklogReader
import com.fileweft.application.outbox.OutboxBacklogSnapshot
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Reads a global operational snapshot of the durable outbox inside the
 * caller's short JDBC transaction. The result deliberately has no tenant
 * dimension: it is intended for bounded-cardinality process metrics.
 */
class JdbcOutboxBacklogReader @JvmOverloads constructor(
    private val queryTimeoutSeconds: Int = DEFAULT_QUERY_TIMEOUT_SECONDS,
) : OutboxBacklogReader {
    init {
        require(queryTimeoutSeconds > 0) { "Outbox backlog query timeout must be positive." }
    }

    override fun snapshot(now: Long, legacyRunningBefore: Long): OutboxBacklogSnapshot {
        require(now >= 0) { "Outbox backlog observation time must not be negative." }
        require(legacyRunningBefore >= 0) { "Outbox legacy running cutoff must not be negative." }
        require(legacyRunningBefore <= now) { "Outbox legacy running cutoff must not be after observation time." }

        return JdbcConnectionContext.requireCurrent().prepareStatement(SNAPSHOT_SQL).use { statement ->
            statement.queryTimeout = queryTimeoutSeconds
            statement.bindObservation(now, legacyRunningBefore)
            statement.executeQuery().use { result ->
                check(result.next()) { "Outbox backlog aggregate query returned no row." }
                result.toSnapshot()
            }
        }
    }

    private fun PreparedStatement.bindObservation(now: Long, legacyRunningBefore: Long) {
        setLong(1, now)
        setLong(2, now)
        setLong(3, now)
        setLong(4, legacyRunningBefore)
        setLong(5, now)
        setLong(6, legacyRunningBefore)
        setLong(7, now)
    }

    private fun ResultSet.toSnapshot(): OutboxBacklogSnapshot {
        val oldestReadyCreatedTime = getLong("oldest_ready_created_time").let { value ->
            if (wasNull()) null else value
        }
        return OutboxBacklogSnapshot(
            readyCount = getLong("ready_count"),
            delayedCount = getLong("delayed_count"),
            runningCount = getLong("running_count"),
            expiredCount = getLong("expired_count"),
            failedCount = getLong("failed_count"),
            oldestReadyCreatedTime = oldestReadyCreatedTime,
        )
    }

    private companion object {
        const val DEFAULT_QUERY_TIMEOUT_SECONDS = 5

        const val SNAPSHOT_SQL = """
            SELECT
                COUNT(CASE
                    WHEN event_status IN ('PENDING', 'RETRY') AND next_attempt_time <= ? THEN 1
                END) AS ready_count,
                COUNT(CASE
                    WHEN event_status IN ('PENDING', 'RETRY') AND next_attempt_time > ? THEN 1
                END) AS delayed_count,
                COUNT(CASE
                    WHEN event_status = 'RUNNING'
                        AND (
                            (lease_token IS NOT NULL AND lease_expire_time <= ?)
                            OR (lease_token IS NULL AND updated_time <= ?)
                        )
                    THEN 1
                END) AS expired_count,
                COUNT(CASE
                    WHEN event_status = 'RUNNING'
                        AND NOT (
                            (lease_token IS NOT NULL AND lease_expire_time <= ?)
                            OR (lease_token IS NULL AND updated_time <= ?)
                        )
                    THEN 1
                END) AS running_count,
                COUNT(CASE WHEN event_status = 'FAILED' THEN 1 END) AS failed_count,
                MIN(CASE
                    WHEN event_status IN ('PENDING', 'RETRY') AND next_attempt_time <= ? THEN created_time
                END) AS oldest_ready_created_time
            FROM fw_outbox_event
            WHERE event_status IN ('PENDING', 'RETRY', 'RUNNING', 'FAILED')
        """
    }
}
