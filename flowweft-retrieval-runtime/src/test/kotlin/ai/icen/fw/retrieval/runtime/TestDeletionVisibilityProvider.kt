package ai.icen.fw.retrieval.runtime

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalCalls
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityBatch
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityDecision
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityDescriptor
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityProvider
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityRequest
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier

internal class TestDeletionVisibilityProvider(
    private val clock: LongSupplier,
    private val beforeInspect: ((Int) -> Unit)? = null,
) : RetrievalDeletionVisibilityProvider {
    private val sequence = AtomicInteger()
    val tombstonedResourceIds: MutableSet<Identifier> = LinkedHashSet()
    val requests: MutableList<RetrievalDeletionVisibilityRequest> = ArrayList()
    private val value = RetrievalDeletionVisibilityDescriptor.create(
        "host.deletion-visibility",
        "test-deletion-visibility",
        "e".repeat(64),
        "deletion-visibility-v1",
        listOf("document"),
        100,
        true,
        true,
        false,
    )

    override fun descriptor(): RetrievalDeletionVisibilityDescriptor = value

    override fun inspect(request: RetrievalDeletionVisibilityRequest): RetrievalCall<RetrievalDeletionVisibilityBatch> {
        val ordinal = sequence.incrementAndGet()
        beforeInspect?.invoke(ordinal)
        requests.add(request)
        val now = clock.asLong
        val decisions = request.resources.map { resource ->
            if (resource.resourceId in tombstonedResourceIds) {
                RetrievalDeletionVisibilityDecision.tombstoned(
                    resource,
                    "deletion-authority-$ordinal",
                    "tombstone-$ordinal",
                    now,
                )
            } else {
                RetrievalDeletionVisibilityDecision.visible(resource, "deletion-authority-$ordinal", now)
            }
        }
        return RetrievalCalls.completed(
            RetrievalDeletionVisibilityBatch.create(request, value, decisions, now),
        )
    }
}
