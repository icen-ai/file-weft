package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.outbox.OutboxEventLease
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.application.outbox.OutboxEventState
import ai.icen.fw.application.outbox.OutboxEventStatus
import ai.icen.fw.application.outbox.LeasedOutboxProcessingRepository
import ai.icen.fw.application.outbox.OutboxLeaseClaim
import ai.icen.fw.application.outbox.OutboxLeaseLostException
import ai.icen.fw.application.outbox.OutboxProcessingRepository
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import java.sql.ResultSet
import java.util.UUID

/** PostgreSQL outbox persistence using SKIP LOCKED for multi-worker leasing. */
class JdbcOutboxProcessingRepository(
    private val objectMapper: ObjectMapper,
) : LeasedOutboxProcessingRepository, OutboxEventMutationRepository {
    override fun findForMutation(tenantId: Identifier, eventId: Identifier): OutboxEventState? =
        JdbcConnectionContext.requireCurrent().prepareStatement(FIND_FOR_MUTATION_SQL).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, eventId.value)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    mapState(result)
                }
            }
        }

    /**
     * Retains the original port for callers compiled before persisted leases.
     * JDBC still creates a bounded token lease so its own acknowledgements are
     * protected; legacy custom repositories can continue to implement only
     * [OutboxProcessingRepository].
     */
    override fun claimAvailable(limit: Int, now: Long): List<OutboxEventLease> =
        claimAvailable(limit, now, legacyClaim(now))

    override fun claimAvailable(limit: Int, now: Long, claim: OutboxLeaseClaim): List<OutboxEventLease> {
        require(limit > 0) { "Outbox claim limit must be positive." }
        require(now >= 0) { "Outbox claim time must not be negative." }
        require(claim.leaseExpiresAt > now) { "Outbox lease expiry must be after claim time." }
        require(claim.legacyRunningBefore <= now) { "Outbox legacy running cutoff must not be after claim time." }
        val dialect = JdbcConnectionContext.requireDialect()
        val connection = JdbcConnectionContext.requireCurrent()

        val candidateIds = connection.prepareStatement(
            "${candidateSelectSql(dialect.claimCandidateTable(OUTBOX_TABLE, CLAIM_ORDER_INDEX))} " +
                "${dialect.limitClause()} ${dialect.forUpdateSkipLocked()}",
        ).use { statement ->
            statement.setLong(1, now)
            statement.setLong(2, now)
            statement.setLong(3, claim.legacyRunningBefore)
            statement.setInt(4, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getString("id"))
                }
            }
        }
        if (candidateIds.isEmpty()) return emptyList()

        val placeholders = candidateIds.joinToString(",") { "?" }
        val updated = connection.prepareStatement(
            "UPDATE fw_outbox_event SET event_status = 'RUNNING', lease_owner = ?, lease_token = ?, lease_expire_time = ?, updated_time = ?, last_error = NULL WHERE id IN ($placeholders)",
        ).use { statement ->
            statement.setString(1, claim.leaseOwner)
            statement.setString(2, claim.leaseToken)
            statement.setLong(3, claim.leaseExpiresAt)
            statement.setLong(4, now)
            candidateIds.forEachIndexed { index, id -> statement.setString(index + 5, id) }
            statement.executeUpdate()
        }
        if (updated == 0) return emptyList()

        return connection.prepareStatement(
            "$SELECT_COLUMNS FROM fw_outbox_event WHERE id IN ($placeholders) AND event_status = 'RUNNING'",
        ).use { statement ->
            candidateIds.forEachIndexed { index, id -> statement.setString(index + 1, id) }
            statement.executeQuery().use { result ->
                val leases = ArrayList<OutboxEventLease>()
                while (result.next()) leases += mapLease(result)
                leases
            }
        }
    }

    override fun markSucceeded(lease: OutboxEventLease, completedAt: Long) {
        require(completedAt >= 0) { "Outbox completion time must not be negative." }
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_outbox_event SET event_status = 'SUCCESS', next_attempt_time = 0, lease_owner = NULL, lease_token = NULL, lease_expire_time = 0, last_error = NULL, updated_time = ? WHERE id = ? AND tenant_id = ? AND event_status = 'RUNNING'${leasePredicate(lease)}",
        ).use { statement ->
            statement.setLong(1, completedAt)
            bindLease(statement, lease, 2)
            requireRunningTransition(statement.executeUpdate(), lease)
        }
    }

    override fun markForRetry(lease: OutboxEventLease, nextAttemptAt: Long, message: String, updatedAt: Long) {
        require(nextAttemptAt >= 0) { "Outbox next attempt time must not be negative." }
        require(updatedAt >= 0) { "Outbox update time must not be negative." }
        require(message.isNotBlank()) { "Outbox retry message must not be blank." }
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_outbox_event SET event_status = 'RETRY', retry_count = retry_count + 1, next_attempt_time = ?, lease_owner = NULL, lease_token = NULL, lease_expire_time = 0, last_error = ?, updated_time = ? WHERE id = ? AND tenant_id = ? AND event_status = 'RUNNING'${leasePredicate(lease)}",
        ).use { statement ->
            statement.setLong(1, nextAttemptAt)
            statement.setString(2, message)
            statement.setLong(3, updatedAt)
            bindLease(statement, lease, 4)
            requireRunningTransition(statement.executeUpdate(), lease)
        }
    }

    override fun markFailed(lease: OutboxEventLease, message: String, updatedAt: Long) {
        require(updatedAt >= 0) { "Outbox update time must not be negative." }
        require(message.isNotBlank()) { "Outbox failure message must not be blank." }
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_outbox_event SET event_status = 'FAILED', lease_owner = NULL, lease_token = NULL, lease_expire_time = 0, last_error = ?, updated_time = ? WHERE id = ? AND tenant_id = ? AND event_status = 'RUNNING'${leasePredicate(lease)}",
        ).use { statement ->
            statement.setString(1, message)
            statement.setLong(2, updatedAt)
            bindLease(statement, lease, 3)
            requireRunningTransition(statement.executeUpdate(), lease)
        }
    }

    private fun bindLease(statement: java.sql.PreparedStatement, lease: OutboxEventLease, offset: Int) {
        statement.setString(offset, lease.event.id.value)
        statement.setString(offset + 1, lease.event.tenantId.value)
        lease.leaseToken?.let { token ->
            statement.setString(offset + 2, requireNotNull(lease.leaseOwner))
            statement.setString(offset + 3, token)
        }
    }

    private fun leasePredicate(lease: OutboxEventLease): String =
        if (lease.leaseToken == null) " AND lease_token IS NULL" else " AND lease_owner = ? AND lease_token = ?"

    private fun requireRunningTransition(updated: Int, lease: OutboxEventLease) {
        if (updated != 1) {
            throw OutboxLeaseLostException(
                "Outbox event ${lease.event.id.value} is no longer leased by the current worker in the current tenant.",
            )
        }
    }

    private fun mapLease(result: ResultSet): OutboxEventLease = OutboxEventLease(
        event = OutboxEvent(
            id = Identifier(result.getString("id")),
            tenantId = Identifier(result.getString("tenant_id")),
            type = result.getString("event_type"),
            payload = objectMapper.readValue(result.getString("payload_json"), STRING_MAP_TYPE),
            timestamp = result.getLong("created_time"),
            traceId = result.getString("trace_id")?.let(::Identifier),
        ),
        retryCount = result.getInt("retry_count"),
        leaseOwner = result.getString("lease_owner") ?: error("Claimed outbox event is missing its lease owner."),
        leaseToken = result.getString("lease_token") ?: error("Claimed outbox event is missing its lease token."),
    )

    private fun mapState(result: ResultSet): OutboxEventState = OutboxEventState(
        id = Identifier(result.getString("id")),
        tenantId = Identifier(result.getString("tenant_id")),
        status = OutboxEventStatus.valueOf(result.getString("event_status")),
        leaseOwner = result.getString("lease_owner"),
        leaseToken = result.getString("lease_token"),
        eventType = result.getString("event_type"),
    )

    private fun legacyClaim(now: Long): OutboxLeaseClaim {
        val expiresAt = safeAdd(now, LEGACY_LEASE_DURATION_MILLIS)
        require(expiresAt > now) { "Outbox lease expiry cannot be represented after the current claim time." }
        return OutboxLeaseClaim(
            LEGACY_LEASE_OWNER,
            UUID.randomUUID().toString(),
            expiresAt,
            safeSubtract(now, LEGACY_RUNNING_GRACE_MILLIS),
        )
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private fun safeSubtract(value: Long, decrement: Long): Long =
        if (value < decrement) 0 else value - decrement

    private companion object {
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}

        const val LEGACY_LEASE_OWNER = "fileweft-legacy-outbox"
        const val LEGACY_LEASE_DURATION_MILLIS = 300_000L
        const val LEGACY_RUNNING_GRACE_MILLIS = 300_000L

        const val SELECT_COLUMNS = "SELECT id, tenant_id, event_type, payload_json, trace_id, retry_count, created_time, lease_owner, lease_token"
        const val OUTBOX_TABLE = "fw_outbox_event"
        const val CLAIM_ORDER_INDEX = "idx_fw_outbox_claim_order"

        fun candidateSelectSql(tableExpression: String): String = """
            SELECT id
            FROM $tableExpression
            WHERE (event_status IN ('PENDING', 'RETRY') AND next_attempt_time <= ?)
               OR (event_status = 'RUNNING' AND (
                    (lease_token IS NOT NULL AND lease_expire_time <= ?)
                    OR (lease_token IS NULL AND updated_time <= ?)
               ))
            ORDER BY created_time, id
        """.trimIndent()

        const val FIND_FOR_MUTATION_SQL = """
            SELECT id, tenant_id, event_type, event_status, lease_owner, lease_token
            FROM fw_outbox_event
            WHERE tenant_id = ? AND id = ?
            FOR UPDATE
        """
    }
}
