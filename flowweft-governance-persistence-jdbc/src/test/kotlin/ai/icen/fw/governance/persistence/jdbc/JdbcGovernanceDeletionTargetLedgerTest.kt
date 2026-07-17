package ai.icen.fw.governance.persistence.jdbc

import ai.icen.fw.governance.api.GovernanceFailure
import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.runtime.GovernanceDeletionTarget
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperation
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperationStatus
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOutcome
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetManifest
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetRequest
import ai.icen.fw.governance.runtime.GovernanceStoreCode
import ai.icen.fw.governance.runtime.InMemoryGovernanceDeletionTargetLedger
import org.h2.jdbcx.JdbcDataSource
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcGovernanceDeletionTargetLedgerTest {
    @Test
    fun `in-memory contract rejects re-prepare after item mutation starts`() {
        val repository = InMemoryGovernanceDeletionTargetLedger()
        val scenario = GovernanceJdbcTestFixture.targetLedgerScenario()
        assertEquals(GovernanceStoreCode.STORED, repository.createIfAbsent(scenario.manifest).code)
        val prepared = GovernanceDeletionTargetItemOperation.prepared(
            scenario.request,
            scenario.manifest,
            scenario.item,
            "in-memory-object-operation-1",
            2_001L,
        )
        assertEquals(GovernanceStoreCode.STORED, repository.prepare(prepared).code)
        val started = GovernanceDeletionTargetItemOperation.markStarted(prepared, 2_002L)
        assertEquals(GovernanceStoreCode.STORED, repository.compareAndSet(prepared, started).code)
        assertEquals(GovernanceStoreCode.REPLAYED, repository.compareAndSet(prepared, started).code)
        assertEquals(GovernanceStoreCode.CONFLICT, repository.prepare(prepared).code)
    }

    @Test
    fun `manifest is canonical idempotent tenant partitioned and exact-step bound`() {
        val repository = repository()
        val scenario = GovernanceJdbcTestFixture.targetLedgerScenario()

        assertEquals(GovernanceStoreCode.STORED, repository.createIfAbsent(scenario.manifest).code)
        assertEquals(GovernanceStoreCode.REPLAYED, repository.createIfAbsent(scenario.manifest).code)
        assertEquals(
            scenario.manifest.manifestDigest,
            assertNotNull(repository.findExact(scenario.request)).manifestDigest,
        )
        assertNull(repository.findByPreparation("another-tenant", scenario.manifest.preparationDigest))

        val planningRequest = GovernanceDeletionTargetRequest.of(
            scenario.request.plan.context,
            scenario.request.plan.assessment.assessmentDigest,
        )
        val conflictingTarget = GovernanceDeletionTarget.of(
            scenario.manifest.stage,
            "different-target-reference",
            scenario.manifest.targetRevision,
            scenario.manifest.targetDigest,
        )
        val conflicting = GovernanceDeletionTargetManifest.of(
            planningRequest, conflictingTarget, scenario.manifest.items,
        )
        assertEquals(GovernanceStoreCode.CONFLICT, repository.createIfAbsent(conflicting).code)
    }

    @Test
    fun `each item checkpoints start once and only read-only reconciliation resolves unknown outcome`() {
        val repository = repository()
        val scenario = GovernanceJdbcTestFixture.targetLedgerScenario()
        assertEquals(GovernanceStoreCode.STORED, repository.createIfAbsent(scenario.manifest).code)

        val prepared = GovernanceDeletionTargetItemOperation.prepared(
            scenario.request,
            scenario.manifest,
            scenario.item,
            "object-delete-operation-1",
            2_001L,
        )
        assertEquals(GovernanceStoreCode.STORED, repository.prepare(prepared).code)
        assertEquals(GovernanceStoreCode.REPLAYED, repository.prepare(prepared).code)

        val started = GovernanceDeletionTargetItemOperation.markStarted(prepared, 2_002L)
        assertEquals(GovernanceStoreCode.STORED, repository.compareAndSet(prepared, started).code)
        assertEquals(GovernanceStoreCode.REPLAYED, repository.compareAndSet(prepared, started).code)
        assertEquals(GovernanceStoreCode.CONFLICT, repository.prepare(prepared).code)

        val unknownFailure = GovernanceFailure.of(
            GovernanceFailureClass.OUTCOME_UNKNOWN,
            "object-delete-outcome-unknown",
            false,
            true,
        )
        val unknown = GovernanceDeletionTargetItemOperation.recordProviderOutcome(
            started,
            GovernanceDeletionTargetItemOutcome.outcomeUnknown(
                started,
                GovernanceJdbcTestFixture.digest('9'),
                unknownFailure,
                2_003L,
            ),
        )
        assertEquals(GovernanceStoreCode.STORED, repository.compareAndSet(started, unknown).code)

        val loadedUnknown = assertNotNull(repository.load(unknown.binding, unknown.itemBindingDigest))
        assertEquals(GovernanceDeletionTargetItemOperationStatus.OUTCOME_UNKNOWN, loadedUnknown.status)
        assertEquals(unknown.stateDigest, loadedUnknown.stateDigest)

        val reconciliationRequest = GovernanceJdbcTestFixture.reconciliationRequest(
            scenario,
            unknownFailure,
            reconciliationRequestedAt = 2_004L,
            observedAtEpochMilli = 2_003L,
        )
        val reconciled = GovernanceDeletionTargetItemOperation.recordReconciliation(
            loadedUnknown,
            reconciliationRequest,
            GovernanceDeletionTargetItemOutcome.verifiedAbsent(
                loadedUnknown,
                "object-absence-evidence-1",
                GovernanceJdbcTestFixture.digest('a'),
                2_010L,
            ),
            reconciliationStartedAtEpochMilli = 2_006L,
        )
        assertEquals(GovernanceStoreCode.STORED, repository.compareAndSet(loadedUnknown, reconciled).code)
        assertEquals(
            GovernanceDeletionTargetItemOperationStatus.VERIFIED_ABSENT,
            assertNotNull(repository.load(reconciled.binding, reconciled.itemBindingDigest)).status,
        )

        val staleCandidate = GovernanceDeletionTargetItemOperation.recordProviderOutcome(
            started,
            GovernanceDeletionTargetItemOutcome.permanentFailure(
                started,
                "object-delete-failure-1",
                GovernanceJdbcTestFixture.digest('c'),
                GovernanceFailure.of(
                    GovernanceFailureClass.PERMANENT_FAILURE,
                    "object-delete-terminal",
                    false,
                    false,
                ),
                2_005L,
            ),
        )
        assertEquals(GovernanceStoreCode.CONFLICT, repository.compareAndSet(started, staleCandidate).code)
    }

    @Test
    fun `provider operation reference cannot be reused for another item in one tenant`() {
        val repository = repository()
        val scenario = GovernanceJdbcTestFixture.targetLedgerScenario()
        assertEquals(GovernanceStoreCode.STORED, repository.createIfAbsent(scenario.manifest).code)
        val operationReference = "tenant-provider-operation-1"
        val first = GovernanceDeletionTargetItemOperation.prepared(
            scenario.request, scenario.manifest, scenario.item, operationReference, 2_001L,
        )
        val second = GovernanceDeletionTargetItemOperation.prepared(
            scenario.request, scenario.manifest, scenario.secondItem, operationReference, 2_001L,
        )

        assertEquals(GovernanceStoreCode.STORED, repository.prepare(first).code)
        assertEquals(GovernanceStoreCode.CONFLICT, repository.prepare(second).code)
        assertNull(repository.load(second.binding, second.itemBindingDigest))
        assertEquals(
            first.stateDigest,
            assertNotNull(repository.load(first.binding, first.itemBindingDigest)).stateDigest,
        )
    }

    @Test
    fun `row fields and memento bytes are both fail closed on read`() {
        val rowDataSource = migratedDataSource()
        val rowRepository = JdbcGovernanceDeletionTargetLedger(rowDataSource, GovernanceJdbcDialect.POSTGRESQL)
        val scenario = GovernanceJdbcTestFixture.targetLedgerScenario()
        assertEquals(GovernanceStoreCode.STORED, rowRepository.createIfAbsent(scenario.manifest).code)
        rowDataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE fw_governance_deletion_target_manifest
                   SET target_reference = ?
                   WHERE tenant_id = ? AND preparation_digest = ?""".trimIndent(),
            ).use { statement ->
                statement.setString(1, "tampered-target-reference")
                statement.setString(2, scenario.manifest.tenantId)
                statement.setString(3, scenario.manifest.preparationDigest)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            rowRepository.findByPreparation(scenario.manifest.tenantId, scenario.manifest.preparationDigest)
        }

        val mementoDataSource = migratedDataSource()
        val mementoRepository = JdbcGovernanceDeletionTargetLedger(
            mementoDataSource, GovernanceJdbcDialect.POSTGRESQL,
        )
        val mementoScenario = GovernanceJdbcTestFixture.targetLedgerScenario()
        assertEquals(GovernanceStoreCode.STORED, mementoRepository.createIfAbsent(mementoScenario.manifest).code)
        mementoDataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE fw_governance_deletion_target_manifest
                   SET manifest_memento = ?
                   WHERE tenant_id = ? AND preparation_digest = ?""".trimIndent(),
            ).use { statement ->
                statement.setBytes(1, byteArrayOf(1, 2, 3, 4))
                statement.setString(2, mementoScenario.manifest.tenantId)
                statement.setString(3, mementoScenario.manifest.preparationDigest)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertFailsWith<IllegalArgumentException> {
            mementoRepository.findByPreparation(
                mementoScenario.manifest.tenantId,
                mementoScenario.manifest.preparationDigest,
            )
        }
    }

    @Test
    fun `lost commit acknowledgement is replayed only after an exact canonical reread`() {
        val dataSource = migratedDataSource()
        val faults = CommitFaultDataSource(dataSource)
        val repository = JdbcGovernanceDeletionTargetLedger(faults, GovernanceJdbcDialect.POSTGRESQL)
        val scenario = GovernanceJdbcTestFixture.targetLedgerScenario()

        faults.failNextCommit(afterDelegateCommit = true)
        assertEquals(GovernanceStoreCode.REPLAYED, repository.createIfAbsent(scenario.manifest).code)

        val prepared = GovernanceDeletionTargetItemOperation.prepared(
            scenario.request,
            scenario.manifest,
            scenario.item,
            "commit-unknown-operation-1",
            2_001L,
        )
        faults.failNextCommit(afterDelegateCommit = true)
        assertEquals(GovernanceStoreCode.REPLAYED, repository.prepare(prepared).code)

        val started = GovernanceDeletionTargetItemOperation.markStarted(prepared, 2_002L)
        faults.failNextCommit(afterDelegateCommit = true)
        assertEquals(GovernanceStoreCode.REPLAYED, repository.compareAndSet(prepared, started).code)
        assertEquals(
            started.stateDigest,
            assertNotNull(repository.load(started.binding, started.itemBindingDigest)).stateDigest,
        )
    }

    @Test
    fun `unreadable post-commit state remains outcome unknown and is never retried`() {
        val dataSource = migratedDataSource()
        val faults = CommitFaultDataSource(dataSource)
        val repository = JdbcGovernanceDeletionTargetLedger(faults, GovernanceJdbcDialect.POSTGRESQL)
        val scenario = GovernanceJdbcTestFixture.targetLedgerScenario()

        faults.failNextCommit(afterDelegateCommit = true, failRereadBorrow = true)
        assertEquals(GovernanceStoreCode.OUTCOME_UNKNOWN, repository.createIfAbsent(scenario.manifest).code)
        assertEquals(1, faults.commitAttempts)
        assertEquals(
            scenario.manifest.manifestDigest,
            assertNotNull(repository.findByPreparation(
                scenario.manifest.tenantId,
                scenario.manifest.preparationDigest,
            )).manifestDigest,
        )

        val rolledBackDataSource = migratedDataSource()
        val rolledBackFaults = CommitFaultDataSource(rolledBackDataSource)
        val rolledBackRepository = JdbcGovernanceDeletionTargetLedger(
            rolledBackFaults, GovernanceJdbcDialect.POSTGRESQL,
        )
        val rolledBackScenario = GovernanceJdbcTestFixture.targetLedgerScenario()
        rolledBackFaults.failNextCommit(afterDelegateCommit = false)
        assertEquals(
            GovernanceStoreCode.OUTCOME_UNKNOWN,
            rolledBackRepository.createIfAbsent(rolledBackScenario.manifest).code,
        )
        assertEquals(1, rolledBackFaults.commitAttempts)
        assertNull(rolledBackRepository.findByPreparation(
            rolledBackScenario.manifest.tenantId,
            rolledBackScenario.manifest.preparationDigest,
        ))
    }

    private fun repository(): JdbcGovernanceDeletionTargetLedger {
        return JdbcGovernanceDeletionTargetLedger(migratedDataSource(), GovernanceJdbcDialect.POSTGRESQL)
    }

    private fun migratedDataSource(): JdbcDataSource {
        val dataSource = JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:governance-target-${UUID.randomUUID()};" +
                    "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            )
            setUser("sa")
            setPassword("")
        }
        val migration = assertNotNull(
            javaClass.getResource(GovernanceJdbcMigrationDialect.POSTGRESQL.targetLedgerResourcePath),
        ).readText(Charsets.UTF_8)
        dataSource.connection.use { connection ->
            migration.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { sql ->
                connection.createStatement().use { statement -> statement.execute(sql) }
            }
        }
        return dataSource
    }

    private class CommitFaultDataSource(private val delegate: DataSource) : DataSource {
        private var fault: CommitFault? = null
        private var failNextBorrow = false
        var commitAttempts: Int = 0
            private set

        @Synchronized
        fun failNextCommit(afterDelegateCommit: Boolean, failRereadBorrow: Boolean = false) {
            check(fault == null && !failNextBorrow)
            fault = CommitFault(afterDelegateCommit, failRereadBorrow)
        }

        override fun getConnection(): Connection {
            val selected = synchronized(this) {
                if (failNextBorrow) {
                    failNextBorrow = false
                    throw SQLException("simulated exact reread connection failure")
                }
                fault.also { fault = null }
            }
            val connection = delegate.connection
            if (selected == null) return connection
            var rollbackOnClose = false
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) handler@{ _, method, arguments ->
                if (method.name == "commit") {
                    synchronized(this) {
                        commitAttempts++
                        if (selected.failRereadBorrow) failNextBorrow = true
                    }
                    if (selected.afterDelegateCommit) invoke(connection, method, arguments)
                    else rollbackOnClose = true
                    throw SQLException("simulated lost commit acknowledgement", "08007")
                }
                if (method.name == "close" && rollbackOnClose) {
                    connection.rollback()
                    connection.close()
                    return@handler null
                }
                invoke(connection, method, arguments)
            } as Connection
        }

        override fun getConnection(username: String?, password: String?): Connection = getConnection()
        override fun getLogWriter(): PrintWriter? = delegate.logWriter
        override fun setLogWriter(out: PrintWriter?) { delegate.logWriter = out }
        override fun setLoginTimeout(seconds: Int) { delegate.loginTimeout = seconds }
        override fun getLoginTimeout(): Int = delegate.loginTimeout
        override fun getParentLogger(): Logger = delegate.parentLogger
        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)
        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)

        private fun invoke(
            connection: Connection,
            method: java.lang.reflect.Method,
            arguments: Array<out Any?>?,
        ): Any? = try {
            method.invoke(connection, *(arguments ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }

        private class CommitFault(
            val afterDelegateCommit: Boolean,
            val failRereadBorrow: Boolean,
        )
    }
}
