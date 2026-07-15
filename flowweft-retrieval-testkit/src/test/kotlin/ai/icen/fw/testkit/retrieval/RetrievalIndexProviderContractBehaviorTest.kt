package ai.icen.fw.testkit.retrieval

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalProviderException
import ai.icen.fw.retrieval.api.RetrievalRetryability
import ai.icen.fw.retrieval.spi.ContentBinarySource
import ai.icen.fw.retrieval.spi.ContentChunk
import ai.icen.fw.retrieval.spi.ContentChunkerDescriptor
import ai.icen.fw.retrieval.spi.ContentChunkingRequest
import ai.icen.fw.retrieval.spi.ContentChunkingResult
import ai.icen.fw.retrieval.spi.ContentExtractionRequest
import ai.icen.fw.retrieval.spi.ContentExtractionResult
import ai.icen.fw.retrieval.spi.ContentExtractorDescriptor
import ai.icen.fw.retrieval.spi.ContentInputHandle
import ai.icen.fw.retrieval.spi.ContentSourceRef
import ai.icen.fw.retrieval.spi.ExtractedContentSegment
import ai.icen.fw.retrieval.spi.RetrievalIndexActivationReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexActivationRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexGenerationManifest
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationKind
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexMutationRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexProvider
import ai.icen.fw.retrieval.spi.RetrievalIndexProviderDescriptor
import ai.icen.fw.retrieval.spi.RetrievalIndexSealReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexSealRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexStageBatch
import ai.icen.fw.retrieval.spi.RetrievalIndexStageReceipt
import ai.icen.fw.retrieval.spi.RetrievalIndexState
import ai.icen.fw.retrieval.spi.RetrievalIndexStateRequest
import java.io.ByteArrayInputStream
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger

/** Runs the public contract against a stateful, thread-safe provider rather than receipt-only mocks. */
class RetrievalIndexProviderContractBehaviorTest : RetrievalIndexProviderContractTest() {
    private val provider = InMemoryRetrievalIndexProvider(DESCRIPTOR)
    private val sequence = AtomicInteger()

    override val indexProvider: RetrievalIndexProvider = provider

    override fun stageRequest(descriptor: RetrievalIndexProviderDescriptor): RetrievalIndexStageBatch =
        newStage(next("stage"), descriptor, newSource(next("stage-source")), 50L)

    override fun sealRequest(descriptor: RetrievalIndexProviderDescriptor): RetrievalIndexSealRequest =
        prepareSeal(next("seal"), descriptor, newSource(next("seal-source")), 50L, 60L).request

    override fun activationRequest(descriptor: RetrievalIndexProviderDescriptor): RetrievalIndexActivationRequest =
        prepareActivation(next("activate"), descriptor, newSource(next("activate-source")), null, 0L, 50L, 60L, 70L)

    override fun mutationRequest(descriptor: RetrievalIndexProviderDescriptor): RetrievalIndexMutationRequest {
        val scope = next("mutation")
        val source = newSource("$scope-source")
        val activated = provider.activate(
            prepareActivation("$scope-baseline", descriptor, source, null, 0L, 50L, 60L, 70L),
        ).toCompletableFuture().join()
        return RetrievalIndexMutationRequest.of(
            Identifier("mutation-$scope"),
            descriptor,
            source,
            activated.activeGenerationId,
            activated.activeProjectionRevision,
            RetrievalIndexMutationKind.AUTHORIZATION_REFRESH,
            "policy-refresh-$scope",
            digest('e'),
            "authorization-revision-changed",
            90L,
            DEADLINE,
        )
    }

    override fun stateRequest(descriptor: RetrievalIndexProviderDescriptor): RetrievalIndexStateRequest {
        val scope = next("state")
        val source = newSource("$scope-source")
        provider.activate(
            prepareActivation("$scope-baseline", descriptor, source, null, 0L, 50L, 60L, 70L),
        ).toCompletableFuture().join()
        return stateRequest("state-$scope", descriptor, source, 80L)
    }

