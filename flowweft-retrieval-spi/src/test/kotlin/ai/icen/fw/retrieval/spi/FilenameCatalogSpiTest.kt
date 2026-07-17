package ai.icen.fw.retrieval.spi

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor
import ai.icen.fw.retrieval.api.RetrievalAccessPlan
import ai.icen.fw.retrieval.api.RetrievalAccessProfile
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest
import ai.icen.fw.retrieval.api.RetrievalAuthorizationSubject
import ai.icen.fw.retrieval.api.RetrievalEvidenceRef
import ai.icen.fw.retrieval.api.RetrievalExecutionGate
import ai.icen.fw.retrieval.api.RetrievalExecutionPolicy
import ai.icen.fw.retrieval.api.RetrievalLineageRevisions
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalPlanResult
import ai.icen.fw.retrieval.api.RetrievalPrincipal
import ai.icen.fw.retrieval.api.RetrievalRequestSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilenameCatalogSpiTest {
    @Test
    fun `scan request contains only trusted tenant and exact authorized ids`() {
        val executable = executable()
        val descriptor = descriptor()
        val request = FilenameCatalogScanRequest.create(executable, descriptor)

        assertEquals(id("tenant-1"), request.tenantId)
        assertEquals(setOf(id("document-1"), id("document-2")), request.authorizedDocumentIds)
        assertEquals(2, request.maximumEntries)
        assertTrue(FilenameCatalogScanRequest::class.java.methods.none { it.name == "getQuery" })
        assertTrue(FilenameCatalogScanRequest::class.java.methods.none { it.name == "getPrincipal" })
        assertFalse(request.toString().contains("document-1"))
    }

    @Test
    fun `page binds authorized tenant generation stable order and cursor progress`() {
        val executable = executable()
        val descriptor = descriptor()
        val request = FilenameCatalogScanRequest.create(executable, descriptor)
        val first = entry("FlowWeft 报告.pdf", "0001", evidence(id("document-1"), 1))
        val second = entry("天津水务.txt", "0002", evidence(id("document-2"), 2))
        val page = FilenameCatalogPage.success(
            request,
            descriptor,
            "catalog-generation-1",
            listOf(first, second),
            "page_2",
            130L,
        )

        page.requireValidFor(request, descriptor)
        assertEquals("page_2", page.nextCursorToken)
        assertFalse(page.toString().contains(first.fileName))

        assertFailsWith<IllegalArgumentException> {
            FilenameCatalogPage.success(
                request,
                descriptor,
                "catalog-generation-1",
                listOf(second, first),
                null,
                130L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FilenameCatalogPage.success(
                request,
                descriptor,
                "catalog-generation-1",
                listOf(entry("越权.pdf", "0003", evidence(id("document-3"), 3))),
                null,
                130L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FilenameCatalogPage.success(
                request,
                descriptor,
                "catalog-generation-2",
                listOf(first),
                null,
                130L,
            )
        }
    }

    @Test
    fun `cursor resume pins catalog generation and forbids non-progress`() {
        val descriptor = descriptor()
        val firstExecutable = executable()
        val firstRequest = FilenameCatalogScanRequest.create(firstExecutable, descriptor)
        val firstPage = FilenameCatalogPage.success(
            firstRequest,
            descriptor,
            "catalog-generation-1",
            listOf(entry("天津水务.txt", "0001", evidence(id("document-1"), 1))),
            "page_2",
            130L,
        )
        val cursor = checkNotNull(
            ai.icen.fw.retrieval.api.RetrievalResultEnvelope.create(
                firstExecutable,
                candidateDescriptor(),
                firstPage.snapshotGeneration,
                firstPage.completedAtEpochMilli,
                emptyList(),
                firstPage.nextCursorToken,
                true,
                false,
            ).verifyFor(firstExecutable, candidateDescriptor(), 140L).nextCursor,
        )
        val resumed = executable(
            RetrievalRequestSpec.create(
                id("request-2"),
                RetrievalMode.CONTAINS_FILENAME,
                "水务",
                2,
                500L,
                cursor,
            ),
            id("attempt-2"),
            150L,
        )
        val resumedRequest = FilenameCatalogScanRequest.create(resumed, descriptor)

        assertEquals("catalog-generation-1", resumedRequest.expectedSnapshotGeneration)
        assertEquals("page_2", resumedRequest.cursorToken)
        assertFailsWith<IllegalArgumentException> {
            FilenameCatalogPage.success(
                resumedRequest,
                descriptor,
                "catalog-generation-1",
                emptyList(),
                "page_2",
                160L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FilenameCatalogPage.success(
                resumedRequest,
                descriptor,
                "catalog-generation-2",
                emptyList(),
                null,
                160L,
            )
        }
    }

    private fun executable(
        requestSpec: RetrievalRequestSpec = RetrievalRequestSpec.create(
            id("request-1"),
            RetrievalMode.CONTAINS_FILENAME,
            "水务",
            2,
            500L,
        ),
        attemptId: Identifier = id("attempt-1"),
        nowEpochMilli: Long = 120L,
    ) = RetrievalExecutionGate.prepare(
        attemptId,
        authorization(),
        RetrievalPlanResult.allow(accessPlan(), 110L),
        requestSpec,
        candidateDescriptor(),
        RetrievalExecutionPolicy.create(false, true, 10, 1_000L),
        nowEpochMilli,
    ).requireExecutable()

    private fun authorization(): RetrievalAuthorizationRequest = RetrievalAuthorizationRequest.create(
        id("authorization-1"),
        id("tenant-1"),
        RetrievalAuthorizationSubject.create(RetrievalPrincipal.create(id("user-1"), "USER"), emptyMap()),
        "document:read",
        "filename-search",
        100L,
    )

    private fun accessPlan(): RetrievalAccessPlan = RetrievalAccessPlan.authorizedIds(
        id("decision-1"),
        authorization(),
        "host-authorization",
        "policy-1",
        105L,
        1_000L,
        listOf(id("document-1"), id("document-2")),
    )

    private fun candidateDescriptor(): CandidateRetrieverDescriptor = CandidateRetrieverDescriptor.builder(
        "safe-filename-test",
        "filename-instance",
        "a".repeat(64),
        "b".repeat(64),
        "filename-v1",
    )
        .tenantConstraint("trusted-tenant-id", "tenant-v1")
        .supportMode(RetrievalMode.CONTAINS_FILENAME)
        .supportAccessProfile(RetrievalAccessProfile.AUTHORIZED_ID_SET)
        .limits(10, 100)
        .queryEgress(false)
        .cancellation(true)
        .cursorPagination(true)
        .tenantAndAccessPreselectionGuaranteed(true)
        .build()

    private fun descriptor(): FilenameCatalogDescriptor = FilenameCatalogDescriptor.create(
        "host.filename-catalog",
        "filename-instance",
        "c".repeat(64),
        "b".repeat(64),
        "catalog-v1",
        10,
        100,
        false,
        true,
        true,
    )

    private fun entry(fileName: String, sortKey: String, evidence: RetrievalEvidenceRef): FilenameCatalogEntry =
        FilenameCatalogEntry.create(fileName, sortKey, evidence)

    private fun evidence(documentId: Identifier, ordinal: Int): RetrievalEvidenceRef = RetrievalEvidenceRef.document(
        id("tenant-1"),
        id("catalog-1"),
        id("projection-$ordinal"),
        documentId,
        id("version-$ordinal"),
        id("asset-$ordinal"),
        id("object-$ordinal"),
        (if (ordinal % 2 == 0) "d" else "e").repeat(64),
        "catalog-generation-1",
        RetrievalLineageRevisions.create("projection-v1", "acl-v1", null, null, null),
    )

    private fun id(value: String): Identifier = Identifier(value)
}
