package ai.icen.fw.domain.retention

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetentionDeletionDecisionEngineTest {
    @Test
    fun `creates a tenant-bound tombstone-first plan when every policy fence allows deletion`() {
        val request = request(retainUntil = 1_000)
        val engine = engineAt(1_000)

        val decision = engine.evaluate(request)
        val repeated = engine.evaluate(request)

        assertTrue(decision.isAllowed())
        assertEquals(SecureDeletionDecisionReason.ALLOWED, decision.reason)
        val plan = assertNotNull(decision.plan)
        assertSame(decision.auditEvidence, plan.decisionAuditEvidence)
        assertEquals(request.tenantId, plan.tombstone.tenantId)
        assertEquals(request.resourceId, plan.tombstone.resourceId)
        assertEquals(7L, plan.tombstone.resourceRevision)
        assertEquals(1_000L, plan.tombstone.blockedAt)
        assertEquals(request.planId, decision.auditEvidence.planId)
        assertEquals(request.tombstoneId, decision.auditEvidence.tombstoneId)
        assertEquals(
            listOf(
                SecureDeletionStage.PERSIST_TOMBSTONE,
                SecureDeletionStage.APPEND_DECISION_AUDIT,
                SecureDeletionStage.ENQUEUE_PURGE_OUTBOX,
                SecureDeletionStage.PURGE_INDEX_PROJECTIONS,
                SecureDeletionStage.PURGE_OBJECT_STORAGE,
                SecureDeletionStage.FINALIZE_DATABASE,
                SecureDeletionStage.APPEND_COMPLETION_AUDIT,
            ),
            plan.steps.map { it.stage },
        )
        assertEquals(
            listOf(
                DeletionExecutionBoundary.DATABASE_TRANSACTION,
                DeletionExecutionBoundary.DATABASE_TRANSACTION,
                DeletionExecutionBoundary.TRANSACTIONAL_OUTBOX,
                DeletionExecutionBoundary.EXTERNAL_INDEX,
                DeletionExecutionBoundary.EXTERNAL_OBJECT_STORAGE,
                DeletionExecutionBoundary.DATABASE_TRANSACTION,
                DeletionExecutionBoundary.DATABASE_TRANSACTION,
            ),
            plan.steps.map { it.boundary },
        )
        assertEquals(listOf(false, false, false, true, true, false, false), plan.steps.map { it.isExternalSideEffect() })
        assertEquals(plan.steps.map { it.idempotencyKey }, assertNotNull(repeated.plan).steps.map { it.idempotencyKey })
        assertEquals(plan.steps.size, plan.steps.map { it.idempotencyKey }.distinct().size)
        assertFailsWith<UnsupportedOperationException> {
            (plan.steps as MutableList<SecureDeletionStep>).clear()
        }
    }

    @Test
    fun `active legal hold has absolute priority over expired retention and valid authorization`() {
        val activeHold = LegalHoldSnapshot(
            id = Identifier("hold-1"),
            tenantId = TENANT,
            resourceType = RESOURCE_TYPE,
            resourceId = RESOURCE,
            revision = "hold-1-r3",
            status = LegalHoldStatus.ACTIVE,
            appliedAt = 100,
            releasedAt = null,
        )

        val decision = engineAt(2_000).evaluate(
            request(
                retainUntil = 500,
                legalHolds = legalHoldSet(complete = true, holds = listOf(activeHold)),
            ),
        )

        assertFalse(decision.isAllowed())
        assertEquals(SecureDeletionDecisionReason.ACTIVE_LEGAL_HOLD, decision.reason)
        assertNull(decision.plan)
        assertEquals(listOf(Identifier("hold-1")), decision.auditEvidence.activeLegalHoldIds)
        assertNull(decision.auditEvidence.planId)
        assertNull(decision.auditEvidence.tombstoneId)
    }

    @Test
    fun `incomplete legal hold and retention evidence fail closed`() {
        val incompleteHolds = engineAt(1_000).evaluate(
            request(
                retainUntil = 0,
                legalHolds = legalHoldSet(complete = false),
            ),
        )
        val unknownPolicy = engineAt(1_000).evaluate(
            request(
                policy = newPolicy(RetentionPolicyMode.UNKNOWN, null),
            ),
        )

        assertEquals(SecureDeletionDecisionReason.INCOMPLETE_LEGAL_HOLD_EVIDENCE, incompleteHolds.reason)
        assertNull(incompleteHolds.plan)
        assertEquals(SecureDeletionDecisionReason.INCOMPLETE_RETENTION_POLICY, unknownPolicy.reason)
        assertNull(unknownPolicy.plan)
    }

    @Test
    fun `controllable clock enforces retention boundary and indefinite retention`() {
        val expiring = request(retainUntil = 1_000)

        assertEquals(
            SecureDeletionDecisionReason.RETENTION_PERIOD_ACTIVE,
            engineAt(999).evaluate(expiring).reason,
        )
        assertTrue(engineAt(1_000).evaluate(expiring).isAllowed())
        assertEquals(
            SecureDeletionDecisionReason.RETAIN_INDEFINITELY,
            engineAt(1_500).evaluate(
                request(policy = newPolicy(RetentionPolicyMode.RETAIN_INDEFINITELY, null)),
            ).reason,
        )
    }

    @Test
    fun `incomplete denied expired and future authorization evidence cannot create a plan`() {
        val incomplete = engineAt(1_000).evaluate(
            request(authorization = newAuthorization(complete = false, authorized = false)),
        )
        val denied = engineAt(1_000).evaluate(
            request(authorization = newAuthorization(complete = true, authorized = false)),
        )
        val expired = engineAt(1_000).evaluate(
            request(authorization = newAuthorization(expiresAt = 1_000)),
        )
        val future = engineAt(1_000).evaluate(
            request(authorization = newAuthorization(evaluatedAt = 1_001, expiresAt = 2_000)),
        )

        assertEquals(SecureDeletionDecisionReason.INCOMPLETE_AUTHORIZATION_EVIDENCE, incomplete.reason)
        assertEquals(SecureDeletionDecisionReason.AUTHORIZATION_DENIED, denied.reason)
        assertEquals(SecureDeletionDecisionReason.AUTHORIZATION_EXPIRED, expired.reason)
        assertEquals(SecureDeletionDecisionReason.EVIDENCE_FROM_FUTURE, future.reason)
        assertTrue(listOf(incomplete, denied, expired, future).all { it.plan == null })
    }

    @Test
    fun `stale legal hold and retention snapshots fail closed before plan creation`() {
        val staleHolds = engineAt(1_000).evaluate(
            request(legalHolds = legalHoldSet(expiresAt = 1_000)),
        )
        val stalePolicy = engineAt(1_000).evaluate(
            request(policy = newPolicy(RetentionPolicyMode.RETAIN_UNTIL, 0, expiresAt = 1_000)),
        )

        assertEquals(SecureDeletionDecisionReason.LEGAL_HOLD_EVIDENCE_EXPIRED, staleHolds.reason)
        assertEquals(SecureDeletionDecisionReason.RETENTION_POLICY_EVIDENCE_EXPIRED, stalePolicy.reason)
        assertNull(staleHolds.plan)
        assertNull(stalePolicy.plan)
    }

    @Test
    fun `rejects cross-tenant resource and principal evidence before evaluation`() {
        assertFailsWith<IllegalArgumentException> {
            request(
                policy = RetentionPolicySnapshot(
                    Identifier("tenant-other"), RESOURCE_TYPE, RESOURCE, "policy", "p1",
                    RetentionPolicyMode.RETAIN_UNTIL, 0, 900, 2_000, 0,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            request(
                authorization = DeletionAuthorizationSnapshot(
                    TENANT, RESOURCE_TYPE, RESOURCE, Identifier("user-other"), "auth-r1",
                    900, 2_000, true, true,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            legalHoldSet(
                holds = listOf(
                    LegalHoldSnapshot(
                        Identifier("hold-1"), Identifier("tenant-other"), RESOURCE_TYPE, RESOURCE,
                        "h1", LegalHoldStatus.ACTIVE, 100, null,
                    ),
                ),
            )
        }
    }

    @Test
    fun `snapshot constructors reject ambiguous or contradictory evidence`() {
        assertFailsWith<IllegalArgumentException> {
            newPolicy(RetentionPolicyMode.RETAIN_UNTIL, null)
        }
        assertFailsWith<IllegalArgumentException> {
            newPolicy(RetentionPolicyMode.RETAIN_INDEFINITELY, 1_000)
        }
        assertFailsWith<IllegalArgumentException> {
            DeletionAuthorizationSnapshot(
                TENANT, RESOURCE_TYPE, RESOURCE, REQUESTER, "auth-r1",
                900, 2_000, complete = false, authorized = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            LegalHoldSnapshot(
                Identifier("hold-1"), TENANT, RESOURCE_TYPE, RESOURCE, "h1",
                LegalHoldStatus.ACTIVE, 100, 200,
            )
        }
    }

    private fun request(
        retainUntil: Long = 0,
        policy: RetentionPolicySnapshot = newPolicy(RetentionPolicyMode.RETAIN_UNTIL, retainUntil),
        legalHolds: LegalHoldSetSnapshot = legalHoldSet(),
        authorization: DeletionAuthorizationSnapshot = newAuthorization(),
    ): SecureDeletionRequest = SecureDeletionRequest(
        decisionEvidenceId = Identifier("deletion-decision-1"),
        planId = Identifier("deletion-plan-1"),
        tombstoneId = Identifier("tombstone-1"),
        tenantId = TENANT,
        resourceType = RESOURCE_TYPE,
        resourceId = RESOURCE,
        resourceRevision = 7,
        requestedBy = REQUESTER,
        policy = policy,
        legalHolds = legalHolds,
        authorization = authorization,
    )

    private fun newPolicy(
        mode: RetentionPolicyMode,
        retainUntil: Long?,
        expiresAt: Long = 2_000,
    ) = RetentionPolicySnapshot(
        tenantId = TENANT,
        resourceType = RESOURCE_TYPE,
        resourceId = RESOURCE,
        policyId = "records-policy",
        policyRevision = "policy-r4",
        mode = mode,
        effectiveAt = 0,
        capturedAt = 900,
        expiresAt = expiresAt,
        retainUntil = retainUntil,
    )

    private fun legalHoldSet(
        complete: Boolean = true,
        holds: List<LegalHoldSnapshot> = emptyList(),
        expiresAt: Long = 2_000,
    ) = LegalHoldSetSnapshot(
        tenantId = TENANT,
        resourceType = RESOURCE_TYPE,
        resourceId = RESOURCE,
        snapshotRevision = "hold-set-r8",
        observedAt = 900,
        expiresAt = expiresAt,
        complete = complete,
        holds = holds,
    )

    private fun newAuthorization(
        evaluatedAt: Long = 900,
        expiresAt: Long = 2_000,
        complete: Boolean = true,
        authorized: Boolean = true,
    ) = DeletionAuthorizationSnapshot(
        tenantId = TENANT,
        resourceType = RESOURCE_TYPE,
        resourceId = RESOURCE,
        principalId = REQUESTER,
        authorizationRevision = "auth-r2",
        evaluatedAt = evaluatedAt,
        expiresAt = expiresAt,
        complete = complete,
        authorized = authorized,
    )

    private fun engineAt(epochMillis: Long): RetentionDeletionDecisionEngine =
        RetentionDeletionDecisionEngine(Clock.fixed(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC))

    private companion object {
        val TENANT = Identifier("tenant-1")
        val RESOURCE = Identifier("document-1")
        val REQUESTER = Identifier("retention-admin-1")
        const val RESOURCE_TYPE = "DOCUMENT"
    }
}
