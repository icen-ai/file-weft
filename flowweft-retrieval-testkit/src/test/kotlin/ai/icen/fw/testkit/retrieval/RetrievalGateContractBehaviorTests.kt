package ai.icen.fw.testkit.retrieval

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.CandidateRetriever
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.RetrievalAccessProfile
import ai.icen.fw.retrieval.api.RetrievalAuthorizationPlanner
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest
import ai.icen.fw.retrieval.api.RetrievalCalls
import ai.icen.fw.retrieval.api.RetrievalContentHydrationGate
import ai.icen.fw.retrieval.api.RetrievalDenialCode
import ai.icen.fw.retrieval.api.RetrievalLineageResolver
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalPlanResult
import ai.icen.fw.retrieval.api.RetrievedContentPayload
import ai.icen.fw.retrieval.spi.RerankItem
import ai.icen.fw.retrieval.spi.RerankRequest
import ai.icen.fw.retrieval.spi.RerankResult
import ai.icen.fw.retrieval.spi.RerankScore
import ai.icen.fw.retrieval.spi.Reranker
import ai.icen.fw.retrieval.spi.RerankerDescriptor
import ai.icen.fw.retrieval.spi.RetrievalContentProvider
import ai.icen.fw.retrieval.spi.RetrievalContentProviderDescriptor
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionException

class CandidateRetrieverContractBehaviorTest : CandidateRetrieverContractTest() {
    private val descriptor = RetrievalSecurityGateFixture.candidateDescriptor

    override val candidateRetriever: CandidateRetriever = object : CandidateRetriever {
        override fun descriptor(): CandidateRetrieverDescriptor = descriptor

        override fun start(request: ExecutableRetrievalRequest) = RetrievalCalls.completed(
            RetrievalSecurityGateFixture.resultEnvelope(request, descriptor),
        )
    }

    override fun executableRequest(descriptor: CandidateRetrieverDescriptor): ExecutableRetrievalRequest =
        RetrievalSecurityGateFixture.executableRequest("candidate-contract", descriptor).second

    override fun requiredModes(): Set<RetrievalMode> = setOf(RetrievalMode.FULL_TEXT)

    override fun requiredAccessProfiles(): Set<RetrievalAccessProfile> =
        setOf(RetrievalAccessProfile.AUTHORIZED_ID_SET)
}

class RetrievalAuthorizationPlannerContractBehaviorTest : RetrievalAuthorizationPlannerContractTest() {
    override val authorizationPlanner: RetrievalAuthorizationPlanner = RetrievalAuthorizationPlanner { request ->
        if (request.purposeCode == "testkit") {
            RetrievalSecurityGateFixture.allowedPlan(request)
        } else {
            RetrievalPlanResult.deny(
                Identifier("denied-${request.id.value}"),
                request,
                "fixture-query-authority",
                "policy-v1",
                115L,
                RetrievalDenialCode.POLICY_DENIED,
            )
        }
    }

    override fun allowedAuthorizationRequest(): RetrievalAuthorizationRequest =
        RetrievalSecurityGateFixture.authorizationRequest("planner-allowed")

    override fun deniedAuthorizationRequest(): RetrievalAuthorizationRequest =
        RetrievalSecurityGateFixture.authorizationRequest("planner-denied", purposeCode = "denied")
}

class RetrievalLineageResolverContractBehaviorTest : RetrievalLineageResolverContractTest() {
    private val source = RetrievalSecurityGateFixture.prefilteredChain("lineage-contract")

    override val lineageResolver: RetrievalLineageResolver = RetrievalSecurityGateFixture.lineageResolver

    override fun executableRequest() = source.request

    override fun prefilteredBatch() = source.batch

    override fun resolutionRequestId(): Identifier = Identifier("lineage-contract-request")

    override fun resolutionClock() = RetrievalSecurityGateFixture.clock(140L, 160L)
}

class RetrievalCandidateAuthorizerContractBehaviorTest : RetrievalCandidateAuthorizerContractTest() {
    private val source = RetrievalSecurityGateFixture.resolvedChain("authorizer-contract")

    override val candidateAuthorizer = RetrievalSecurityGateFixture.candidateAuthorizer

    override fun queryAuthorizationRequest() = source.source.authorization

    override fun resolvedCandidateBatch() = source.batch

    override fun authorizationBatchId(): Identifier = Identifier("authorizer-contract-batch")

    override fun candidateAuthorizationRequestIds(): Collection<Identifier> =
        listOf(Identifier("authorizer-contract-request"))

    override fun authorizationClock() = RetrievalSecurityGateFixture.clock(170L, 200L)
}

class RetrievalContentProviderContractBehaviorTest : RetrievalContentProviderContractTest() {
    override val contentProvider: RetrievalContentProvider = RetrievalSecurityGateFixture.contentProvider

