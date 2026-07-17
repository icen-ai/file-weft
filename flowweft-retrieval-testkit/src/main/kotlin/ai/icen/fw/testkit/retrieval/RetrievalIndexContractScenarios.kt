package ai.icen.fw.testkit.retrieval

import ai.icen.fw.retrieval.spi.RetrievalIndexActivationRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexSealRequest
import ai.icen.fw.retrieval.spi.RetrievalIndexStageBatch
import ai.icen.fw.retrieval.spi.RetrievalIndexStateRequest
import java.util.ArrayList
import java.util.Collections

/** Immutable input for a linearizable activation race against one authoritative projection. */
class RetrievalIndexActivationRaceScenario private constructor(
    activationRequests: List<RetrievalIndexActivationRequest>,
    val baselineStateRequest: RetrievalIndexStateRequest,
    val observedStateRequest: RetrievalIndexStateRequest,
) {
    val activationRequests: List<RetrievalIndexActivationRequest> =
        Collections.unmodifiableList(ArrayList(activationRequests))

    init {
        require(this.activationRequests.size >= 2) { "An index activation race requires at least two contenders." }
        val first = this.activationRequests.first()
        require(this.activationRequests.map { request -> request.requestId }.toSet().size == this.activationRequests.size) {
            "Index activation race request identifiers must be unique."
        }
        require(this.activationRequests.map { request -> request.digest }.toSet().size == this.activationRequests.size) {
            "Index activation race requests must have unique request digests."
        }
        require(
            this.activationRequests.map { request -> request.sealReceipt.request.manifest.generationId }
                .toSet().size == this.activationRequests.size,
        ) { "Index activation race generations must be unique." }
        require(this.activationRequests.all { request ->
            request.expectedPreviousGenerationId == first.expectedPreviousGenerationId &&
                request.expectedProjectionRevision == first.expectedProjectionRevision &&
                request.sealReceipt.request.manifest.source.digest == first.sealReceipt.request.manifest.source.digest &&
                request.sealReceipt.request.manifest.descriptor.digest ==
                first.sealReceipt.request.manifest.descriptor.digest
        }) { "Index activation race contenders must share one provider, source, and CAS baseline." }
        requireStateRequestsMatch(first, baselineStateRequest, observedStateRequest)
    }

    companion object {
        @JvmStatic
        fun of(
            activationRequests: List<RetrievalIndexActivationRequest>,
            baselineStateRequest: RetrievalIndexStateRequest,
            observedStateRequest: RetrievalIndexStateRequest,
        ): RetrievalIndexActivationRaceScenario = RetrievalIndexActivationRaceScenario(
            activationRequests,
            baselineStateRequest,
            observedStateRequest,
        )
    }
}

/** Immutable input for concurrent, exact replays of one activation request. */
class RetrievalIndexActivationReplayScenario private constructor(
    val activationRequest: RetrievalIndexActivationRequest,
    val replayCount: Int,
    val baselineStateRequest: RetrievalIndexStateRequest,
    val observedStateRequest: RetrievalIndexStateRequest,
) {
    init {
        require(replayCount >= 2) { "An index activation replay scenario requires at least two calls." }
        requireStateRequestsMatch(activationRequest, baselineStateRequest, observedStateRequest)
    }

    companion object {
        @JvmStatic
        fun of(
            activationRequest: RetrievalIndexActivationRequest,
            replayCount: Int,
            baselineStateRequest: RetrievalIndexStateRequest,
            observedStateRequest: RetrievalIndexStateRequest,
        ): RetrievalIndexActivationReplayScenario = RetrievalIndexActivationReplayScenario(
            activationRequest,
            replayCount,
            baselineStateRequest,
            observedStateRequest,
        )
    }
}

/** Same operation/request id bound to two different request digests. */
class RetrievalIndexActivationReplayMismatchScenario private constructor(
    val acceptedRequest: RetrievalIndexActivationRequest,
    val conflictingRequest: RetrievalIndexActivationRequest,
    val baselineStateRequest: RetrievalIndexStateRequest,
    val observedStateRequest: RetrievalIndexStateRequest,
) {
    init {
        require(acceptedRequest.requestId == conflictingRequest.requestId) {
            "Index replay mismatch requests must reuse the same request identifier."
        }
        require(acceptedRequest.digest != conflictingRequest.digest) {
            "Index replay mismatch requests must have different request digests."
        }
        require(
            acceptedRequest.expectedPreviousGenerationId == conflictingRequest.expectedPreviousGenerationId &&
                acceptedRequest.expectedProjectionRevision == conflictingRequest.expectedProjectionRevision,
        ) { "Index replay mismatch requests must share one CAS baseline." }
        val acceptedManifest = acceptedRequest.sealReceipt.request.manifest
        val conflictingManifest = conflictingRequest.sealReceipt.request.manifest
        require(
            acceptedManifest.source.digest == conflictingManifest.source.digest &&
                acceptedManifest.descriptor.digest == conflictingManifest.descriptor.digest,
        ) { "Index replay mismatch requests must target the same provider and source." }
        requireStateRequestsMatch(acceptedRequest, baselineStateRequest, observedStateRequest)
    }

    companion object {
        @JvmStatic
        fun of(
            acceptedRequest: RetrievalIndexActivationRequest,
            conflictingRequest: RetrievalIndexActivationRequest,
            baselineStateRequest: RetrievalIndexStateRequest,
            observedStateRequest: RetrievalIndexStateRequest,
        ): RetrievalIndexActivationReplayMismatchScenario = RetrievalIndexActivationReplayMismatchScenario(
            acceptedRequest,
            conflictingRequest,
            baselineStateRequest,
            observedStateRequest,
        )
    }
}

