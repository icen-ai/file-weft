package ai.icen.fw.retrieval.runtime

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.AuthorizedCandidateBatch
import ai.icen.fw.retrieval.api.AuthorizedRetrievalCandidate
import ai.icen.fw.retrieval.api.CandidateRetriever
import ai.icen.fw.retrieval.api.CandidateRetrieverDescriptor
import ai.icen.fw.retrieval.api.ExecutableRetrievalRequest
import ai.icen.fw.retrieval.api.PrefilteredCandidateBatch
import ai.icen.fw.retrieval.api.ResolvedCandidateBatch
import ai.icen.fw.retrieval.api.RetrievalAuthorizationPlanner
import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalCancellationOutcome
import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizationGate
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizer
import ai.icen.fw.retrieval.api.RetrievalCandidateAuthorizerDescriptor
import ai.icen.fw.retrieval.api.RetrievalContentEgressDecision
import ai.icen.fw.retrieval.api.RetrievalContentHydrationGate
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityDescriptor
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityProvider
import ai.icen.fw.retrieval.api.RetrievalDeletionResourceRef
import ai.icen.fw.retrieval.api.RetrievalExecutionGate
import ai.icen.fw.retrieval.api.RetrievalHydrationRequest
import ai.icen.fw.retrieval.api.RetrievalLineageResolutionGate
import ai.icen.fw.retrieval.api.RetrievalLineageResolver
import ai.icen.fw.retrieval.api.RetrievalLineageResolverDescriptor
import ai.icen.fw.retrieval.api.RetrievalProviderException
import ai.icen.fw.retrieval.api.RetrievalResultEnvelope
import ai.icen.fw.retrieval.api.RetrievalRetryability
import ai.icen.fw.retrieval.api.RetrievedContent
import ai.icen.fw.retrieval.spi.FilenameCatalog
import ai.icen.fw.retrieval.spi.RerankItem
import ai.icen.fw.retrieval.spi.RerankRequest
import ai.icen.fw.retrieval.spi.RerankResult
import ai.icen.fw.retrieval.spi.Reranker
import ai.icen.fw.retrieval.spi.RerankerDescriptor
import ai.icen.fw.retrieval.spi.RetrievalContentProvider
import ai.icen.fw.retrieval.spi.RetrievalContentProviderDescriptor
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.function.LongSupplier

/**
 * Production security orchestrator for built-in and provider-backed retrieval.
 *
 * Provider candidates remain hidden until the exact prefilter receipt is verified. Every candidate
 * then crosses authoritative lineage resolution and a fresh resource authorization decision before
 * a content provider or reranker can observe it.
 */