    override fun activationRaceScenario(
        descriptor: RetrievalIndexProviderDescriptor,
        contenderCount: Int,
    ): RetrievalIndexActivationRaceScenario {
        val scope = next("race")
        val source = newSource("$scope-source")
        val baseline = activateBaseline(scope, descriptor, source)
        val requests = (0 until contenderCount).map { index ->
            prepareActivation(
                "$scope-contender-$index",
                descriptor,
                source,
                baseline.activeGenerationId,
                baseline.activeProjectionRevision,
                80L,
                90L,
                100L,
            )
        }
        return RetrievalIndexActivationRaceScenario.of(
            requests,
            stateRequest("$scope-before", descriptor, source, 75L),
            stateRequest("$scope-after", descriptor, source, 120L),
        )
    }

    override fun activationReplayScenario(
        descriptor: RetrievalIndexProviderDescriptor,
        replayCount: Int,
    ): RetrievalIndexActivationReplayScenario {
        val scope = next("replay")
        val source = newSource("$scope-source")
        val baseline = activateBaseline(scope, descriptor, source)
        val request = prepareActivation(
            "$scope-target",
            descriptor,
            source,
            baseline.activeGenerationId,
            baseline.activeProjectionRevision,
            80L,
            90L,
            100L,
        )
        return RetrievalIndexActivationReplayScenario.of(
            request,
            replayCount,
            stateRequest("$scope-before", descriptor, source, 75L),
            stateRequest("$scope-after", descriptor, source, 120L),
        )
    }

    override fun activationReplayMismatchScenario(
        descriptor: RetrievalIndexProviderDescriptor,
    ): RetrievalIndexActivationReplayMismatchScenario {
        val scope = next("replay-mismatch")
        val source = newSource("$scope-source")
        val baseline = activateBaseline(scope, descriptor, source)
        val sharedId = Identifier("activate-$scope-shared")
        val acceptedSeal = prepareSeal("$scope-accepted", descriptor, source, 80L, 90L)
        val conflictingSeal = prepareSeal("$scope-conflicting", descriptor, source, 80L, 90L)
        val accepted = activation(sharedId, acceptedSeal.receipt, baseline, 100L)
        val conflicting = activation(sharedId, conflictingSeal.receipt, baseline, 100L)
        return RetrievalIndexActivationReplayMismatchScenario.of(
            accepted,
            conflicting,
            stateRequest("$scope-after", descriptor, source, 120L),
        )
    }

    override fun providerBindingMismatchScenario(
        descriptor: RetrievalIndexProviderDescriptor,
    ): RetrievalIndexProviderBindingMismatchScenario {
        val scope = next("binding")
        val source = newSource("$scope-source")
        val baseline = activateBaseline(scope, descriptor, source)
        val foreign = RetrievalIndexProviderDescriptor.of(
            descriptor.providerId,
            "foreign-provider-instance",
            descriptor.providerRevision,
            descriptor.indexSchemaRevision,
            descriptor.maximumStageBatchSize,
            descriptor.supportsText,
            descriptor.supportsVectors,
            true,
            true,
            true,
        )
        val stage = newStage("$scope-foreign", foreign, source, 80L)
        val staged = RetrievalIndexStageReceipt.staged(stage, "foreign-stage-$scope", 81L)
        val seal = RetrievalIndexSealRequest.of(
            Identifier("seal-$scope-foreign"),
            stage.manifest,
            listOf(staged),
            90L,
            DEADLINE,
        )
        val sealed = RetrievalIndexSealReceipt.sealed(seal, "foreign-seal-$scope", 91L)
        val activate = activation(Identifier("activate-$scope-foreign"), sealed, baseline, 100L)
        return RetrievalIndexProviderBindingMismatchScenario.of(
            stage,
            seal,
            activate,
            stateRequest("$scope-before", descriptor, source, 75L),
            stateRequest("$scope-after", descriptor, source, 120L),
        )
    }

