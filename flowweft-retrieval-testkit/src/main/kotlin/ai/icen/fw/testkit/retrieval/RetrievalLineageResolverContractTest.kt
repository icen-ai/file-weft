package ai.icen.fw.testkit.retrieval

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.PrefilteredCandidateBatch
import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalLineageResolutionGate
import ai.icen.fw.retrieval.api.RetrievalLineageResolver
import ai.icen.fw.retrieval.api.RetrievalLineageResolverDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.function.LongSupplier

/** Exact lineage-resolution contract exercised only through the runtime-owned validation gate. */
abstract class RetrievalLineageResolverContractTest {
    protected abstract val lineageResolver: RetrievalLineageResolver

    /** Exact source request for [prefilteredBatch]; both hooks must describe one security receipt chain. */
    protected abstract fun executableRequest(): ExecutableRetrievalRequest

    /** A batch produced from [executableRequest], not an independently assembled candidate list. */
    protected abstract fun prefilteredBatch(): PrefilteredCandidateBatch

    protected abstract fun resolutionRequestId(): Identifier

    /** Supplies at least the gate's start and completion timestamps. */
    protected abstract fun resolutionClock(): LongSupplier

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `keeps its descriptor stable and returns exact lineage through the validation gate`() {
        val descriptor = lineageResolver.descriptor()
        assertStableDescriptor(descriptor, lineageResolver.descriptor())
        val call = RetrievalLineageResolutionGate.create(lineageResolver, resolutionClock()).resolve(
            executableRequest(),
            prefilteredBatch(),
            resolutionRequestId(),
            descriptor,
        )
        val result = RetrievalContractAssertions.awaitStage(
            call.completion(),
            asynchronousTimeout(),
            "Retrieval lineage completion",
        )
        assertEquals(descriptor.binding.digest, result.lineageProviderBindingDigest)
        val cancellation = RetrievalContractAssertions.awaitStage(
            call.cancel(RetrievalCancellationReason.CALLER_CANCELLED),
            asynchronousTimeout(),
            "Retrieval lineage cancellation",
        )
        RetrievalContractAssertions.assertCancellationDeclaration(descriptor.supportsCancellation, cancellation)
    }

    private fun assertStableDescriptor(
        first: RetrievalLineageResolverDescriptor,
        second: RetrievalLineageResolverDescriptor,
    ) {
        assertEquals(first.providerTypeId, second.providerTypeId)
        assertEquals(first.providerInstanceId, second.providerInstanceId)
        assertEquals(first.configurationDigest, second.configurationDigest)
        assertEquals(first.capabilityDigest, second.capabilityDigest)
        assertEquals(first.capabilityRevision, second.capabilityRevision)
        assertEquals(first.supportsCancellation, second.supportsCancellation)
        assertEquals(first.digest, second.digest, "Lineage descriptor digest must be stable.")
    }
}
