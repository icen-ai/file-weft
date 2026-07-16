package ai.icen.fw.observability.jdbc

import ai.icen.fw.observability.SystemDoctorBucket
import ai.icen.fw.observability.SystemDoctorCapability
import ai.icen.fw.observability.SystemDoctorCode
import ai.icen.fw.observability.SystemDoctorProbeRequest
import ai.icen.fw.observability.SystemDoctorProbeSignal
import ai.icen.fw.observability.SystemDoctorProbeState
import ai.icen.fw.observability.SystemDoctorRepairAction
import ai.icen.fw.observability.SystemDoctorScope
import ai.icen.fw.observability.SystemDoctorSeverity
import java.sql.Connection
import java.util.Collections

class JdbcQueueSystemDoctorProbe private constructor(
    access: JdbcSystemDoctorAccess,
    probeId: String,
    private val configuration: QueueProbeConfiguration,
) : AbstractJdbcSystemDoctorProbe(
    access,
    jdbcProbeDescriptor(
        access,
        configuration.capability,
        probeId,
        "queue",
        configuration.definitions.map { definition -> definition.configurationDigest },
    ),
) {
    constructor(
        access: JdbcSystemDoctorAccess,
        probeId: String,
        definitions: Collection<JdbcQueueDefinition>,
    ) : this(access, probeId, QueueProbeConfiguration(definitions))

    override fun inspect(connection: Connection, request: SystemDoctorProbeRequest): JdbcProbeSnapshot {
        var readyCount = 0L
        var oldestReadyAge = 0L
        var highBacklogCount = 0L
        var highOldestReadyAge = 0L
        var failedCount = 0L
        var outcomeUnknownCount = 0L
        var reconciliationPendingCount = 0L
        val now = access.clock.currentTimeMillis()

        configuration.definitions.forEach { definition ->
            val binding = tenantBinding(request, definition.tenantBinding)
            val ready = readReady(connection, request, definition, binding, now)
            readyCount = safeJdbcAdd(readyCount, ready.count)
            oldestReadyAge = maxOf(oldestReadyAge, ready.ageMillis)
            if (ready.count > definition.maximumReadyCount) {
                highBacklogCount = safeJdbcAdd(highBacklogCount, ready.count)
            }
            if (ready.ageMillis > definition.maximumOldestReadyAgeMillis) {
                highOldestReadyAge = maxOf(highOldestReadyAge, ready.ageMillis)
            }
            failedCount = safeJdbcAdd(
                failedCount,
                countStates(connection, request, definition, definition.failedStates, binding),
            )
            outcomeUnknownCount = safeJdbcAdd(
                outcomeUnknownCount,
                countStates(connection, request, definition, definition.outcomeUnknownStates, binding),
            )
            reconciliationPendingCount = safeJdbcAdd(
                reconciliationPendingCount,
                countStates(connection, request, definition, definition.reconciliationPendingStates, binding),
            )
        }
        access.requireAfterQuery(request)

        val signals = ArrayList<SystemDoctorProbeSignal>()
        var degraded = false
        if (highBacklogCount == 0L) {
            signals += healthySignal(
                SystemDoctorCode.QUEUE_BACKLOG_WITHIN_LIMIT,
                readyCount,
                SystemDoctorBucket.WITHIN_LIMIT,
            )
        } else {
            degraded = true
            signals += unhealthySignal(
                SystemDoctorCode.QUEUE_BACKLOG_HIGH,
                highBacklogCount,
                SystemDoctorBucket.ABOVE_LIMIT,
                SystemDoctorRepairAction.SCALE_WORKERS,
            )
        }
        if (highOldestReadyAge == 0L) {
            signals += healthySignal(
                SystemDoctorCode.QUEUE_OLDEST_AGE_WITHIN_LIMIT,
                oldestReadyAge,
                SystemDoctorBucket.WITHIN_LIMIT,
            )
        } else {
            degraded = true
            signals += unhealthySignal(
                SystemDoctorCode.QUEUE_OLDEST_AGE_HIGH,
                highOldestReadyAge,
                SystemDoctorBucket.ABOVE_LIMIT,
                SystemDoctorRepairAction.DRAIN_QUEUE,
            )
        }
        if (failedCount > 0L) {
            degraded = true
            signals += unhealthySignal(
                SystemDoctorCode.QUEUE_FAILED_ITEMS,
                failedCount,
                SystemDoctorBucket.ABOVE_LIMIT,
                SystemDoctorRepairAction.DRAIN_QUEUE,
            )
        }
        if (outcomeUnknownCount > 0L) {
            degraded = true
            signals += SystemDoctorProbeSignal(
                SystemDoctorSeverity.ERROR,
                SystemDoctorCode.QUEUE_OUTCOME_UNKNOWN,
                outcomeUnknownCount,
                SystemDoctorBucket.UNKNOWN,
                SystemDoctorRepairAction.RECONCILE_UNKNOWN_OUTCOMES,
            )
        }
        if (reconciliationPendingCount > 0L) {
            degraded = true
            signals += unhealthySignal(
                SystemDoctorCode.QUEUE_RECONCILIATION_PENDING,
                reconciliationPendingCount,
                SystemDoctorBucket.ABOVE_LIMIT,
                SystemDoctorRepairAction.RECONCILE_UNKNOWN_OUTCOMES,
            )
        }
        return JdbcProbeSnapshot(
            if (degraded) SystemDoctorProbeState.DEGRADED else SystemDoctorProbeState.HEALTHY,
            signals,
        )
    }

    private fun readReady(
        connection: Connection,
        request: SystemDoctorProbeRequest,
        definition: JdbcQueueDefinition,
        tenant: JdbcTenantBinding?,
        now: Long,
    ): QueueReadySnapshot {
        val sql = buildString {
            append("SELECT COUNT(*), MIN(")
            append(definition.createdTimeColumn.sql)
            append(") FROM ")
            append(definition.table.sql)
            append(" WHERE ")
            append(definition.stateColumn.sql)
            append(" IN (")
            append(placeholders(definition.readyStates.size))
            append(')')
            definition.nextEligibleTimeColumn?.let { column ->
                append(" AND (")
                append(column.sql)
                append(" IS NULL OR ")
                append(column.sql)
                append(" <= ?)")
            }
            if (request.scope == SystemDoctorScope.TENANT) {
                append(" AND ")
                append(definition.tenantColumn.sql)
                append(" = ?")
            }
        }
        val statement = access.prepare(connection, request, sql)
        statement.use { bounded ->
            var index = bounded.bindTrustedValues(1, definition.readyStates)
            if (definition.nextEligibleTimeColumn != null) bounded.setLong(index++, now)
            if (tenant != null) bounded.setString(index, tenant.value)
            bounded.executeQuery().use { rows ->
                if (!rows.next()) unavailableJdbcDoctor()
                val count = safeJdbcCount(rows.getLong(1))
                val oldest = rows.getLong(2)
                val oldestMissing = rows.wasNull()
                return QueueReadySnapshot(
                    count,
                    if (oldestMissing || count == 0L) 0L else elapsedAge(now, oldest),
                )
            }
        }
    }

    private fun countStates(
        connection: Connection,
        request: SystemDoctorProbeRequest,
        definition: JdbcQueueDefinition,
        states: Collection<JdbcTrustedValue>,
        tenant: JdbcTenantBinding?,
    ): Long {
        if (states.isEmpty()) return 0L
        val sql = buildString {
            append("SELECT COUNT(*) FROM ")
            append(definition.table.sql)
            append(" WHERE ")
            append(definition.stateColumn.sql)
            append(" IN (")
            append(placeholders(states.size))
            append(')')
            if (request.scope == SystemDoctorScope.TENANT) {
                append(" AND ")
                append(definition.tenantColumn.sql)
                append(" = ?")
            }
        }
        val statement = access.prepare(connection, request, sql)
        statement.use { bounded ->
            val index = bounded.bindTrustedValues(1, states)
            if (tenant != null) bounded.setString(index, tenant.value)
            bounded.executeQuery().use { rows ->
                if (!rows.next()) unavailableJdbcDoctor()
                return safeJdbcCount(rows.getLong(1))
            }
        }
    }
}