    override fun activationFailureScenario(
        descriptor: RetrievalIndexProviderDescriptor,
    ): RetrievalIndexActivationFailureScenario {
        val scope = next("failure")
        val source = newSource("$scope-source")
        val baseline = activateBaseline(scope, descriptor, source)
        val request = prepareActivation(
            "$scope-target",
            descriptor,
            source,
            baseline.activeGenerationId,
            baseline.activeProjectionRevision,
            80L,
            90L,
            100L,
        )
        provider.failOnceOnActivation(request)
        return RetrievalIndexActivationFailureScenario.of(
            request,
            stateRequest("$scope-before", descriptor, source, 75L),
            stateRequest("$scope-after", descriptor, source, 120L),
        )
    }

    private fun activateBaseline(
        scope: String,
        descriptor: RetrievalIndexProviderDescriptor,
        source: ContentSourceRef,
    ): RetrievalIndexActivationReceipt = provider.activate(
        prepareActivation("$scope-baseline", descriptor, source, null, 0L, 50L, 60L, 70L),
    ).toCompletableFuture().join()

    private fun prepareActivation(
        scope: String,
        descriptor: RetrievalIndexProviderDescriptor,
        source: ContentSourceRef,
        expectedGenerationId: String?,
        expectedRevision: Long,
        stageAt: Long,
        sealAt: Long,
        activateAt: Long,
    ): RetrievalIndexActivationRequest {
        val sealed = prepareSeal(scope, descriptor, source, stageAt, sealAt)
        return RetrievalIndexActivationRequest.of(
            Identifier("activate-$scope"),
            sealed.receipt,
            expectedRevision,
            activateAt,
            DEADLINE,
            expectedGenerationId,
        )
    }

    private fun prepareSeal(
        scope: String,
        descriptor: RetrievalIndexProviderDescriptor,
        source: ContentSourceRef,
        stageAt: Long,
        sealAt: Long,
    ): PreparedSeal {
        val stage = newStage(scope, descriptor, source, stageAt)
        val staged = provider.stage(stage).toCompletableFuture().join()
        val request = RetrievalIndexSealRequest.of(
            Identifier("seal-$scope"),
            stage.manifest,
            listOf(staged),
            sealAt,
            DEADLINE,
        )
        val receipt = provider.seal(request).toCompletableFuture().join()
        return PreparedSeal(request, receipt)
    }

    private fun newStage(
        scope: String,
        descriptor: RetrievalIndexProviderDescriptor,
        source: ContentSourceRef,
        requestedAt: Long,
    ): RetrievalIndexStageBatch {
        val manifest = manifest(scope, descriptor, source)
        return RetrievalIndexStageBatch.of(
            Identifier("stage-$scope"),
            manifest,
            0,
            0,
            manifest.records.size,
            requestedAt,
            DEADLINE,
        )
    }

