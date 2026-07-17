package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentPolicyProposal
import ai.icen.fw.agent.api.AgentPolicyProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Duration

/** Reusable exact-binding contract for an Agent tool policy provider. */
abstract class AgentPolicyProviderContractTest {
    protected abstract val policyProvider: AgentPolicyProvider

    protected abstract fun policyProposal(): AgentPolicyProposal

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(10)

    protected open fun cancellation(proposal: AgentPolicyProposal): AgentCancellation =
        AgentCancellation("testkit-contract-cancelled", proposal.requestedAt)

    @Test
    fun `returns an exact policy decision through a non-null cancellable handle`() {
        val providerId = policyProvider.providerId()
        assertEquals(providerId, policyProvider.providerId(), "Policy provider identity must be stable.")
        val proposal = policyProposal()
        assertEquals(proposal.policyProviderId, providerId, "The proposal must target the provider under test.")

        val call = policyProvider.start(proposal)
        assertNotNull(call, "Policy provider must return a non-null call handle.")
        val decision = AgentContractAssertions.awaitStage(
            requireNotNull(call).completion(),
            asynchronousTimeout(),
            "Agent policy completion",
        )

        decision.requireValidFor(proposal, decision.decidedAt)
        assertEquals(providerId, decision.providerId)
        AgentContractAssertions.awaitStage(
            call.cancel(cancellation(proposal)),
            asynchronousTimeout(),
            "Agent policy cancellation",
        )
    }
}