class JdbcWorkerLeaseSystemDoctorProbe private constructor(
    access: JdbcSystemDoctorAccess,
    probeId: String,
    private val configuration: LeaseProbeConfiguration,
) : AbstractJdbcSystemDoctorProbe(
    access,
    jdbcProbeDescriptor(
        access,
        SystemDoctorCapability.WORKER_LEASE,
        probeId,
        "worker-lease",
        configuration.definitions.map { definition -> definition.configurationDigest },
    ),
) {
    constructor(
        access: JdbcSystemDoctorAccess,
        probeId: String,
        definitions: Collection<JdbcWorkerLeaseDefinition>,
    ) : this(access, probeId, LeaseProbeConfiguration(definitions))

    override fun inspect(connection: Connection, request: SystemDoctorProbeRequest): JdbcProbeSnapshot {
        var currentCount = 0L
        var unhealthyCount = 0L
        val now = access.clock.currentTimeMillis()
        configuration.definitions.forEach { definition ->
            val binding = tenantBinding(request, definition.tenantBinding)
            val staleBefore = (now - definition.maximumRunningAgeMillis).coerceAtLeast(0L)
            currentCount = safeJdbcAdd(
                currentCount,
                countLeases(connection, request, definition, binding, now, staleBefore, false),
            )
            unhealthyCount = safeJdbcAdd(
                unhealthyCount,
                countLeases(connection, request, definition, binding, now, staleBefore, true),
            )
        }
        access.requireAfterQuery(request)
        val signals = ArrayList<SystemDoctorProbeSignal>()
        signals += healthySignal(
            SystemDoctorCode.WORKER_LEASE_CURRENT,
            currentCount,
            SystemDoctorBucket.CURRENT,
        )
        if (unhealthyCount > 0L) {
            signals += SystemDoctorProbeSignal(
                SystemDoctorSeverity.ERROR,
                SystemDoctorCode.WORKER_LEASE_EXPIRED,
                unhealthyCount,
                SystemDoctorBucket.STALE,
                SystemDoctorRepairAction.RECOVER_EXPIRED_LEASES,
            )
        }
        return JdbcProbeSnapshot(
            if (unhealthyCount == 0L) SystemDoctorProbeState.HEALTHY else SystemDoctorProbeState.DEGRADED,
            signals,
        )
    }

    private fun countLeases(
        connection: Connection,
        request: SystemDoctorProbeRequest,
        definition: JdbcWorkerLeaseDefinition,
        tenant: JdbcTenantBinding?,
        now: Long,
        staleBefore: Long,
        unhealthy: Boolean,
    ): Long {
        val sql = buildString {
            append("SELECT COUNT(*) FROM ")
            append(definition.table.sql)
            append(" WHERE ")
            append(definition.stateColumn.sql)
            append(" IN (")
            append(placeholders(definition.activeStates.size))
            append(") AND ")
            if (unhealthy) {
                append('(')
                append(definition.leaseExpiresTimeColumn.sql)
                append(" IS NULL OR ")
                append(definition.leaseExpiresTimeColumn.sql)
                append(" <= ? OR ")
                append(definition.updatedTimeColumn.sql)
                append(" <= ?)")
            } else {
                append(definition.leaseExpiresTimeColumn.sql)
                append(" IS NOT NULL AND ")
                append(definition.leaseExpiresTimeColumn.sql)
                append(" > ? AND ")
                append(definition.updatedTimeColumn.sql)
                append(" > ?")
            }
            if (request.scope == SystemDoctorScope.TENANT) {
                append(" AND ")
                append(definition.tenantColumn.sql)
                append(" = ?")
            }
        }
        val statement = access.prepare(connection, request, sql)
        statement.use { bounded ->
            var index = bounded.bindTrustedValues(1, definition.activeStates)
            bounded.setLong(index++, now)
            bounded.setLong(index++, staleBefore)
            if (tenant != null) bounded.setString(index, tenant.value)
            bounded.executeQuery().use { rows ->
                if (!rows.next()) unavailableJdbcDoctor()
                return safeJdbcCount(rows.getLong(1))
            }
        }
    }
}

