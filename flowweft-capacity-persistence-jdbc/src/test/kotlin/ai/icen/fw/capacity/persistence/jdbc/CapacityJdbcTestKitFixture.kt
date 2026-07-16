package ai.icen.fw.capacity.persistence.jdbc

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityAdmissionRequest
import ai.icen.fw.capacity.api.CapacityDoctorReport
import ai.icen.fw.capacity.api.CapacityDoctorRequest
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseReleaseRequest
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalRequest
import ai.icen.fw.capacity.api.CapacityProviderDescriptor
import ai.icen.fw.capacity.api.CapacityProviderErrorCode
import ai.icen.fw.capacity.api.CapacityProviderResult
import ai.icen.fw.capacity.api.CapacityProviderSpi
import ai.icen.fw.capacity.api.CapacitySnapshotRequest
import ai.icen.fw.capacity.api.CapacityUsageSnapshot
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.testkit.capacity.CapacityContractAssertions
import ai.icen.fw.testkit.capacity.CapacityHierarchyFixture
import ai.icen.fw.testkit.capacity.CapacityMutationEvidenceKey
import ai.icen.fw.testkit.capacity.CapacityPersistenceFaultController
import ai.icen.fw.testkit.capacity.CapacityPersistenceInspection
import ai.icen.fw.testkit.capacity.CapacityProviderContractHarness
import ai.icen.fw.testkit.capacity.CapacityProviderRestartFactory
import ai.icen.fw.testkit.capacity.CapacityTestIntentStatus
import org.h2.jdbcx.JdbcDataSource
import org.h2.tools.RunScript
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier
import javax.sql.DataSource

/**
 * Real H2-backed composition for the reusable Capacity TestKit. H2 runs the shipped MySQL V039
 * migration and the production provider is configured for its MySQL SQL dialect; no in-memory
 * provider or synthetic persistence record participates in these contracts.
 */
internal object CapacityJdbcTestKitFixture {
    fun newHarness(): CapacityProviderContractHarness {
        val hierarchy = CapacityHierarchyFixture.standard()
        val database = ContractDatabase.create(hierarchy)
        database.seedPolicies()
        return database.newHarness()
    }
}

private class ContractDatabase private constructor(
    private val hierarchy: CapacityHierarchyFixture,
    private val dataSource: JdbcDataSource,
    private val faultDataSource: CapacityFaultDataSource,
) {
    private val mutationInvocations = AtomicLong()
    private val inspection = JdbcCapacityPersistenceInspection(
        dataSource,
        hierarchy.providerId,
        mutationInvocations,
    )

    fun seedPolicies() {
        val provider = provider()
        hierarchy.policies.forEachIndexed { index, policy ->
            val receipt = provider.putPolicy(
                CapacityPolicyPutRequest(
                    hierarchy.context(
                        CapacityJdbcPurposes.POLICY_MANAGE,
                        "policy-seed-$index",
                        authorizedScope = ResourceScope.system(),
                    ),
                    policy,
                    0L,
                    hierarchy.nowEpochMilli,
                ),
            )
            check(receipt.status == CapacityPolicyPutStatus.CREATED) {
                "Capacity JDBC contract policy fixture was not created."
            }
        }
    }

    fun newHarness(): CapacityProviderContractHarness {
        val jdbc = provider()
        val scoped = TenantScopedFaultableCapacityProvider(
            jdbc,
            hierarchy.tenantId,
            faultDataSource,
            mutationInvocations,
        )
        return CapacityProviderContractHarness(
            hierarchy,
            scoped,
            jdbc,
            jdbc,
            inspection,
            scoped,
            CapacityProviderRestartFactory { newHarness() },
        )
    }

    private fun provider(): JdbcCapacityProvider = JdbcCapacityProvider(
        faultDataSource,
        hierarchy.providerId,
        CapacityContractAssertions.sha256("capacity-jdbc-contract-configuration"),
        CapacityJdbcDialect.MYSQL,
        LongSupplier { hierarchy.nowEpochMilli },
    )

    companion object {
        fun create(hierarchy: CapacityHierarchyFixture): ContractDatabase {
            val dataSource = JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:capacity-contract-${System.nanoTime()};" +
                        "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
                )
                user = "sa"
                password = ""
            }
            dataSource.connection.use { connection ->
                val resource = requireNotNull(
                    CapacityJdbcTestKitFixture::class.java.getResourceAsStream(
                        CapacityJdbcMigrationDialect.MYSQL.resourcePath,
                    ),
                ) { "Capacity JDBC V039 MySQL migration is unavailable." }
                InputStreamReader(resource, StandardCharsets.UTF_8).use { reader ->
                    RunScript.execute(connection, reader)
                }
            }
            return ContractDatabase(hierarchy, dataSource, CapacityFaultDataSource(dataSource))
        }
    }
}