    private fun manifest(
        scope: String,
        descriptor: RetrievalIndexProviderDescriptor,
        source: ContentSourceRef,
    ): RetrievalIndexGenerationManifest {
        val extractor = ContentExtractorDescriptor.of(
            "fixture-extractor",
            "fixture-extractor-instance",
            "extractor-v1",
            "plain-text-v1",
            listOf("text/plain"),
            4_096L,
            4_096,
            false,
            false,
        )
        val binary = ContentBinarySource { requested, _ ->
            CompletableFuture.completedFuture(
                ContentInputHandle.of(
                    ByteArrayInputStream(CONTENT_BYTES),
                    requested.digest,
                    requested.sourceSha256,
                    requested.sourceSizeBytes,
                ),
            )
        }
        val extractionRequest = ContentExtractionRequest.of(
            Identifier("extract-$scope"),
            source,
            extractor,
            digest('1'),
            4_096,
            10L,
            DEADLINE,
            binary,
        )
        val extraction = ContentExtractionResult.success(
            extractionRequest,
            listOf(ExtractedContentSegment.of(0, CONTENT, "text/plain")),
            "extract-provider-$scope",
            20L,
        )
        val chunker = ContentChunkerDescriptor.of(
            "fixture-chunker",
            "fixture-chunker-instance",
            "chunker-v1",
            "single-chunk-v1",
            4_096,
            16,
            4_096,
            128,
        )
        val chunkRequest = ContentChunkingRequest.of(
            Identifier("chunk-$scope"),
            extraction,
            chunker,
            digest('2'),
            16,
            4_096,
            128,
            30L,
            DEADLINE,
        )
        val length = CONTENT.codePointCount(0, CONTENT.length)
        val chunking = ContentChunkingResult.success(
            chunkRequest,
            listOf(ContentChunk.of(0, 0, 0, length, CONTENT, "text/plain", SOURCE_SHA256)),
            40L,
        )
        return RetrievalIndexGenerationManifest.of(
            "generation-$scope",
            source,
            extraction,
            chunking,
            descriptor,
            "policy-$scope",
            digest('3'),
        )
    }

    private fun newSource(scope: String): ContentSourceRef = ContentSourceRef.of(
        Identifier("tenant-$scope"),
        Identifier("catalog-$scope"),
        Identifier("document-$scope"),
        Identifier("version-$scope"),
        Identifier("asset-$scope"),
        Identifier("object-$scope"),
        SOURCE_SHA256,
        CONTENT_BYTES.size.toLong(),
        "text/plain",
        "content-$scope",
    )

    private fun stateRequest(
        id: String,
        descriptor: RetrievalIndexProviderDescriptor,
        source: ContentSourceRef,
        requestedAt: Long,
    ): RetrievalIndexStateRequest = RetrievalIndexStateRequest.of(
        Identifier("state-$id"),
        descriptor,
        source,
        requestedAt,
        DEADLINE,
    )

    private fun activation(
        id: Identifier,
        seal: RetrievalIndexSealReceipt,
        baseline: RetrievalIndexActivationReceipt,
        requestedAt: Long,
    ): RetrievalIndexActivationRequest = RetrievalIndexActivationRequest.of(
        id,
        seal,
        baseline.activeProjectionRevision,
        requestedAt,
        DEADLINE,
        baseline.activeGenerationId,
    )

    private fun next(label: String): String = "$label-${sequence.incrementAndGet()}"

    private class PreparedSeal(
        val request: RetrievalIndexSealRequest,
        val receipt: RetrievalIndexSealReceipt,
    )

    companion object {
        private const val CONTENT = "authorized fixture index content"
        private val CONTENT_BYTES = CONTENT.toByteArray(Charsets.UTF_8)
        private const val SOURCE_SHA256 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DEADLINE = 500L
        private val DESCRIPTOR = RetrievalIndexProviderDescriptor.of(
            "fixture-index",
            "fixture-index-instance",
            "provider-v1",
            "schema-v1",
            16,
            true,
            false,
            true,
            true,
            true,
        )

        private fun digest(character: Char): String = character.toString().repeat(64)
    }
}

