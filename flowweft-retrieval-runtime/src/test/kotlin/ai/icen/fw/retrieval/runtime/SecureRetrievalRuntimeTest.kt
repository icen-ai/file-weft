package ai.icen.fw.retrieval.runtime

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.CandidateRetriever
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.RetrievalAccessPlan
import ai.icen.fw.retrieval.api.RetrievalAccessProfile
import ai.icen.fw.retrieval.api.RetrievalAuthorizationPlanner
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest
import ai.icen.fw.retrieval.api.RetrievalAuthorizationSubject
import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalCancellationOutcome
import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalCandidate
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationDecision
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationDecisionBatch
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationBatch
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizer
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizerDescriptor
import ai.icen.fw.retrieval.api.RetrievalDenialCode
import ai.icen.fw.retrieval.api.RetrievalEvidenceRef
import ai.icen.fw.retrieval.api.RetrievalExecutionPolicy
import ai.icen.fw.retrieval.api.RetrievalLineageResolution
import ai.icen.fw.retrieval.api.RetrievalLineageResolutionBatch
import ai.icen.fw.retrieval.api.RetrievalLineageResolutionRequest
import ai.icen.fw.retrieval.api.RetrievalLineageResolver
import ai.icen.fw.retrieval.api.RetrievalLineageResolverDescriptor
import ai.icen.fw.retrieval.api.RetrievalLineageRevisions
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalPlanResult
import ai.icen.fw.retrieval.api.RetrievalPrincipal
import ai.icen.fw.retrieval.api.RetrievalRequestSpec
import ai.icen.fw.retrieval.api.RetrievalResultEnvelope
import ai.icen.fw.retrieval.api.RetrievedContentPayload
import ai.icen.fw.retrieval.spi.FilenameCatalog
import ai.icen.fw.retrieval.spi.FilenameCatalogDescriptor
import ai.icen.fw.retrieval.spi.FilenameCatalogEntry
import ai.icen.fw.retrieval.spi.FilenameCatalogPage
import ai.icen.fw.retrieval.spi.FilenameCatalogScanRequest
import ai.icen.fw.retrieval.spi.RerankRequest
import ai.icen.fw.retrieval.spi.RerankResult
import ai.icen.fw.retrieval.spi.RerankScore
import ai.icen.fw.retrieval.spi.Reranker
import ai.icen.fw.retrieval.spi.RerankerDescriptor
import ai.icen.fw.retrieval.spi.RetrievalContentProvider
import ai.icen.fw.retrieval.spi.RetrievalContentProviderDescriptor
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureRetrievalRuntimeTest {
    @Test
    fun `denied plan invokes no provider and returns no executable evidence`() {
        val clock = MutableClock()
        val scheduler = ManualDeadlineScheduler()
        val candidate = TestCandidateRetriever(clock, listOf(id("document-1")))
        val content = TestContentProvider()
        val reranker = TestReranker(clock)
        val runtime = runtime(
            clock,
            scheduler,
            candidate,
            content,
            reranker,
            planner = RetrievalAuthorizationPlanner { request ->
                RetrievalPlanResult.deny(
                    id("denied-plan"),
                    request,
                    "host-authorization",
                    "policy-1",
                    115L,
                    RetrievalDenialCode.POLICY_DENIED,
                )
            },
        )

        val result = runtime.start(runtimeRequest(rerank = true)).completion().toCompletableFuture().join()

        assertEquals(RetrievalRuntimeStatus.DENIED, result.status)
        assertEquals(RetrievalDenialCode.POLICY_DENIED, result.denialCode)
        assertTrue(result.items.isEmpty())
        assertEquals(0, candidate.descriptorCalls)
        assertEquals(0, candidate.startCalls)
        assertEquals(0, content.descriptorCalls)
        assertEquals(0, content.hydratedDocuments.size)
        assertEquals(0, reranker.descriptorCalls)
        assertEquals(0, reranker.calls)
    }

    @Test
    fun `receipt replay from another attempt fails before lineage or hydration`() {
        val clock = MutableClock()
        val candidate = TestCandidateRetriever(clock, listOf(id("document-1"))).apply {
            reuseFirstEnvelope = true
        }
        val content = TestContentProvider()
        val lineageCalls = AtomicInteger()
        val runtime = runtime(
            clock,
            ManualDeadlineScheduler(),
            candidate,
            content,
            null,
            lineageResolver = TestLineageResolver(clock) { request ->
                lineageCalls.incrementAndGet()
                exactLineage(request)
            },
        )

        assertEquals(
            RetrievalRuntimeStatus.COMPLETED,
            runtime.start(runtimeRequest()).completion().toCompletableFuture().join().status,
        )
        content.hydratedDocuments.clear()
        val failure = failure(runtime.start(runtimeRequest()))

        assertEquals(RetrievalRuntimeFailureCode.INVALID_PROVIDER_RESPONSE, failure.code)
        assertTrue(content.hydratedDocuments.isEmpty())
        assertEquals(1, lineageCalls.get())
    }

    @Test
    fun `authoritative lineage mismatch fails before authorization and hydration`() {
        val clock = MutableClock()
        val content = TestContentProvider()
        val authorizerCalls = AtomicInteger()
        val runtime = runtime(
            clock,
            ManualDeadlineScheduler(),
            TestCandidateRetriever(clock, listOf(id("document-1"))),
            content,
            null,
            lineageResolver = TestLineageResolver(clock) { request ->
                val candidate = request.source.candidates.single()
                val evidence = candidate.evidence
                val altered = RetrievalEvidenceRef.document(
                    evidence.tenantId,
                    evidence.catalogId,
                    evidence.projectionId,
                    evidence.documentId,
                    evidence.versionId,
                    evidence.fileAssetId,
                    evidence.fileObjectId,
                    "f".repeat(64),
                    evidence.indexGeneration,
                    evidence.lineageRevisions,
                )
                RetrievalLineageResolutionBatch.success(
                        request,
                        listOf(
                            RetrievalLineageResolution.create(
                                candidate,
                                altered,
                                "catalog-authority",
                                "lineage-1",
                            ),
                        ),
                        clock.asLong,
                )
            },
            authorizer = TestAuthorizer(clock) {
                authorizerCalls.incrementAndGet()
                throw AssertionError("Authorizer must not receive unresolved candidates")
            },
        )

        val failure = failure(runtime.start(runtimeRequest()))

        assertEquals(RetrievalRuntimeFailureCode.LINEAGE_FAILED, failure.code)
        assertEquals(0, authorizerCalls.get())
        assertTrue(content.hydratedDocuments.isEmpty())
    }

    @Test
    fun `only freshly allowed candidates are hydrated and exposed to reranker`() {
        val clock = MutableClock()
        val documents = listOf(id("document-1"), id("document-2"))
        val content = TestContentProvider()
        val reranker = TestReranker(clock)
        val authorizationRequests = ArrayList<Identifier>()
        val runtime = runtime(
            clock,
            ManualDeadlineScheduler(),
            TestCandidateRetriever(clock, documents),
            content,
            reranker,
            documents = documents,
            authorizer = TestAuthorizer(clock) { batch ->
                authorizationRequests.addAll(batch.requests.map { it.documentId })
                val decisions = batch.requests.mapIndexed { index, request ->
                    if (request.documentId == documents.first()) {
                        RetrievalCandidateAuthorizationDecision.allow(
                            id("candidate-allow-$index"),
                            request,
                            "host-authorization",
                            "policy-current",
                            clock.asLong,
                            400L,
                        )
                    } else {
                        RetrievalCandidateAuthorizationDecision.deny(
                            id("candidate-deny-$index"),
                            request,
                            "host-authorization",
                            "policy-current",
                            clock.asLong,
                            400L,
                            RetrievalDenialCode.POLICY_DENIED,
                        )
                    }
                }
                decisions
            },
        )

        val result = runtime.start(runtimeRequest(candidateLimit = 2, rerank = true))
            .completion().toCompletableFuture().join()

        assertEquals(documents, authorizationRequests)
        assertEquals(listOf(documents.first()), content.hydratedDocuments)
        assertEquals(listOf(documents.first()), reranker.observedDocuments)
        assertEquals(1, result.items.size)
        assertEquals(documents.first(), result.items.single().candidate.resolvedCandidate.candidate.evidence.documentId)
        assertTrue(result.reranked)
    }

    @Test
    fun `missing full text provider falls back to local filename matching and still reauthorizes every hit`() {
        val clock = MutableClock()
        val documents = listOf(id("document-1"), id("document-2"))
        val catalog = RuntimeFilenameCatalog(clock, documents)
        val content = TestContentProvider()
        val executor = Executors.newSingleThreadScheduledExecutor()
        val runtime = try {
            SecureRetrievalRuntime.createWithFilenameFallback(
                allowPlanner(documents),
                null,
                catalog,
                TestLineageResolver(clock),
                TestAuthorizer(clock) { batch ->
                    batch.requests.mapIndexed { index, request ->
                        if (request.documentId == documents.first()) {
                            RetrievalCandidateAuthorizationDecision.allow(
                                id("filename-allow-$index"),
                                request,
                                "host-authorization",
                                "policy-current",
                                clock.asLong,
                                400L,
                            )
                        } else {
                            RetrievalCandidateAuthorizationDecision.deny(
                                id("filename-deny-$index"),
                                request,
                                "host-authorization",
                                "policy-current",
                                clock.asLong,
                                400L,
                                RetrievalDenialCode.POLICY_DENIED,
                            )
                        }
                    }
                },
                content,
                TestDeletionVisibilityProvider(clock),
                clock,
                SequenceIds(),
                executor,
                RetrievalRuntimeConfiguration(10, 1_000, 10_000, 10, 10),
            )
        } catch (failure: RuntimeException) {
            executor.shutdownNow()
            throw failure
        }

        try {
            val result = runtime.start(runtimeRequest(candidateLimit = 2)).completion().toCompletableFuture().join()

            assertEquals(RetrievalRuntimeStatus.COMPLETED, result.status)
            assertEquals(
                SafeFilenameCandidateRetriever.FALLBACK_PROVIDER_TYPE_ID,
                result.securityFilterReceipt?.providerTypeId,
            )
            assertEquals(documents, catalog.lastRequest?.authorizedDocumentIds?.toList())
            assertTrue(FilenameCatalogScanRequest::class.java.methods.none { it.name == "getQuery" })
            assertEquals(listOf(documents.first()), content.hydratedDocuments)
            assertEquals(listOf(documents.first()), result.items.map {
                it.candidate.resolvedCandidate.candidate.evidence.documentId
            })

            val unsupported = RetrievalRuntimeRequest.create(
                authorizationRequest(),
                RetrievalRequestSpec.create(
                    id("vector-request"),
                    RetrievalMode.VECTOR,
                    "天津水务",
                    2,
                    450L,
                ),
                RetrievalExecutionPolicy.create(false, true, 10, 1_000L),
                false,
            )
            assertEquals(RetrievalRuntimeFailureCode.UNSUPPORTED, failure(runtime.start(unsupported)).code)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `verified continuation cursor reaches only the terminal authorized result`() {
        val clock = MutableClock()
        val provider = TestCandidateRetriever(
            clock,
            listOf(id("document-1")),
            nextCursorToken = "runtime_cursor_page_2",
        )
        val result = runtime(
            clock,
            ManualDeadlineScheduler(),
            provider,
            TestContentProvider(),
            null,
        ).start(runtimeRequest()).completion().toCompletableFuture().join()

        val cursor = checkNotNull(result.nextCursor)
        assertEquals("runtime_cursor_page_2", cursor.opaqueToken)
        assertEquals(cursor.digest, result.securityFilterReceipt?.nextCursorDigest)
        assertFalse(cursor.toString().contains(cursor.opaqueToken))
    }

    @Test
    fun `expired candidate authorization fails closed before content access`() {
        val clock = MutableClock()
        val content = TestContentProvider()
        val runtime = runtime(
            clock,
            ManualDeadlineScheduler(),
            TestCandidateRetriever(clock, listOf(id("document-1"))),
            content,
            null,
            authorizer = TestAuthorizer(clock) { batch ->
                val request = batch.requests.single()
                val decision = RetrievalCandidateAuthorizationDecision.allow(
                    id("short-lived-decision"),
                    request,
                    "host-authorization",
                    "policy-current",
                    120L,
                    121L,
                )
                clock.now = 122L
                listOf(decision)
            },
        )

        val failure = failure(runtime.start(runtimeRequest()))

        assertEquals(RetrievalRuntimeFailureCode.AUTHORIZATION_FAILED, failure.code)
        assertTrue(content.hydratedDocuments.isEmpty())
    }

    @Test
    fun `deadline and caller cancellation terminate the call and cancel candidate provider`() {
        val clock = MutableClock()
        val deadlineScheduler = ManualDeadlineScheduler()
        val hanging = TestCandidateRetriever(clock, listOf(id("document-1"))).apply { hang = true }
        val runtime = runtime(clock, deadlineScheduler, hanging, TestContentProvider(), null)

        val deadlineCall = runtime.start(runtimeRequest())
        deadlineScheduler.fireLast()
        assertEquals(RetrievalRuntimeFailureCode.DEADLINE_EXCEEDED, failure(deadlineCall).code)
        assertEquals(listOf(RetrievalCancellationReason.DEADLINE_EXCEEDED), hanging.cancellationReasons)

        val callerScheduler = ManualDeadlineScheduler()
        val callerHanging = TestCandidateRetriever(clock, listOf(id("document-1"))).apply { hang = true }
        val callerRuntime = runtime(clock, callerScheduler, callerHanging, TestContentProvider(), null)
        val callerCall = callerRuntime.start(runtimeRequest())
        assertEquals(
            RetrievalCancellationOutcome.ACCEPTED,
            callerCall.cancel(RetrievalCancellationReason.CALLER_CANCELLED).toCompletableFuture().join(),
        )
        assertEquals(RetrievalRuntimeFailureCode.CANCELLED, failure(callerCall).code)
        assertEquals(listOf(RetrievalCancellationReason.CALLER_CANCELLED), callerHanging.cancellationReasons)
    }

    @Test
    fun `cancellation reaches lineage authorization content and rerank current stages`() {
        val clock = MutableClock()

        val lineage = TestLineageResolver(clock).apply { hang = true }
        val lineageCall = runtime(
            clock,
            ManualDeadlineScheduler(),
            TestCandidateRetriever(clock, listOf(id("document-1"))),
            TestContentProvider(),
            null,
            lineageResolver = lineage,
        ).start(runtimeRequest())
        lineageCall.cancel(RetrievalCancellationReason.CALLER_CANCELLED).toCompletableFuture().join()
        assertEquals(listOf(RetrievalCancellationReason.CALLER_CANCELLED), lineage.cancellationReasons)

        val authorizer = TestAuthorizer(clock).apply { hang = true }
        val authorizationCall = runtime(
            clock,
            ManualDeadlineScheduler(),
            TestCandidateRetriever(clock, listOf(id("document-1"))),
            TestContentProvider(),
            null,
            authorizer = authorizer,
        ).start(runtimeRequest())
        authorizationCall.cancel(RetrievalCancellationReason.CALLER_CANCELLED).toCompletableFuture().join()
        assertEquals(listOf(RetrievalCancellationReason.CALLER_CANCELLED), authorizer.cancellationReasons)

        val contentScheduler = ManualDeadlineScheduler()
        val content = TestContentProvider().apply { hang = true }
        val contentCall = runtime(
            clock,
            contentScheduler,
            TestCandidateRetriever(clock, listOf(id("document-1"))),
            content,
            null,
        ).start(runtimeRequest())
        contentScheduler.fireLast()
        assertEquals(RetrievalRuntimeFailureCode.DEADLINE_EXCEEDED, failure(contentCall).code)
        assertEquals(listOf(RetrievalCancellationReason.DEADLINE_EXCEEDED), content.cancellationReasons)

        val reranker = TestReranker(clock).apply { hang = true }
        val rerankCall = runtime(
            clock,
            ManualDeadlineScheduler(),
            TestCandidateRetriever(clock, listOf(id("document-1"))),
            TestContentProvider(),
            reranker,
        ).start(runtimeRequest(rerank = true))
        rerankCall.cancel(RetrievalCancellationReason.CALLER_CANCELLED).toCompletableFuture().join()
        assertEquals(listOf(RetrievalCancellationReason.CALLER_CANCELLED), reranker.cancellationReasons)
    }

    @Test
    fun `lineage descriptor drift after provider completion fails closed`() {
        val clock = MutableClock()
        val stable = lineageDescriptor()
        val changed = lineageDescriptor(configurationDigest = "9".repeat(64))
        val lineage = TestLineageResolver(clock).apply {
            descriptors = ArrayDeque(listOf(stable, stable, stable, changed))
        }

        val failure = failure(
            runtime(
                clock,
                ManualDeadlineScheduler(),
                TestCandidateRetriever(clock, listOf(id("document-1"))),
                TestContentProvider(),
                null,
                lineageResolver = lineage,
            ).start(runtimeRequest()),
        )

        assertEquals(RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED, failure.code)
    }

    @Test
    fun `authorization content and rerank descriptor drift after execution fails closed`() {
        val clock = MutableClock()

        val authorizerStable = authorizerDescriptor()
        val authorizer = TestAuthorizer(clock).apply {
            descriptors = ArrayDeque(
                listOf(
                    authorizerStable,
                    authorizerStable,
                    authorizerStable,
                    authorizerDescriptor(configurationDigest = "a".repeat(64)),
                ),
            )
        }
        assertEquals(
            RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED,
            failure(
                runtime(
                    clock,
                    ManualDeadlineScheduler(),
                    TestCandidateRetriever(clock, listOf(id("document-1"))),
                    TestContentProvider(),
                    null,
                    authorizer = authorizer,
                ).start(runtimeRequest()),
            ).code,
        )

        val contentStable = contentDescriptor()
        val content = TestContentProvider().apply {
            descriptors = ArrayDeque(
                listOf(
                    contentStable,
                    contentStable,
                    contentStable,
                    contentDescriptor(configurationDigest = "b".repeat(64)),
                ),
            )
        }
        assertEquals(
            RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED,
            failure(
                runtime(
                    clock,
                    ManualDeadlineScheduler(),
                    TestCandidateRetriever(clock, listOf(id("document-1"))),
                    content,
                    null,
                ).start(runtimeRequest()),
            ).code,
        )

        val rerankerStable = rerankerDescriptor()
        val reranker = TestReranker(clock).apply {
            descriptors = ArrayDeque(
                listOf(
                    rerankerStable,
                    rerankerStable,
                    rerankerStable,
                    rerankerDescriptor(configurationDigest = "c".repeat(64)),
                ),
            )
        }
        assertEquals(
            RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED,
            failure(
                runtime(
                    clock,
                    ManualDeadlineScheduler(),
                    TestCandidateRetriever(clock, listOf(id("document-1"))),
                    TestContentProvider(),
                    reranker,
                ).start(runtimeRequest(rerank = true)),
            ).code,
        )
    }

    @Test
    fun `descriptor drift and forbidden content egress stop before provider execution`() {
        val clock = MutableClock()
        val first = candidateDescriptor()
        val changed = candidateDescriptor(configurationDigest = "e".repeat(64))
        val drifting = TestCandidateRetriever(clock, listOf(id("document-1"))).apply {
            descriptors = ArrayDeque(listOf(first, changed))
        }
        val driftFailure = failure(
            runtime(clock, ManualDeadlineScheduler(), drifting, TestContentProvider(), null)
                .start(runtimeRequest()),
        )
        assertEquals(RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED, driftFailure.code)
        assertEquals(0, drifting.startCalls)

        val egressCandidate = TestCandidateRetriever(clock, listOf(id("document-1")))
        val egressContent = TestContentProvider(sendsContentOffHost = true)
        val egressFailure = failure(
            runtime(clock, ManualDeadlineScheduler(), egressCandidate, egressContent, null)
                .start(runtimeRequest()),
        )
        assertEquals(RetrievalRuntimeFailureCode.EGRESS_FORBIDDEN, egressFailure.code)
        assertEquals(0, egressCandidate.startCalls)
        assertTrue(egressContent.hydratedDocuments.isEmpty())
    }

    @Test
    fun `request query policy still blocks an off-host reranker when global egress is enabled`() {
        val clock = MutableClock()
        val reranker = TestReranker(clock, sendsQueryOrContentOffHost = true)
        val configuration = RetrievalRuntimeConfiguration(
            maximumCandidates = 10,
            maximumContentCodePointsPerCandidate = 1_000,
            maximumTotalContentCodePoints = 10_000,
            maximumRerankItems = 10,
            maximumRerankResults = 10,
            rerankEgressAllowed = true,
        )
        val failure = failure(
            runtime(
                clock,
                ManualDeadlineScheduler(),
                TestCandidateRetriever(clock, listOf(id("document-1"))),
                TestContentProvider(),
                reranker,
                configuration = configuration,
            ).start(runtimeRequest(rerank = true)),
        )

        assertEquals(RetrievalRuntimeFailureCode.EGRESS_FORBIDDEN, failure.code)
        assertEquals(0, reranker.calls)
    }

    @Test
    fun `arbitrary provider failures are sanitized without retaining their cause`() {
        val clock = MutableClock()
        val content = TestContentProvider().apply { failureMessage = "secret bearer credential and endpoint" }
        val runtime = runtime(
            clock,
            ManualDeadlineScheduler(),
            TestCandidateRetriever(clock, listOf(id("document-1"))),
            content,
            null,
        )

        val failure = failure(runtime.start(runtimeRequest()))

        assertEquals(RetrievalRuntimeFailureCode.INVALID_PROVIDER_RESPONSE, failure.code)
        assertFalse(failure.message.orEmpty().contains("secret"))
        assertEquals(null, failure.cause)
    }

    @Test
    fun `tombstone observed after hydration removes content before result exposure`() {
        val clock = MutableClock()
        val content = TestContentProvider()
        lateinit var visibility: TestDeletionVisibilityProvider
        visibility = TestDeletionVisibilityProvider(clock) { inspectionOrdinal ->
            if (inspectionOrdinal == 3) visibility.tombstonedResourceIds.add(id("document-1"))
        }
        val runtime = runtime(
            clock,
            ManualDeadlineScheduler(),
            TestCandidateRetriever(clock, listOf(id("document-1"))),
            content,
            null,
            deletionVisibilityProvider = visibility,
        )

        val result = runtime.start(runtimeRequest()).completion().toCompletableFuture().join()

        assertEquals(listOf(id("document-1")), content.hydratedDocuments)
        assertTrue(result.items.isEmpty())
        assertEquals(3, visibility.requests.size)
    }

    private fun runtime(
        clock: MutableClock,
        scheduler: ManualDeadlineScheduler,
        candidate: TestCandidateRetriever,
        content: TestContentProvider,
        reranker: TestReranker?,
        documents: List<Identifier> = listOf(id("document-1")),
        planner: RetrievalAuthorizationPlanner = allowPlanner(documents),
        lineageResolver: RetrievalLineageResolver = TestLineageResolver(clock),
        authorizer: RetrievalCandidateAuthorizer = allowAuthorizer(clock),
        deletionVisibilityProvider: TestDeletionVisibilityProvider = TestDeletionVisibilityProvider(clock),
        configuration: RetrievalRuntimeConfiguration = RetrievalRuntimeConfiguration(
            maximumCandidates = 10,
            maximumContentCodePointsPerCandidate = 1_000,
            maximumTotalContentCodePoints = 10_000,
            maximumRerankItems = 10,
            maximumRerankResults = 10,
        ),
    ): SecureRetrievalRuntime {
        val ids = SequenceIds()
        return SecureRetrievalRuntime.createForTests(
            planner,
            candidate,
            lineageResolver,
            authorizer,
            content,
            deletionVisibilityProvider,
            reranker,
            clock,
            ids,
            scheduler,
            configuration,
        )
    }

    private fun runtimeRequest(candidateLimit: Int = 1, rerank: Boolean = false): RetrievalRuntimeRequest =
        RetrievalRuntimeRequest.create(
            authorizationRequest(),
            RetrievalRequestSpec.create(
                id("retrieval-request"),
                RetrievalMode.FULL_TEXT,
                "天津水务",
                candidateLimit,
                450L,
            ),
            RetrievalExecutionPolicy.create(false, true, 10, 1_000L),
            rerank,
        )

    private fun authorizationRequest(): RetrievalAuthorizationRequest = RetrievalAuthorizationRequest.create(
        id("authorization-request"),
        id("tenant-1"),
        RetrievalAuthorizationSubject.create(
            RetrievalPrincipal.create(id("user-1"), "USER"),
            mapOf("department" to "研发"),
        ),
        "document:read",
        "agent-answer",
        100L,
    )

    private fun allowPlanner(documents: List<Identifier>): RetrievalAuthorizationPlanner =
        RetrievalAuthorizationPlanner { request ->
            val plan = RetrievalAccessPlan.authorizedIds(
                id("query-decision"),
                request,
                "host-authorization",
                "policy-1",
                110L,
                500L,
                documents,
            )
            RetrievalPlanResult.allow(plan, 115L)
        }

    private fun allowAuthorizer(clock: MutableClock): RetrievalCandidateAuthorizer =
        TestAuthorizer(clock) { batch ->
            val decisions = batch.requests.mapIndexed { index, request ->
                RetrievalCandidateAuthorizationDecision.allow(
                    id("candidate-decision-$index"),
                    request,
                    "host-authorization",
                    "policy-current",
                    clock.asLong,
                    400L,
                )
            }
            decisions
        }

    private fun exactLineage(request: RetrievalLineageResolutionRequest): RetrievalLineageResolutionBatch =
        RetrievalLineageResolutionBatch.success(
            request,
            request.source.candidates.map { candidate ->
                RetrievalLineageResolution.create(
                    candidate,
                    candidate.evidence,
                    "catalog-authority",
                    "lineage-1",
                )
            },
            request.requestedAtEpochMilli,
        )

    private fun failure(call: RetrievalRuntimeCall): RetrievalRuntimeException {
        val completion = try {
            call.completion().toCompletableFuture().join()
            throw AssertionError("Expected retrieval runtime failure")
        } catch (failure: CompletionException) {
            failure
        }
        return completion.cause as RetrievalRuntimeException
    }

    private class MutableClock(var now: Long = 120L) : LongSupplier {
        override fun getAsLong(): Long = now
    }

    private class SequenceIds : RetrievalRuntimeIdGenerator {
        private val sequence = AtomicInteger()
        override fun nextId(purpose: RetrievalRuntimeIdPurpose): Identifier =
            id("${purpose.id}-${sequence.incrementAndGet()}")
    }

    private class ManualDeadlineScheduler : RetrievalRuntimeDeadlineScheduler {
        private val tasks = ArrayList<ManualTask>()

        override fun schedule(delayMillis: Long, action: Runnable): RetrievalRuntimeScheduledTask {
            val task = ManualTask(action)
            tasks.add(task)
            return task
        }

        fun fireLast() {
            tasks.last().fire()
        }

        private class ManualTask(private val action: Runnable) : RetrievalRuntimeScheduledTask {
            private var cancelled = false
            override fun cancel() {
                cancelled = true
            }

            fun fire() {
                if (!cancelled) action.run()
            }
        }
    }

    private class TestLineageResolver(
        private val clock: MutableClock,
        private val handler: ((RetrievalLineageResolutionRequest) -> RetrievalLineageResolutionBatch)? = null,
    ) : RetrievalLineageResolver {
        var descriptors = ArrayDeque(listOf(lineageDescriptor()))
        var calls = 0
        var hang = false
        val cancellationReasons = ArrayList<RetrievalCancellationReason>()
        private var lastDescriptor = descriptors.first()

        override fun descriptor(): RetrievalLineageResolverDescriptor {
            if (descriptors.isNotEmpty()) lastDescriptor = descriptors.removeFirst()
            return lastDescriptor
        }

        override fun resolve(request: RetrievalLineageResolutionRequest): RetrievalCall<RetrievalLineageResolutionBatch> {
            calls++
            if (hang) return TestCall(CompletableFuture(), cancellationReasons)
            val result = handler?.invoke(request) ?: RetrievalLineageResolutionBatch.success(
                request,
                request.source.candidates.map { candidate ->
                    RetrievalLineageResolution.create(
                        candidate,
                        candidate.evidence,
                        "catalog-authority",
                        "lineage-1",
                    )
                },
                clock.asLong,
            )
            return TestCall(CompletableFuture.completedFuture(result), cancellationReasons)
        }
    }

    private class TestAuthorizer(
        private val clock: MutableClock,
        private val handler: ((RetrievalCandidateAuthorizationBatch) ->
            List<RetrievalCandidateAuthorizationDecision>)? = null,
    ) : RetrievalCandidateAuthorizer {
        var descriptors = ArrayDeque(listOf(authorizerDescriptor()))
        var calls = 0
        var hang = false
        val cancellationReasons = ArrayList<RetrievalCancellationReason>()
        private var lastDescriptor = descriptors.first()

        override fun descriptor(): RetrievalCandidateAuthorizerDescriptor {
            if (descriptors.isNotEmpty()) lastDescriptor = descriptors.removeFirst()
            return lastDescriptor
        }

        override fun authorize(
            requests: RetrievalCandidateAuthorizationBatch,
        ): RetrievalCall<RetrievalCandidateAuthorizationDecisionBatch> {
            calls++
            if (hang) return TestCall(CompletableFuture(), cancellationReasons)
            val decisions = handler?.invoke(requests) ?: requests.requests.mapIndexed { index, request ->
                RetrievalCandidateAuthorizationDecision.allow(
                    id("candidate-decision-$index"),
                    request,
                    "host-authorization",
                    "policy-current",
                    clock.asLong,
                    400L,
                )
            }
            val result = RetrievalCandidateAuthorizationDecisionBatch.success(
                requests,
                decisions,
                clock.asLong,
            )
            return TestCall(CompletableFuture.completedFuture(result), cancellationReasons)
        }
    }

    private class TestCall<T>(
        private val future: CompletionStage<T>,
        private val cancellationReasons: MutableList<RetrievalCancellationReason>,
    ) : RetrievalCall<T> {
        override fun completion(): CompletionStage<T> = future

        override fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome> {
            cancellationReasons.add(reason)
            return CompletableFuture.completedFuture(RetrievalCancellationOutcome.ACCEPTED)
        }
    }

    private class TestCandidateRetriever(
        private val clock: MutableClock,
        private val documents: List<Identifier>,
        private val nextCursorToken: String? = null,
    ) : CandidateRetriever {
        var descriptors = ArrayDeque(listOf(candidateDescriptor(supportsCursorPagination = nextCursorToken != null)))
        var descriptorCalls = 0
        var startCalls = 0
        var reuseFirstEnvelope = false
        var hang = false
        val cancellationReasons = ArrayList<RetrievalCancellationReason>()
        private var cachedEnvelope: RetrievalResultEnvelope? = null
        private var lastDescriptor = descriptors.first()

        override fun descriptor(): CandidateRetrieverDescriptor {
            descriptorCalls++
            if (descriptors.isNotEmpty()) lastDescriptor = descriptors.removeFirst()
            return lastDescriptor
        }

        override fun start(request: ExecutableRetrievalRequest): RetrievalCall<RetrievalResultEnvelope> {
            startCalls++
            val completion = if (hang) {
                CompletableFuture<RetrievalResultEnvelope>()
            } else {
                val envelope = if (reuseFirstEnvelope && cachedEnvelope != null) {
                    checkNotNull(cachedEnvelope)
                } else {
                    createEnvelope(request).also { if (cachedEnvelope == null) cachedEnvelope = it }
                }
                CompletableFuture.completedFuture(envelope)
            }
            return object : RetrievalCall<RetrievalResultEnvelope> {
                override fun completion(): CompletionStage<RetrievalResultEnvelope> = completion

                override fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome> {
                    cancellationReasons.add(reason)
                    return CompletableFuture.completedFuture(RetrievalCancellationOutcome.ACCEPTED)
                }
            }
        }

        private fun createEnvelope(request: ExecutableRetrievalRequest): RetrievalResultEnvelope {
            val candidates = documents.mapIndexed { index, documentId ->
                val evidence = evidence(request.tenantId, documentId, index)
                RetrievalCandidate.create(evidence, request.mode, 1.0 - index * 0.1, index + 1)
            }
            return RetrievalResultEnvelope.create(
                request,
                lastDescriptor,
                "generation-1",
                clock.asLong,
                candidates,
                nextCursorToken,
                false,
                false,
            )
        }
    }

    private class RuntimeFilenameCatalog(
        private val clock: MutableClock,
        private val documents: List<Identifier>,
    ) : FilenameCatalog {
        private val value = FilenameCatalogDescriptor.create(
            "host.filename-catalog",
            "runtime-filename-catalog",
            "9".repeat(64),
            "a".repeat(64),
            "catalog-v1",
            10,
            100,
            false,
            true,
            true,
        )
        var lastRequest: FilenameCatalogScanRequest? = null

        override fun descriptor(): FilenameCatalogDescriptor = value

        override fun scan(request: FilenameCatalogScanRequest): RetrievalCall<FilenameCatalogPage> {
            lastRequest = request
            val entries = documents.mapIndexed { index, document ->
                FilenameCatalogEntry.create(
                    if (index == 0) "天津水务规划.pdf" else "天津水务预算.pdf",
                    "document-${index + 1}",
                    evidence(request.tenantId, document, index),
                )
            }
            return TestCall(
                CompletableFuture.completedFuture(
                    FilenameCatalogPage.success(
                        request,
                        value,
                        "generation-1",
                        entries,
                        null,
                        clock.asLong,
                    ),
                ),
                ArrayList(),
            )
        }
    }

    private class TestContentProvider(
        sendsContentOffHost: Boolean = false,
    ) : RetrievalContentProvider {
        private val value = contentDescriptor(sendsContentOffHost = sendsContentOffHost)
        var descriptors = ArrayDeque(listOf(value))
        var descriptorCalls = 0
        var failureMessage: String? = null
        var hang = false
        val cancellationReasons = ArrayList<RetrievalCancellationReason>()
        val hydratedDocuments = ArrayList<Identifier>()
        private var lastDescriptor = descriptors.first()

        override fun descriptor(): RetrievalContentProviderDescriptor {
            descriptorCalls++
            if (descriptors.isNotEmpty()) lastDescriptor = descriptors.removeFirst()
            return lastDescriptor
        }

        override fun hydrate(request: ai.icen.fw.retrieval.api.RetrievalHydrationRequest):
            RetrievalCall<RetrievedContentPayload> {
            val evidence = request.candidate.resolvedCandidate.candidate.evidence
            hydratedDocuments.add(evidence.documentId)
            if (hang) return TestCall(CompletableFuture(), cancellationReasons)
            val configuredFailure = failureMessage
            if (configuredFailure != null) {
                return TestCall(CompletableFuture<RetrievedContentPayload>().also {
                    it.completeExceptionally(IllegalStateException(configuredFailure))
                }, cancellationReasons)
            }
            return TestCall(
                CompletableFuture.completedFuture(
                RetrievedContentPayload.success(
                    request,
                    "content-${evidence.documentId.value}",
                    "text/plain",
                    evidence.sourceSha256,
                ),
                ),
                cancellationReasons,
            )
        }
    }

    private class TestReranker(
        private val clock: MutableClock,
        sendsQueryOrContentOffHost: Boolean = false,
    ) : Reranker {
        private val value = rerankerDescriptor(sendsQueryOrContentOffHost = sendsQueryOrContentOffHost)
        var descriptors = ArrayDeque(listOf(value))
        var descriptorCalls = 0
        var calls = 0
        var hang = false
        val cancellationReasons = ArrayList<RetrievalCancellationReason>()
        val observedDocuments = ArrayList<Identifier>()
        private var lastDescriptor = descriptors.first()

        override fun descriptor(): RerankerDescriptor {
            descriptorCalls++
            if (descriptors.isNotEmpty()) lastDescriptor = descriptors.removeFirst()
            return lastDescriptor
        }

        override fun rerank(request: RerankRequest): RetrievalCall<RerankResult> {
            calls++
            observedDocuments.addAll(
                request.items.map { it.candidate.resolvedCandidate.candidate.evidence.documentId },
            )
            if (hang) return TestCall(CompletableFuture(), cancellationReasons)
            val scores = request.items.mapIndexed { index, item ->
                RerankScore.of(item, 10.0 - index, "a".repeat(64))
            }.take(request.maximumResults)
            return TestCall(
                CompletableFuture.completedFuture(
                    RerankResult.success(request, scores, "rerank-provider-request", clock.asLong),
                ),
                cancellationReasons,
            )
        }
    }

    private companion object {
        fun contentDescriptor(
            configurationDigest: String = "1".repeat(64),
            sendsContentOffHost: Boolean = false,
        ): RetrievalContentProviderDescriptor = RetrievalContentProviderDescriptor.of(
            "content-test",
            "content-instance",
            configurationDigest,
            "2".repeat(64),
            "content-revision",
            10_000,
            sendsContentOffHost,
            true,
        )

        fun rerankerDescriptor(
            configurationDigest: String = "3".repeat(64),
            sendsQueryOrContentOffHost: Boolean = false,
        ): RerankerDescriptor =
            RerankerDescriptor.of(
                "reranker-test",
                "reranker-instance",
                configurationDigest,
                "4".repeat(64),
                "reranker-revision",
                "reranker-model",
                "model-1",
                10,
                10_000,
                sendsQueryOrContentOffHost,
                true,
            )

        fun lineageDescriptor(configurationDigest: String = "5".repeat(64)):
            RetrievalLineageResolverDescriptor = RetrievalLineageResolverDescriptor.create(
            "host-lineage",
            "lineage-instance",
            configurationDigest,
            "6".repeat(64),
            "lineage-revision",
            true,
        )

        fun authorizerDescriptor(configurationDigest: String = "7".repeat(64)):
            RetrievalCandidateAuthorizerDescriptor = RetrievalCandidateAuthorizerDescriptor.create(
            "host-authorization",
            "authorization-instance",
            configurationDigest,
            "8".repeat(64),
            "authorization-revision",
            true,
        )

        fun candidateDescriptor(
            configurationDigest: String = "c".repeat(64),
            supportsCursorPagination: Boolean = false,
        ): CandidateRetrieverDescriptor =
            CandidateRetrieverDescriptor.builder(
                "local-index",
                "candidate-instance",
                configurationDigest,
                "d".repeat(64),
                "candidate-revision",
            )
                .tenantConstraint("tenant_id", "tenant-capability-1")
                .supportMode(RetrievalMode.FULL_TEXT)
                .supportAccessProfile(RetrievalAccessProfile.AUTHORIZED_ID_SET)
                .limits(10, 100)
                .queryEgress(false)
                .cancellation(true)
                .cursorPagination(supportsCursorPagination)
                .tenantAndAccessPreselectionGuaranteed(true)
                .build()

        fun evidence(tenantId: Identifier, documentId: Identifier, ordinal: Int): RetrievalEvidenceRef =
            RetrievalEvidenceRef.document(
                tenantId,
                id("catalog-1"),
                id("projection-${ordinal + 1}"),
                documentId,
                id("version-${ordinal + 1}"),
                id("asset-${ordinal + 1}"),
                id("object-${ordinal + 1}"),
                (if (ordinal == 0) "b" else "c").repeat(64),
                "generation-1",
                RetrievalLineageRevisions.create("projection-1", "acl-1", null, null, null),
            )

        fun id(value: String): Identifier = Identifier(value)
    }
}