private class QueueProbeConfiguration(definitions: Collection<JdbcQueueDefinition>) {
    val definitions: List<JdbcQueueDefinition>
    val capability: SystemDoctorCapability

    init {
        val snapshot = ArrayList(definitions)
        require(snapshot.isNotEmpty() && snapshot.size <= JdbcDoctorLimits.MAX_DEFINITIONS) {
            "JDBC queue probe definition inventory is invalid."
        }
        require(snapshot.map { definition -> definition.configurationDigest }.distinct().size == snapshot.size) {
            "JDBC queue probe contains duplicate definitions."
        }
        val capabilities = snapshot.map { definition -> definition.workload.capability }.distinct()
        require(capabilities.size == 1) {
            "A JDBC queue probe must aggregate exactly one System Doctor capability."
        }
        this.definitions = Collections.unmodifiableList(snapshot)
        capability = capabilities.single()
    }
}

private class LeaseProbeConfiguration(definitions: Collection<JdbcWorkerLeaseDefinition>) {
    val definitions: List<JdbcWorkerLeaseDefinition>

    init {
        val snapshot = ArrayList(definitions)
        require(snapshot.isNotEmpty() && snapshot.size <= JdbcDoctorLimits.MAX_DEFINITIONS) {
            "JDBC lease probe definition inventory is invalid."
        }
        require(snapshot.map { definition -> definition.configurationDigest }.distinct().size == snapshot.size) {
            "JDBC lease probe contains duplicate definitions."
        }
        this.definitions = Collections.unmodifiableList(snapshot)
    }
}

private data class QueueReadySnapshot(val count: Long, val ageMillis: Long)

private fun elapsedAge(now: Long, oldest: Long): Long = when {
    oldest < 0L || oldest > now -> JdbcDoctorLimits.MAX_AGE_MILLIS
    else -> (now - oldest).coerceAtMost(JdbcDoctorLimits.MAX_AGE_MILLIS)
}

private fun healthySignal(
    code: SystemDoctorCode,
    count: Long,
    bucket: SystemDoctorBucket,
): SystemDoctorProbeSignal = SystemDoctorProbeSignal(
    SystemDoctorSeverity.HEALTHY,
    code,
    count,
    bucket,
    SystemDoctorRepairAction.NONE,
)

private fun unhealthySignal(
    code: SystemDoctorCode,
    count: Long,
    bucket: SystemDoctorBucket,
    repairAction: SystemDoctorRepairAction,
): SystemDoctorProbeSignal = SystemDoctorProbeSignal(
    SystemDoctorSeverity.WARNING,
    code,
    count,
    bucket,
    repairAction,
)
