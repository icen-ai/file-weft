package ai.icen.fw.workflow.cycle.guard.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleBudgetPolicy
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardCommand
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardConsumeRequest
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardLookupCode
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardReceiptLookup
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardScope
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardStoreCode
import ai.icen.fw.workflow.cycle.guard.WorkflowCycleGuardOperation
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class JdbcWorkflowCycleGuardPersistenceH2Test {
    @Test
    fun `consume atomically persists exact replay receipt and tenant scoped state`() {
        val dataSource = fixture("apply")
        val store = JdbcWorkflowCycleGuardPersistence(dataSource)
        val scope = scope("tenant-a", 1L, "revision-1", SUBJECT_DIGEST_1)
        val consumeRequest = request(command(scope, 5L, 0L, "command-1", "idem-1", 100L), policy(scope))

        val applied = store.consume(consumeRequest)
        val replayed = store.consume(consumeRequest)
        val idempotencyConflict = store.consume(
            request(command(scope, 5L, 1L, "changed-command", "idem-1", 101L), policy(scope)),
        )
        val receipt = store.findReceipt(WorkflowCycleGuardReceiptLookup.of(consumeRequest.command))
        val loaded = store.load(scope)

        assertSame(WorkflowCycleGuardStoreCode.APPLIED, applied.code)
        assertSame(WorkflowCycleGuardStoreCode.REPLAYED, replayed.code)
        assertSame(WorkflowCycleGuardStoreCode.IDEMPOTENCY_CONFLICT, idempotencyConflict.code)
        assertEquals(applied.record!!.recordDigest, replayed.record!!.recordDigest)
        assertSame(WorkflowCycleGuardLookupCode.FOUND, receipt.code)
        assertSame(WorkflowCycleGuardLookupCode.FOUND, loaded.code)
        assertEquals(1, loaded.record!!.perCycleCount)
        assertEquals(1, loaded.record!!.instanceOperationCount)
        assertEquals(1, CycleGuardJdbcH2Fixture.count(dataSource, "fw_wf_cycle_guard_total", "tenant-a"))
        assertEquals(1, CycleGuardJdbcH2Fixture.count(dataSource, "fw_wf_cycle_guard_cycle", "tenant-a"))
        assertEquals(1, CycleGuardJdbcH2Fixture.count(dataSource, "fw_wf_cycle_guard_receipt", "tenant-a"))
    }

    @Test
    fun `instance fence cycle CAS limits and pinned policy fail closed without partial counts`() {
        val dataSource = fixture("fences")
        val store = JdbcWorkflowCycleGuardPersistence(dataSource)
        val scope = scope("tenant-a", 1L, "revision-1", SUBJECT_DIGEST_1)
        val selectedPolicy = policy(scope, maximumPerCycle = 2, maximumPerInstance = 3)
        assertSame(
            WorkflowCycleGuardStoreCode.APPLIED,
            store.consume(request(command(scope, 5L, 0L, "command-1", "idem-1", 100L), selectedPolicy)).code,
        )

        val staleCycle = store.consume(
            request(command(scope, 5L, 0L, "command-stale", "idem-stale", 101L), selectedPolicy),
        )
        val staleInstance = store.consume(
            request(command(scope, 4L, 1L, "command-version", "idem-version", 102L), selectedPolicy),
        )
        val second = store.consume(
            request(command(scope, 5L, 1L, "command-2", "idem-2", 103L), selectedPolicy),
        )
        val limited = store.consume(
            request(command(scope, 5L, 2L, "command-3", "idem-3", 104L), selectedPolicy),
        )
        val changedPolicy = store.consume(
            request(
                command(scope, 5L, 2L, "command-policy", "idem-policy", 105L),
                policy(scope, authorityRevision = "policy-authority-2", maximumPerCycle = 2, maximumPerInstance = 3),
            ),
        )

        assertSame(WorkflowCycleGuardStoreCode.VERSION_CONFLICT, staleCycle.code)
        assertSame(WorkflowCycleGuardStoreCode.VERSION_CONFLICT, staleInstance.code)
        assertSame(WorkflowCycleGuardStoreCode.APPLIED, second.code)
        assertSame(WorkflowCycleGuardStoreCode.LIMIT_REACHED, limited.code)
        assertSame(WorkflowCycleGuardStoreCode.POLICY_CONFLICT, changedPolicy.code)
        assertEquals(2, store.load(scope).record!!.perCycleCount)
        assertEquals(2, CycleGuardJdbcH2Fixture.count(dataSource, "fw_wf_cycle_guard_receipt", "tenant-a"))
    }

    @Test
    fun `new subject revision cycle and node share the stable instance operation aggregate`() {
        val dataSource = fixture("cycles")
        val store = JdbcWorkflowCycleGuardPersistence(dataSource)
        val firstScope = scope("tenant-a", 1L, "revision-1", SUBJECT_DIGEST_1)
        val firstRequest = request(
            command(firstScope, 5L, 0L, "command-1", "idem-1", 100L),
            policy(firstScope),
        )
        assertSame(WorkflowCycleGuardStoreCode.APPLIED, store.consume(firstRequest).code)

        CycleGuardJdbcH2Fixture.updateInstanceSubject(
            dataSource,
            "tenant-a",
            INSTANCE_ID,
            6L,
            "revision-2",
            SUBJECT_DIGEST_2,
        )
        val secondScope = scope("tenant-a", 2L, "revision-2", SUBJECT_DIGEST_2, "legal-review")
        val secondRequest = request(
            command(secondScope, 6L, 0L, "command-2", "idem-2", 200L),
            policy(secondScope),
        )

        val second = store.consume(secondRequest)
        val replayAfterInstanceAdvance = store.consume(firstRequest)
        val historicalReceipt = store.findReceipt(WorkflowCycleGuardReceiptLookup.of(firstRequest.command))
        val currentFirstCycle = store.load(firstScope)

        assertSame(WorkflowCycleGuardStoreCode.APPLIED, second.code)
        assertSame(WorkflowCycleGuardStoreCode.REPLAYED, replayAfterInstanceAdvance.code)
        assertEquals(1, second.record!!.perCycleCount)
        assertEquals(2, second.record!!.instanceOperationCount)
        assertEquals(1, historicalReceipt.record!!.instanceOperationCount)
        assertEquals(2, currentFirstCycle.record!!.instanceOperationCount)
        assertEquals(1, CycleGuardJdbcH2Fixture.count(dataSource, "fw_wf_cycle_guard_total", "tenant-a"))
        assertEquals(2, CycleGuardJdbcH2Fixture.count(dataSource, "fw_wf_cycle_guard_cycle", "tenant-a"))
    }

    @Test
    fun `tenant id participates in every idempotency and state key`() {
        val dataSource = CycleGuardJdbcH2Fixture.prepared("tenants")
        CycleGuardJdbcH2Fixture.insertInstance(dataSource, "tenant-a", INSTANCE_ID, 5L, "revision-1", SUBJECT_DIGEST_1)
        CycleGuardJdbcH2Fixture.insertInstance(dataSource, "tenant-b", INSTANCE_ID, 5L, "revision-1", SUBJECT_DIGEST_1)
        val store = JdbcWorkflowCycleGuardPersistence(dataSource)
        val scopeA = scope("tenant-a", 1L, "revision-1", SUBJECT_DIGEST_1)
        val scopeB = scope("tenant-b", 1L, "revision-1", SUBJECT_DIGEST_1)

        assertSame(
            WorkflowCycleGuardStoreCode.APPLIED,
            store.consume(request(command(scopeA, 5L, 0L, "command-1", "shared-key", 100L), policy(scopeA))).code,
        )
        assertSame(
            WorkflowCycleGuardStoreCode.APPLIED,
            store.consume(request(command(scopeB, 5L, 0L, "command-1", "shared-key", 100L), policy(scopeB))).code,
        )

        assertEquals(1, CycleGuardJdbcH2Fixture.count(dataSource, "fw_wf_cycle_guard_receipt", "tenant-a"))
        assertEquals(1, CycleGuardJdbcH2Fixture.count(dataSource, "fw_wf_cycle_guard_receipt", "tenant-b"))
        assertSame(WorkflowCycleGuardLookupCode.FOUND, store.load(scopeA).code)
        assertSame(WorkflowCycleGuardLookupCode.FOUND, store.load(scopeB).code)
    }

    @Test
    fun `concurrent exact requests serialize to one apply and one replay`() {
        val dataSource = fixture("concurrent")
        val store = JdbcWorkflowCycleGuardPersistence(dataSource)
        val scope = scope("tenant-a", 1L, "revision-1", SUBJECT_DIGEST_1)
        val request = request(command(scope, 5L, 0L, "command-1", "idem-1", 100L), policy(scope))
        val executor = Executors.newFixedThreadPool(2)
        val results = try {
            executor.invokeAll(listOf(
                Callable { store.consume(request) },
                Callable { store.consume(request) },
            )).map { future -> future.get().code }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it == WorkflowCycleGuardStoreCode.APPLIED })
        assertEquals(1, results.count { it == WorkflowCycleGuardStoreCode.REPLAYED })
        assertEquals(1, CycleGuardJdbcH2Fixture.count(dataSource, "fw_wf_cycle_guard_receipt", "tenant-a"))
    }

    @Test
    fun `lost commit acknowledgement is reconciled from immutable receipt without double count`() {
        val delegate = fixture("commit-unknown")
        val store = JdbcWorkflowCycleGuardPersistence(CycleGuardJdbcH2Fixture.failFirstCommitAfterSuccess(delegate))
        val scope = scope("tenant-a", 1L, "revision-1", SUBJECT_DIGEST_1)
        val request = request(command(scope, 5L, 0L, "command-1", "idem-1", 100L), policy(scope))

        val recovered = store.consume(request)

        assertSame(WorkflowCycleGuardStoreCode.REPLAYED, recovered.code)
        assertNotNull(recovered.record)
        assertEquals(1, recovered.record!!.perCycleCount)
        assertEquals(1, CycleGuardJdbcH2Fixture.count(delegate, "fw_wf_cycle_guard_receipt", "tenant-a"))
    }

    @Test
    fun `three production schema contracts retain tenant keys receipts and timestamps`() {
        WorkflowCycleGuardJdbcSchemaDialect.values().forEach { dialect ->
            val sql = checkNotNull(javaClass.getResourceAsStream(dialect.resourcePath)).use {
                String(it.readBytes(), StandardCharsets.UTF_8).lowercase()
            }
            listOf(
                "fw_wf_cycle_guard_total",
                "fw_wf_cycle_guard_cycle",
                "fw_wf_cycle_guard_receipt",
                "policy_binding_digest",
                "policy_content_digest",
                "unique (tenant_id, aggregate_digest)",
                "unique (tenant_id, scope_digest)",
                "unique (tenant_id, idempotency_key)",
                "created_time",
                "updated_time",
            ).forEach { required -> check(sql.contains(required)) { "${dialect.name} is missing $required" } }
        }
    }

    private fun fixture(name: String): DataSource = CycleGuardJdbcH2Fixture.prepared(name).also { dataSource ->
        CycleGuardJdbcH2Fixture.insertInstance(
            dataSource,
            "tenant-a",
            INSTANCE_ID,
            5L,
            "revision-1",
            SUBJECT_DIGEST_1,
        )
    }

    private fun scope(
        tenantId: String,
        cycle: Long,
        revision: String,
        subjectDigest: String,
        nodeId: String = "manager-review",
    ): WorkflowCycleGuardScope = WorkflowCycleGuardScope.of(
        tenantId,
        INSTANCE_ID,
        CycleGuardJdbcH2Fixture.DEFINITION_ID,
        WorkflowDefinitionRef.of(
            CycleGuardJdbcH2Fixture.DEFINITION_KEY,
            CycleGuardJdbcH2Fixture.DEFINITION_VERSION,
            CycleGuardJdbcH2Fixture.DEFINITION_DIGEST,
        ),
        nodeId,
        WorkflowCycleGuardOperation.RETURN,
        cycle,
        WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of(CycleGuardJdbcH2Fixture.SUBJECT_TYPE, CycleGuardJdbcH2Fixture.SUBJECT_ID),
            revision,
            subjectDigest,
        ),
    )

    private fun command(
        scope: WorkflowCycleGuardScope,
        expectedInstanceVersion: Long,
        expectedGuardRevision: Long,
        commandId: String,
        idempotencyKey: String,
        now: Long,
    ): WorkflowCycleGuardCommand = WorkflowCycleGuardCommand.of(
        WorkflowTrustedCallContext.of(
            scope.tenantId,
            WorkflowPrincipalRef.of("user", "alice"),
            "auth-${scope.tenantId}",
            AUTHORITY_CONTEXT_DIGEST,
        ),
        scope,
        commandId,
        idempotencyKey,
        expectedInstanceVersion,
        expectedGuardRevision,
        REASON_DIGEST,
        now,
    )

    private fun policy(
        scope: WorkflowCycleGuardScope,
        authorityRevision: String = "policy-authority-1",
        maximumPerCycle: Int = 2,
        maximumPerInstance: Int = 3,
    ): WorkflowCycleBudgetPolicy = WorkflowCycleBudgetPolicy.of(
        POLICY_REQUEST_DIGEST,
        scope.scopeDigest,
        "enterprise-cycle-policy",
        "1",
        POLICY_DIGEST,
        authorityRevision,
        POLICY_AUTHORITY_DIGEST,
        maximumPerCycle,
        maximumPerInstance,
        1L,
        1_000L,
    )

    private fun request(
        command: WorkflowCycleGuardCommand,
        policy: WorkflowCycleBudgetPolicy,
    ): WorkflowCycleGuardConsumeRequest = WorkflowCycleGuardConsumeRequest.of(
        command,
        policy,
        AUTHORIZATION_DECISION_DIGEST,
    )

    private companion object {
        const val INSTANCE_ID = "instance-1"
        const val SUBJECT_DIGEST_1 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SUBJECT_DIGEST_2 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val AUTHORITY_CONTEXT_DIGEST = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val REASON_DIGEST = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val POLICY_REQUEST_DIGEST = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        const val POLICY_DIGEST = "1111111111111111111111111111111111111111111111111111111111111111"
        const val POLICY_AUTHORITY_DIGEST = "2222222222222222222222222222222222222222222222222222222222222222"
        const val AUTHORIZATION_DECISION_DIGEST = "3333333333333333333333333333333333333333333333333333333333333333"
    }
}
