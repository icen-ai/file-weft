package ai.icen.fw.workflow.cycle.guard

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationDecision
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationRequest
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationStatus
import ai.icen.fw.workflow.runtime.WorkflowRuntimeHumanDecisionReceiptRequest
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WorkflowCycleGuardRuntimeTest {
    @Test
    fun `durable CAS applies once and exact replay does not increment`() {
        val fixture = Fixture()
        val command = fixture.command(0L, "command-1", "idem-1")

        val applied = fixture.runtime.consume(command)
        val replayed = fixture.runtime.consume(command)

        assertEquals(WorkflowCycleGuardResultCode.ALLOWED, applied.code)
        assertEquals(1, applied.record?.perCycleCount)
        assertEquals(WorkflowCycleGuardResultCode.REPLAYED, replayed.code)
        assertEquals(applied.record?.recordDigest, replayed.record?.recordDigest)
        assertEquals(1, fixture.store.instanceCount)
    }

    @Test
    fun `policy limit fails closed without another durable increment`() {
        val fixture = Fixture()
        assertEquals(
            WorkflowCycleGuardResultCode.ALLOWED,
            fixture.runtime.consume(fixture.command(0L, "command-1", "idem-1")).code,
        )
        assertEquals(
            WorkflowCycleGuardResultCode.ALLOWED,
            fixture.runtime.consume(fixture.command(1L, "command-2", "idem-2")).code,
        )

        val exhausted = fixture.runtime.consume(fixture.command(2L, "command-3", "idem-3"))

        assertEquals(WorkflowCycleGuardResultCode.LIMIT_REACHED, exhausted.code)
        assertEquals(2, fixture.store.instanceCount)
    }

    @Test
    fun `stale concurrent revision is rejected by persistence CAS`() {
        val fixture = Fixture()
        fixture.runtime.consume(fixture.command(0L, "command-1", "idem-1"))

        val stale = fixture.runtime.consume(fixture.command(0L, "command-2", "idem-2"))

        assertEquals(WorkflowCycleGuardResultCode.VERSION_CONFLICT, stale.code)
        assertEquals(1, fixture.store.instanceCount)
    }

    @Test
    fun `commit authorization revocation prevents durable consumption`() {
        val fixture = Fixture()
        fixture.authorization.denySecondCall = true

        val denied = fixture.runtime.consume(fixture.command(0L, "command-1", "idem-1"))

        assertEquals(WorkflowCycleGuardResultCode.AUTHORIZATION_DENIED, denied.code)
        assertEquals(0, fixture.store.consumeCalls)
    }

    @Test
    fun `policy revision drift between prepare and commit fails closed`() {
        val fixture = Fixture()
        fixture.policy.driftSecondResolution = true

        val denied = fixture.runtime.consume(fixture.command(0L, "command-1", "idem-1"))

        assertEquals(WorkflowCycleGuardResultCode.UNSUPPORTED, denied.code)
        assertEquals("policy-revision-drift", denied.diagnosticCode)
        assertEquals(0, fixture.store.consumeCalls)
    }

    @Test
    fun `authorization digest drift with unchanged revision fails closed`() {
        val fixture = Fixture()
        fixture.authorization.driftSecondAuthorityDigest = true

        val denied = fixture.runtime.consume(fixture.command(0L, "command-1", "idem-1"))

        assertEquals(WorkflowCycleGuardResultCode.AUTHORIZATION_DENIED, denied.code)
        assertEquals("authorization-authority-drift", denied.diagnosticCode)
        assertEquals(0, fixture.store.consumeCalls)
    }

    @Test
    fun `durable cycle rejects a different policy instead of resetting counters`() {
        val fixture = Fixture()
        assertEquals(
            WorkflowCycleGuardResultCode.ALLOWED,
            fixture.runtime.consume(fixture.command(0L, "command-1", "idem-1")).code,
        )
        fixture.policy.authorityRevision = "policy-authority-2"

        val conflict = fixture.runtime.consume(fixture.command(1L, "command-2", "idem-2"))

        assertEquals(WorkflowCycleGuardResultCode.POLICY_CONFLICT, conflict.code)
        assertEquals(1, fixture.store.instanceCount)
    }

    @Test
    fun `unknown commit outcome can be recovered by exact durable receipt lookup`() {
        val fixture = Fixture()
        fixture.store.persistThenReturnUnknown = true
        val command = fixture.command(0L, "command-1", "idem-1")

        val unknown = fixture.runtime.consume(command)
        fixture.store.persistThenReturnUnknown = false
        val recovered = fixture.runtime.reconcile(
            WorkflowCycleGuardReconciliationRequest.of(fixture.context, command, NOW + 1L),
        )

        assertEquals(WorkflowCycleGuardResultCode.OUTCOME_UNKNOWN, unknown.code)
        assertEquals(WorkflowCycleGuardResultCode.REPLAYED, recovered.code)
        assertNotNull(recovered.record)
        assertEquals(1, fixture.store.instanceCount)
    }

    @Test
    fun `diagnostic reports bounded remaining budget from durable state`() {
        val fixture = Fixture()
        fixture.runtime.consume(fixture.command(0L, "command-1", "idem-1"))

        val diagnostic = fixture.runtime.diagnose(
            WorkflowCycleGuardDiagnosticQuery.of(fixture.context, fixture.scope, NOW + 1L),
        )

        assertEquals(WorkflowCycleGuardDiagnosticCode.NEAR_LIMIT, diagnostic.code)
        assertEquals(1, diagnostic.remainingPerCycle)
        assertEquals(2, diagnostic.remainingPerInstance)
    }

    private class Fixture {
        private val actor = WorkflowPrincipalRef.of("user", "alice")
        val context = WorkflowTrustedCallContext.of("tenant-1", actor, "authn-1", DIGEST)
        val scope = WorkflowCycleGuardScope.of(
            "tenant-1",
            "instance-1",
            "definition-1",
            WorkflowDefinitionRef.of("expense", "1", DIGEST),
            "manager-review",
            WorkflowCycleGuardOperation.RETURN,
            1L,
            WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of("document", "document-1"),
                "version-1",
                DIGEST,
            ),
        )
        val authorization = RecordingAuthorization(actor)
        val policy = RecordingPolicy()
        val store = TestDurableStore()
        val runtime = WorkflowCycleGuardRuntime(policy, authorization, store)

        fun command(expectedRevision: Long, commandId: String, idempotencyKey: String) =
            WorkflowCycleGuardCommand.of(
                context,
                scope,
                commandId,
                idempotencyKey,
                5L,
                expectedRevision,
                DIGEST,
                NOW,
            )
    }

    private class RecordingAuthorization(
        private val actor: WorkflowPrincipalRef,
    ) : WorkflowRuntimeAuthorizationPort {
        var calls = 0
        var denySecondCall = false
        var driftSecondAuthorityDigest = false

        override fun authorize(request: WorkflowRuntimeAuthorizationRequest): WorkflowRuntimeAuthorizationDecision {
            calls++
            val denied = denySecondCall && calls == 2
            return WorkflowRuntimeAuthorizationDecision.of(
                "authorization-$calls",
                "tenant-1",
                actor,
                request.action,
                request.instanceId,
                request.requestDigest,
                if (denied) WorkflowRuntimeAuthorizationStatus.DENIED else
                    WorkflowRuntimeAuthorizationStatus.AUTHORIZED,
                "authority-revision-1",
                if (driftSecondAuthorityDigest && calls == 2) OTHER_DIGEST else DIGEST,
                request.evaluatedAt,
                request.evaluatedAt,
            )
        }

        override fun issueHumanDecisionReceipt(
            request: WorkflowRuntimeHumanDecisionReceiptRequest,
        ): WorkflowHumanDecisionAuthorizationReceipt = throw UnsupportedOperationException()
    }

    private class RecordingPolicy : WorkflowCycleBudgetPolicyPort {
        var calls = 0
        var driftSecondResolution = false
        var authorityRevision = "policy-authority-1"

        override fun resolve(request: WorkflowCycleBudgetPolicyRequest): WorkflowCycleBudgetPolicy {
            calls++
            return WorkflowCycleBudgetPolicy.of(
                request.requestDigest,
                request.command.scope.scopeDigest,
                "enterprise-loop-policy",
                "1",
                DIGEST,
                if (driftSecondResolution && calls == 2) "policy-authority-drift" else authorityRevision,
                DIGEST,
                2,
                3,
                request.command.requestedAtEpochMilli,
                request.command.requestedAtEpochMilli,
            )
        }
    }

    /** Test-only contract fixture; the production module intentionally ships no in-memory store. */
    private class TestDurableStore : WorkflowCycleGuardPersistencePort {
        private val current = LinkedHashMap<String, WorkflowCycleGuardRecord>()
        private val receipts = LinkedHashMap<String, WorkflowCycleGuardRecord>()
        var instanceCount = 0
        var consumeCalls = 0
        var persistThenReturnUnknown = false

        override fun consume(request: WorkflowCycleGuardConsumeRequest): WorkflowCycleGuardStoreResult {
            consumeCalls++
            val receiptKey = receiptKey(request.command)
            val priorReceipt = receipts[receiptKey]
            if (priorReceipt != null) {
                return if (priorReceipt.matchesCommand(request.command)) {
                    WorkflowCycleGuardStoreResult.success(WorkflowCycleGuardStoreCode.REPLAYED, priorReceipt)
                } else {
                    WorkflowCycleGuardStoreResult.failure(WorkflowCycleGuardStoreCode.IDEMPOTENCY_CONFLICT)
                }
            }
            val prior = current[request.command.scope.scopeDigest]
            if (prior != null && !prior.matchesPolicy(request.policy)) {
                return WorkflowCycleGuardStoreResult.failure(WorkflowCycleGuardStoreCode.POLICY_CONFLICT)
            }
            val currentRevision = prior?.guardRevision ?: 0L
            if (currentRevision != request.command.expectedGuardRevision) {
                return WorkflowCycleGuardStoreResult.failure(WorkflowCycleGuardStoreCode.VERSION_CONFLICT)
            }
            val nextCycle = (prior?.perCycleCount ?: 0) + 1
            val nextInstance = instanceCount + 1
            if (nextCycle > request.policy.maximumPerCycle ||
                nextInstance > request.policy.maximumPerInstance
            ) return WorkflowCycleGuardStoreResult.failure(WorkflowCycleGuardStoreCode.LIMIT_REACHED)
            val record = WorkflowCycleGuardRecord.of(
                request.command.scope,
                request.policy.policyId,
                request.policy.policyVersion,
                request.policy.policyDigest,
                request.policy.contentDigest,
                request.policy.authorityRevision,
                request.policy.maximumPerCycle,
                request.policy.maximumPerInstance,
                nextCycle,
                nextInstance,
                currentRevision + 1L,
                request.command.idempotencyKey,
                request.command.requestDigest,
                request.authorizationDecisionDigest,
                request.command.requestedAtEpochMilli,
            )
            current[request.command.scope.scopeDigest] = record
            receipts[receiptKey] = record
            instanceCount = nextInstance
            return if (persistThenReturnUnknown) {
                WorkflowCycleGuardStoreResult.failure(WorkflowCycleGuardStoreCode.OUTCOME_UNKNOWN)
            } else {
                WorkflowCycleGuardStoreResult.success(WorkflowCycleGuardStoreCode.APPLIED, record)
            }
        }

        override fun findReceipt(request: WorkflowCycleGuardReceiptLookup): WorkflowCycleGuardLookupResult =
            receipts[receiptKey(request.command)]?.let(WorkflowCycleGuardLookupResult::found)
                ?: WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.NOT_FOUND)

        override fun load(scope: WorkflowCycleGuardScope): WorkflowCycleGuardLookupResult =
            current[scope.scopeDigest]?.let(WorkflowCycleGuardLookupResult::found)
                ?: WorkflowCycleGuardLookupResult.absent(WorkflowCycleGuardLookupCode.NOT_FOUND)

        private fun receiptKey(command: WorkflowCycleGuardCommand): String =
            "${command.scope.tenantId}:${command.idempotencyKey}"
    }

    private companion object {
        const val NOW = 1_000L
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
