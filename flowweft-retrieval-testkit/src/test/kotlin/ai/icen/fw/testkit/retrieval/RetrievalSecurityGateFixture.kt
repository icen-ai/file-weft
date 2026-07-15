package ai.icen.fw.testkit.retrieval

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.AuthorizedCandidateBatch
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.PrefilteredCandidateBatch
import ai.icen.fw.retrieval.api.ResolvedCandidateBatch
import ai.icen.fw.retrieval.api.RetrievalAccessPlan
import ai.icen.fw.retrieval.api.RetrievalAccessProfile
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest
import ai.icen.fw.retrieval.api.RetrievalAuthorizationSubject
import ai.icen.fw.retrieval.api.RetrievalCalls
import ai.icen.fw.retrieval.api.RetrievalCandidate
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationDecision
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationDecisionBatch
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationGate
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizer
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizerDescriptor
import ai.icen.fw.retrieval.api.RetrievalContentEgressDecision
import ai.icen.fw.retrieval.api.RetrievalContentHydrationGate
import ai.icen.fw.retrieval.api.RetrievalEvidenceRef
import ai.icen.fw.retrieval.api.RetrievalExecutionGate
import ai.icen.fw.retrieval.api.RetrievalExecutionPolicy
import ai.icen.fw.retrieval.api.RetrievalHydrationRequest
import ai.icen.fw.retrieval.api.RetrievalLineageResolution
import ai.icen.fw.retrieval.api.RetrievalLineageResolutionBatch
import ai.icen.fw.retrieval.api.RetrievalLineageResolutionGate
import ai.icen.fw.retrieval.api.RetrievalLineageResolver
import ai.icen.fw.retrieval.api.RetrievalLineageResolverDescriptor
import ai.icen.fw.retrieval.api.RetrievalLineageRevisions
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalPlanResult
import ai.icen.fw.retrieval.api.RetrievalPrincipal
import ai.icen.fw.retrieval.api.RetrievalRequestSpec
import ai.icen.fw.retrieval.api.RetrievalResultEnvelope
import ai.icen.fw.retrieval.api.RetrievedContent
import ai.icen.fw.retrieval.api.RetrievedContentPayload
import ai.icen.fw.retrieval.spi.RetrievalContentProvider
import ai.icen.fw.retrieval.spi.RetrievalContentProviderDescriptor
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier

internal object RetrievalSecurityGateFixture {
    const val SOURCE_SHA256: String =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    val candidateDescriptor: CandidateRetrieverDescriptor = CandidateRetrieverDescriptor.builder(
        "fixture-index",
        "fixture-index-instance",
        digest('c'),
        digest('d'),
        "capability-v1",
    )
        .tenantConstraint("fw_tenant_id", "tenant-capability-v1")
        .supportMode(RetrievalMode.FULL_TEXT)
        .supportAccessProfile(RetrievalAccessProfile.AUTHORIZED_ID_SET)
        .limits(10, 100)
        .cancellation(true)
        .tenantAndAccessPreselectionGuaranteed(true)
        .build()

    val lineageDescriptor: RetrievalLineageResolverDescriptor = RetrievalLineageResolverDescriptor.create(
        "fixture-lineage",
        "fixture-lineage-instance",
        digest('a'),
        digest('b'),
        "lineage-v1",
        true,
    )

    val authorizerDescriptor: RetrievalCandidateAuthorizerDescriptor =
        RetrievalCandidateAuthorizerDescriptor.create(
            "fixture-authorization",
            "fixture-authorization-instance",
            digest('e'),
            digest('f'),
            "authorization-v1",
            true,
        )

    val contentDescriptor: RetrievalContentProviderDescriptor = RetrievalContentProviderDescriptor.of(
        "fixture-content",
        "fixture-content-instance",
        digest('1'),
        digest('2'),
        "content-v1",
        1_024,
        false,
        true,
    )

