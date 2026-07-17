package ai.icen.fw.testkit.retrieval

import ai.icen.fw.retrieval.api.RetrievalAuthorizationPlanner
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reusable fail-closed contract for the host's trusted retrieval authorization planner. */
abstract class RetrievalAuthorizationPlannerContractTest {
    protected abstract val authorizationPlanner: RetrievalAuthorizationPlanner

    /** A trusted request that is expected to receive an explicit bounded access plan. */
    protected abstract fun allowedAuthorizationRequest(): RetrievalAuthorizationRequest

    /** A distinct trusted request that must be denied without an executable plan. */
    protected abstract fun deniedAuthorizationRequest(): RetrievalAuthorizationRequest

    @Test
    fun `binds an allowed plan to the exact trusted request`() {
        val request = allowedAuthorizationRequest()
        val result = authorizationPlanner.plan(request)

        result.requireValidFor(request)
        assertTrue(result.allowed)
        val plan = result.requireAllowed()
        plan.requireValidFor(request, plan.issuedAtEpochMilli)
    }

    @Test
    fun `denies without exposing an executable access plan`() {
        val request = deniedAuthorizationRequest()
        val result = authorizationPlanner.plan(request)

        result.requireValidFor(request)
        assertFalse(result.allowed)
        assertNotNull(result.denialCode, "Denied retrieval authorization must carry a stable denial code.")
        assertThrows(IllegalStateException::class.java) { result.requireAllowed() }
    }
}