/** The state machine deliberately keeps ledger insertion and projection changes in one monitor. */
private class InMemoryRetrievalIndexProvider(
    private val providerDescriptor: RetrievalIndexProviderDescriptor,
) : RetrievalIndexProvider {
    private val monitor = Any()
    private val sequence = AtomicInteger()
    private val ledger = HashMap<String, LedgerEntry>()
    private val stagedReceipts = HashMap<String, MutableMap<Int, MutableSet<String>>>()
    private val sealedReceipts = HashMap<String, String>()
    private val projections = HashMap<String, Projection>()
    private var injectedActivationFailure: FailureKey? = null

    override fun descriptor(): RetrievalIndexProviderDescriptor = providerDescriptor

    fun failOnceOnActivation(request: RetrievalIndexActivationRequest) {
        synchronized(monitor) {
            check(injectedActivationFailure == null) { "Only one activation failure may be armed at a time." }
            injectedActivationFailure = FailureKey(request.requestId.value, request.digest)
        }
    }

    override fun stage(request: RetrievalIndexStageBatch): CompletionStage<RetrievalIndexStageReceipt> = completed {
        synchronized(monitor) {
            requireDescriptor(request.manifest.descriptor)
            replay("stage", request.requestId, request.digest)?.let { existing ->
                @Suppress("UNCHECKED_CAST")
                return@synchronized existing as RetrievalIndexStageReceipt
            }
            val receipt = RetrievalIndexStageReceipt.staged(
                request,
                providerRequestId("stage"),
                request.requestedAtEpochMilli + 1L,
            )
            val batches = stagedReceipts.getOrPut(request.manifest.digest) { HashMap() }
            batches.getOrPut(request.batchOrdinal) { HashSet() }.add(receipt.digest)
            record("stage", request.requestId, request.digest, receipt)
            receipt
        }
    }

    override fun seal(request: RetrievalIndexSealRequest): CompletionStage<RetrievalIndexSealReceipt> = completed {
        synchronized(monitor) {
            requireDescriptor(request.manifest.descriptor)
            replay("seal", request.requestId, request.digest)?.let { existing ->
                @Suppress("UNCHECKED_CAST")
                return@synchronized existing as RetrievalIndexSealReceipt
            }
            val batches = stagedReceipts[request.manifest.digest] ?: bindingFailure()
            request.stageReceipts.forEach { receipt ->
                if (batches[receipt.batchOrdinal]?.contains(receipt.digest) != true) bindingFailure()
            }
            val receipt = RetrievalIndexSealReceipt.sealed(
                request,
                providerRequestId("seal"),
                request.requestedAtEpochMilli + 1L,
            )
            sealedReceipts[request.digest] = receipt.digest
            record("seal", request.requestId, request.digest, receipt)
            receipt
        }
    }

    override fun activate(
        request: RetrievalIndexActivationRequest,
    ): CompletionStage<RetrievalIndexActivationReceipt> = completed {
        synchronized(monitor) {
            val manifest = request.sealReceipt.request.manifest
            requireDescriptor(manifest.descriptor)
            replay("activate", request.requestId, request.digest)?.let { existing ->
                @Suppress("UNCHECKED_CAST")
                return@synchronized existing as RetrievalIndexActivationReceipt
            }
            if (sealedReceipts[request.sealReceipt.request.digest] != request.sealReceipt.digest) bindingFailure()
            val injected = injectedActivationFailure
            if (injected != null && injected.matches(request)) {
                injectedActivationFailure = null
                throw RetrievalProviderException(
                    RetrievalFailureCode.TEMPORARY_UNAVAILABLE,
                    RetrievalRetryability.RETRYABLE,
                    providerRequestId("temporary"),
                )
            }
            val projection = projections.getOrPut(manifest.source.digest) { Projection() }
            if (projection.activeGenerationId != request.expectedPreviousGenerationId ||
                projection.revision != request.expectedProjectionRevision
            ) projectionConflict()
            val receipt = RetrievalIndexActivationReceipt.activated(
                request,
                projection.activeGenerationId,
                projection.revision,
                providerRequestId("activate"),
                request.requestedAtEpochMilli + 1L,
            )
            projection.activeGenerationId = receipt.activeGenerationId
            projection.revision = receipt.activeProjectionRevision
            projection.policyRevision = manifest.authorizationPolicyRevision
            projection.policyScopeDigest = manifest.authorizationScopeDigest
            projection.tombstoned = false
            record("activate", request.requestId, request.digest, receipt)
            receipt
        }
    }

    override fun mutate(request: RetrievalIndexMutationRequest): CompletionStage<RetrievalIndexMutationReceipt> = completed {
        synchronized(monitor) {
            requireDescriptor(request.descriptor)
            replay("mutate", request.requestId, request.digest)?.let { existing ->
                @Suppress("UNCHECKED_CAST")
                return@synchronized existing as RetrievalIndexMutationReceipt
            }
            val projection = projections[request.source.digest] ?: projectionConflict()
            if (projection.activeGenerationId != request.activeGenerationId ||
                projection.revision != request.expectedProjectionRevision
            ) projectionConflict()
            val receipt = RetrievalIndexMutationReceipt.applied(
                request,
                projection.revision,
                1,
                request.policyScopeDigest,
                providerRequestId("mutate"),
                request.requestedAtEpochMilli + 1L,
            )
            projection.revision = receipt.activeProjectionRevision
            if (request.kind == RetrievalIndexMutationKind.TOMBSTONE) {
                projection.activeGenerationId = null
                projection.policyRevision = null
                projection.policyScopeDigest = null
                projection.tombstoned = true
            } else {
                projection.policyRevision = request.policyRevision
                projection.policyScopeDigest = request.policyScopeDigest
            }
            record("mutate", request.requestId, request.digest, receipt)
            receipt
        }
    }

    override fun state(request: RetrievalIndexStateRequest): CompletionStage<RetrievalIndexState> = completed {
        synchronized(monitor) {
            requireDescriptor(request.descriptor)
            val projection = projections[request.source.digest] ?: Projection()
            RetrievalIndexState.observed(
                request,
                projection.revision,
                request.requestedAtEpochMilli + 1L,
                projection.activeGenerationId,
                projection.policyRevision,
                projection.policyScopeDigest,
                projection.tombstoned,
            )
        }
    }

    private fun requireDescriptor(descriptor: RetrievalIndexProviderDescriptor) {
        if (descriptor.digest != providerDescriptor.digest) bindingFailure()
    }

    private fun replay(operation: String, requestId: Identifier, digest: String): Any? {
        val existing = ledger[ledgerKey(operation, requestId)] ?: return null
        if (existing.requestDigest != digest) {
            throw RetrievalProviderException(
                RetrievalFailureCode.INDEX_REQUEST_REPLAY_MISMATCH,
                RetrievalRetryability.NOT_RETRYABLE,
                providerRequestId("replay"),
            )
        }
        return existing.receipt
    }

    private fun record(operation: String, requestId: Identifier, digest: String, receipt: Any) {
        check(ledger.put(ledgerKey(operation, requestId), LedgerEntry(digest, receipt)) == null)
    }

    private fun ledgerKey(operation: String, requestId: Identifier): String = "$operation:${requestId.value}"

    private fun providerRequestId(operation: String): String = "fixture-$operation-${sequence.incrementAndGet()}"

    private fun bindingFailure(): Nothing = throw RetrievalProviderException(
        RetrievalFailureCode.INDEX_PROVIDER_BINDING_MISMATCH,
        RetrievalRetryability.NOT_RETRYABLE,
        providerRequestId("binding"),
    )

    private fun projectionConflict(): Nothing = throw RetrievalProviderException(
        RetrievalFailureCode.INDEX_PROJECTION_CONFLICT,
        RetrievalRetryability.NOT_RETRYABLE,
        providerRequestId("conflict"),
    )

    private fun <T> completed(action: () -> T): CompletionStage<T> {
        val result = CompletableFuture<T>()
        try {
            result.complete(action())
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
        return result
    }

    private class LedgerEntry(val requestDigest: String, val receipt: Any)

    private class FailureKey(private val requestId: String, private val requestDigest: String) {
        fun matches(request: RetrievalIndexActivationRequest): Boolean =
            requestId == request.requestId.value && requestDigest == request.digest
    }

    private class Projection(
        var activeGenerationId: String? = null,
        var revision: Long = 0L,
        var policyRevision: String? = null,
        var policyScopeDigest: String? = null,
        var tombstoned: Boolean = false,
    )
}