    override fun hydrationRequest(descriptor: RetrievalContentProviderDescriptor) =
        RetrievalSecurityGateFixture.hydrationRequest("content-contract", descriptor)

    override fun hydrationClock() = RetrievalSecurityGateFixture.clock(230L, 240L)
}

class RerankerContractBehaviorTest : RerankerContractTest() {
    private val descriptor = RerankerDescriptor.of(
        "fixture-reranker",
        "fixture-reranker-instance",
        RetrievalSecurityGateFixture.digest('4'),
        RetrievalSecurityGateFixture.digest('5'),
        "reranker-v1",
        "fixture-model",
        "model-v1",
        10,
        1_024,
        false,
        true,
    )

    override val reranker: Reranker = object : Reranker {
        override fun descriptor(): RerankerDescriptor = descriptor

        override fun rerank(request: RerankRequest) = RetrievalCalls.completed(
            RerankResult.success(
                request,
                listOf(
                    RerankScore.of(
                        request.items.single(),
                        1.0,
                        RetrievalSecurityGateFixture.digest('6'),
                    ),
                ),
                "fixture-rerank-request",
                260L,
            ),
        )
    }

    override fun rerankRequest(descriptor: RerankerDescriptor): RerankRequest {
        val hydrated = RetrievalSecurityGateFixture.hydratedChain("reranker-contract")
        return RerankRequest.of(
            Identifier("rerank-contract-request"),
            descriptor,
            "water",
            listOf(RerankItem.of(hydrated.authorized.batch.candidates.single(), hydrated.content)),
            1,
            250L,
            500L,
            false,
        )
    }
}

/** Public gate regression proofs for replay boundaries expressible without internal constructors. */
class RetrievalSecurityGateReplayBehaviorTest {
    @Test
    fun `rejects a receipt replayed across retrieval attempts`() {
        val original = RetrievalSecurityGateFixture.prefilteredChain("attempt-original")
        val replayTarget = RetrievalSecurityGateFixture.executableRequest("attempt-target").second

        assertThrows(IllegalArgumentException::class.java) {
            original.envelope.verifyFor(
                replayTarget,
                RetrievalSecurityGateFixture.candidateDescriptor,
                135L,
            )
        }
    }

    @Test
    fun `rejects a receipt replayed into another tenant`() {
        val original = RetrievalSecurityGateFixture.prefilteredChain("tenant-original")
        val replayTarget = RetrievalSecurityGateFixture.executableRequest(
            "tenant-target",
            tenantId = "tenant-2",
        ).second

        assertThrows(IllegalArgumentException::class.java) {
            original.envelope.verifyFor(
                replayTarget,
                RetrievalSecurityGateFixture.candidateDescriptor,
                135L,
            )
        }
    }

    @Test
    fun `rejects a receipt verified with another provider binding`() {
        val original = RetrievalSecurityGateFixture.prefilteredChain("provider-original")
        val otherDescriptor = CandidateRetrieverDescriptor.builder(
            "fixture-index",
            "other-index-instance",
            RetrievalSecurityGateFixture.digest('c'),
            RetrievalSecurityGateFixture.digest('d'),
            "capability-v1",
        )
            .tenantConstraint("fw_tenant_id", "tenant-capability-v1")
            .supportMode(RetrievalMode.FULL_TEXT)
            .supportAccessProfile(RetrievalAccessProfile.AUTHORIZED_ID_SET)
            .limits(10, 100)
            .cancellation(true)
            .tenantAndAccessPreselectionGuaranteed(true)
            .build()

        assertThrows(IllegalArgumentException::class.java) {
            original.envelope.verifyFor(original.request, otherDescriptor, 135L)
        }
    }

    @Test
    fun `rejects a hydration payload replayed into another request binding`() {
        val first = RetrievalSecurityGateFixture.hydrationRequest("binding-first")
        val second = RetrievalSecurityGateFixture.hydrationRequest("binding-second")
        val replayedPayload = RetrievedContentPayload.success(
            first,
            "authorized fixture content",
            "text/plain",
            RetrievalSecurityGateFixture.SOURCE_SHA256,
        )
        val replayingProvider = object : RetrievalContentProvider {
            override fun descriptor(): RetrievalContentProviderDescriptor =
                RetrievalSecurityGateFixture.contentDescriptor

            override fun hydrate(request: ai.icen.fw.retrieval.api.RetrievalHydrationRequest) =
                RetrievalCalls.completed(replayedPayload)
        }

        assertThrows(CompletionException::class.java) {
            RetrievalContentHydrationGate.create(
                replayingProvider,
                RetrievalSecurityGateFixture.clock(230L, 240L),
            ).hydrate(second).completion().toCompletableFuture().join()
        }
    }
}
