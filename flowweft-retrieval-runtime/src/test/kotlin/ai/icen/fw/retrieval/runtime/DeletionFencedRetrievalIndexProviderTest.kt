package ai.icen.fw.retrieval.runtime

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalProviderException
import ai.icen.fw.retrieval.spi.ContentSourceRef
import ai.icen.fw.retrieval.spi.RetrievalIndexActivationReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexActivationRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationKind
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexProvider
import ai.icen.fw.retrieval.spi.RetrievalIndexProviderDescriptor
import ai.icen.fw.retrieval.spi.RetrievalIndexSealReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexSealRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexStageBatch
import ai.icen.fw.retrieval.spi.RetrievalIndexStageReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexState
import ai.icen.fw.retrieval.spi.RetrievalIndexStateRequest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeletionFencedRetrievalIndexProviderTest {
    @Test
    fun `tombstone mutation requires authoritative tombstone before and after propagation`() {
        val clock = MutableClock()
        val visibility = TestDeletionVisibilityProvider(clock).apply {
            tombstonedResourceIds.add(id("document-1"))
        }
        val delegate = MutationIndexProvider(clock)
        val fenced = DeletionFencedRetrievalIndexProvider.create(delegate, visibility, clock, SequenceIds())
        val request = mutation(delegate.value, RetrievalIndexMutationKind.TOMBSTONE)

        val receipt = fenced.mutate(request).toCompletableFuture().join()

        assertEquals(RetrievalIndexMutationKind.TOMBSTONE, receipt.kind)
        assertEquals(1, delegate.mutationCalls)
        assertEquals(2, visibility.requests.size)
        assertTrue(visibility.requests.all { it.resources.single().resourceRevision.length == 64 })
    }

    @Test
    fun `authorization refresh cannot reinsert a tombstoned source revision`() {
        val clock = MutableClock()
        val visibility = TestDeletionVisibilityProvider(clock).apply {
            tombstonedResourceIds.add(id("document-1"))
        }
        val delegate = MutationIndexProvider(clock)
        val fenced = DeletionFencedRetrievalIndexProvider.create(delegate, visibility, clock, SequenceIds())

        val failure = try {
            fenced.mutate(mutation(delegate.value, RetrievalIndexMutationKind.AUTHORIZATION_REFRESH))
                .toCompletableFuture().join()
            throw AssertionError("Expected tombstone fence failure")
        } catch (failure: CompletionException) {
            failure.cause as RetrievalProviderException
        }

        assertEquals(RetrievalFailureCode.RESOURCE_TOMBSTONED, failure.code)
        assertEquals(0, delegate.mutationCalls)
    }

    private class MutationIndexProvider(private val clock: MutableClock) : RetrievalIndexProvider {
        val value = RetrievalIndexProviderDescriptor.of(
            "test-index",
            "test-index-instance",
            "index-provider-v1",
            "schema-v1",
            100,
            true,
            false,
            true,
            true,
            true,
        )
        var mutationCalls: Int = 0

        override fun descriptor(): RetrievalIndexProviderDescriptor = value

        override fun mutate(request: RetrievalIndexMutationRequest): CompletionStage<RetrievalIndexMutationReceipt> {
            mutationCalls++
            clock.now = 140L
            return CompletableFuture.completedFuture(
                RetrievalIndexMutationReceipt.applied(
                    request,
                    request.expectedProjectionRevision,
                    3,
                    "d".repeat(64),
                    "index-mutation-1",
                    135L,
                ),
            )
        }

        override fun stage(request: RetrievalIndexStageBatch): CompletionStage<RetrievalIndexStageReceipt> =
            throw AssertionError("stage is not used by this focused mutation test")

        override fun seal(request: RetrievalIndexSealRequest): CompletionStage<RetrievalIndexSealReceipt> =
            throw AssertionError("seal is not used by this focused mutation test")

        override fun activate(request: RetrievalIndexActivationRequest): CompletionStage<RetrievalIndexActivationReceipt> =
            throw AssertionError("activate is not used by this focused mutation test")

        override fun state(request: RetrievalIndexStateRequest): CompletionStage<RetrievalIndexState> =
            throw AssertionError("state is not used by this focused mutation test")
    }

    private class MutableClock(var now: Long = 120L) : LongSupplier {
        override fun getAsLong(): Long = now
    }

    private class SequenceIds : RetrievalRuntimeIdGenerator {
        private val sequence = AtomicInteger()
        override fun nextId(purpose: RetrievalRuntimeIdPurpose): Identifier =
            id("${purpose.id}-${sequence.incrementAndGet()}")
    }

    private companion object {
        fun mutation(
            descriptor: RetrievalIndexProviderDescriptor,
            kind: RetrievalIndexMutationKind,
        ): RetrievalIndexMutationRequest = RetrievalIndexMutationRequest.of(
            id("mutation-${kind.code}"),
            descriptor,
            ContentSourceRef.of(
                id("tenant-1"),
                id("catalog-1"),
                id("document-1"),
                id("version-1"),
                id("asset-1"),
                id("object-1"),
                "a".repeat(64),
                10L,
                "text/plain",
                "content-v1",
            ),
            "generation-1",
            7L,
            kind,
            "policy-1",
            "b".repeat(64),
            "document-deleted",
            100L,
            500L,
        )

        fun id(value: String): Identifier = Identifier(value)
    }
}