/**
 * Foreign inputs used to prove fail-closed binding validation before side effects.
 *
 * The stage request carries a foreign descriptor. The seal and activation requests keep the
 * provider's own descriptor but must carry structurally valid receipts that the provider under
 * test never accepted. This distinguishes descriptor validation from durable receipt binding.
 */
class RetrievalIndexProviderBindingMismatchScenario private constructor(
    val foreignStageRequest: RetrievalIndexStageBatch,
    val foreignSealRequest: RetrievalIndexSealRequest,
    val foreignActivationRequest: RetrievalIndexActivationRequest,
    val baselineStateRequest: RetrievalIndexStateRequest,
    val observedStateRequest: RetrievalIndexStateRequest,
) {
    init {
        require(
            baselineStateRequest.descriptor.digest == observedStateRequest.descriptor.digest &&
                baselineStateRequest.source.digest == observedStateRequest.source.digest &&
                baselineStateRequest.requestId != observedStateRequest.requestId,
        ) { "Index binding failure observations must share one authoritative state scope." }
        val authoritativeDescriptorDigest = baselineStateRequest.descriptor.digest
        val authoritativeSourceDigest = baselineStateRequest.source.digest
        require(foreignStageRequest.manifest.source.digest == authoritativeSourceDigest) {
            "Foreign index stage and authoritative observations must address the same source."
        }
        require(foreignStageRequest.manifest.descriptor.digest != authoritativeDescriptorDigest) {
            "Index binding mismatch scenario must use a genuinely foreign provider descriptor."
        }
        require(
            foreignSealRequest.manifest.source.digest == authoritativeSourceDigest &&
                foreignSealRequest.manifest.descriptor.digest == authoritativeDescriptorDigest,
        ) { "Foreign index seal evidence must target the authoritative provider and source." }
        val activationManifest = foreignActivationRequest.sealReceipt.request.manifest
        require(
            activationManifest.source.digest == authoritativeSourceDigest &&
                activationManifest.descriptor.digest == authoritativeDescriptorDigest,
        ) { "Foreign index activation evidence must target the authoritative provider and source." }
        require(
            listOf(
                foreignStageRequest.requestId,
                foreignSealRequest.requestId,
                foreignActivationRequest.requestId,
            ).toSet().size == 3,
        ) { "Foreign index binding operations must use unique request identifiers." }
    }

    companion object {
        @JvmStatic
        fun of(
            foreignStageRequest: RetrievalIndexStageBatch,
            foreignSealRequest: RetrievalIndexSealRequest,
            foreignActivationRequest: RetrievalIndexActivationRequest,
            baselineStateRequest: RetrievalIndexStateRequest,
            observedStateRequest: RetrievalIndexStateRequest,
        ): RetrievalIndexProviderBindingMismatchScenario = RetrievalIndexProviderBindingMismatchScenario(
            foreignStageRequest,
            foreignSealRequest,
            foreignActivationRequest,
            baselineStateRequest,
            observedStateRequest,
        )
    }
}

/** Fault-injected activation with before/after state observations for failure evidence. */
class RetrievalIndexActivationFailureScenario private constructor(
    val activationRequest: RetrievalIndexActivationRequest,
    val baselineStateRequest: RetrievalIndexStateRequest,
    val observedStateRequest: RetrievalIndexStateRequest,
) {
    init {
        requireStateRequestsMatch(activationRequest, baselineStateRequest, observedStateRequest)
    }

    companion object {
        @JvmStatic
        fun of(
            activationRequest: RetrievalIndexActivationRequest,
            baselineStateRequest: RetrievalIndexStateRequest,
            observedStateRequest: RetrievalIndexStateRequest,
        ): RetrievalIndexActivationFailureScenario = RetrievalIndexActivationFailureScenario(
            activationRequest,
            baselineStateRequest,
            observedStateRequest,
        )
    }
}

private fun requireStateRequestsMatch(
    activationRequest: RetrievalIndexActivationRequest,
    baselineStateRequest: RetrievalIndexStateRequest,
    observedStateRequest: RetrievalIndexStateRequest,
) {
    requireStateRequestMatches(activationRequest, baselineStateRequest)
    requireStateRequestMatches(activationRequest, observedStateRequest)
    require(baselineStateRequest.requestId != observedStateRequest.requestId) {
        "Index baseline and observed state requests must have unique identifiers."
    }
}

private fun requireStateRequestMatches(
    activationRequest: RetrievalIndexActivationRequest,
    stateRequest: RetrievalIndexStateRequest,
) {
    val manifest = activationRequest.sealReceipt.request.manifest
    require(
        stateRequest.descriptor.digest == manifest.descriptor.digest &&
            stateRequest.source.digest == manifest.source.digest,
    ) { "Index state request does not belong to the activation provider and source." }
}