class SecureRetrievalRuntime private constructor(
    private val authorizationPlanner: RetrievalAuthorizationPlanner,
    private val candidateRetriever: CandidateRetriever,
    private val lineageResolver: RetrievalLineageResolver,
    private val candidateAuthorizer: RetrievalCandidateAuthorizer,
    private val contentProvider: RetrievalContentProvider,
    deletionVisibilityProvider: RetrievalDeletionVisibilityProvider?,
    private val reranker: Reranker?,
    private val clock: LongSupplier,
    private val ids: RetrievalRuntimeIdGenerator,
    private val deadlineScheduler: RetrievalRuntimeDeadlineScheduler,
    private val configuration: RetrievalRuntimeConfiguration,
) {
    private val deletionVisibility: DeletionVisibilityRuntimeGuard? =
        deletionVisibilityProvider?.let { provider -> DeletionVisibilityRuntimeGuard.create(provider, clock, ids) }

    fun start(request: RetrievalRuntimeRequest): RetrievalRuntimeCall {
        val startedAt = clock.asLong
        val call = RuntimeCall(startedAt)
        if (startedAt < 0L) {
            call.fail(RetrievalRuntimeException.create(RetrievalRuntimeFailureCode.INTERNAL_FAILURE))
            return call
        }
        if (startedAt >= request.requestSpec.deadlineEpochMilli) {
            call.expire()
            return call
        }
        try {
            val delay = request.requestSpec.deadlineEpochMilli - startedAt
            call.installDeadline(deadlineScheduler.schedule(delay, Runnable(call::expire)))
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.INTERNAL, failure)
            return call
        }
        execute(call, request)
        return call
    }

    private fun execute(call: RuntimeCall, request: RetrievalRuntimeRequest) {
        if (!call.isActive()) return
        val planResult = try {
            authorizationPlanner.plan(request.authorizationRequest).also {
                it.requireValidFor(request.authorizationRequest)
            }
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.AUTHORIZATION, failure)
            return
        }
        if (!call.isActive()) return
        if (!planResult.allowed) {
            val completedAt = clock.asLong
            if (completedAt < call.startedAtEpochMilli || completedAt >= request.requestSpec.deadlineEpochMilli) {
                call.expire()
                return
            }
            call.complete(
                RetrievalRuntimeResult.denied(
                    request,
                    checkNotNull(planResult.denialCode),
                    call.startedAtEpochMilli,
                    completedAt,
                ),
            )
            return
        }

        try {
            planResult.requireAllowed().requireValidFor(request.authorizationRequest, clock.asLong)
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.AUTHORIZATION, failure)
            return
        }
        if (!call.isActive()) return

        val snapshots = try {
            preflightProviderSnapshots(request)
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.PROVIDER_DESCRIPTOR, failure)
            return
        }
        val executable = try {
            val now = clock.asLong
            val attemptId = call.nextId(RetrievalRuntimeIdPurpose.ATTEMPT)
            RetrievalExecutionGate.prepare(
                attemptId,
                request.authorizationRequest,
                planResult,
                request.requestSpec,
                snapshots.candidate,
                request.executionPolicy,
                now,
            ).requireExecutable()
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.PREFLIGHT, failure)
            return
        }

        val providerCall = try {
            call.ensureActive()
            requireCandidateDescriptor(snapshots.candidate)
            requireLineageDescriptor(snapshots.lineage)
            requireAuthorizerDescriptor(snapshots.authorizer)
            requireContentDescriptor(snapshots.content)
            snapshots.reranker?.let(::requireRerankerDescriptor)
            requireNotNull(candidateRetriever.start(executable)) {
                "Candidate provider returned no call handle."
            }
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.CANDIDATE_PROVIDER, failure)
            return
        }
        if (!call.attachProvider(providerCall)) return
        val completion = try {
            requireNotNull(providerCall.completion()) { "Candidate provider returned no completion stage." }
        } catch (failure: RuntimeException) {
            call.detachProvider(providerCall)
            fail(call, RuntimeStage.CANDIDATE_PROVIDER, failure)
            return
        }
        completion.whenComplete { envelope, failure ->
            call.detachProvider(providerCall)
            if (failure != null) {
                fail(call, RuntimeStage.CANDIDATE_PROVIDER, failure)
            } else if (call.isActive()) {
                val exactEnvelope = try {
                    requireNotNull(envelope) { "Candidate provider completed without an envelope." }
                } catch (problem: RuntimeException) {
                    fail(call, RuntimeStage.CANDIDATE_RESPONSE, problem)
                    return@whenComplete
                }
                verifyEnvelope(call, RetrievalExecutionContext(request, executable, snapshots, exactEnvelope))
            }
        }
    }

    private fun preflightProviderSnapshots(request: RetrievalRuntimeRequest): ProviderSnapshots {
        if (request.requestSpec.candidateLimit > configuration.maximumCandidates) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.POLICY_REJECTED)
        }
        if (request.rerankRequested && request.requestSpec.candidateLimit > configuration.maximumRerankItems) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.POLICY_REJECTED)
        }
        val candidate = requireNotNull(candidateRetriever.descriptor()) {
            "Candidate provider returned no descriptor."
        }
        if (request.requestSpec.mode !in candidate.supportedModes) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.UNSUPPORTED)
        }
        val lineage = requireNotNull(lineageResolver.descriptor()) {
            "Lineage resolver returned no descriptor."
        }
        val authorizer = requireNotNull(candidateAuthorizer.descriptor()) {
            "Candidate authorizer returned no descriptor."
        }
        val content = requireNotNull(contentProvider.descriptor()) {
            "Content provider returned no descriptor."
        }
        val deletion = deletionVisibility ?: throw RuntimeAbort(
            RetrievalRuntimeFailureCode.DELETION_VISIBILITY_UNAVAILABLE,
        )
        deletion.requireSameDescriptor()
        if (request.executionPolicy.cancellationRequired &&
            (!candidate.supportsCancellation || !lineage.supportsCancellation ||
                !authorizer.supportsCancellation || !content.supportsCancellation ||
                !deletion.descriptor.supportsCancellation)
        ) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.UNSUPPORTED)
        }
        if (content.sendsContentOffHost && !configuration.contentEgressAllowed) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.EGRESS_FORBIDDEN)
        }
        val rerankerDescriptor = if (request.rerankRequested) {
            val configuredReranker = reranker ?: throw RuntimeAbort(RetrievalRuntimeFailureCode.UNSUPPORTED)
            requireNotNull(configuredReranker.descriptor()) { "Reranker returned no descriptor." }.also {
                if (request.executionPolicy.cancellationRequired && !it.supportsCancellation) {
                    throw RuntimeAbort(RetrievalRuntimeFailureCode.UNSUPPORTED)
                }
                if (it.sendsQueryOrContentOffHost &&
                    (!configuration.rerankEgressAllowed || !request.executionPolicy.queryOffHostAllowed)
                ) {
                    throw RuntimeAbort(RetrievalRuntimeFailureCode.EGRESS_FORBIDDEN)
                }
            }
        } else {
            null
        }
        return ProviderSnapshots(candidate, lineage, authorizer, content, deletion.descriptor, rerankerDescriptor)
    }

    private fun verifyEnvelope(call: RuntimeCall, context: RetrievalExecutionContext) {
        val prefiltered = try {
            call.ensureActive()
            requireCandidateDescriptor(context.snapshots.candidate)
            context.envelope.verifyFor(context.executable, context.snapshots.candidate, clock.asLong)
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.CANDIDATE_RESPONSE, failure)
            return
        }
        val resolutionCall = try {
            requireLineageDescriptor(context.snapshots.lineage)
            RetrievalLineageResolutionGate.create(lineageResolver, clock)
                .resolve(
                    context.executable,
                    prefiltered,
                    call.nextId(RetrievalRuntimeIdPurpose.LINEAGE),
                    context.snapshots.lineage,
                )
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.LINEAGE, failure)
            return
        }
        if (!call.attachProvider(resolutionCall)) return
        val completion = try {
            requireNotNull(resolutionCall.completion()) { "Lineage resolver returned no completion stage." }
        } catch (failure: RuntimeException) {
            call.detachProvider(resolutionCall)
            fail(call, RuntimeStage.LINEAGE, failure)
            return
        }
        completion.whenComplete { resolved, failure ->
            call.detachProvider(resolutionCall)
            if (failure != null) {
                fail(call, RuntimeStage.LINEAGE, failure)
            } else if (call.isActive()) {
                val exactResolved = try {
                    requireLineageDescriptor(context.snapshots.lineage)
                    requireNotNull(resolved) { "Lineage resolver completed without a result." }
                } catch (problem: RuntimeException) {
                    fail(call, RuntimeStage.LINEAGE, problem)
                    return@whenComplete
                }
                authorizeCandidates(call, context.withPrefiltered(prefiltered), exactResolved)
            }
        }
    }

    private fun authorizeCandidates(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        resolved: ResolvedCandidateBatch,
    ) {
        val authorizationCall = try {
            call.ensureActive()
            requireAuthorizerDescriptor(context.snapshots.authorizer)
            val requestIds = resolved.candidates.map {
                call.nextId(RetrievalRuntimeIdPurpose.CANDIDATE_AUTHORIZATION)
            }
            RetrievalCandidateAuthorizationGate.create(candidateAuthorizer, clock)
                .authorize(
                    context.request.authorizationRequest,
                    resolved,
                    call.nextId(RetrievalRuntimeIdPurpose.CANDIDATE_AUTHORIZATION_BATCH),
                    requestIds,
                    context.snapshots.authorizer,
                )
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.CANDIDATE_AUTHORIZATION, failure)
            return
        }
        if (!call.attachProvider(authorizationCall)) return
        val completion = try {
            requireNotNull(authorizationCall.completion()) { "Candidate authorizer returned no completion stage." }
        } catch (failure: RuntimeException) {
            call.detachProvider(authorizationCall)
            fail(call, RuntimeStage.CANDIDATE_AUTHORIZATION, failure)
            return
        }
        completion.whenComplete { authorized, failure ->
            call.detachProvider(authorizationCall)
            if (failure != null) {
                fail(call, RuntimeStage.CANDIDATE_AUTHORIZATION, failure)
            } else if (call.isActive()) {
                val exactAuthorized = try {
                    requireAuthorizerDescriptor(context.snapshots.authorizer)
                    requireNotNull(authorized) { "Candidate authorizer completed without a result." }
                } catch (problem: RuntimeException) {
                    fail(call, RuntimeStage.CANDIDATE_AUTHORIZATION, problem)
                    return@whenComplete
                }
                filterDeletedCandidates(call, context.withResolved(resolved), exactAuthorized)
            }
        }
    }

    /** Authorization is necessary but not sufficient: an authoritative tombstone always wins. */
    private fun filterDeletedCandidates(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        authorized: AuthorizedCandidateBatch,
    ) {
        val candidates = authorized.candidates
        inspectVisibility(call, context, candidates.map(::deletionResource)) { inspection ->
            val visible = candidates.filter { candidate -> inspection.isVisible(deletionResource(candidate)) }
            hydrateAuthorized(call, context, visible)
        }
    }

    private fun inspectVisibility(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        resources: Collection<RetrievalDeletionResourceRef>,
        onSuccess: (DeletionVisibilityInspection) -> Unit,
    ) {
        val visibilityCall = try {
            call.ensureActive()
            val guard = deletionVisibility ?: throw RuntimeAbort(
                RetrievalRuntimeFailureCode.DELETION_VISIBILITY_UNAVAILABLE,
            )
            require(guard.descriptor.digest == context.snapshots.deletionVisibility.digest) {
                "Deletion visibility descriptor changed during retrieval."
            }
            guard.requireSameDescriptor()
            guard.inspect(resources, context.executable.deadlineEpochMilli)
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.DELETION_VISIBILITY, failure)
            return
        }
        if (!call.attachProvider(visibilityCall)) return
        val completion = try {
            requireNotNull(visibilityCall.completion()) {
                "Deletion visibility provider returned no completion stage."
            }
        } catch (failure: RuntimeException) {
            call.detachProvider(visibilityCall)
            fail(call, RuntimeStage.DELETION_VISIBILITY, failure)
            return
        }
        completion.whenComplete { inspection, failure ->
            call.detachProvider(visibilityCall)
            if (failure != null) {
                fail(call, RuntimeStage.DELETION_VISIBILITY, failure)
            } else if (call.isActive()) {
                try {
                    val guard = deletionVisibility ?: throw RuntimeAbort(
                        RetrievalRuntimeFailureCode.DELETION_VISIBILITY_UNAVAILABLE,
                    )
                    require(guard.descriptor.digest == context.snapshots.deletionVisibility.digest) {
                        "Deletion visibility descriptor changed during retrieval."
                    }
                    guard.requireSameDescriptor()
                    onSuccess(requireNotNull(inspection) {
                        "Deletion visibility provider completed without an inspection."
                    })
                } catch (problem: RuntimeException) {
                    fail(call, RuntimeStage.DELETION_VISIBILITY, problem)
                }
            }
        }
    }

    private fun hydrateAuthorized(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        candidates: List<AuthorizedRetrievalCandidate>,
    ) {
        if (candidates.size > configuration.maximumCandidates) {
            fail(call, RuntimeStage.CANDIDATE_AUTHORIZATION, RuntimeAbort(RetrievalRuntimeFailureCode.POLICY_REJECTED))
            return
        }
        val rerankerDescriptor = context.snapshots.reranker
        if (context.request.rerankRequested &&
            (rerankerDescriptor == null || candidates.size > rerankerDescriptor.maximumItems)
        ) {
            fail(call, RuntimeStage.PREFLIGHT, RuntimeAbort(RetrievalRuntimeFailureCode.UNSUPPORTED))
            return
        }
        if (candidates.isEmpty()) {
            finish(call, context, emptyList(), false)
            return
        }
        val perItemLimit = minOf(
            configuration.maximumContentCodePointsPerCandidate,
            context.snapshots.content.maximumContentCodePoints,
            rerankerDescriptor?.maximumContentCodePointsPerItem ?: Int.MAX_VALUE,
        )
        var chain: CompletionStage<HydrationAccumulator> =
            CompletableFuture.completedFuture(HydrationAccumulator(candidates.size))
        candidates.forEach { candidate ->
            chain = chain.thenCompose { accumulator ->
                hydrateOne(call, context, candidate, perItemLimit, accumulator)
            }
        }
        chain.whenComplete { accumulator, failure ->
            if (failure != null) {
                fail(call, RuntimeStage.HYDRATION, failure)
            } else if (call.isActive()) {
                val items = requireNotNull(accumulator).items
                if (context.request.rerankRequested && items.isNotEmpty()) {
                    rerank(call, context, items)
                } else {
                    finish(call, context, items, false)
                }
            }
        }
    }

    private fun hydrateOne(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        candidate: AuthorizedRetrievalCandidate,
        perItemLimit: Int,
        accumulator: HydrationAccumulator,
    ): CompletionStage<HydrationAccumulator> {
        val resource = deletionResource(candidate)
        return visibilityStage(call, context, listOf(resource)).thenCompose { beforeHydration ->
            if (!beforeHydration.isVisible(resource)) {
                CompletableFuture.completedFuture(accumulator)
            } else {
                hydrateVisibleCandidate(call, context, candidate, resource, perItemLimit, accumulator)
            }
        }
    }

    private fun hydrateVisibleCandidate(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        candidate: AuthorizedRetrievalCandidate,
        resource: RetrievalDeletionResourceRef,
        perItemLimit: Int,
        accumulator: HydrationAccumulator,
    ): CompletionStage<HydrationAccumulator> {
        call.ensureActive()
        requireContentDescriptor(context.snapshots.content)
        val now = clock.asLong
        candidate.requireUsableAt(now)
        val deadline = minOf(
            context.executable.deadlineEpochMilli,
            context.executable.accessPlanExpiresAtEpochMilli,
            candidate.expiresAtEpochMilli,
        )
        if (now >= deadline) throw RuntimeAbort(RetrievalRuntimeFailureCode.AUTHORIZATION_FAILED)
        val egressDecision = RetrievalContentEgressDecision.create(
            call.nextId(RetrievalRuntimeIdPurpose.CONTENT_EGRESS_DECISION),
            candidate,
            context.snapshots.content.binding,
            configuration.digest,
            "flowweft-runtime",
            "retrieval-runtime-configuration-v1",
            context.snapshots.content.sendsContentOffHost,
            configuration.contentEgressAllowed,
            now,
            deadline,
        )
        val request = RetrievalHydrationRequest.create(
            call.nextId(RetrievalRuntimeIdPurpose.HYDRATION),
            candidate,
            context.snapshots.content.binding,
            egressDecision,
            perItemLimit,
            now,
            deadline,
        )
        val hydrationCall = RetrievalContentHydrationGate.create(contentProvider, clock).hydrate(request)
        if (!call.attachProvider(hydrationCall)) throw RuntimeAbort(RetrievalRuntimeFailureCode.CANCELLED)
        val completion = try {
            requireNotNull(hydrationCall.completion()) { "Content provider returned no completion stage." }
        } catch (failure: RuntimeException) {
            call.detachProvider(hydrationCall)
            throw failure
        }
        return completion.whenComplete { _, _ -> call.detachProvider(hydrationCall) }.thenCompose { content ->
            call.ensureActive()
            requireContentDescriptor(context.snapshots.content)
            visibilityStage(call, context, listOf(resource)).thenApply { afterHydration ->
                if (!afterHydration.isVisible(resource)) return@thenApply accumulator
                val itemCodePoints = content.text.codePointCount(0, content.text.length)
                val total = Math.addExact(accumulator.consumedCodePoints, itemCodePoints)
                if (total > configuration.maximumTotalContentCodePoints) {
                    throw RuntimeAbort(RetrievalRuntimeFailureCode.CONTENT_LIMIT_EXCEEDED)
                }
                accumulator.items.add(RetrievalRuntimeItem.hydrated(candidate, content))
                accumulator.consumedCodePoints = total
                accumulator
            }
        }
    }

    private fun visibilityStage(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        resources: Collection<RetrievalDeletionResourceRef>,
    ): CompletionStage<DeletionVisibilityInspection> {
        call.ensureActive()
        val guard = deletionVisibility ?: throw RuntimeAbort(
            RetrievalRuntimeFailureCode.DELETION_VISIBILITY_UNAVAILABLE,
        )
        require(guard.descriptor.digest == context.snapshots.deletionVisibility.digest) {
            "Deletion visibility descriptor changed during retrieval."
        }
        guard.requireSameDescriptor()
        val visibilityCall = guard.inspect(resources, context.executable.deadlineEpochMilli)
        if (!call.attachProvider(visibilityCall)) throw RuntimeAbort(RetrievalRuntimeFailureCode.CANCELLED)
        val completion = try {
            requireNotNull(visibilityCall.completion()) {
                "Deletion visibility provider returned no completion stage."
            }
        } catch (failure: RuntimeException) {
            call.detachProvider(visibilityCall)
            throw failure
        }
        return completion.whenComplete { _, _ -> call.detachProvider(visibilityCall) }.thenApply { inspection ->
            call.ensureActive()
            guard.requireSameDescriptor()
            requireNotNull(inspection) { "Deletion visibility provider completed without an inspection." }
        }
    }

    private fun rerank(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        hydrated: List<RetrievalRuntimeItem>,
    ) {
        inspectVisibility(call, context, hydrated.map { item -> deletionResource(item.candidate) }) { inspection ->
            val visible = hydrated.filter { item -> inspection.isVisible(deletionResource(item.candidate)) }
            if (visible.isEmpty()) {
                finish(call, context, emptyList(), false)
            } else {
                rerankVisible(call, context, visible)
            }
        }
    }

    private fun rerankVisible(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        hydrated: List<RetrievalRuntimeItem>,
    ) {
        val configuredReranker = reranker
        val descriptor = context.snapshots.reranker
        if (configuredReranker == null || descriptor == null) {
            fail(call, RuntimeStage.RERANK, RuntimeAbort(RetrievalRuntimeFailureCode.UNSUPPORTED))
            return
        }
        val request = try {
            call.ensureActive()
            requireRerankerDescriptor(descriptor)
            if (hydrated.isEmpty() || hydrated.size > configuration.maximumRerankItems ||
                hydrated.size > descriptor.maximumItems
            ) {
                throw RuntimeAbort(RetrievalRuntimeFailureCode.POLICY_REJECTED)
            }
            val requestedAt = clock.asLong
            hydrated.forEach { it.candidate.requireUsableAt(requestedAt) }
            val deadline = hydrated.fold(
                minOf(context.executable.deadlineEpochMilli, context.executable.accessPlanExpiresAtEpochMilli),
            ) { current, item -> minOf(current, item.candidate.expiresAtEpochMilli) }
            if (requestedAt >= deadline) throw RuntimeAbort(RetrievalRuntimeFailureCode.AUTHORIZATION_FAILED)
            RerankRequest.of(
                call.nextId(RetrievalRuntimeIdPurpose.RERANK),
                descriptor,
                context.request.requestSpec.query,
                hydrated.map { RerankItem.of(it.candidate, it.content) },
                minOf(configuration.maximumRerankResults, hydrated.size),
                requestedAt,
                deadline,
                configuration.rerankEgressAllowed && context.request.executionPolicy.queryOffHostAllowed,
            )
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.RERANK, failure)
            return
        }
        val rerankCall = try {
            requireNotNull(configuredReranker.rerank(request)) { "Reranker returned no call handle." }
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.RERANK, failure)
            return
        }
        if (!call.attachProvider(rerankCall)) return
        val completion = try {
            requireNotNull(rerankCall.completion()) { "Reranker returned no completion stage." }
        } catch (failure: RuntimeException) {
            call.detachProvider(rerankCall)
            fail(call, RuntimeStage.RERANK, failure)
            return
        }
        completion.whenComplete { result, failure ->
            call.detachProvider(rerankCall)
            if (failure != null) {
                fail(call, RuntimeStage.RERANK, failure)
            } else if (call.isActive()) {
                try {
                    requireRerankerDescriptor(descriptor)
                    val exact = requireNotNull(result)
                    requireExactRerankResult(request, descriptor, exact)
                    val inputs = hydrated.associateBy { it.candidate.digest }
                    val rerankedItems = exact.scores.map { score ->
                        val source = inputs[score.candidateDigest]
                            ?: throw RuntimeAbort(RetrievalRuntimeFailureCode.INVALID_PROVIDER_RESPONSE)
                        RetrievalRuntimeItem.reranked(source, score.score, score.providerEvidenceDigest)
                    }
                    finish(call, context, rerankedItems, true)
                } catch (problem: RuntimeException) {
                    fail(call, RuntimeStage.RERANK, problem)
                }
            }
        }
    }

    private fun requireExactRerankResult(
        request: RerankRequest,
        descriptor: RerankerDescriptor,
        result: RerankResult,
    ) {
        require(
                result.requestId == request.requestId &&
                result.requestDigest == request.digest &&
                result.descriptorDigest == descriptor.digest &&
                result.providerBindingDigest == descriptor.binding.digest &&
                result.scores.isNotEmpty() &&
                result.scores.size <= request.maximumResults,
        ) { "Rerank result does not match its exact request." }
        val allowed = request.items.map { it.candidate.digest }.toSet()
        require(result.scores.map { it.candidateDigest }.toSet().size == result.scores.size &&
            result.scores.all { it.candidateDigest in allowed }) {
            "Reranker introduced or duplicated a candidate."
        }
        result.scores.forEach { score -> require(score.score.isFinite()) { "Rerank score is invalid." } }
        require(result.scores.zipWithNext().all { (left, right) -> left.score >= right.score }) {
            "Rerank result order is invalid."
        }
    }

    private fun finish(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        items: Collection<RetrievalRuntimeItem>,
        reranked: Boolean,
    ) {
        inspectVisibility(call, context, items.map { item -> deletionResource(item.candidate) }) { inspection ->
            val visible = items.filter { item -> inspection.isVisible(deletionResource(item.candidate)) }
            finishVisible(call, context, visible, reranked && visible.isNotEmpty())
        }
    }

    private fun finishVisible(
        call: RuntimeCall,
        context: RetrievalExecutionContext,
        items: Collection<RetrievalRuntimeItem>,
        reranked: Boolean,
    ) {
        try {
            call.ensureActive()
            requireCandidateDescriptor(context.snapshots.candidate)
            requireLineageDescriptor(context.snapshots.lineage)
            requireAuthorizerDescriptor(context.snapshots.authorizer)
            requireContentDescriptor(context.snapshots.content)
            deletionVisibility?.requireSameDescriptor() ?: throw RuntimeAbort(
                RetrievalRuntimeFailureCode.DELETION_VISIBILITY_UNAVAILABLE,
            )
            context.snapshots.reranker?.let(::requireRerankerDescriptor)
            val completedAt = clock.asLong
            require(completedAt >= call.startedAtEpochMilli &&
                completedAt < context.executable.deadlineEpochMilli &&
                completedAt < context.executable.accessPlanExpiresAtEpochMilli) {
                "Retrieval completed outside its trusted validity window."
            }
            items.forEach { it.candidate.requireUsableAt(completedAt) }
            val prefiltered = checkNotNull(context.prefiltered)
            call.complete(
                RetrievalRuntimeResult.completed(
                    context.request,
                    context.snapshots.candidate.digest,
                    prefiltered.securityFilterReceipt,
                    prefiltered.nextCursor,
                    items,
                    reranked,
                    prefiltered.partial,
                    prefiltered.timedOut,
                    call.startedAtEpochMilli,
                    completedAt,
                ),
            )
        } catch (failure: RuntimeException) {
            fail(call, RuntimeStage.FINALIZE, failure)
        }
    }

    private fun requireCandidateDescriptor(expected: CandidateRetrieverDescriptor) {
        val actual = requireNotNull(candidateRetriever.descriptor()) { "Candidate provider returned no descriptor." }
        if (actual.digest != expected.digest ||
            actual.providerInstanceId != expected.providerInstanceId ||
            actual.configurationDigest != expected.configurationDigest
        ) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED)
        }
    }

    private fun requireContentDescriptor(expected: RetrievalContentProviderDescriptor) {
        val actual = requireNotNull(contentProvider.descriptor()) { "Content provider returned no descriptor." }
        if (actual.digest != expected.digest ||
            actual.providerInstanceId != expected.providerInstanceId ||
            actual.configurationDigest != expected.configurationDigest ||
            actual.capabilityDigest != expected.capabilityDigest ||
            actual.providerRevision != expected.providerRevision
        ) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED)
        }
        if (actual.sendsContentOffHost && !configuration.contentEgressAllowed) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.EGRESS_FORBIDDEN)
        }
    }

    private fun requireLineageDescriptor(expected: RetrievalLineageResolverDescriptor) {
        val actual = requireNotNull(lineageResolver.descriptor()) { "Lineage resolver returned no descriptor." }
        if (actual.digest != expected.digest ||
            actual.providerInstanceId != expected.providerInstanceId ||
            actual.configurationDigest != expected.configurationDigest ||
            actual.capabilityDigest != expected.capabilityDigest ||
            actual.capabilityRevision != expected.capabilityRevision
        ) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED)
        }
    }

    private fun requireAuthorizerDescriptor(expected: RetrievalCandidateAuthorizerDescriptor) {
        val actual = requireNotNull(candidateAuthorizer.descriptor()) { "Candidate authorizer returned no descriptor." }
        if (actual.digest != expected.digest ||
            actual.providerInstanceId != expected.providerInstanceId ||
            actual.configurationDigest != expected.configurationDigest ||
            actual.capabilityDigest != expected.capabilityDigest ||
            actual.capabilityRevision != expected.capabilityRevision
        ) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED)
        }
    }

    private fun requireRerankerDescriptor(expected: RerankerDescriptor) {
        val configured = reranker ?: throw RuntimeAbort(RetrievalRuntimeFailureCode.UNSUPPORTED)
        val actual = requireNotNull(configured.descriptor()) { "Reranker returned no descriptor." }
        if (actual.digest != expected.digest ||
            actual.providerInstanceId != expected.providerInstanceId ||
            actual.configurationDigest != expected.configurationDigest ||
            actual.capabilityDigest != expected.capabilityDigest ||
            actual.providerRevision != expected.providerRevision
        ) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED)
        }
        if (actual.sendsQueryOrContentOffHost && !configuration.rerankEgressAllowed) {
            throw RuntimeAbort(RetrievalRuntimeFailureCode.EGRESS_FORBIDDEN)
        }
    }

    private fun fail(call: RuntimeCall, stage: RuntimeStage, failure: Throwable) {
        if (!call.isActive()) return
        val exact = unwrap(failure)
        val sanitized = when (exact) {
            is RetrievalRuntimeException -> RetrievalRuntimeException.create(
                exact.code,
                exact.retryability,
                exact.providerFailureCode,
                exact.providerRequestId,
            )
            is RuntimeAbort -> RetrievalRuntimeException.create(exact.code)
            is RetrievalProviderException -> RetrievalRuntimeException.create(
                when (exact.code) {
                    ai.icen.fw.retrieval.api.RetrievalFailureCode.DELETION_VISIBILITY_UNAVAILABLE ->
                        RetrievalRuntimeFailureCode.DELETION_VISIBILITY_UNAVAILABLE
                    ai.icen.fw.retrieval.api.RetrievalFailureCode.DELETION_VISIBILITY_MISMATCH ->
                        RetrievalRuntimeFailureCode.INVALID_PROVIDER_RESPONSE
                    else -> RetrievalRuntimeFailureCode.PROVIDER_FAILED
                },
                exact.retryability,
                exact.code,
                exact.providerRequestId,
            )
            else -> RetrievalRuntimeException.create(stage.failureCode)
        }
        call.fail(sanitized)
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = checkNotNull(current.cause)
        }
        return current
    }

    private inner class RuntimeCall(val startedAtEpochMilli: Long) : RetrievalRuntimeCall {
        private val monitor = Any()
        private val completion = CompletableFuture<RetrievalRuntimeResult>()
        private val usedIds = LinkedHashSet<Identifier>()
        private var deadlineTask: RetrievalRuntimeScheduledTask? = null
        private var providerCall: RetrievalCall<*>? = null
        private var terminalCancellationReason: RetrievalCancellationReason? = null

        init {
            completion.whenComplete { _, _ -> clearDeadline() }
        }

        override fun completion(): CompletionStage<RetrievalRuntimeResult> = completion

        override fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome> {
            val attached: RetrievalCall<*>?
            synchronized(monitor) {
                if (completion.isDone) {
                    return CompletableFuture.completedFuture(RetrievalCancellationOutcome.ALREADY_COMPLETED)
                }
                attached = providerCall
                terminalCancellationReason = reason
                completion.completeExceptionally(
                    RetrievalRuntimeException.create(RetrievalRuntimeFailureCode.CANCELLED),
                )
            }
            cancelProvider(attached, reason)
            return CompletableFuture.completedFuture(RetrievalCancellationOutcome.ACCEPTED)
        }

        fun expire() {
            val attached: RetrievalCall<*>?
            synchronized(monitor) {
                if (completion.isDone) return
                attached = providerCall
                terminalCancellationReason = RetrievalCancellationReason.DEADLINE_EXCEEDED
                completion.completeExceptionally(
                    RetrievalRuntimeException.create(RetrievalRuntimeFailureCode.DEADLINE_EXCEEDED),
                )
            }
            cancelProvider(attached, RetrievalCancellationReason.DEADLINE_EXCEEDED)
        }

        fun installDeadline(task: RetrievalRuntimeScheduledTask) {
            synchronized(monitor) {
                if (completion.isDone) {
                    task.cancel()
                } else {
                    deadlineTask = task
                }
            }
        }

        fun nextId(purpose: RetrievalRuntimeIdPurpose): Identifier = synchronized(monitor) {
            ensureActiveLocked()
            val identifier = requireRuntimeIdentifier(
                ids.nextId(purpose),
                "Retrieval runtime identifier generator returned an invalid identifier.",
            )
            require(usedIds.add(identifier)) { "Retrieval runtime identifier generator returned a duplicate." }
            identifier
        }

        fun attachProvider(call: RetrievalCall<*>): Boolean {
            var immediateReason: RetrievalCancellationReason? = null
            synchronized(monitor) {
                if (completion.isDone) {
                    immediateReason = terminalCancellationReason ?: RetrievalCancellationReason.RUNTIME_SHUTDOWN
                } else {
                    check(providerCall == null) { "A retrieval provider call is already attached." }
                    providerCall = call
                }
            }
            immediateReason?.let { cancelProvider(call, it) }
            return immediateReason == null
        }

        fun detachProvider(call: RetrievalCall<*>) {
            synchronized(monitor) {
                if (providerCall === call) providerCall = null
            }
        }

        fun ensureActive() {
            synchronized(monitor) { ensureActiveLocked() }
        }

        fun isActive(): Boolean = synchronized(monitor) { !completion.isDone }

        fun complete(result: RetrievalRuntimeResult) {
            synchronized(monitor) {
                if (!completion.isDone) completion.complete(result)
            }
        }

        fun fail(failure: RetrievalRuntimeException) {
            synchronized(monitor) {
                if (!completion.isDone) completion.completeExceptionally(failure)
            }
        }

        private fun ensureActiveLocked() {
            if (completion.isDone) throw RuntimeAbort(RetrievalRuntimeFailureCode.CANCELLED)
        }

        private fun clearDeadline() {
            val task = synchronized(monitor) {
                val current = deadlineTask
                deadlineTask = null
                current
            }
            task?.cancel()
        }

        private fun cancelProvider(call: RetrievalCall<*>?, reason: RetrievalCancellationReason) {
            call ?: return
            try {
                call.cancel(reason)?.whenComplete { _, _ -> Unit }
            } catch (_: RuntimeException) {
                // Cancellation remains terminal even if an adapter cannot acknowledge it.
            }
        }
    }

    private class RuntimeAbort(val code: RetrievalRuntimeFailureCode) : RuntimeException()

    private class ProviderSnapshots(
        val candidate: CandidateRetrieverDescriptor,
        val lineage: RetrievalLineageResolverDescriptor,
        val authorizer: RetrievalCandidateAuthorizerDescriptor,
        val content: RetrievalContentProviderDescriptor,
        val deletionVisibility: RetrievalDeletionVisibilityDescriptor,
        val reranker: RerankerDescriptor?,
    )

    private class HydrationAccumulator(capacity: Int) {
        val items = ArrayList<RetrievalRuntimeItem>(capacity)
        var consumedCodePoints: Int = 0
    }

    private class RetrievalExecutionContext(
        val request: RetrievalRuntimeRequest,
        val executable: ExecutableRetrievalRequest,
        val snapshots: ProviderSnapshots,
        val envelope: RetrievalResultEnvelope,
        val prefiltered: PrefilteredCandidateBatch? = null,
        val resolved: ResolvedCandidateBatch? = null,
    ) {
        fun withPrefiltered(value: PrefilteredCandidateBatch): RetrievalExecutionContext =
            RetrievalExecutionContext(request, executable, snapshots, envelope, value, resolved)

        fun withResolved(value: ResolvedCandidateBatch): RetrievalExecutionContext =
            RetrievalExecutionContext(request, executable, snapshots, envelope, prefiltered, value)
    }

    private enum class RuntimeStage(val failureCode: RetrievalRuntimeFailureCode) {
        AUTHORIZATION(RetrievalRuntimeFailureCode.AUTHORIZATION_FAILED),
        PROVIDER_DESCRIPTOR(RetrievalRuntimeFailureCode.PROVIDER_FAILED),
        PREFLIGHT(RetrievalRuntimeFailureCode.POLICY_REJECTED),
        CANDIDATE_PROVIDER(RetrievalRuntimeFailureCode.PROVIDER_FAILED),
        CANDIDATE_RESPONSE(RetrievalRuntimeFailureCode.INVALID_PROVIDER_RESPONSE),
        LINEAGE(RetrievalRuntimeFailureCode.LINEAGE_FAILED),
        CANDIDATE_AUTHORIZATION(RetrievalRuntimeFailureCode.AUTHORIZATION_FAILED),
        DELETION_VISIBILITY(RetrievalRuntimeFailureCode.INVALID_PROVIDER_RESPONSE),
        HYDRATION(RetrievalRuntimeFailureCode.INVALID_PROVIDER_RESPONSE),
        RERANK(RetrievalRuntimeFailureCode.INVALID_PROVIDER_RESPONSE),
        FINALIZE(RetrievalRuntimeFailureCode.AUTHORIZATION_FAILED),
        INTERNAL(RetrievalRuntimeFailureCode.INTERNAL_FAILURE),
    }

    companion object {
        @JvmStatic
        fun create(
            authorizationPlanner: RetrievalAuthorizationPlanner,
            candidateRetriever: CandidateRetriever,
            lineageResolver: RetrievalLineageResolver,
            candidateAuthorizer: RetrievalCandidateAuthorizer,
            contentProvider: RetrievalContentProvider,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
            deadlineExecutor: ScheduledExecutorService,
            configuration: RetrievalRuntimeConfiguration,
        ): SecureRetrievalRuntime = SecureRetrievalRuntime(
            authorizationPlanner,
            candidateRetriever,
            lineageResolver,
            candidateAuthorizer,
            contentProvider,
            null,
            null,
            clock,
            ids,
            ExecutorRetrievalRuntimeDeadlineScheduler(deadlineExecutor),
            configuration,
        )

        /** Creates a secure runtime with the mandatory authoritative deletion visibility bridge. */
        @JvmStatic
        fun create(
            authorizationPlanner: RetrievalAuthorizationPlanner,
            candidateRetriever: CandidateRetriever,
            lineageResolver: RetrievalLineageResolver,
            candidateAuthorizer: RetrievalCandidateAuthorizer,
            contentProvider: RetrievalContentProvider,
            deletionVisibilityProvider: RetrievalDeletionVisibilityProvider,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
            deadlineExecutor: ScheduledExecutorService,
            configuration: RetrievalRuntimeConfiguration,
        ): SecureRetrievalRuntime = SecureRetrievalRuntime(
            authorizationPlanner,
            candidateRetriever,
            lineageResolver,
            candidateAuthorizer,
            contentProvider,
            deletionVisibilityProvider,
            null,
            clock,
            ids,
            ExecutorRetrievalRuntimeDeadlineScheduler(deadlineExecutor),
            configuration,
        )

        /**
         * Uses the primary candidate provider when present, otherwise installs the explicit local
         * filename-contains fallback for FULL_TEXT requests. Vector and hybrid modes remain a
         * diagnosable unsupported state.
         */
        @JvmStatic
        fun createWithFilenameFallback(
            authorizationPlanner: RetrievalAuthorizationPlanner,
            primaryCandidateRetriever: CandidateRetriever?,
            filenameCatalog: FilenameCatalog,
            lineageResolver: RetrievalLineageResolver,
            candidateAuthorizer: RetrievalCandidateAuthorizer,
            contentProvider: RetrievalContentProvider,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
            deadlineExecutor: ScheduledExecutorService,
            configuration: RetrievalRuntimeConfiguration,
        ): SecureRetrievalRuntime = create(
            authorizationPlanner,
            primaryCandidateRetriever ?: SafeFilenameCandidateRetriever.createFullTextFallback(filenameCatalog),
            lineageResolver,
            candidateAuthorizer,
            contentProvider,
            clock,
            ids,
            deadlineExecutor,
            configuration,
        )

        /** Filename fallback variant with mandatory authoritative deletion fencing. */
        @JvmStatic
        fun createWithFilenameFallback(
            authorizationPlanner: RetrievalAuthorizationPlanner,
            primaryCandidateRetriever: CandidateRetriever?,
            filenameCatalog: FilenameCatalog,
            lineageResolver: RetrievalLineageResolver,
            candidateAuthorizer: RetrievalCandidateAuthorizer,
            contentProvider: RetrievalContentProvider,
            deletionVisibilityProvider: RetrievalDeletionVisibilityProvider,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
            deadlineExecutor: ScheduledExecutorService,
            configuration: RetrievalRuntimeConfiguration,
        ): SecureRetrievalRuntime = create(
            authorizationPlanner,
            primaryCandidateRetriever ?: SafeFilenameCandidateRetriever.createFullTextFallback(
                filenameCatalog,
                deletionVisibilityProvider,
                clock,
                ids,
            ),
            lineageResolver,
            candidateAuthorizer,
            contentProvider,
            deletionVisibilityProvider,
            clock,
            ids,
            deadlineExecutor,
            configuration,
        )

        @JvmStatic
        fun create(
            authorizationPlanner: RetrievalAuthorizationPlanner,
            candidateRetriever: CandidateRetriever,
            lineageResolver: RetrievalLineageResolver,
            candidateAuthorizer: RetrievalCandidateAuthorizer,
            contentProvider: RetrievalContentProvider,
            reranker: Reranker,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
            deadlineExecutor: ScheduledExecutorService,
            configuration: RetrievalRuntimeConfiguration,
        ): SecureRetrievalRuntime = SecureRetrievalRuntime(
            authorizationPlanner,
            candidateRetriever,
            lineageResolver,
            candidateAuthorizer,
            contentProvider,
            null,
            reranker,
            clock,
            ids,
            ExecutorRetrievalRuntimeDeadlineScheduler(deadlineExecutor),
            configuration,
        )

        /** Creates a reranking runtime with mandatory authoritative deletion visibility. */
        @JvmStatic
        fun create(
            authorizationPlanner: RetrievalAuthorizationPlanner,
            candidateRetriever: CandidateRetriever,
            lineageResolver: RetrievalLineageResolver,
            candidateAuthorizer: RetrievalCandidateAuthorizer,
            contentProvider: RetrievalContentProvider,
            deletionVisibilityProvider: RetrievalDeletionVisibilityProvider,
            reranker: Reranker,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
            deadlineExecutor: ScheduledExecutorService,
            configuration: RetrievalRuntimeConfiguration,
        ): SecureRetrievalRuntime = SecureRetrievalRuntime(
            authorizationPlanner,
            candidateRetriever,
            lineageResolver,
            candidateAuthorizer,
            contentProvider,
            deletionVisibilityProvider,
            reranker,
            clock,
            ids,
            ExecutorRetrievalRuntimeDeadlineScheduler(deadlineExecutor),
            configuration,
        )

        @JvmSynthetic
        internal fun createForTests(
            authorizationPlanner: RetrievalAuthorizationPlanner,
            candidateRetriever: CandidateRetriever,
            lineageResolver: RetrievalLineageResolver,
            candidateAuthorizer: RetrievalCandidateAuthorizer,
            contentProvider: RetrievalContentProvider,
            reranker: Reranker?,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
            deadlineScheduler: RetrievalRuntimeDeadlineScheduler,
            configuration: RetrievalRuntimeConfiguration,
        ): SecureRetrievalRuntime = SecureRetrievalRuntime(
            authorizationPlanner,
            candidateRetriever,
            lineageResolver,
            candidateAuthorizer,
            contentProvider,
            null,
            reranker,
            clock,
            ids,
            deadlineScheduler,
            configuration,
        )

        @JvmSynthetic
        internal fun createForTests(
            authorizationPlanner: RetrievalAuthorizationPlanner,
            candidateRetriever: CandidateRetriever,
            lineageResolver: RetrievalLineageResolver,
            candidateAuthorizer: RetrievalCandidateAuthorizer,
            contentProvider: RetrievalContentProvider,
            deletionVisibilityProvider: RetrievalDeletionVisibilityProvider,
            reranker: Reranker?,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
            deadlineScheduler: RetrievalRuntimeDeadlineScheduler,
            configuration: RetrievalRuntimeConfiguration,
        ): SecureRetrievalRuntime = SecureRetrievalRuntime(
            authorizationPlanner,
            candidateRetriever,
            lineageResolver,
            candidateAuthorizer,
            contentProvider,
            deletionVisibilityProvider,
            reranker,
            clock,
            ids,
            deadlineScheduler,
            configuration,
        )
    }
}
