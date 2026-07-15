package ai.icen.fw.testkit.retrieval

import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalContentHydrationGate
import ai.icen.fw.retrieval.api.RetrievalHydrationRequest
import ai.icen.fw.retrieval.spi.RetrievalContentProvider
import ai.icen.fw.retrieval.spi.RetrievalContentProviderDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.function.LongSupplier

/** Content hydration contract that never exposes content before current authorization and egress gates. */
abstract class RetrievalContentProviderContractTest {
    protected abstract val contentProvider: RetrievalContentProvider

    protected abstract fun hydrationRequest(descriptor: RetrievalContentProviderDescriptor): RetrievalHydrationRequest

    /** Supplies at least the hydration gate's start and completion timestamps. */
    protected abstract fun hydrationClock(): LongSupplier

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(30)

    @Test
    fun `keeps its descriptor stable and returns exact content through the hydration gate`() {
        val descriptor = contentProvider.descriptor()
        assertStableDescriptor(descriptor, contentProvider.descriptor())
        val request = hydrationRequest(descriptor)
        assertEquals(descriptor.binding.digest, request.providerBinding.digest)
        assertTrue(request.maximumContentCodePoints <= descriptor.maximumContentCodePoints)

        val call = RetrievalContentHydrationGate.create(contentProvider, hydrationClock()).hydrate(request)
        val result = RetrievalContractAssertions.awaitStage(
            call.completion(),
            asynchronousTimeout(),
            "Retrieval content hydration completion",
        )
        result.requireValidFor(request)
        val cancellation = RetrievalContractAssertions.awaitStage(
            call.cancel(RetrievalCancellationReason.AUTHORIZATION_REVOKED),
            asynchronousTimeout(),
            "Retrieval content hydration cancellation",
        )
        RetrievalContractAssertions.assertCancellationDeclaration(descriptor.supportsCancellation, cancellation)
    }

    private fun assertStableDescriptor(
        first: RetrievalContentProviderDescriptor,
        second: RetrievalContentProviderDescriptor,
    ) {
        assertEquals(first.providerId, second.providerId)
        assertEquals(first.providerInstanceId, second.providerInstanceId)
        assertEquals(first.configurationDigest, second.configurationDigest)
        assertEquals(first.capabilityDigest, second.capabilityDigest)
        assertEquals(first.providerRevision, second.providerRevision)
        assertEquals(first.maximumContentCodePoints, second.maximumContentCodePoints)
        assertEquals(first.sendsContentOffHost, second.sendsContentOffHost)
        assertEquals(first.supportsCancellation, second.supportsCancellation)
        assertEquals(first.digest, second.digest, "Content provider descriptor digest must be stable.")
    }
}
