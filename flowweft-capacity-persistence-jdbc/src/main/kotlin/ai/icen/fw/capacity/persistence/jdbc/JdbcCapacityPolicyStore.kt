package ai.icen.fw.capacity.persistence.jdbc

import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityDimension
import ai.icen.fw.capacity.api.CapacityLimit
import ai.icen.fw.capacity.api.CapacityPolicy
import ai.icen.fw.capacity.api.CapacityPolicyResolution
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityScopeLevel
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.CapacityUnit
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.capacity.runtime.CapacityPolicySource
import ai.icen.fw.capacity.runtime.CapacityPolicySourceRequest
import ai.icen.fw.capacity.runtime.CapacityPolicySourceSnapshot
import ai.icen.fw.core.id.Identifier
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

object CapacityJdbcPurposes {
    @JvmField val POLICY_MANAGE: CapacityPurpose = CapacityPurpose("capacity.policy.manage")
}

/** Trusted, exact-CAS administrative command; the tenant is always taken from [context]. */
class CapacityPolicyPutRequest(
    val context: CapacityTrustedContext,
    val policy: CapacityPolicy,
    val expectedStateVersion: Long,
    val requestedAt: Long,
) {
    init {
        context.requirePurpose(CapacityJdbcPurposes.POLICY_MANAGE)
        context.requireFresh(requestedAt)
        require(expectedStateVersion >= 0L && policy.stateVersion == expectedStateVersion + 1L) {
            "Capacity policy update requires one exact state-version transition."
        }
        require((policy.scope.level == CapacityScopeLevel.SYSTEM || policy.scope.tenantId == context.tenantId) &&
            context.authorizedScope.appliesTo(policy.scope)
        ) { "Capacity policy scope is outside the trusted authorization." }
    }
}

enum class CapacityPolicyPutStatus { CREATED, UPDATED, STATE_CONFLICT }

class CapacityPolicyPutReceipt(
    val status: CapacityPolicyPutStatus,
    val stateVersion: Long,
    val policyBindingDigest: String,
)

/**
 * JDBC policy administration and policy-source implementation. No Spring dependency is required;
 * hosts supply their managed [DataSource] and their own authenticated trusted context.
 */