/**
 * The provider is normally selected after trusted tenant routing. This facade models that host
 * boundary so the contract's foreign-tenant probe cannot create an idempotency row in a tenant
 * for which this isolated provider composition was not selected. All authorized work still runs
 * through the production [JdbcCapacityProvider].
 */
private class TenantScopedFaultableCapacityProvider(
    private val delegate: JdbcCapacityProvider,
    private val tenantId: Identifier,
    private val faults: CapacityFaultDataSource,
    private val mutationInvocations: AtomicLong,
) : CapacityProviderSpi, CapacityPersistenceFaultController {
    override fun descriptor(): CapacityProviderDescriptor = delegate.descriptor()

    override fun snapshot(request: CapacitySnapshotRequest): CapacityProviderResult<CapacityUsageSnapshot> =
        delegate.snapshot(request)

    override fun admit(request: CapacityAdmissionRequest): CapacityProviderResult<CapacityAdmissionDecision> {
        mutationInvocations.incrementAndGet()
        if (request.context.tenantId != tenantId) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        return delegate.admit(request)
    }

    override fun renew(request: CapacityLeaseRenewalRequest): CapacityProviderResult<CapacityLeaseRenewalReceipt> {
        mutationInvocations.incrementAndGet()
        if (request.context.tenantId != tenantId) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        return delegate.renew(request)
    }

    override fun release(request: CapacityLeaseReleaseRequest): CapacityProviderResult<CapacityLeaseReleaseReceipt> {
        mutationInvocations.incrementAndGet()
        if (request.context.tenantId != tenantId) {
            return CapacityProviderResult.failure(CapacityProviderErrorCode.UNAUTHORIZED)
        }
        return delegate.release(request)
    }

    override fun doctor(request: CapacityDoctorRequest): CapacityProviderResult<CapacityDoctorReport> =
        delegate.doctor(request)

    override fun failNextMutationAfterApply() {
        faults.failNextMutationAfterApply()
    }

    override fun leaveNextMutationPrepared() {
        faults.leaveNextMutationPrepared()
    }
}

private enum class CapacityFaultMode {
    FAIL_AFTER_APPLY,
    LEAVE_PREPARED,
}

/**
 * Faults are injected at real JDBC transaction boundaries. FAIL_AFTER_APPLY delegates commit and
 * then reports SQLState 08007. LEAVE_PREPARED waits until the PREPARED insert commits, then fails
 * the production provider's next connection acquisition before the state mutation can begin.
 */
