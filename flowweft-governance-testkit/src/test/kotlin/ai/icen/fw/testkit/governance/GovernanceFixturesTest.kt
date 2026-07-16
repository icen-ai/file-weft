package ai.icen.fw.testkit.governance

import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.api.GovernancePrincipalRef
import ai.icen.fw.governance.runtime.GovernanceRuntimeAuthorizationRequest
import ai.icen.fw.governance.runtime.GovernanceRuntimeIdKind
import ai.icen.fw.governance.runtime.GovernanceRuntimeIdRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GovernanceFixturesTest {
    @Test
    fun `clock and identifiers are deterministic monotonic and opaque`() {
        val clock = DeterministicGovernanceClock.startingAt(100_000L)
        assertEquals(100_000L, clock.nowEpochMilli())
        assertEquals(101_000L, clock.advance(1_000L))
        clock.set(102_000L)
        assertEquals(102_000L, clock.nowEpochMilli())
        assertThrows<IllegalArgumentException> { clock.set(101_999L) }

        val ids = DeterministicGovernanceIds.create()
        val request = GovernanceRuntimeIdRequest.of(
            GovernanceRuntimeIdKind.PLAN,
            "tenant-contract",
            GovernanceContractAssertions.digest('1'),
            0,
        )
        assertEquals("plan-1", ids.nextId(request))
        assertEquals("plan-2", ids.nextId(request))
        assertEquals(2L, ids.currentSequence())
    }

    @Test
    fun `strict authorization rejects a changed tenant principal or denied purpose`() {
        val fixture = GovernanceRuntimeContractHarness.inMemory()
        val accepted = fixture.invocation(GovernancePurpose.PLAN_SECURE_DELETION, "accepted")
        val result = fixture.planning.plan(
            ai.icen.fw.governance.runtime.GovernanceDeletionPlanCommand.of(accepted, fixture.fence, true),
        ).toCompletableFuture().get()
        assertTrue(result.plan != null)

        val wrongTenant = fixture.invocation(
            GovernancePurpose.PLAN_SECURE_DELETION,
            "wrong-tenant",
            tenantId = "tenant-not-authorized",
        )
        assertThrows<IllegalStateException> {
            fixture.authorization.authorize(
                GovernanceRuntimeAuthorizationRequest.of(
                    wrongTenant,
                    GovernancePurpose.PLAN_SECURE_DELETION,
                    "request-wrong-tenant",
                    "authorization-wrong-tenant",
                ),
            )
        }
        val wrongPrincipal = fixture.invocation(
            GovernancePurpose.PLAN_SECURE_DELETION,
            "wrong-principal",
            principal = GovernancePrincipalRef.of("user", "intruder"),
        )
        assertThrows<IllegalStateException> {
            fixture.authorization.authorize(
                GovernanceRuntimeAuthorizationRequest.of(
                    wrongPrincipal,
                    GovernancePurpose.PLAN_SECURE_DELETION,
                    "request-wrong-principal",
                    "authorization-wrong-principal",
                ),
            )
        }

        fixture.authorization.deny(GovernancePurpose.PLAN_SECURE_DELETION)
        val denied = fixture.plan("denied", true).toCompletableFuture().get()
        assertEquals(ai.icen.fw.governance.runtime.GovernancePlanningStatus.FAILED, denied.status)

        GovernanceContractAssertions.assertRedacted(
            accepted.toString(),
            fixture.tenantId,
            fixture.principal.id,
            "accepted",
        )
    }
}