class JdbcCapacityPolicyStore @JvmOverloads constructor(
    dataSource: DataSource,
    private val providerId: Identifier,
    configuredDialect: CapacityJdbcDialect? = null,
) : CapacityPolicySource {
    private val transactions = CapacityJdbcTransactions(dataSource, configuredDialect)

    fun put(request: CapacityPolicyPutRequest): CapacityPolicyPutReceipt = transactions.transaction { connection, _ ->
        val tenantId = request.context.tenantId.value
        val rowId = policyRowId(tenantId, request.policy.policyId.value)
        val current = currentPolicyVersion(connection, tenantId, rowId)
        if ((current ?: 0L) != request.expectedStateVersion || (current == null && request.expectedStateVersion != 0L)) {
            return@transaction CapacityPolicyPutReceipt(
                CapacityPolicyPutStatus.STATE_CONFLICT,
                current ?: 0L,
                request.policy.bindingDigest,
            )
        }
        if (current == null) insertPolicy(connection, tenantId, rowId, request.policy, request.requestedAt)
        else updatePolicy(connection, tenantId, rowId, request.policy, request.expectedStateVersion, request.requestedAt)
        replaceChildren(connection, tenantId, rowId, request.policy, request.requestedAt)
        insertOutbox(
            connection,
            tenantId,
            providerId.value,
            "capacity.policy.put",
            request.policy.bindingDigest,
            request.requestedAt,
        )
        CapacityPolicyPutReceipt(
            if (current == null) CapacityPolicyPutStatus.CREATED else CapacityPolicyPutStatus.UPDATED,
            request.policy.stateVersion,
            request.policy.bindingDigest,
        )
    }

    override fun snapshot(request: CapacityPolicySourceRequest): CapacityPolicySourceSnapshot =
        transactions.transaction { connection, dialect ->
            require(request.providerId == providerId) { "Capacity policy source provider does not match." }
            val tenantId = request.context.tenantId.value
            val candidates = loadActivePolicies(connection, tenantId, request.requestedAt)
            val applicable = candidates.filter { it.isApplicableTo(request.target, request.workload, request.requestedAt) }
            val revision = CapacityJdbcDigests.digest(
                "flowweft.capacity.jdbc.policy-source.v1",
                tenantId,
                *candidates.sortedBy { it.bindingDigest }.map { it.bindingDigest }.toTypedArray(),
            )
            val snapshot = CapacityPolicySourceSnapshot(
                request,
                applicable,
                CapacityScopeLevel.values().toList(),
                revision,
                request.requestedAt,
                request.deadlineAt,
            )
            if (applicable.isNotEmpty()) {
                val resolution = CapacityPolicyResolution.resolve(
                    request.target,
                    request.workload,
                    applicable,
                    request.requestedAt,
                )
                require(resolution.expiresAt >= request.deadlineAt) {
                    "Capacity policy expires before the requested operation deadline."
                }
                cacheResolution(connection, dialect, tenantId, request, resolution, revision)
            }
            snapshot
        }

    private fun currentPolicyVersion(connection: Connection, tenantId: String, rowId: String): Long? {
        connection.prepareStatement(
            "SELECT state_version FROM fw_capacity_policy WHERE tenant_id = ? AND id = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, rowId)
            statement.executeQuery().use { result -> return if (result.next()) result.getLong(1) else null }
        }
    }

    private fun insertPolicy(
        connection: Connection,
        tenantId: String,
        rowId: String,
        policy: CapacityPolicy,
        now: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO fw_capacity_policy (
                id, tenant_id, policy_id, contract_version, revision, state_version, scope_level,
                scope_tenant_id, scope_provider_id, resource_type, resource_id, effective_time,
                expires_time, enabled, binding_digest, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            bindPolicy(statement = statement, tenantId = tenantId, rowId = rowId, policy = policy, now = now)
            check(statement.executeUpdate() == 1) { "Capacity policy insert did not affect one row." }
        }
    }

    private fun updatePolicy(
        connection: Connection,
        tenantId: String,
        rowId: String,
        policy: CapacityPolicy,
        expectedStateVersion: Long,
        now: Long,
    ) {
        connection.prepareStatement(
            """
            UPDATE fw_capacity_policy SET
                policy_id = ?, contract_version = ?, revision = ?, state_version = ?, scope_level = ?,
                scope_tenant_id = ?, scope_provider_id = ?, resource_type = ?, resource_id = ?,
                effective_time = ?, expires_time = ?, enabled = ?, binding_digest = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND state_version = ?
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            statement.setString(index++, policy.policyId.value)
            statement.setString(index++, policy.contractVersion)
            statement.setString(index++, policy.revision)
            statement.setLong(index++, policy.stateVersion)
            statement.setString(index++, policy.scope.level.name)
            statement.setString(index++, policy.scope.tenantId?.value)
            statement.setString(index++, policy.scope.providerId?.value)
            statement.setString(index++, policy.scope.resourceType)
            statement.setString(index++, policy.scope.resourceId?.value)
            statement.setLong(index++, policy.effectiveFrom)
            statement.setLong(index++, policy.expiresAt)
            statement.setBoolean(index++, policy.enabled)
            statement.setString(index++, policy.bindingDigest)
            statement.setLong(index++, now)
            statement.setString(index++, tenantId)
            statement.setString(index++, rowId)
            statement.setLong(index, expectedStateVersion)
            check(statement.executeUpdate() == 1) { "Capacity policy state changed during update." }
        }
    }

    private fun bindPolicy(
        statement: java.sql.PreparedStatement,
        tenantId: String,
        rowId: String,
        policy: CapacityPolicy,
        now: Long,
    ) {
        var index = 1
        statement.setString(index++, rowId)
        statement.setString(index++, tenantId)
        statement.setString(index++, policy.policyId.value)
        statement.setString(index++, policy.contractVersion)
        statement.setString(index++, policy.revision)
        statement.setLong(index++, policy.stateVersion)
        statement.setString(index++, policy.scope.level.name)
        statement.setString(index++, policy.scope.tenantId?.value)
        statement.setString(index++, policy.scope.providerId?.value)
        statement.setString(index++, policy.scope.resourceType)
        statement.setString(index++, policy.scope.resourceId?.value)
        statement.setLong(index++, policy.effectiveFrom)
        statement.setLong(index++, policy.expiresAt)
        statement.setBoolean(index++, policy.enabled)
        statement.setString(index++, policy.bindingDigest)
        statement.setLong(index++, now)
        statement.setLong(index, now)
    }

    private fun replaceChildren(
        connection: Connection,
        tenantId: String,
        rowId: String,
        policy: CapacityPolicy,
        now: Long,
    ) {
        listOf(
            "DELETE FROM fw_capacity_policy_workload WHERE tenant_id = ? AND policy_row_id = ?",
            "DELETE FROM fw_capacity_policy_limit WHERE tenant_id = ? AND policy_row_id = ?",
            "DELETE FROM fw_capacity_policy_degradation WHERE tenant_id = ? AND policy_row_id = ?",
        ).forEach { sql -> connection.prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId)
            statement.setString(2, rowId)
            statement.executeUpdate()
        } }
        connection.prepareStatement(
            """INSERT INTO fw_capacity_policy_workload
                (id, tenant_id, policy_row_id, workload_kind, created_time, updated_time)
                VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
        ).use { statement -> policy.workloads.forEach { workload ->
            statement.setString(1, CapacityJdbcDigests.rowId("capacity-policy-workload", tenantId, rowId, workload.value))
            statement.setString(2, tenantId)
            statement.setString(3, rowId)
            statement.setString(4, workload.value)
            statement.setLong(5, now)
            statement.setLong(6, now)
            statement.addBatch()
        }; statement.executeBatch() }
        connection.prepareStatement(
            """INSERT INTO fw_capacity_policy_limit
                (id, tenant_id, policy_row_id, dimension_code, unit_code, limit_value,
                 warning_watermark, critical_watermark, created_time, updated_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
        ).use { statement -> policy.limits.forEach { limit ->
            statement.setString(1, CapacityJdbcDigests.rowId("capacity-policy-limit", tenantId, rowId, limit.dimension.code))
            statement.setString(2, tenantId)
            statement.setString(3, rowId)
            statement.setString(4, limit.dimension.code)
            statement.setString(5, limit.dimension.unit.value)
            statement.setLong(6, limit.limit)
            statement.setLong(7, limit.warningWatermark)
            statement.setLong(8, limit.criticalWatermark)
            statement.setLong(9, now)
            statement.setLong(10, now)
            statement.addBatch()
        }; statement.executeBatch() }
        connection.prepareStatement(
            """INSERT INTO fw_capacity_policy_degradation
                (id, tenant_id, policy_row_id, capability_code, created_time, updated_time)
                VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
        ).use { statement -> policy.degradationCapabilities.forEach { degradation ->
            statement.setString(1, CapacityJdbcDigests.rowId("capacity-policy-degradation", tenantId, rowId, degradation.value))
            statement.setString(2, tenantId)
            statement.setString(3, rowId)
            statement.setString(4, degradation.value)
            statement.setLong(5, now)
            statement.setLong(6, now)
            statement.addBatch()
        }; statement.executeBatch() }
    }

    private fun loadActivePolicies(connection: Connection, tenantId: String, at: Long): List<CapacityPolicy> {
        val rows = LinkedHashMap<String, PolicyRow>()
        connection.prepareStatement(
            """
            SELECT id, policy_id, contract_version, revision, state_version, scope_level,
                   scope_tenant_id, scope_provider_id, resource_type, resource_id,
                   effective_time, expires_time, enabled, binding_digest
            FROM fw_capacity_policy
            WHERE tenant_id = ? AND enabled = ? AND effective_time <= ? AND expires_time > ?
            ORDER BY id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.setBoolean(2, true)
            statement.setLong(3, at)
            statement.setLong(4, at)
            statement.executeQuery().use { result -> while (result.next()) {
                val row = PolicyRow.from(result)
                rows[row.rowId] = row
            } }
        }
        if (rows.isEmpty()) return emptyList()
        loadWorkloads(connection, tenantId, rows)
        loadLimits(connection, tenantId, rows)
        loadDegradations(connection, tenantId, rows)
        return rows.values.map { it.toPolicy() }
    }

    private fun loadWorkloads(connection: Connection, tenantId: String, rows: Map<String, PolicyRow>) {
        connection.prepareStatement(
            "SELECT policy_row_id, workload_kind FROM fw_capacity_policy_workload WHERE tenant_id = ? ORDER BY policy_row_id, workload_kind",
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.executeQuery().use { result -> while (result.next()) {
                rows[result.getString(1)]?.workloads?.add(WorkloadKind(result.getString(2)))
            } }
        }
    }

    private fun loadLimits(connection: Connection, tenantId: String, rows: Map<String, PolicyRow>) {
        connection.prepareStatement(
            """SELECT policy_row_id, dimension_code, unit_code, limit_value, warning_watermark, critical_watermark
               FROM fw_capacity_policy_limit WHERE tenant_id = ? ORDER BY policy_row_id, dimension_code""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.executeQuery().use { result -> while (result.next()) {
                rows[result.getString(1)]?.limits?.add(CapacityLimit(
                    CapacityDimension(result.getString(2), CapacityUnit(result.getString(3))),
                    result.getLong(4), result.getLong(5), result.getLong(6),
                ))
            } }
        }
    }

    private fun loadDegradations(connection: Connection, tenantId: String, rows: Map<String, PolicyRow>) {
        connection.prepareStatement(
            """SELECT policy_row_id, capability_code FROM fw_capacity_policy_degradation
               WHERE tenant_id = ? ORDER BY policy_row_id, capability_code""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId)
            statement.executeQuery().use { result -> while (result.next()) {
                rows[result.getString(1)]?.degradations?.add(CapacityDegradationCapability(result.getString(2)))
            } }
        }
    }

    private fun cacheResolution(
        connection: Connection,
        dialect: CapacityJdbcDialect,
        tenantId: String,
        request: CapacityPolicySourceRequest,
        resolution: CapacityPolicyResolution,
        revision: String,
    ) {
        val id = CapacityJdbcDigests.rowId(
            "capacity-policy-snapshot",
            tenantId,
            providerId.value,
            request.target.bindingDigest,
            request.workload.value,
            resolution.resolutionDigest,
        )
        val memento = CapacityJdbcCanonicalCodec.encodeResolution(resolution)
        connection.prepareStatement(dialect.policySnapshotUpsertSql()).use { statement ->
            statement.setString(1, id)
            statement.setString(2, tenantId)
            statement.setString(3, providerId.value)
            statement.setString(4, request.target.bindingDigest)
            statement.setString(5, request.workload.value)
            statement.setString(6, resolution.resolutionDigest)
            statement.setString(7, revision)
            statement.setBytes(8, memento)
            statement.setLong(9, resolution.observedAt)
            statement.setLong(10, resolution.expiresAt)
            statement.setLong(11, request.requestedAt)
            statement.setLong(12, request.requestedAt)
            statement.executeUpdate()
        }
    }

    private data class PolicyRow(
        val rowId: String,
        val policyId: Identifier,
        val contractVersion: String,
        val revision: String,
        val stateVersion: Long,
        val scope: ResourceScope,
        val effectiveFrom: Long,
        val expiresAt: Long,
        val enabled: Boolean,
        val storedDigest: String,
        val workloads: MutableList<WorkloadKind> = mutableListOf(),
        val limits: MutableList<CapacityLimit> = mutableListOf(),
        val degradations: MutableList<CapacityDegradationCapability> = mutableListOf(),
    ) {
        fun toPolicy(): CapacityPolicy = CapacityPolicy(
            policyId, contractVersion, revision, stateVersion, scope, workloads, limits,
            effectiveFrom, expiresAt, degradations, enabled,
        ).also { require(it.bindingDigest == storedDigest) { "Capacity policy row digest is invalid." } }

        companion object {
            fun from(result: ResultSet): PolicyRow {
                val level = CapacityScopeLevel.valueOf(result.getString("scope_level"))
                val tenant = result.getString("scope_tenant_id")?.let(::Identifier)
                val provider = result.getString("scope_provider_id")?.let(::Identifier)
                val resourceType = result.getString("resource_type")
                val resource = result.getString("resource_id")?.let(::Identifier)
                val scope = when (level) {
                    CapacityScopeLevel.SYSTEM -> ResourceScope.system()
                    CapacityScopeLevel.TENANT -> ResourceScope.tenant(requireNotNull(tenant))
                    CapacityScopeLevel.PROVIDER -> ResourceScope.provider(requireNotNull(tenant), requireNotNull(provider))
                    CapacityScopeLevel.RESOURCE -> ResourceScope.resource(
                        requireNotNull(tenant), requireNotNull(resourceType), requireNotNull(resource), provider,
                    )
                }
                return PolicyRow(
                    result.getString("id"), Identifier(result.getString("policy_id")),
                    result.getString("contract_version"), result.getString("revision"),
                    result.getLong("state_version"), scope, result.getLong("effective_time"),
                    result.getLong("expires_time"), result.getBoolean("enabled"),
                    result.getString("binding_digest"),
                )
            }
        }
    }

    companion object {
        internal fun policyRowId(tenantId: String, policyId: String): String =
            CapacityJdbcDigests.rowId("capacity-policy", tenantId, policyId)
    }
}

internal fun insertOutbox(
    connection: Connection,
    tenantId: String,
    providerId: String,
    operation: String,
    evidenceDigest: String,
    now: Long,
) {
    val id = CapacityJdbcDigests.rowId("capacity-outbox", tenantId, providerId, operation, evidenceDigest)
    connection.prepareStatement(
        """INSERT INTO fw_capacity_outbox
           (id, tenant_id, provider_id, operation_code, aggregate_digest, event_code, evidence_digest,
            status, attempt_count, available_time, published_time, created_time, updated_time)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
    ).use { statement ->
        statement.setString(1, id)
        statement.setString(2, tenantId)
        statement.setString(3, providerId)
        statement.setString(4, operation)
        statement.setString(5, evidenceDigest)
        statement.setString(6, "$operation.applied")
        statement.setString(7, evidenceDigest)
        statement.setString(8, "PENDING")
        statement.setLong(9, 0L)
        statement.setLong(10, now)
        statement.setObject(11, null)
        statement.setLong(12, now)
        statement.setLong(13, now)
        statement.executeUpdate()
    }
}
