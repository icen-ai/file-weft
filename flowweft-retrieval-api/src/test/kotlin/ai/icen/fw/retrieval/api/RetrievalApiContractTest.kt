package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.LongSupplier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RetrievalApiContractTest {
    @Test
    fun `extensible public identifiers remain stable machine codes`() {
        assertEquals("content.custom", RetrievalMode.of("content.custom").id)
        assertEquals("scope.custom", RetrievalAccessProfile.of("scope.custom").id)
        assertEquals("policy.custom", RetrievalDenialCode.of("policy.custom").id)

        listOf("Full Text", "中文模式", "content/full-text", "content..full-text").forEach { value ->
            assertFailsWith<IllegalArgumentException> { RetrievalMode.of(value) }
        }
        assertFailsWith<IllegalArgumentException> { RetrievalAccessProfile.of("AUTHORIZED_SET") }
        assertFailsWith<IllegalArgumentException> { RetrievalDenialCode.of("Human readable denial") }
    }

    @Test
    fun `full chain hides candidates until prefilter and authorizes exact resources before hydration`() {
        val fixture = fixture()
        val envelope = envelope(fixture)

        assertTrue(RetrievalResultEnvelope::class.java.methods.none { it.name == "getCandidates" })
        assertTrue(RetrievalResultEnvelope::class.java.methods.none { it.name == "getCandidateCount" })

        val prefiltered = envelope.verifyFor(fixture.executable, fixture.descriptor, 140L)
        val resolvedBatch = resolveBatch(fixture, prefiltered)
        val authorizationTimes = ArrayDeque(listOf(160L, 170L))
        val authorizer = authorizer(165L) { requests ->
            val decisions = requests.requests.mapIndexed { index, request ->
                RetrievalCandidateAuthorizationDecision.allow(
                    id("candidate-decision-$index"),
                    request,
                    "host-authorization",
                    "policy-8",
                    165L,
                    210L,
                )
            }
            decisions
        }
        val authorizationGate = RetrievalCandidateAuthorizationGate.create(
            authorizer,
            LongSupplier { authorizationTimes.removeFirst() },
        )
        val authorized = authorizationGate.authorize(
            fixture.authorizationRequest,
            resolvedBatch,
            id("candidate-authorization-batch-1"),
            listOf(id("candidate-authorization-1")),
            authorizerDescriptor(),
        ).completion().toCompletableFuture().join()

        assertEquals(1, authorized.candidates.size)
        val providerBinding = contentBinding()
        val egressDecision = RetrievalContentEgressDecision.create(
            id("content-egress-1"),
            authorized.candidates.single(),
            providerBinding,
            "a".repeat(64),
            "runtime-policy",
            "policy-1",
            false,
            false,
            175L,
            205L,
        )
        val hydrationRequest = RetrievalHydrationRequest.create(
            id("hydration-1"),
            authorized.candidates.single(),
            providerBinding,
            egressDecision,
            1_000,
            180L,
            205L,
        )
        val hydrationTimes = ArrayDeque(listOf(180L, 190L))
        val exactPayload = RetrievedContentPayload.success(
            hydrationRequest,
            "第一行\n第二行\t内容",
            "text/plain",
            fixture.evidence.sourceSha256,
        )
        val hydrationGate = RetrievalContentHydrationGate.create(
            object : RetrievalContentHydrator {
                override fun hydrate(request: RetrievalHydrationRequest): RetrievalCall<RetrievedContentPayload> =
                    RetrievalCalls.completed(exactPayload)
            },
            LongSupplier { hydrationTimes.removeFirst() },
        )
        val content = hydrationGate.hydrate(hydrationRequest).completion().toCompletableFuture().join()

        content.requireValidFor(hydrationRequest)
        assertEquals("第一行\n第二行\t内容", content.text)
        assertEquals(fixture.evidence.digest, content.evidenceDigest)
        assertEquals(providerBinding.digest, content.providerBindingDigest)
        assertEquals(providerBinding.providerInstanceId, content.providerInstanceId)
        assertEquals(providerBinding.configurationDigest, content.providerConfigurationDigest)
        assertEquals(providerBinding.capabilityDigest, content.providerCapabilityDigest)
        assertEquals(providerBinding.descriptorDigest, content.providerDescriptorDigest)
        assertEquals(providerBinding.capabilityRevision, content.providerRevision)
        assertEquals(egressDecision.digest, content.egressDecisionDigest)
        assertTrue(content.contentSha256.matches(Regex("[0-9a-f]{64}")))
        assertFailsWith<IllegalArgumentException> {
            RetrievedContentPayload.success(hydrationRequest, "bad\u0000content", "text/plain", "b".repeat(64))
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievedContentPayload.success(
                hydrationRequest,
                "content",
                "text/plain; charset=utf-8",
                "b".repeat(64),
            )
        }
        val replayRequest = RetrievalHydrationRequest.create(
            id("hydration-replay"),
            authorized.candidates.single(),
            providerBinding,
            egressDecision,
            1_000,
            180L,
            205L,
        )
        val replayTimes = ArrayDeque(listOf(180L, 190L))
        val replayGate = RetrievalContentHydrationGate.create(
            object : RetrievalContentHydrator {
                override fun hydrate(request: RetrievalHydrationRequest): RetrievalCall<RetrievedContentPayload> =
                    RetrievalCalls.completed(exactPayload)
            },
            LongSupplier { replayTimes.removeFirst() },
        )
        assertFailsWith<CompletionException> {
            replayGate.hydrate(replayRequest).completion().toCompletableFuture().join()
        }
    }

    @Test
    fun `denial and empty authorization sets never create a provider executable`() {
        val authorization = authorizationRequest()
        val denied = RetrievalPlanResult.deny(
            id("query-denial"),
            authorization,
            "host-authorization",
            "policy-7",
            110L,
            RetrievalDenialCode.NO_VISIBLE_DOCUMENTS,
        )
        val preparation = RetrievalExecutionGate.prepare(
            id("attempt-denied"),
            authorization,
            denied,
            requestSpec(),
            descriptor(),
            executionPolicy(),
            120L,
        )

        assertFalse(preparation.providerCallAllowed)
        assertEquals(RetrievalDenialCode.NO_VISIBLE_DOCUMENTS, preparation.denialCode)
        assertFailsWith<IllegalStateException> { preparation.requireExecutable() }
        assertFailsWith<IllegalArgumentException> {
            RetrievalAccessPlan.authorizedIds(
                id("empty-decision"),
                authorization,
                "host-authorization",
                "policy-7",
                110L,
                1_000L,
                emptyList(),
            )
        }
    }

    @Test
    fun `receipt binds tenant provider attempt generation rank and exact candidate payload`() {
        val fixture = fixture()
        val envelope = envelope(fixture)
        val wrongDescriptor = descriptor(configurationDigest = "e".repeat(64))

        assertFailsWith<IllegalArgumentException> {
            envelope.verifyFor(fixture.executable, wrongDescriptor, 140L)
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalResultEnvelope.create(
                fixture.executable,
                fixture.descriptor,
                "another-generation",
                130L,
                listOf(fixture.candidate),
                false,
                false,
            )
        }

        val unauthorized = candidate(
            evidence(documentId = id("document-outside-plan")),
            providerRank = 1,
        )
        val unauthorizedEnvelope = RetrievalResultEnvelope.create(
            fixture.executable,
            fixture.descriptor,
            unauthorized.evidence.indexGeneration,
            130L,
            listOf(unauthorized),
            false,
            false,
        )
        assertFailsWith<IllegalArgumentException> {
            unauthorizedEnvelope.verifyFor(fixture.executable, fixture.descriptor, 140L)
        }

        assertFailsWith<IllegalArgumentException> {
            RetrievalResultEnvelope.create(
                fixture.executable,
                fixture.descriptor,
                fixture.evidence.indexGeneration,
                130L,
                listOf(candidate(fixture.evidence, providerRank = 2)),
                false,
                false,
            )
        }
        assertNotEquals(
            candidate(fixture.evidence, providerScore = -0.0).digest,
            candidate(fixture.evidence, providerScore = 0.0).digest,
        )
        assertFalse(envelope.securityFilterReceipt.toString().contains("1"))
    }

    @Test
    fun `cursor is hidden until receipt verification and cannot cross query scope provider or expiry`() {
        val authorization = authorizationRequest()
        val plan = accessPlan(authorization)
        val descriptor = descriptor(supportsCursorPagination = true)
        val firstSpec = requestSpec()
        val firstExecutable = RetrievalExecutionGate.prepare(
            id("attempt-cursor-1"),
            authorization,
            RetrievalPlanResult.allow(plan, 110L),
            firstSpec,
            descriptor,
            executionPolicy(),
            120L,
        ).requireExecutable()
        val evidence = evidence()
        val envelope = RetrievalResultEnvelope.create(
            firstExecutable,
            descriptor,
            evidence.indexGeneration,
            130L,
            listOf(candidate(evidence)),
            "cursor_page_2",
            false,
            false,
        )

        assertTrue(RetrievalResultEnvelope::class.java.methods.none { it.name == "getNextCursor" })
        val firstPage = envelope.verifyFor(firstExecutable, descriptor, 140L)
        val cursor = checkNotNull(firstPage.nextCursor)
        assertEquals("cursor_page_2", cursor.opaqueToken)
        assertEquals(cursor.digest, firstPage.securityFilterReceipt.nextCursorDigest)
        assertFalse(cursor.toString().contains(cursor.opaqueToken))

        val resumedSpec = RetrievalRequestSpec.create(
            id("request-cursor-2"),
            RetrievalMode.FULL_TEXT,
            "天津水务",
            5,
            500L,
            cursor,
        )
        val resumed = RetrievalExecutionGate.prepare(
            id("attempt-cursor-2"),
            authorization,
            RetrievalPlanResult.allow(plan, 110L),
            resumedSpec,
            descriptor,
            executionPolicy(),
            150L,
        ).requireExecutable()
        assertEquals(cursor.digest, resumed.pageCursor?.digest)
        val switchedEvidence = evidence(indexGeneration = "generation-2")
        val switchedGeneration = RetrievalResultEnvelope.create(
            resumed,
            descriptor,
            switchedEvidence.indexGeneration,
            160L,
            listOf(candidate(switchedEvidence)),
            false,
            false,
        )
        assertFailsWith<IllegalArgumentException> {
            switchedGeneration.verifyFor(resumed, descriptor, 170L)
        }

        assertFailsWith<IllegalArgumentException> {
            RetrievalRequestSpec.create(
                id("request-cursor-other-query"),
                RetrievalMode.FULL_TEXT,
                "另一查询",
                5,
                500L,
                cursor,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalExecutionGate.prepare(
                id("attempt-cursor-other-provider"),
                authorization,
                RetrievalPlanResult.allow(plan, 110L),
                resumedSpec,
                descriptor(configurationDigest = "9".repeat(64), supportsCursorPagination = true),
                executionPolicy(),
                150L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalExecutionGate.prepare(
                id("attempt-cursor-expired"),
                authorization,
                RetrievalPlanResult.allow(plan, 110L),
                resumedSpec,
                descriptor,
                executionPolicy(),
                500L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalResultEnvelope.create(
                firstExecutable,
                descriptor(supportsCursorPagination = false),
                evidence.indexGeneration,
                130L,
                listOf(candidate(evidence)),
                "cursor_page_2",
                false,
                false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalResultEnvelope.create(
                firstExecutable,
                descriptor,
                evidence.indexGeneration,
                130L,
                listOf(candidate(evidence)),
                "cursor with spaces",
                false,
                false,
            )
        }
    }

    @Test
    fun `lineage and candidate authorization reject stale altered and denied resources`() {
        val fixture = fixture()
        val prefiltered = envelope(fixture).verifyFor(fixture.executable, fixture.descriptor, 140L)
        assertFailsWith<IllegalArgumentException> {
            RetrievalLineageResolution.create(
                fixture.candidate,
                evidence(sourceSha256 = "c".repeat(64)),
                "catalog-authority",
                "lineage-7",
            )
        }
        val alteredCandidate = candidate(fixture.evidence, providerScore = 9.0)
        val alteredResolution = RetrievalLineageResolution.create(
            alteredCandidate,
            fixture.evidence,
            "catalog-authority",
            "lineage-7",
        )
        val lineageDescriptor = lineageDescriptor()
        val lineageRequest = RetrievalLineageResolutionRequest.prepare(
            id("lineage-altered"),
            prefiltered,
            lineageDescriptor,
            145L,
            500L,
        )
        assertFailsWith<IllegalArgumentException> {
            RetrievalLineageResolutionBatch.success(
                lineageRequest,
                listOf(alteredResolution),
                150L,
            )
        }
        val staleTimes = ArrayDeque(listOf(145L, fixture.executable.deadlineEpochMilli))
        val staleGate = RetrievalLineageResolutionGate.create(
            resolver(fixture),
            LongSupplier { staleTimes.removeFirst() },
        )
        assertFailsWith<CompletionException> {
            staleGate.resolve(
                fixture.executable,
                prefiltered,
                id("lineage-stale"),
                lineageDescriptor,
            ).completion().toCompletableFuture().join()
        }

        val resolvedBatch = resolveBatch(fixture, prefiltered)
        val times = ArrayDeque(listOf(160L, 170L))
        val denyingGate = RetrievalCandidateAuthorizationGate.create(
            authorizer(165L) { requests ->
                val request = requests.requests.single()
                listOf(
                            RetrievalCandidateAuthorizationDecision.deny(
                                id("candidate-denial"),
                                request,
                                "host-authorization",
                                "policy-8",
                                165L,
                                210L,
                                RetrievalDenialCode.POLICY_DENIED,
                            ),
                )
            },
            LongSupplier { times.removeFirst() },
        )
        val authorized = denyingGate.authorize(
            fixture.authorizationRequest,
            resolvedBatch,
            id("candidate-auth-denied-batch"),
            listOf(id("candidate-auth-denied")),
            authorizerDescriptor(),
        ).completion().toCompletableFuture().join()
        assertTrue(authorized.candidates.isEmpty())

        val futureTimes = ArrayDeque(listOf(160L, 170L))
        val futureGate = RetrievalCandidateAuthorizationGate.create(
            authorizer(180L) { requests ->
                val request = requests.requests.single()
                listOf(
                            RetrievalCandidateAuthorizationDecision.allow(
                                id("future-decision"),
                                request,
                                "host-authorization",
                                "policy-8",
                                180L,
                                210L,
                            ),
                )
            },
            LongSupplier { futureTimes.removeFirst() },
        )
        assertFailsWith<CompletionException> {
            futureGate.authorize(
                fixture.authorizationRequest,
                resolvedBatch,
                id("candidate-auth-future-batch"),
                listOf(id("candidate-auth-future")),
                authorizerDescriptor(),
            ).completion().toCompletableFuture().join()
        }
    }

    @Test
    fun `preflight fails closed for request authority lifetime egress and cancellation mismatches`() {
        val authorization = authorizationRequest()
        val plan = accessPlan(authorization)
        val result = RetrievalPlanResult.allow(plan, 110L)

        assertFailsWith<IllegalArgumentException> {
            RetrievalExecutionGate.prepare(
                id("attempt-other-subject"),
                authorizationRequest(requestId = id("another-query-auth")),
                result,
                requestSpec(),
                descriptor(),
                executionPolicy(),
                120L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalExecutionGate.prepare(
                id("attempt-egress"),
                authorization,
                result,
                requestSpec(),
                descriptor(sendsQueryOffHost = true),
                executionPolicy(queryOffHostAllowed = false),
                120L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalExecutionGate.prepare(
                id("attempt-cancel"),
                authorization,
                result,
                requestSpec(),
                descriptor(supportsCancellation = false),
                executionPolicy(cancellationRequired = true),
                120L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RetrievalPlanResult.allow(plan, 109L).requireValidFor(authorization)
        }
    }

    @Test
    fun `contracts defensively copy data validate content and sanitize failures`() {
        val attributes = linkedMapOf("department" to "研发")
        val authorization = authorizationRequest(attributes = attributes)
        attributes.clear()
        assertEquals(mapOf("department" to "研发"), authorization.subject.attributes)
        assertFailsWith<UnsupportedOperationException> {
            (authorization.subject.attributes as MutableMap<String, String>).clear()
        }

        val documents = mutableListOf(id("document-1"))
        val plan = accessPlan(authorization, documents)
        documents.clear()
        assertEquals(setOf(id("document-1")), plan.authorizedDocumentIds)
        assertFailsWith<UnsupportedOperationException> {
            (plan.authorizedDocumentIds as MutableSet<Identifier>).clear()
        }

        assertFailsWith<IllegalArgumentException> { RetrievalCancellationReason.of("human readable reason") }

        val failure = RetrievalProviderException(
            RetrievalFailureCode.AUTHENTICATION_FAILED,
            RetrievalRetryability.NOT_RETRYABLE,
            "provider-request-7",
        )
        assertFalse(failure.message.orEmpty().contains("provider-request-7"))
        assertFalse(failure.toString().contains("provider-request-7"))
    }

    @Test
    fun `index concurrency failures are stable typed and non-retryable for the same request`() {
        val expected = linkedMapOf(
            RetrievalFailureCode.INDEX_PROJECTION_CONFLICT to "index.projection-conflict",
            RetrievalFailureCode.INDEX_REQUEST_REPLAY_MISMATCH to "index.request-replay-mismatch",
            RetrievalFailureCode.INDEX_PROVIDER_BINDING_MISMATCH to "index.provider-binding-mismatch",
        )

        expected.forEach { (code, identifier) ->
            assertEquals(identifier, code.id)
            assertEquals(code, RetrievalFailureCode.of(identifier))
            val failure = RetrievalProviderException(code, RetrievalRetryability.NOT_RETRYABLE, "provider-index-7")
            assertFalse(failure.message.orEmpty().contains("provider-index-7"))
            assertFalse(failure.toString().contains("provider-index-7"))
            assertFailsWith<IllegalArgumentException> {
                RetrievalProviderException(code, RetrievalRetryability.RETRYABLE)
            }
        }
    }

    @Test
    fun `plain interfaces use JDK8 completion stages and typed idempotent cancellation`() {
        val fixture = fixture()
        val expected = envelope(fixture)
        val retriever = object : CandidateRetriever {
            override fun descriptor(): CandidateRetrieverDescriptor = fixture.descriptor

            override fun start(request: ExecutableRetrievalRequest): RetrievalCall<RetrievalResultEnvelope> =
                object : RetrievalCall<RetrievalResultEnvelope> {
                override fun completion() = CompletableFuture.completedFuture(expected)

                override fun cancel(reason: RetrievalCancellationReason) =
                    CompletableFuture.completedFuture(RetrievalCancellationOutcome.ACCEPTED)
            }
        }

        assertEquals(expected, retriever.start(fixture.executable).completion().toCompletableFuture().join())
        assertEquals(
            RetrievalCancellationOutcome.ACCEPTED,
            retriever.start(fixture.executable)
                .cancel(RetrievalCancellationReason.CALLER_CANCELLED)
                .toCompletableFuture()
                .join(),
        )
        assertTrue(ExecutableRetrievalRequest::class.java.declaredConstructors.none {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic
        })
        assertTrue(AuthorizedRetrievalCandidate::class.java.declaredConstructors.none {
            Modifier.isPublic(it.modifiers) && !it.isSynthetic
        })
    }

    private fun fixture(): Fixture {
        val authorization = authorizationRequest()
        val descriptor = descriptor()
        val plan = accessPlan(authorization)
        val executable = RetrievalExecutionGate.prepare(
            id("attempt-1"),
            authorization,
            RetrievalPlanResult.allow(plan, 110L),
            requestSpec(),
            descriptor,
            executionPolicy(),
            120L,
        ).requireExecutable()
        val evidence = evidence()
        return Fixture(
            authorization,
            descriptor,
            executable,
            evidence,
            candidate(evidence),
        )
    }

    private fun envelope(fixture: Fixture): RetrievalResultEnvelope = RetrievalResultEnvelope.create(
        fixture.executable,
        fixture.descriptor,
        fixture.evidence.indexGeneration,
        130L,
        listOf(fixture.candidate),
        false,
        false,
    )

    private fun resolver(fixture: Fixture): RetrievalLineageResolver = object : RetrievalLineageResolver {
        override fun descriptor(): RetrievalLineageResolverDescriptor = lineageDescriptor()

        override fun resolve(
            request: RetrievalLineageResolutionRequest,
        ): RetrievalCall<RetrievalLineageResolutionBatch> = RetrievalCalls.completed(
            RetrievalLineageResolutionBatch.success(
                request,
                request.source.candidates.map { candidate ->
                    RetrievalLineageResolution.create(
                        candidate,
                        fixture.evidence,
                        "catalog-authority",
                        "lineage-7",
                    )
                },
                150L,
            ),
        )
    }

    private fun resolveBatch(
        fixture: Fixture,
        prefiltered: PrefilteredCandidateBatch,
    ): ResolvedCandidateBatch {
        val times = ArrayDeque(listOf(145L, 150L))
        return RetrievalLineageResolutionGate.create(
            resolver(fixture),
            LongSupplier { times.removeFirst() },
        ).resolve(
            fixture.executable,
            prefiltered,
            id("lineage-resolution-1"),
            lineageDescriptor(),
        ).completion().toCompletableFuture().join()
    }

    private fun lineageDescriptor(): RetrievalLineageResolverDescriptor =
        RetrievalLineageResolverDescriptor.create(
            "host-lineage",
            "lineage-instance",
            "1".repeat(64),
            "2".repeat(64),
            "lineage-revision",
            true,
        )

    private fun authorizerDescriptor(): RetrievalCandidateAuthorizerDescriptor =
        RetrievalCandidateAuthorizerDescriptor.create(
            "host-authorization",
            "authorization-instance",
            "3".repeat(64),
            "4".repeat(64),
            "authorization-revision",
            true,
        )

    private fun authorizer(
        completedAtEpochMilli: Long,
        decisions: (RetrievalCandidateAuthorizationBatch) ->
            Collection<RetrievalCandidateAuthorizationDecision>,
    ): RetrievalCandidateAuthorizer = object : RetrievalCandidateAuthorizer {
        override fun descriptor(): RetrievalCandidateAuthorizerDescriptor = authorizerDescriptor()

        override fun authorize(
            requests: RetrievalCandidateAuthorizationBatch,
        ): RetrievalCall<RetrievalCandidateAuthorizationDecisionBatch> = RetrievalCalls.completed(
            RetrievalCandidateAuthorizationDecisionBatch.success(
                requests,
                decisions(requests),
                completedAtEpochMilli,
            ),
        )
    }

    private fun contentBinding(): RetrievalStageProviderBinding = RetrievalStageProviderBinding.create(
        "content-hydration",
        "content-test",
        "content-instance",
        "5".repeat(64),
        "6".repeat(64),
        "content-revision",
        "7".repeat(64),
        true,
    )

    private fun authorizationRequest(
        requestId: Identifier = id("query-authorization-1"),
        tenantId: Identifier = id("tenant-1"),
        attributes: Map<String, String> = mapOf("department" to "研发"),
    ): RetrievalAuthorizationRequest = RetrievalAuthorizationRequest.create(
        requestId,
        tenantId,
        RetrievalAuthorizationSubject.create(
            RetrievalPrincipal.create(id("user-1"), "USER"),
            attributes,
        ),
        "document:read",
        "agent-answer",
        100L,
    )

    private fun accessPlan(
        authorization: RetrievalAuthorizationRequest,
        documents: Collection<Identifier> = listOf(id("document-1")),
    ): RetrievalAccessPlan = RetrievalAccessPlan.authorizedIds(
        id("query-decision-1"),
        authorization,
        "host-authorization",
        "policy-7",
        110L,
        1_000L,
        documents,
    )

    private fun requestSpec(): RetrievalRequestSpec = RetrievalRequestSpec.create(
        id("request-1"),
        RetrievalMode.FULL_TEXT,
        "天津水务",
        5,
        500L,
    )

    private fun executionPolicy(
        queryOffHostAllowed: Boolean = false,
        cancellationRequired: Boolean = true,
    ): RetrievalExecutionPolicy = RetrievalExecutionPolicy.create(
        queryOffHostAllowed,
        cancellationRequired,
        10,
        1_000L,
    )

    private fun descriptor(
        configurationDigest: String = "c".repeat(64),
        sendsQueryOffHost: Boolean = false,
        supportsCancellation: Boolean = true,
        supportsCursorPagination: Boolean = false,
    ): CandidateRetrieverDescriptor = CandidateRetrieverDescriptor.builder(
        "local-index",
        "provider-1",
        configurationDigest,
        "d".repeat(64),
        "capability-1",
    )
        .tenantConstraint("fw_tenant_id", "tenant-capability-1")
        .supportMode(RetrievalMode.FULL_TEXT)
        .supportAccessProfile(RetrievalAccessProfile.AUTHORIZED_ID_SET)
        .limits(10, 100)
        .queryEgress(sendsQueryOffHost)
        .cancellation(supportsCancellation)
        .cursorPagination(supportsCursorPagination)
        .tenantAndAccessPreselectionGuaranteed(true)
        .build()

    private fun evidence(
        tenantId: Identifier = id("tenant-1"),
        documentId: Identifier = id("document-1"),
        sourceSha256: String = "b".repeat(64),
        indexGeneration: String = "generation-1",
    ): RetrievalEvidenceRef = RetrievalEvidenceRef.document(
        tenantId,
        id("catalog-1"),
        id("projection-1"),
        documentId,
        id("version-1"),
        id("asset-1"),
        id("object-1"),
        sourceSha256,
        indexGeneration,
        RetrievalLineageRevisions.create("projection-v1", "acl-v1", null, null, null),
    )

    private fun candidate(
        evidence: RetrievalEvidenceRef,
        providerScore: Double = 1.0,
        providerRank: Int = 1,
    ): RetrievalCandidate = RetrievalCandidate.create(
        evidence,
        RetrievalMode.FULL_TEXT,
        providerScore,
        providerRank,
    )

    private fun id(value: String): Identifier = Identifier(value)

    private class Fixture(
        val authorizationRequest: RetrievalAuthorizationRequest,
        val descriptor: CandidateRetrieverDescriptor,
        val executable: ExecutableRetrievalRequest,
        val evidence: RetrievalEvidenceRef,
        val candidate: RetrievalCandidate,
    )
}
