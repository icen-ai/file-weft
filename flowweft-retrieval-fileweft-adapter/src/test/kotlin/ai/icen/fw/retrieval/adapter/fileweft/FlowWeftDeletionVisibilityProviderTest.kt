package ai.icen.fw.retrieval.adapter.fileweft

import ai.icen.fw.application.retention.DeletionVisibilityFence
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.RetrievalDeletionResourceRef
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityRequest
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityState
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalProviderException
import java.util.function.LongSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FlowWeftDeletionVisibilityProviderTest {
    @Test
    fun `bridges durable host tombstones in exact request order without leaking evidence`() {
        val query = TestQuery().apply {
            fences[id("document-2")] = fence("document-2", 7L)
        }
        val provider = provider(query)
        val request = request(
            provider,
            listOf(
                RetrievalDeletionResourceRef.document(id("tenant-1"), id("document-1"), id("version-1")),
                RetrievalDeletionResourceRef.document(id("tenant-1"), id("document-2"), id("version-9")),
            ),
        )

        val batch = provider.inspect(request).completion().toCompletableFuture().join()
        batch.requireExactFor(request, provider.descriptor())

        assertEquals(
            listOf(RetrievalDeletionVisibilityState.VISIBLE, RetrievalDeletionVisibilityState.TOMBSTONED),
            batch.decisions.map { decision -> decision.state },
        )
        assertFalse(batch.toString().contains("tombstone-secret"))
        assertEquals(1, query.batchCalls)
    }

    @Test
    fun `foreign or extra projection evidence fails closed with a sanitized mismatch`() {
        val query = object : DeletionVisibilityQuery {
            override fun findFence(
                tenantId: Identifier,
                resourceType: String,
                resourceId: Identifier,
            ): DeletionVisibilityFence? = null

            override fun findFences(
                tenantId: Identifier,
                resourceType: String,
                resourceIds: Collection<Identifier>,
            ): Map<Identifier, DeletionVisibilityFence> = mapOf(
                id("document-foreign") to fence("document-foreign", 1L),
            )
        }
        val provider = provider(query)
        val request = request(
            provider,
            listOf(RetrievalDeletionResourceRef.document(id("tenant-1"), id("document-1"), id("version-1"))),
        )

        val failure = assertFailsWith<RetrievalProviderException> { provider.inspect(request) }

        assertEquals(RetrievalFailureCode.DELETION_VISIBILITY_MISMATCH, failure.code)
        assertFalse(failure.message.orEmpty().contains("document-foreign"))
    }

    @Test
    fun `descriptor drift and expired requests fail before querying host state`() {
        val query = TestQuery()
        val provider = provider(query)
        val otherProvider = FlowWeftDeletionVisibilityProvider(
            query,
            "b".repeat(64),
            LongSupplier { 150L },
        )
        val resources = listOf(
            RetrievalDeletionResourceRef.document(id("tenant-1"), id("document-1"), id("version-1")),
        )
        val drifted = request(otherProvider, resources)

        assertEquals(
            RetrievalFailureCode.DELETION_VISIBILITY_MISMATCH,
            assertFailsWith<RetrievalProviderException> { provider.inspect(drifted) }.code,
        )

        val expiredProvider = FlowWeftDeletionVisibilityProvider(
            query,
            "a".repeat(64),
            LongSupplier { 200L },
        )
        val expired = RetrievalDeletionVisibilityRequest.create(
            id("request-expired"),
            resources,
            expiredProvider.descriptor(),
            100L,
            200L,
        )
        assertEquals(
            RetrievalFailureCode.CANCELLED,
            assertFailsWith<RetrievalProviderException> { expiredProvider.inspect(expired) }.code,
        )
        assertEquals(0, query.batchCalls)
    }

    private fun provider(query: DeletionVisibilityQuery): FlowWeftDeletionVisibilityProvider =
        FlowWeftDeletionVisibilityProvider(query, "a".repeat(64), LongSupplier { 150L })

    private fun request(
        provider: FlowWeftDeletionVisibilityProvider,
        resources: List<RetrievalDeletionResourceRef>,
    ): RetrievalDeletionVisibilityRequest = RetrievalDeletionVisibilityRequest.create(
        id("request-1"),
        resources,
        provider.descriptor(),
        100L,
        500L,
    )

    private class TestQuery : DeletionVisibilityQuery {
        val fences = linkedMapOf<Identifier, DeletionVisibilityFence>()
        var batchCalls: Int = 0

        override fun findFence(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
        ): DeletionVisibilityFence? = fences[resourceId]

        override fun findFences(
            tenantId: Identifier,
            resourceType: String,
            resourceIds: Collection<Identifier>,
        ): Map<Identifier, DeletionVisibilityFence> {
            batchCalls += 1
            return fences.filterKeys(resourceIds::contains)
        }
    }

    companion object {
        private fun id(value: String): Identifier = Identifier(value)

        private fun fence(documentId: String, revision: Long): DeletionVisibilityFence =
            DeletionVisibilityFence(
                id("tombstone-secret"),
                id("plan-secret"),
                id("tenant-1"),
                RetrievalDeletionResourceRef.DOCUMENT_RESOURCE_TYPE,
                id(documentId),
                revision,
                120L,
            )
    }
}
