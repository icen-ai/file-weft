package ai.icen.fw.retrieval.runtime

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.RetrievalAccessPlan
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest
import ai.icen.fw.retrieval.api.RetrievalAuthorizationSubject
import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalCalls
import ai.icen.fw.retrieval.api.RetrievalCancellationOutcome
import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalEvidenceRef
import ai.icen.fw.retrieval.api.RetrievalExecutionGate
import ai.icen.fw.retrieval.api.RetrievalExecutionPolicy
import ai.icen.fw.retrieval.api.RetrievalLineageRevisions
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalPlanResult
import ai.icen.fw.retrieval.api.RetrievalPrincipal
import ai.icen.fw.retrieval.api.RetrievalRequestSpec
import ai.icen.fw.retrieval.api.RetrievalResultEnvelope
import ai.icen.fw.retrieval.spi.FilenameCatalog
import ai.icen.fw.retrieval.spi.FilenameCatalogDescriptor
import ai.icen.fw.retrieval.spi.FilenameCatalogEntry
import ai.icen.fw.retrieval.spi.FilenameCatalogPage
import ai.icen.fw.retrieval.spi.FilenameCatalogScanRequest
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeFilenameCandidateRetrieverTest {
    @Test
    fun `matches unicode filenames locally with stable rank and hidden filename payload`() {
        lateinit var observed: FilenameCatalogScanRequest
        val catalog = TestFilenameCatalog { request, descriptor ->
            observed = request
            FilenameCatalogPage.success(
                request,
                descriptor,
                "catalog-generation-1",
                listOf(
                    entry("archive-flowweft-方案.txt", "0001", evidence(id("document-1"), 1)),
                    entry("ＦＬＯＷＷＥＦＴ 报告.PDF", "0002", evidence(id("document-2"), 2)),
                    entry("其他资料.pdf", "0003", evidence(id("document-3"), 3)),
                ),
                "page_2",
                130L,
            )
        }
        val retriever = SafeFilenameCandidateRetriever.create(catalog)
        val executable = executable(retriever, RetrievalMode.CONTAINS_FILENAME, "flowweft")
        val envelope = retriever.start(executable).completion().toCompletableFuture().join()

        assertEquals(id("tenant-1"), observed.tenantId)
        assertEquals(3, observed.maximumEntries)
        assertEquals(setOf(id("document-1"), id("document-2"), id("document-3")), observed.authorizedDocumentIds)
        assertTrue(FilenameCatalogScanRequest::class.java.methods.none { it.name == "getQuery" })
        assertTrue(RetrievalResultEnvelope::class.java.methods.none { it.name == "getCandidates" })

        val verified = envelope.verifyFor(executable, retriever.descriptor(), 140L)
        assertEquals(listOf(id("document-2"), id("document-1")), verified.candidates.map { it.evidence.documentId })
        assertEquals(listOf(1, 2), verified.candidates.map { it.providerRank })
        assertEquals(listOf(2.0, 1.0), verified.candidates.map { it.providerScore })
        assertEquals("page_2", verified.nextCursor?.opaqueToken)
        assertTrue(verified.partial)
        assertFalse(envelope.toString().contains("FLOWWEFT", ignoreCase = true))
    }

    @Test
    fun `full text fallback is explicit and vector semantics remain unsupported`() {
        val catalog = TestFilenameCatalog { request, descriptor ->
            FilenameCatalogPage.success(
                request,
                descriptor,
                "catalog-generation-1",
                listOf(entry("天津水务规划.pdf", "0001", evidence(id("document-1"), 1))),
                null,
                130L,
            )
        }
        val fallback = SafeFilenameCandidateRetriever.createFullTextFallback(catalog)
        val executable = executable(fallback, RetrievalMode.FULL_TEXT, "水务")
        val verified = fallback.start(executable).completion().toCompletableFuture().join()
            .verifyFor(executable, fallback.descriptor(), 140L)

        assertEquals(SafeFilenameCandidateRetriever.FALLBACK_PROVIDER_TYPE_ID, fallback.descriptor().providerTypeId)
        assertEquals(1, verified.candidates.size)
        assertEquals(RetrievalMode.FULL_TEXT, verified.candidates.single().sourceMode)
        assertFalse(fallback.descriptor().supportedModes.contains(RetrievalMode.VECTOR))
        assertFailsWith<IllegalArgumentException> {
            executable(fallback, RetrievalMode.VECTOR, "水务")
        }
    }

    @Test
    fun `exact and prefix modes keep distinct bounded semantics`() {
        val catalog = TestFilenameCatalog { request, descriptor ->
            FilenameCatalogPage.success(
                request,
                descriptor,
                "catalog-generation-1",
                listOf(
                    entry("天津水务.pdf", "0001", evidence(id("document-1"), 1)),
                    entry("天津水务规划.pdf", "0002", evidence(id("document-2"), 2)),
                    entry("河北水务.pdf", "0003", evidence(id("document-3"), 3)),
                ),
                null,
                130L,
            )
        }
        val retriever = SafeFilenameCandidateRetriever.create(catalog)
        val exact = executable(retriever, RetrievalMode.EXACT_FILENAME, "天津水务.pdf")
        val prefix = executable(retriever, RetrievalMode.PREFIX_FILENAME, "天津")

        assertEquals(
            listOf(id("document-1")),
            retriever.start(exact).completion().toCompletableFuture().join()
                .verifyFor(exact, retriever.descriptor(), 140L).candidates.map { it.evidence.documentId },
        )
        assertEquals(
            listOf(id("document-1"), id("document-2")),
            retriever.start(prefix).completion().toCompletableFuture().join()
                .verifyFor(prefix, retriever.descriptor(), 140L).candidates.map { it.evidence.documentId },
        )
    }

    @Test
    fun `unsafe catalog egress unstable pagination drift and cancellation fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            SafeFilenameCandidateRetriever.create(TestFilenameCatalog(sendsMetadataOffHost = true) { _, _ ->
                throw AssertionError("unsafe catalog must not be called")
            })
        }
        assertFailsWith<IllegalArgumentException> {
            SafeFilenameCandidateRetriever.create(TestFilenameCatalog(stablePagination = false) { _, _ ->
                throw AssertionError("unstable catalog must not be called")
            })
        }

        val drifting = TestFilenameCatalog { request, descriptor ->
            FilenameCatalogPage.success(request, descriptor, "catalog-generation-1", emptyList(), null, 130L)
        }
        val retriever = SafeFilenameCandidateRetriever.create(drifting)
        val executable = executable(retriever, RetrievalMode.CONTAINS_FILENAME, "水务")
        drifting.currentDescriptor = descriptor(configurationDigest = "9".repeat(64))
        assertFailsWith<IllegalArgumentException> { retriever.start(executable) }

        val stable = TestFilenameCatalog { request, descriptor ->
            FilenameCatalogPage.success(request, descriptor, "catalog-generation-1", emptyList(), null, 130L)
        }
        val cancellable = SafeFilenameCandidateRetriever.create(stable)
        val cancellableRequest = executable(cancellable, RetrievalMode.CONTAINS_FILENAME, "水务")
        val outcome = cancellable.start(cancellableRequest).cancel(RetrievalCancellationReason.CALLER_CANCELLED)
            .toCompletableFuture().join()
        assertEquals(RetrievalCancellationOutcome.ACCEPTED, outcome)
        assertEquals(listOf(RetrievalCancellationReason.CALLER_CANCELLED), stable.cancellations)
    }

    private fun executable(
        retriever: SafeFilenameCandidateRetriever,
        mode: RetrievalMode,
        query: String,
    ): ExecutableRetrievalRequest {
        val authorization = authorization()
        val plan = RetrievalAccessPlan.authorizedIds(
            id("decision-1"),
            authorization,
            "host-authorization",
            "policy-1",
            105L,
            1_000L,
            listOf(id("document-1"), id("document-2"), id("document-3")),
        )
        return RetrievalExecutionGate.prepare(
            id("attempt-1"),
            authorization,
            RetrievalPlanResult.allow(plan, 110L),
            RetrievalRequestSpec.create(id("request-1"), mode, query, 3, 500L),
            retriever.descriptor(),
            RetrievalExecutionPolicy.create(false, true, 10, 1_000L),
            120L,
        ).requireExecutable()
    }

    private fun authorization(): RetrievalAuthorizationRequest = RetrievalAuthorizationRequest.create(
        id("authorization-1"),
        id("tenant-1"),
        RetrievalAuthorizationSubject.create(RetrievalPrincipal.create(id("user-1"), "USER"), emptyMap()),
        "document:read",
        "filename-search",
        100L,
    )

    private class TestFilenameCatalog(
        sendsMetadataOffHost: Boolean = false,
        stablePagination: Boolean = true,
        private val handler: (FilenameCatalogScanRequest, FilenameCatalogDescriptor) -> FilenameCatalogPage,
    ) : FilenameCatalog {
        var currentDescriptor: FilenameCatalogDescriptor = descriptor(
            sendsMetadataOffHost = sendsMetadataOffHost,
            stablePagination = stablePagination,
        )
        val cancellations = ArrayList<RetrievalCancellationReason>()

        override fun descriptor(): FilenameCatalogDescriptor = currentDescriptor

        override fun scan(request: FilenameCatalogScanRequest): RetrievalCall<FilenameCatalogPage> {
            val completion = CompletableFuture.completedFuture(handler(request, currentDescriptor))
            return RetrievalCalls.from(completion) { reason ->
                cancellations.add(reason)
                CompletableFuture.completedFuture(RetrievalCancellationOutcome.ACCEPTED)
            }
        }
    }

    private companion object {
        fun descriptor(
            configurationDigest: String = "a".repeat(64),
            sendsMetadataOffHost: Boolean = false,
            stablePagination: Boolean = true,
        ): FilenameCatalogDescriptor = FilenameCatalogDescriptor.create(
            "host.filename-catalog",
            "filename-instance",
            configurationDigest,
            "b".repeat(64),
            "catalog-v1",
            10,
            100,
            sendsMetadataOffHost,
            true,
            stablePagination,
        )

        fun entry(fileName: String, sortKey: String, evidence: RetrievalEvidenceRef): FilenameCatalogEntry =
            FilenameCatalogEntry.create(fileName, sortKey, evidence)

        fun evidence(documentId: Identifier, ordinal: Int): RetrievalEvidenceRef = RetrievalEvidenceRef.document(
            id("tenant-1"),
            id("catalog-1"),
            id("projection-$ordinal"),
            documentId,
            id("version-$ordinal"),
            id("asset-$ordinal"),
            id("object-$ordinal"),
            (if (ordinal % 2 == 0) "c" else "d").repeat(64),
            "catalog-generation-1",
            RetrievalLineageRevisions.create("projection-v1", "acl-v1", null, null, null),
        )

        fun id(value: String): Identifier = Identifier(value)
    }
}
