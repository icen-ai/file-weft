package ai.icen.fw.retrieval.runtime

import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalCalls
import ai.icen.fw.retrieval.api.RetrievalCancellationOutcome
import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalDeletionResourceRef
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityBatch
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityDecision
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityDescriptor
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityProvider
import ai.icen.fw.retrieval.api.RetrievalDeletionVisibilityRequest
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalProviderException
import ai.icen.fw.retrieval.api.RetrievalRetryability
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier

/** Runtime-owned batching and exact-response gate for the deletion visibility provider. */
internal class DeletionVisibilityRuntimeGuard private constructor(
    private val provider: RetrievalDeletionVisibilityProvider,
    private val clock: LongSupplier,
    private val ids: RetrievalRuntimeIdGenerator,
    val descriptor: RetrievalDeletionVisibilityDescriptor,
) {
    fun inspect(
        resources: Collection<RetrievalDeletionResourceRef>,
        deadlineEpochMilli: Long,
    ): RetrievalCall<DeletionVisibilityInspection> {
        val distinct = LinkedHashMap<String, RetrievalDeletionResourceRef>()
        resources.forEach { resource -> distinct.putIfAbsent(resource.digest, resource) }
        if (distinct.isEmpty()) return RetrievalCalls.completed(DeletionVisibilityInspection.empty(clock.asLong))
        val exactResources = ArrayList(distinct.values)
        require(exactResources.map { resource -> resource.tenantId }.toSet().size == 1) {
            "Deletion visibility inspection cannot cross tenants."
        }
        require(exactResources.all { resource -> resource.resourceType in descriptor.supportedResourceTypes }) {
            "Deletion visibility provider does not support a requested resource type."
        }

        val cancelled = AtomicBoolean(false)
        val active = AtomicReference<RetrievalCall<RetrievalDeletionVisibilityBatch>?>(null)
        var chain: CompletionStage<List<RetrievalDeletionVisibilityDecision>> =
            CompletableFuture.completedFuture(emptyList())
        exactResources.chunked(descriptor.maximumBatchSize).forEach { batch ->
            chain = chain.thenCompose { collected ->
                if (cancelled.get()) {
                    throw RetrievalProviderException(
                        RetrievalFailureCode.CANCELLED,
                        RetrievalRetryability.NOT_RETRYABLE,
                    )
                }
                requireSameDescriptor()
                val now = clock.asLong
                if (now < 0L || now >= deadlineEpochMilli) {
                    throw RetrievalProviderException(
                        RetrievalFailureCode.DELETION_VISIBILITY_UNAVAILABLE,
                        RetrievalRetryability.RETRYABLE,
                    )
                }
                val request = RetrievalDeletionVisibilityRequest.create(
                    ids.nextId(RetrievalRuntimeIdPurpose.DELETION_VISIBILITY),
                    batch,
                    descriptor,
                    now,
                    deadlineEpochMilli,
                )
                val providerCall = try {
                    requireNotNull(provider.inspect(request)) {
                        "Deletion visibility provider returned no call handle."
                    }
                } catch (failure: RetrievalProviderException) {
                    throw failure
                } catch (_: RuntimeException) {
                    throw mismatch()
                }
                active.set(providerCall)
                val completion = try {
                    requireNotNull(providerCall.completion()) {
                        "Deletion visibility provider returned no completion stage."
                    }
                } catch (failure: RuntimeException) {
                    active.compareAndSet(providerCall, null)
                    throw if (failure is RetrievalProviderException) failure else mismatch()
                }
                completion.thenApply { response ->
                    active.compareAndSet(providerCall, null)
                    try {
                        requireSameDescriptor()
                        val exact = requireNotNull(response) {
                            "Deletion visibility provider completed without a response."
                        }
                        exact.requireExactFor(request, descriptor)
                        val verifiedAt = clock.asLong
                        require(verifiedAt >= exact.completedAtEpochMilli && verifiedAt < deadlineEpochMilli) {
                            "Deletion visibility response is outside the caller validity window."
                        }
                        ArrayList<RetrievalDeletionVisibilityDecision>(collected.size + exact.decisions.size).also {
                            it.addAll(collected)
                            it.addAll(exact.decisions)
                        }
                    } catch (failure: RetrievalProviderException) {
                        throw failure
                    } catch (_: RuntimeException) {
                        throw mismatch()
                    }
                }
            }
        }
        val inspected = chain.thenApply { decisions ->
            DeletionVisibilityInspection.create(exactResources, decisions, clock.asLong)
        }
        return object : RetrievalCall<DeletionVisibilityInspection> {
            override fun completion(): CompletionStage<DeletionVisibilityInspection> = inspected

            override fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome> {
                cancelled.set(true)
                val current = active.get()
                    ?: return CompletableFuture.completedFuture(RetrievalCancellationOutcome.ACCEPTED)
                return try {
                    requireNotNull(current.cancel(reason)) {
                        "Deletion visibility cancellation returned no completion stage."
                    }
                } catch (_: RuntimeException) {
                    CompletableFuture.completedFuture(RetrievalCancellationOutcome.UNSUPPORTED)
                }
            }
        }
    }

    fun requireSameDescriptor() {
        val actual = try {
            requireNotNull(provider.descriptor()) { "Deletion visibility provider returned no descriptor." }
        } catch (_: RuntimeException) {
            throw mismatch()
        }
        if (actual.digest != descriptor.digest ||
            actual.providerTypeId != descriptor.providerTypeId ||
            actual.providerInstanceId != descriptor.providerInstanceId ||
            actual.configurationDigest != descriptor.configurationDigest ||
            actual.capabilityDigest != descriptor.capabilityDigest ||
            actual.capabilityRevision != descriptor.capabilityRevision
        ) {
            throw mismatch()
        }
    }

    companion object {
        fun create(
            provider: RetrievalDeletionVisibilityProvider,
            clock: LongSupplier,
            ids: RetrievalRuntimeIdGenerator,
        ): DeletionVisibilityRuntimeGuard {
            val descriptor = try {
                requireNotNull(provider.descriptor()) {
                    "Deletion visibility provider returned no descriptor."
                }
            } catch (_: RuntimeException) {
                throw RetrievalProviderException(
                    RetrievalFailureCode.DELETION_VISIBILITY_UNAVAILABLE,
                    RetrievalRetryability.NOT_RETRYABLE,
                )
            }
            require(RetrievalDeletionResourceRef.DOCUMENT_RESOURCE_TYPE in descriptor.supportedResourceTypes) {
                "Deletion visibility provider must support document resources."
            }
            return DeletionVisibilityRuntimeGuard(provider, clock, ids, descriptor)
        }

        private fun mismatch(): RetrievalProviderException = RetrievalProviderException(
            RetrievalFailureCode.DELETION_VISIBILITY_MISMATCH,
            RetrievalRetryability.NOT_RETRYABLE,
        )
    }
}