private class CapacityFaultDataSource(
    private val delegate: DataSource,
) : DataSource by delegate, CapacityPersistenceFaultController {
    private val mode = AtomicReference<CapacityFaultMode?>()
    private val failNextConnection = AtomicBoolean()

    override fun getConnection(): Connection = connection(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        connection(delegate.getConnection(username, password))

    override fun failNextMutationAfterApply() {
        check(mode.compareAndSet(null, CapacityFaultMode.FAIL_AFTER_APPLY)) {
            "A Capacity JDBC contract fault is already armed."
        }
    }

    override fun leaveNextMutationPrepared() {
        check(mode.compareAndSet(null, CapacityFaultMode.LEAVE_PREPARED)) {
            "A Capacity JDBC contract fault is already armed."
        }
    }

    private fun connection(connection: Connection): Connection {
        if (failNextConnection.compareAndSet(true, false)) {
            connection.close()
            throw SQLException("simulated connection loss after durable prepare", "08006")
        }
        val transaction = CapacityFaultTransaction()
        return proxy(Connection::class.java, connection) { method, arguments ->
            when (method.name) {
                // H2's SERIALIZABLE emulation aborts the waiting writer with SQLState 40001
                // before it can observe the row locked by the winner. The supported database
                // lanes exercise the production SERIALIZABLE request. This H2 contract lane uses
                // READ_COMMITTED plus the production SELECT FOR UPDATE/CAS SQL so the reusable
                // suite can assert the provider's deterministic loser semantics.
                "setTransactionIsolation" -> {
                    val requested = arguments?.firstOrNull() as? Int
                    if (requested == Connection.TRANSACTION_SERIALIZABLE) {
                        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                        null
                    } else {
                        invoke(method, connection, arguments)
                    }
                }

                "prepareStatement" -> {
                    val statement = invoke(method, connection, arguments) as PreparedStatement
                    val sql = arguments?.firstOrNull() as? String
                    if (sql == null) statement else statement(statement, sql, transaction)
                }

                "commit" -> {
                    invoke(method, connection, arguments)
                    afterCommit(transaction)
                    null
                }

                else -> invoke(method, connection, arguments)
            }
        }
    }

    private fun statement(
        statement: PreparedStatement,
        sql: String,
        transaction: CapacityFaultTransaction,
    ): PreparedStatement {
        val normalized = sql.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        val preparesIntent = normalized.startsWith("insert into fw_capacity_idempotency")
        val completesIntent = normalized.startsWith("update fw_capacity_idempotency set status = ?")
        var status: String? = null
        return proxy(PreparedStatement::class.java, statement) { method, arguments ->
            if (completesIntent && method.name == "setString" && arguments?.getOrNull(0) == 1) {
                status = arguments.getOrNull(1) as? String
            }
            val result = invoke(method, statement, arguments)
            if (method.name == "executeUpdate" && (result as? Int ?: 0) > 0) {
                if (preparesIntent) transaction.preparedIntentInserted = true
                if (completesIntent && status == "APPLIED") transaction.appliedIntentCompleted = true
            }
            result
        }
    }

    private fun afterCommit(transaction: CapacityFaultTransaction) {
        when (mode.get()) {
            CapacityFaultMode.LEAVE_PREPARED -> if (transaction.preparedIntentInserted &&
                mode.compareAndSet(CapacityFaultMode.LEAVE_PREPARED, null)
            ) {
                failNextConnection.set(true)
            }

            CapacityFaultMode.FAIL_AFTER_APPLY -> if (transaction.appliedIntentCompleted &&
                mode.compareAndSet(CapacityFaultMode.FAIL_AFTER_APPLY, null)
            ) {
                throw SQLException("simulated ambiguous commit after canonical apply", "08007")
            }

            null -> Unit
        }
    }

    private class CapacityFaultTransaction {
        var preparedIntentInserted: Boolean = false
        var appliedIntentCompleted: Boolean = false
    }
}

private class JdbcCapacityPersistenceInspection(
    private val dataSource: DataSource,
    private val providerId: Identifier,
    private val mutationInvocations: AtomicLong,
) : CapacityPersistenceInspection {
    override fun intentStatus(key: CapacityMutationEvidenceKey): CapacityTestIntentStatus? =
        intent(key)?.status?.let { status -> CapacityTestIntentStatus.valueOf(status) }

    override fun canonicalOutcomeDigest(key: CapacityMutationEvidenceKey): String? =
        intent(key)?.outcomeDigest

    override fun outboxEvidenceCount(key: CapacityMutationEvidenceKey): Long {
        val outcomeDigest = intent(key)?.outcomeDigest ?: return 0L
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT COUNT(*) FROM fw_capacity_outbox
                   WHERE tenant_id = ? AND provider_id = ? AND operation_code = ?
                     AND evidence_digest = ?""".trimIndent(),
            ).use { statement ->
                statement.setString(1, key.tenantId.value)
                statement.setString(2, providerId.value)
                statement.setString(3, key.operation)
                statement.setString(4, outcomeDigest)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1)
                }
            }
        }
    }

    override fun persistenceFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.isReadOnly = true
            connection.autoCommit = false
            try {
                FINGERPRINT_TABLES.forEach { table -> fingerprintTable(connection, table, digest) }
            } finally {
                connection.rollback()
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    override fun mutationInvocationCount(): Long = mutationInvocations.get()

    private fun intent(key: CapacityMutationEvidenceKey): IntentEvidence? =
        dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.prepareStatement(
                """SELECT status, outcome_digest FROM fw_capacity_idempotency
                   WHERE tenant_id = ? AND provider_id = ? AND operation_code = ?
                     AND scope_digest = ?""".trimIndent(),
            ).use { statement ->
                statement.setString(1, key.tenantId.value)
                statement.setString(2, providerId.value)
                statement.setString(3, key.operation)
                statement.setString(4, key.idempotencyScopeDigest)
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        null
                    } else {
                        val evidence = IntentEvidence(result.getString(1), result.getString(2))
                        check(!result.next()) { "Capacity JDBC inspection found duplicate intent evidence." }
                        evidence
                    }
                }
            }
        }

    private fun fingerprintTable(connection: Connection, table: String, digest: MessageDigest) {
        update(digest, table)
        connection.prepareStatement("SELECT * FROM $table ORDER BY id").use { statement ->
            statement.executeQuery().use { result -> fingerprintRows(result, digest) }
        }
    }

    private fun fingerprintRows(result: ResultSet, digest: MessageDigest) {
        val metadata = result.metaData
        update(digest, metadata.columnCount.toString())
        for (index in 1..metadata.columnCount) update(digest, metadata.getColumnLabel(index))
        while (result.next()) {
            update(digest, "row")
            for (index in 1..metadata.columnCount) {
                when (metadata.getColumnType(index)) {
                    Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                        val value = result.getBytes(index)
                        if (value == null) update(digest, null) else update(digest, value)
                    }

                    else -> update(digest, result.getString(index))
                }
            }
        }
    }

    private fun update(digest: MessageDigest, value: String?) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(4).putInt(-1).array())
        } else {
            update(digest, value.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun update(digest: MessageDigest, value: ByteArray) {
        digest.update(ByteBuffer.allocate(4).putInt(value.size).array())
        digest.update(value)
    }

    private data class IntentEvidence(val status: String, val outcomeDigest: String?)

    companion object {
        private val FINGERPRINT_TABLES = listOf(
            "fw_capacity_policy",
            "fw_capacity_policy_workload",
            "fw_capacity_policy_limit",
            "fw_capacity_policy_degradation",
            "fw_capacity_policy_snapshot",
            "fw_capacity_state",
            "fw_capacity_measure",
            "fw_capacity_reservation",
            "fw_capacity_idempotency",
            "fw_capacity_outbox",
        )
    }
}

private fun <T> proxy(
    contract: Class<T>,
    delegate: T,
    handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?,
): T = contract.cast(
    Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, arguments ->
        handler(method, arguments)
    },
)

private fun invoke(
    method: java.lang.reflect.Method,
    target: Any,
    arguments: Array<out Any?>?,
): Any? = try {
    method.invoke(target, *(arguments ?: emptyArray()))
} catch (failure: InvocationTargetException) {
    throw failure.targetException
}
