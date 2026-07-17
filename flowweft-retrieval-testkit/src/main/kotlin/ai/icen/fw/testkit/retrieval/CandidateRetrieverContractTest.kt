package ai.icen.fw.testkit.retrieval

import ai.icen.fw.retrieval.api.CandidateRetriever
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.RetrievalAccessProfile
import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalResultEnvelope
import ai.icen.fw.retrieval.spi.HybridSearchProvider
import ai.icen.fw.retrieval.spi.RetrievalProviderContracts
import ai.icen.fw.retrieval.spi.TextSearchProvider
import ai.icen.fw.retrieval.spi.VectorSearchProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * Reusable contract for a preselection-safe retrieval candidate provider.
 *
 * The request hook must return a request produced by [ai.icen.fw.retrieval.api.RetrievalExecutionGate];
 * TestKit deliberately exposes no constructor or bypass for this security boundary.
 */
abstract class CandidateRetrieverContractTest {
    protected abstract val candidateRetriever: CandidateRetriever

    protected abstract fun executableRequest(descriptor: CandidateRetrieverDescriptor): ExecutableRetrievalRequest

    /** Capabilities this configured provider instance is expected to expose to selection. */
    protected open fun requiredModes(): Set<RetrievalMode> = emptySet()

    protected open fun requiredAccessProfiles(): Set<RetrievalAccessProfile> = emptySet()

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    protected open fun cancellationReason(): RetrievalCancellationReason =
        RetrievalCancellationReason.CALLER_CANCELLED

    protected open fun verificationTime(
        request: ExecutableRetrievalRequest,
        result: RetrievalResultEnvelope,
    ): Long = result.securityFilterReceipt.filteredAtEpochMilli

    @Test
    fun `keeps its capability descriptor stable and returns an exactly attested candidate batch`() {
        val provider = candidateRetriever
        val descriptor = provider.descriptor()
        assertStableDescriptor(descriptor, provider.descriptor())
        if (provider is TextSearchProvider) RetrievalProviderContracts.requireText(provider)
        if (provider is VectorSearchProvider) RetrievalProviderContracts.requireVector(provider)
        if (provider is HybridSearchProvider) RetrievalProviderContracts.requireHybrid(provider)
        assertTrue(
            descriptor.supportedModes.containsAll(requiredModes()),
            "Candidate provider is missing a retrieval mode required by the contract fixture.",
        )
        assertTrue(
            descriptor.supportedAccessProfiles.containsAll(requiredAccessProfiles()),
            "Candidate provider is missing an access profile required by the contract fixture.",
        )
        val request = executableRequest(descriptor)
        assertEquals(descriptor.providerTypeId, request.providerTypeId)
        assertEquals(descriptor.providerInstanceId, request.providerInstanceId)
        assertEquals(descriptor.configurationDigest, request.providerConfigurationDigest)
        assertEquals(descriptor.digest, request.providerDescriptorDigest)

        val call = provider.start(request)
        assertNotNull(call, "Candidate retriever must return a non-null call handle.")
        val result = RetrievalContractAssertions.awaitStage(
            requireNotNull(call).completion(),
            asynchronousTimeout(),
            "Candidate retrieval completion",
        )
        result.verifyFor(request, descriptor, verificationTime(request, result))

        val cancellation = RetrievalContractAssertions.awaitStage(
            call.cancel(cancellationReason()),
            asynchronousTimeout(),
            "Candidate retrieval cancellation",
        )
        RetrievalContractAssertions.assertCancellationDeclaration(descriptor.supportsCancellation, cancellation)
    }

    private fun assertStableDescriptor(
        first: CandidateRetrieverDescriptor,
        second: CandidateRetrieverDescriptor,
    ) {
        assertEquals(first.providerTypeId, second.providerTypeId)
        assertEquals(first.providerInstanceId, second.providerInstanceId)
        assertEquals(first.configurationDigest, second.configurationDigest)
        assertEquals(first.securityDomainDigest, second.securityDomainDigest)
        assertEquals(first.capabilityRevision, second.capabilityRevision)
        assertEquals(first.digest, second.digest, "Candidate provider descriptor digest must be stable.")
        assertEquals(first.supportedModes, second.supportedModes)
        assertEquals(first.supportedAccessProfiles, second.supportedAccessProfiles)
        assertEquals(first.supportsCancellation, second.supportsCancellation)
    }
}