internal class DeletionVisibilityInspection private constructor(
    private val decisionsByResourceDigest: Map<String, RetrievalDeletionVisibilityDecision>,
    val verifiedAtEpochMilli: Long,
) {
    fun isVisible(resource: RetrievalDeletionResourceRef): Boolean =
        requireNotNull(decisionsByResourceDigest[resource.digest]) {
            "Deletion visibility inspection does not contain the requested resource revision."
        }.isVisible()

    fun requireVisible(resource: RetrievalDeletionResourceRef) {
        if (!isVisible(resource)) {
            throw RetrievalProviderException(
                RetrievalFailureCode.RESOURCE_TOMBSTONED,
                RetrievalRetryability.NOT_RETRYABLE,
            )
        }
    }

    companion object {
        fun empty(verifiedAtEpochMilli: Long): DeletionVisibilityInspection =
            DeletionVisibilityInspection(emptyMap(), verifiedAtEpochMilli)

        fun create(
            resources: Collection<RetrievalDeletionResourceRef>,
            decisions: Collection<RetrievalDeletionVisibilityDecision>,
            verifiedAtEpochMilli: Long,
        ): DeletionVisibilityInspection {
            require(resources.size == decisions.size) {
                "Deletion visibility inspection did not account for every resource."
            }
            val answers = LinkedHashMap<String, RetrievalDeletionVisibilityDecision>()
            resources.zip(decisions).forEach { (resource, decision) ->
                require(resource.digest == decision.resource.digest && answers.put(resource.digest, decision) == null) {
                    "Deletion visibility inspection changed resource order or identity."
                }
            }
            require(
                verifiedAtEpochMilli >=
                    (decisions.maxOfOrNull { decision -> decision.decidedAtEpochMilli } ?: 0L),
            ) {
                "Deletion visibility verification predates its decisions."
            }
            return DeletionVisibilityInspection(answers, verifiedAtEpochMilli)
        }
    }
}

internal fun deletionResource(candidate: ai.icen.fw.retrieval.api.AuthorizedRetrievalCandidate):
    RetrievalDeletionResourceRef {
    val evidence = candidate.resolvedCandidate.candidate.evidence
    return RetrievalDeletionResourceRef.documentContent(
        evidence.tenantId,
        evidence.documentId,
        evidence.versionId,
        evidence.sourceSha256,
    )
}

internal fun deletionResource(evidence: ai.icen.fw.retrieval.api.RetrievalEvidenceRef):
    RetrievalDeletionResourceRef =
    RetrievalDeletionResourceRef.documentContent(
        evidence.tenantId,
        evidence.documentId,
        evidence.versionId,
        evidence.sourceSha256,
    )