    val lineageResolver: RetrievalLineageResolver = object : RetrievalLineageResolver {
        override fun descriptor(): RetrievalLineageResolverDescriptor = lineageDescriptor

        override fun resolve(request: ai.icen.fw.retrieval.api.RetrievalLineageResolutionRequest) =
            RetrievalCalls.completed(
                RetrievalLineageResolutionBatch.success(
                    request,
                    request.source.candidates.map { candidate ->
                        RetrievalLineageResolution.create(
                            candidate,
                            candidate.evidence,
                            "fixture-lineage-authority",
                            "lineage-authority-v1",
                        )
                    },
                    150L,
                ),
            )
    }

    val candidateAuthorizer: RetrievalCandidateAuthorizer = object : RetrievalCandidateAuthorizer {
        override fun descriptor(): RetrievalCandidateAuthorizerDescriptor = authorizerDescriptor

        override fun authorize(requests: ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationBatch) =
            RetrievalCalls.completed(
                RetrievalCandidateAuthorizationDecisionBatch.success(
                    requests,
                    requests.requests.mapIndexed { index, request ->
                        RetrievalCandidateAuthorizationDecision.allow(
                            Identifier("candidate-decision-$index"),
                            request,
                            "fixture-authorization-authority",
                            "policy-v2",
                            180L,
                            800L,
                        )
                    },
                    190L,
                ),
            )
    }

    val contentProvider: RetrievalContentProvider = object : RetrievalContentProvider {
        override fun descriptor(): RetrievalContentProviderDescriptor = contentDescriptor

        override fun hydrate(request: RetrievalHydrationRequest) = RetrievalCalls.completed(
            RetrievedContentPayload.success(request, "authorized fixture content", "text/plain", SOURCE_SHA256),
        )
    }

    fun authorizationRequest(
        suffix: String,
        tenantId: String = "tenant-1",
        purposeCode: String = "testkit",
    ): RetrievalAuthorizationRequest = RetrievalAuthorizationRequest.create(
        Identifier("authorization-$suffix"),
        Identifier(tenantId),
        RetrievalAuthorizationSubject.create(
            RetrievalPrincipal.create(Identifier("user-$suffix"), "USER"),
            mapOf("department" to "engineering"),
        ),
        "document:read",
        purposeCode,
        100L,
    )

    fun allowedPlan(
        request: RetrievalAuthorizationRequest,
        documentId: Identifier = Identifier("document-1"),
    ): RetrievalPlanResult {
        val plan = RetrievalAccessPlan.authorizedIds(
            Identifier("decision-${request.id.value}"),
            request,
            "fixture-query-authority",
            "policy-v1",
            110L,
            1_000L,
            listOf(documentId),
        )
        return RetrievalPlanResult.allow(plan, 115L)
    }

    fun executableRequest(
        suffix: String,
        descriptor: CandidateRetrieverDescriptor = candidateDescriptor,
        tenantId: String = "tenant-1",
        documentId: Identifier = Identifier("document-1"),
    ): Pair<RetrievalAuthorizationRequest, ExecutableRetrievalRequest> {
        val authorization = authorizationRequest(suffix, tenantId)
        val executable = RetrievalExecutionGate.prepare(
            Identifier("attempt-$suffix"),
            authorization,
            allowedPlan(authorization, documentId),
            RetrievalRequestSpec.create(
                Identifier("query-$suffix"),
                RetrievalMode.FULL_TEXT,
                "water",
                5,
                900L,
            ),
            descriptor,
            RetrievalExecutionPolicy.create(false, true, 10, 900L),
            120L,
        ).requireExecutable()
        return authorization to executable
    }

    fun resultEnvelope(
        request: ExecutableRetrievalRequest,
        descriptor: CandidateRetrieverDescriptor = candidateDescriptor,
        documentId: Identifier = Identifier("document-1"),
    ): RetrievalResultEnvelope = RetrievalResultEnvelope.create(
        request,
        descriptor,
        "generation-v1",
        130L,
        listOf(
            RetrievalCandidate.create(
                evidence(request.tenantId, documentId),
                RetrievalMode.FULL_TEXT,
                1.0,
                1,
            ),
        ),
        false,
        false,
    )

