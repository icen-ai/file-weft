package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeletionVisibilityContractsTest {
    @Test
    fun `response is bound to exact tenant resource revision order and provider snapshot`() {
        val descriptor = descriptor()
        val first = resource("document-1", "version-1", "a")
        val second = resource("document-2", "version-2", "b")
        val request = RetrievalDeletionVisibilityRequest.create(
            Identifier("visibility-request-1"),
            listOf(first, second),
            descriptor,
            100L,
            200L,
        )
        val response = RetrievalDeletionVisibilityBatch.create(
            request,
            descriptor,
            listOf(
                RetrievalDeletionVisibilityDecision.visible(first, "authority-10", 120L),
                RetrievalDeletionVisibilityDecision.tombstoned(second, "authority-10", "delete-7", 121L),
            ),
            130L,
        )

        response.requireExactFor(request, descriptor)
        assertTrue(response.decisions.first().isVisible())
        assertFalse(response.decisions.last().isVisible())
        assertEquals("delete-7", response.decisions.last().tombstoneRevision)
        assertFailsWith<IllegalArgumentException> {
            RetrievalDeletionVisibilityBatch.create(
                request,
                descriptor,
                response.decisions.reversed(),
                130L,
            )
        }
    }

    @Test
    fun `request is single tenant bounded and revision sensitive`() {
        val descriptor = descriptor(maximumBatchSize = 1)
        val first = resource("document-1", "version-1", "a")
        val changedSource = resource("document-1", "version-1", "c")
        assertFalse(first.digest == changedSource.digest)
        assertFailsWith<IllegalArgumentException> {
            RetrievalDeletionVisibilityRequest.create(
                Identifier("too-large"),
                listOf(first, changedSource),
                descriptor,
                100L,
                200L,
            )
        }

        val otherTenant = RetrievalDeletionResourceRef.documentContent(
            Identifier("tenant-2"),
            Identifier("document-2"),
            Identifier("version-2"),
            "d".repeat(64),
        )
        assertFailsWith<IllegalArgumentException> {
            RetrievalDeletionVisibilityRequest.create(
                Identifier("cross-tenant"),
                listOf(first, otherTenant),
                descriptor(maximumBatchSize = 2),
                100L,
                200L,
            )
        }
    }

    private fun descriptor(maximumBatchSize: Int = 10): RetrievalDeletionVisibilityDescriptor =
        RetrievalDeletionVisibilityDescriptor.create(
            "host.deletion-visibility",
            "visibility-instance",
            "f".repeat(64),
            "visibility-v1",
            listOf(RetrievalDeletionResourceRef.DOCUMENT_RESOURCE_TYPE),
            maximumBatchSize,
            true,
            true,
            false,
        )

    private fun resource(document: String, version: String, digestCharacter: String): RetrievalDeletionResourceRef =
        RetrievalDeletionResourceRef.documentContent(
            Identifier("tenant-1"),
            Identifier(document),
            Identifier(version),
            digestCharacter.repeat(64),
        )
}
