package ai.icen.fw.testkit.retrieval

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.ResolvedCandidateBatch
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest
import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationGate
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizer
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizerDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.function.LongSupplier

/** Current-authority candidate contract exercised only through the authorization assembly gate. */
abstract class RetrievalCandidateAuthorizerContractTest {
    protected abstract val candidateAuthorizer: RetrievalCandidateAuthorizer

    /** Trusted query authorization that belongs to the source chain of [resolvedCandidateBatch]. */
    protected abstract fun queryAuthorizationRequest(): RetrievalAuthorizationRequest

    /** A fresh resolved batch whose every candidate has a matching request identifier below. */
    protected abstract fun resolvedCandidateBatch(): ResolvedCandidateBatch

    protected abstract fun authorizationBatchId(): Identifier

    /** One unique identifier per candidate, in the exact resolved ranking order. */
    protected abstract fun candidateAuthorizationRequestIds(): Collection<Identifier>

    /** Supplies at least the gate's request and verification timestamps. */
    protected abstract fun authorizationClock(): LongSupplier

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `keeps its descriptor stable and returns current exact decisions through the gate`() {
        val descriptor = candidateAuthorizer.descriptor()
        assertStableDescriptor(descriptor, candidateAuthorizer.descriptor())
        val call = RetrievalCandidateAuthorizationGate.create(candidateAuthorizer, authorizationClock()).authorize(
            queryAuthorizationRequest(),
            resolvedCandidateBatch(),
            authorizationBatchId(),
            candidateAuthorizationRequestIds(),
            descriptor,
        )
        val result = RetrievalContractAssertions.awaitStage(
            call.completion(),
            asynchronousTimeout(),
            "Retrieval candidate authorization completion",
        )
        assertEquals(descriptor.binding.digest, result.authorizationProviderBindingDigest)
        val cancellation = RetrievalContractAssertions.awaitStage(
            call.cancel(RetrievalCancellationReason.AUTHORIZATION_REVOKED),
            asynchronousTimeout(),
            "Retrieval candidate authorization cancellation",
        )
        RetrievalContractAssertions.assertCancellationDeclaration(descriptor.supportsCancellation, cancellation)
    }

    private fun assertStableDescriptor(
        first: RetrievalCandidateAuthorizerDescriptor,
        second: RetrievalCandidateAuthorizerDescriptor,
    ) {
        assertEquals(first.providerTypeId, second.providerTypeId)
        assertEquals(first.providerInstanceId, second.providerInstanceId)
        assertEquals(first.configurationDigest, second.configurationDigest)
        assertEquals(first.capabilityDigest, second.capabilityDigest)
        assertEquals(first.capabilityRevision, second.capabilityRevision)
        assertEquals(first.supportsCancellation, second.supportsCancellation)
        assertEquals(first.digest, second.digest, "Candidate authorizer descriptor digest must be stable.")
    }
}