    fun prefilteredChain(suffix: String = "fixture"): PrefilteredChain {
        val (authorization, request) = executableRequest(suffix)
        val envelope = resultEnvelope(request)
        return PrefilteredChain(
            authorization,
            request,
            envelope,
            envelope.verifyFor(request, candidateDescriptor, 135L),
        )
    }

    fun resolvedChain(suffix: String = "fixture"): ResolvedChain {
        val source = prefilteredChain(suffix)
        val resolved = RetrievalContractAssertions.awaitStage(
            RetrievalLineageResolutionGate.create(lineageResolver, clock(140L, 160L)).resolve(
                source.request,
                source.batch,
                Identifier("lineage-$suffix"),
                lineageDescriptor,
            ).completion(),
            Duration.ofSeconds(1),
            "Fixture lineage resolution",
        )
        return ResolvedChain(source, resolved)
    }

    fun authorizedChain(suffix: String = "fixture"): AuthorizedChain {
        val resolved = resolvedChain(suffix)
        val authorized = RetrievalContractAssertions.awaitStage(
            RetrievalCandidateAuthorizationGate.create(candidateAuthorizer, clock(170L, 200L)).authorize(
                resolved.source.authorization,
                resolved.batch,
                Identifier("authorization-batch-$suffix"),
                listOf(Identifier("candidate-authorization-$suffix")),
                authorizerDescriptor,
            ).completion(),
            Duration.ofSeconds(1),
            "Fixture candidate authorization",
        )
        return AuthorizedChain(resolved, authorized)
    }

    fun hydrationRequest(
        suffix: String,
        descriptor: RetrievalContentProviderDescriptor = contentDescriptor,
        authorized: AuthorizedCandidateBatch = authorizedChain(suffix).batch,
    ): RetrievalHydrationRequest {
        val candidate = authorized.candidates.single()
        val egress = RetrievalContentEgressDecision.create(
            Identifier("egress-$suffix"),
            candidate,
            descriptor.binding,
            digest('3'),
            "fixture-egress-authority",
            "egress-v1",
            descriptor.sendsContentOffHost,
            true,
            210L,
            700L,
        )
        return RetrievalHydrationRequest.create(
            Identifier("hydration-$suffix"),
            candidate,
            descriptor.binding,
            egress,
            1_024,
            220L,
            600L,
        )
    }

    fun hydratedChain(suffix: String = "fixture"): HydratedChain {
        val authorized = authorizedChain(suffix)
        val request = hydrationRequest(suffix, contentDescriptor, authorized.batch)
        val content = RetrievalContractAssertions.awaitStage(
            RetrievalContentHydrationGate.create(contentProvider, clock(230L, 240L))
                .hydrate(request)
                .completion(),
            Duration.ofSeconds(1),
            "Fixture content hydration",
        )
        return HydratedChain(authorized, request, content)
    }

    fun clock(vararg values: Long): LongSupplier {
        require(values.isNotEmpty())
        val index = AtomicInteger()
        return LongSupplier { values[minOf(index.getAndIncrement(), values.lastIndex)] }
    }

    fun digest(character: Char): String = character.toString().repeat(64)

    private fun evidence(tenantId: Identifier, documentId: Identifier): RetrievalEvidenceRef =
        RetrievalEvidenceRef.document(
            tenantId,
            Identifier("catalog-1"),
            Identifier("projection-1"),
            documentId,
            Identifier("version-1"),
            Identifier("asset-1"),
            Identifier("object-1"),
            SOURCE_SHA256,
            "generation-v1",
            RetrievalLineageRevisions.create("projection-v1", "acl-v1", null, null, null),
        )
}

internal class PrefilteredChain(
    val authorization: RetrievalAuthorizationRequest,
    val request: ExecutableRetrievalRequest,
    val envelope: RetrievalResultEnvelope,
    val batch: PrefilteredCandidateBatch,
)

internal class ResolvedChain(
    val source: PrefilteredChain,
    val batch: ResolvedCandidateBatch,
)

internal class AuthorizedChain(
    val resolved: ResolvedChain,
    val batch: AuthorizedCandidateBatch,
)

internal class HydratedChain(
    val authorized: AuthorizedChain,
    val request: RetrievalHydrationRequest,
    val content: RetrievedContent,
)
