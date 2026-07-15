package ai.icen.fw.release.smoke.library

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.CandidateRetriever
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.RetrievalAccessPlan
import ai.icen.fw.retrieval.api.RetrievalAccessProfile
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest
import ai.icen.fw.retrieval.api.RetrievalAuthorizationSubject
import ai.icen.fw.retrieval.api.RetrievalCalls
import ai.icen.fw.retrieval.api.RetrievalCandidate
import ai.icen.fw.retrieval.api.RetrievalExecutionGate
import ai.icen.fw.retrieval.api.RetrievalExecutionPolicy
import ai.icen.fw.retrieval.api.RetrievalEvidenceRef
import ai.icen.fw.retrieval.api.RetrievalLineageRevisions
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalPlanResult
import ai.icen.fw.retrieval.api.RetrievalPrincipal
import ai.icen.fw.retrieval.api.RetrievalRequestSpec
import ai.icen.fw.retrieval.api.RetrievalResultEnvelope
import ai.icen.fw.testkit.retrieval.CandidateRetrieverContractTest

/**
 * Independent Kotlin/JVM 8 host proof for the published TestKit artifact.
 *
 * The concrete class is discovered by JUnit and executes the inherited security-gated retrieval
 * contract; it deliberately prepares its request through the public execution gate.
 */
class PublishedCandidateRetrieverContractTest : CandidateRetrieverContractTest() {
    private val descriptor = CandidateRetrieverDescriptor.builder(
        "release-smoke-index",
        "release-smoke-index-instance",
        "c".repeat(64),
        "d".repeat(64),
        "capability-v1",
    )
        .tenantConstraint("fw_tenant_id", "tenant-capability-v1")
        .supportMode(RetrievalMode.FULL_TEXT)
        .supportAccessProfile(RetrievalAccessProfile.AUTHORIZED_ID_SET)
        .limits(10, 100)
        .cancellation(true)
        .tenantAndAccessPreselectionGuaranteed(true)
        .build()

    override val candidateRetriever: CandidateRetriever = object : CandidateRetriever {
        override fun descriptor(): CandidateRetrieverDescriptor = descriptor

        override fun start(request: ExecutableRetrievalRequest) = RetrievalCalls.completed(
            resultEnvelope(request, descriptor),
        )
    }

    override fun requiredModes(): Set<RetrievalMode> = setOf(RetrievalMode.FULL_TEXT)

    override fun requiredAccessProfiles(): Set<RetrievalAccessProfile> =
        setOf(RetrievalAccessProfile.AUTHORIZED_ID_SET)

    override fun executableRequest(descriptor: CandidateRetrieverDescriptor): ExecutableRetrievalRequest {
        val authorization = RetrievalAuthorizationRequest.create(
            Identifier("retrieval-authorization"),
            Identifier("tenant-1"),
            RetrievalAuthorizationSubject.create(
                RetrievalPrincipal.create(Identifier("user-1"), "USER"),
                emptyMap(),
            ),
            "document:read",
            "release-smoke",
            100,
        )
        val plan = RetrievalAccessPlan.authorizedIds(
            Identifier("retrieval-decision"),
            authorization,
            "release-smoke-authorization",
            "policy-v1",
            110,
            1_000,
            listOf(Identifier("document-1")),
        )
        return RetrievalExecutionGate.prepare(
            Identifier("retrieval-attempt"),
            authorization,
            RetrievalPlanResult.allow(plan, 110),
            RetrievalRequestSpec.create(
                Identifier("retrieval-request"),
                RetrievalMode.FULL_TEXT,
                "water",
                5,
                500,
            ),
            descriptor,
            RetrievalExecutionPolicy.create(false, true, 10, 1_000),
            120,
        ).requireExecutable()
    }

    private fun resultEnvelope(
        request: ExecutableRetrievalRequest,
        descriptor: CandidateRetrieverDescriptor,
    ): RetrievalResultEnvelope {
        val evidence = RetrievalEvidenceRef.document(
            Identifier("tenant-1"),
            Identifier("catalog-1"),
            Identifier("projection-1"),
            Identifier("document-1"),
            Identifier("version-1"),
            Identifier("asset-1"),
            Identifier("object-1"),
            "b".repeat(64),
            "generation-1",
            RetrievalLineageRevisions.create("projection-v1", "acl-v1", null, null, null),
        )
        return RetrievalResultEnvelope.create(
            request,
            descriptor,
            evidence.indexGeneration,
            130,
            listOf(RetrievalCandidate.create(evidence, RetrievalMode.FULL_TEXT, 1.0, 1)),
            false,
            false,
        )
    }
}
