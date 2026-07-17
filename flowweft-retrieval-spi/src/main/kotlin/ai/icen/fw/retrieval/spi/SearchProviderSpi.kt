package ai.icen.fw.retrieval.spi

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.AuthorizedRetrievalCandidate
import ai.icen.fw.retrieval.api.CandidateRetriever
import ai.icen.fw.retrieval.api.RetrievalContentHydrator
import ai.icen.fw.retrieval.api.RetrievalCall
import ai.icen.fw.retrieval.api.RetrievalMode
import ai.icen.fw.retrieval.api.RetrievalStageProviderBinding
import ai.icen.fw.retrieval.api.RetrievedContent

/** Common high-level candidate SPI. Providers still receive only API-gated executable requests. */
interface RetrievalCandidateProvider : CandidateRetriever

interface TextSearchProvider : RetrievalCandidateProvider

interface VectorSearchProvider : RetrievalCandidateProvider

interface HybridSearchProvider : RetrievalCandidateProvider

class RetrievalContentProviderDescriptor private constructor(
    providerId: String,
    providerInstanceId: String,
    configurationDigest: String,
    capabilityDigest: String,
    providerRevision: String,
    val maximumContentCodePoints: Int,
    val sendsContentOffHost: Boolean,
    val supportsCancellation: Boolean,
) {
    val providerId: String = requireSpiText(providerId, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Provider id is invalid.")
    val providerInstanceId: String = requireSpiText(
        providerInstanceId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Provider instance id is invalid.",
    )
    val providerRevision: String = requireSpiText(
        providerRevision,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Provider revision is invalid.",
    )
    val configurationDigest: String = requireSpiSha256(
        configurationDigest,
        "Content provider configuration digest is invalid.",
    )
    val capabilityDigest: String = requireSpiSha256(
        capabilityDigest,
        "Content provider capability digest is invalid.",
    )
    val digest: String
    val binding: RetrievalStageProviderBinding

    init {
        require(maximumContentCodePoints in 1..RetrievalSpiLimits.MAX_TEXT_CODE_POINTS) {
            "Content provider size limit is invalid."
        }
        digest = RetrievalSpiDigest("flowweft-retrieval-content-provider-descriptor-v2")
            .text(this.providerId)
            .text(this.providerInstanceId)
            .text(this.configurationDigest)
            .text(this.capabilityDigest)
            .text(this.providerRevision)
            .integer(maximumContentCodePoints)
            .boolean(sendsContentOffHost)
            .boolean(supportsCancellation)
            .finish()
        binding = RetrievalStageProviderBinding.create(
            "content-hydration",
            this.providerId,
            this.providerInstanceId,
            this.configurationDigest,
            this.capabilityDigest,
            this.providerRevision,
            digest,
            supportsCancellation,
        )
    }

    override fun toString(): String = "RetrievalContentProviderDescriptor(providerId=$providerId)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerInstanceId: String,
            configurationDigest: String,
            capabilityDigest: String,
            providerRevision: String,
            maximumContentCodePoints: Int,
            sendsContentOffHost: Boolean,
            supportsCancellation: Boolean,
        ): RetrievalContentProviderDescriptor = RetrievalContentProviderDescriptor(
            providerId,
            providerInstanceId,
            configurationDigest,
            capabilityDigest,
            providerRevision,
            maximumContentCodePoints,
            sendsContentOffHost,
            supportsCancellation,
        )
    }
}

interface RetrievalContentProvider : RetrievalContentHydrator {
    fun descriptor(): RetrievalContentProviderDescriptor
}

class RerankerDescriptor private constructor(
    providerId: String,
    providerInstanceId: String,
    configurationDigest: String,
    capabilityDigest: String,
    providerRevision: String,
    modelId: String,
    modelVersion: String,
    val maximumItems: Int,
    val maximumContentCodePointsPerItem: Int,
    val sendsQueryOrContentOffHost: Boolean,
    val supportsCancellation: Boolean,
) {
    val providerId: String = requireSpiText(providerId, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Provider id is invalid.")
    val providerInstanceId: String = requireSpiText(
        providerInstanceId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Provider instance id is invalid.",
    )
    val providerRevision: String = requireSpiText(
        providerRevision,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Provider revision is invalid.",
    )
    val configurationDigest: String = requireSpiSha256(
        configurationDigest,
        "Reranker configuration digest is invalid.",
    )
    val capabilityDigest: String = requireSpiSha256(
        capabilityDigest,
        "Reranker capability digest is invalid.",
    )
    val modelId: String = requireSpiText(modelId, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Reranker model id is invalid.")
    val modelVersion: String = requireSpiText(
        modelVersion,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Reranker model version is invalid.",
    )
    val digest: String
    val binding: RetrievalStageProviderBinding

    init {
        require(maximumItems in 1..RetrievalSpiLimits.MAX_RERANK_ITEMS) { "Reranker item limit is invalid." }
        require(maximumContentCodePointsPerItem in 1..RetrievalSpiLimits.MAX_TEXT_CODE_POINTS) {
            "Reranker content limit is invalid."
        }
        digest = RetrievalSpiDigest("flowweft-reranker-descriptor-v2")
            .text(this.providerId)
            .text(this.providerInstanceId)
            .text(this.configurationDigest)
            .text(this.capabilityDigest)
            .text(this.providerRevision)
            .text(this.modelId)
            .text(this.modelVersion)
            .integer(maximumItems)
            .integer(maximumContentCodePointsPerItem)
            .boolean(sendsQueryOrContentOffHost)
            .boolean(supportsCancellation)
            .finish()
        binding = RetrievalStageProviderBinding.create(
            "rerank",
            this.providerId,
            this.providerInstanceId,
            this.configurationDigest,
            this.capabilityDigest,
            this.providerRevision,
            digest,
            supportsCancellation,
        )
    }

    override fun toString(): String = "RerankerDescriptor(providerId=$providerId, modelId=$modelId)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerInstanceId: String,
            configurationDigest: String,
            capabilityDigest: String,
            providerRevision: String,
            modelId: String,
            modelVersion: String,
            maximumItems: Int,
            maximumContentCodePointsPerItem: Int,
            sendsQueryOrContentOffHost: Boolean,
            supportsCancellation: Boolean,
        ): RerankerDescriptor = RerankerDescriptor(
            providerId,
            providerInstanceId,
            configurationDigest,
            capabilityDigest,
            providerRevision,
            modelId,
            modelVersion,
            maximumItems,
            maximumContentCodePointsPerItem,
            sendsQueryOrContentOffHost,
            supportsCancellation,
        )
    }
}

/** Pairing proves a reranker sees content only after an authoritative candidate authorization. */
class RerankItem private constructor(
    val candidate: AuthorizedRetrievalCandidate,
    val content: RetrievedContent,
) {
    val digest: String

    init {
        val evidence = candidate.resolvedCandidate.candidate.evidence
        require(content.evidenceDigest == evidence.digest && content.sourceSha256 == evidence.sourceSha256) {
            "Rerank content does not match the authorized candidate."
        }
        digest = RetrievalSpiDigest("flowweft-rerank-item-v1")
            .text(candidate.digest)
            .text(content.digest)
            .finish()
    }

    override fun toString(): String = "RerankItem(<redacted>)"

    companion object {
        @JvmStatic
        fun of(candidate: AuthorizedRetrievalCandidate, content: RetrievedContent): RerankItem =
            RerankItem(candidate, content)
    }
}

class RerankRequest private constructor(
    val requestId: Identifier,
    val descriptor: RerankerDescriptor,
    query: String,
    items: Collection<RerankItem>,
    val maximumResults: Int,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
    val egressAllowed: Boolean,
) {
    val query: String = requireSpiContent(query, 8_192, "Rerank query is invalid.")
    val items: List<RerankItem> = immutableSpiList(
        items,
        RetrievalSpiLimits.MAX_RERANK_ITEMS,
        "Rerank request contains too many items.",
    )
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Rerank request id is invalid.")
        require(this.items.isNotEmpty() && this.items.size <= descriptor.maximumItems) {
            "Rerank item count exceeds provider capability."
        }
        require(this.items.map { item -> item.candidate.digest }.toSet().size == this.items.size) {
            "Rerank candidates must be unique."
        }
        require(this.items.all { item ->
            item.content.text.codePointCount(0, item.content.text.length) <= descriptor.maximumContentCodePointsPerItem
        }) { "Rerank content exceeds provider capability." }
        require(maximumResults in 1..this.items.size) { "Rerank result limit is invalid." }
        requireSpiTime(requestedAtEpochMilli, "Rerank request time is invalid.")
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Rerank deadline must follow request time." }
        require(!descriptor.sendsQueryOrContentOffHost || egressAllowed) {
            "Reranker egress is forbidden by execution policy."
        }
        this.items.forEach { item ->
            require(requestedAtEpochMilli >= item.content.hydratedAtEpochMilli) {
                "Rerank request predates content hydration."
            }
            item.candidate.requireUsableAt(requestedAtEpochMilli)
            require(deadlineEpochMilli <= item.candidate.expiresAtEpochMilli) {
                "Rerank deadline exceeds candidate authorization."
            }
        }
        val writer = RetrievalSpiDigest("flowweft-rerank-request-v1")
            .text(requestId.value)
            .text(descriptor.digest)
            .text(sha256Spi(this.query))
            .integer(this.items.size)
        this.items.forEach { item -> writer.text(item.digest) }
        digest = writer.integer(maximumResults)
            .long(requestedAtEpochMilli)
            .long(deadlineEpochMilli)
            .boolean(egressAllowed)
            .finish()
    }

    override fun toString(): String = "RerankRequest(items=${items.size})"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            descriptor: RerankerDescriptor,
            query: String,
            items: Collection<RerankItem>,
            maximumResults: Int,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
            egressAllowed: Boolean,
        ): RerankRequest = RerankRequest(
            requestId,
            descriptor,
            query,
            items,
            maximumResults,
            requestedAtEpochMilli,
            deadlineEpochMilli,
            egressAllowed,
        )
    }
}

class RerankScore private constructor(
    candidateDigest: String,
    val score: Double,
    providerEvidenceDigest: String,
) {
    val candidateDigest: String = requireSpiSha256(candidateDigest, "Rerank candidate digest is invalid.")
    val providerEvidenceDigest: String = requireSpiSha256(
        providerEvidenceDigest,
        "Rerank provider evidence digest is invalid.",
    )
    val digest: String

    init {
        require(score.isFinite()) { "Rerank score must be finite." }
        digest = RetrievalSpiDigest("flowweft-rerank-score-v1")
            .text(this.candidateDigest)
            .floating(score)
            .text(this.providerEvidenceDigest)
            .finish()
    }

    override fun toString(): String = "RerankScore(<redacted>)"

    companion object {
        @JvmStatic
        fun of(item: RerankItem, score: Double, providerEvidenceDigest: String): RerankScore =
            RerankScore(item.candidate.digest, score, providerEvidenceDigest)
    }
}

class RerankResult private constructor(
    val requestId: Identifier,
    requestDigest: String,
    descriptorDigest: String,
    providerBindingDigest: String,
    scores: Collection<RerankScore>,
    providerRequestId: String,
    val completedAtEpochMilli: Long,
) {
    val requestDigest: String = requireSpiSha256(requestDigest, "Rerank request digest is invalid.")
    val descriptorDigest: String = requireSpiSha256(descriptorDigest, "Reranker descriptor digest is invalid.")
    val providerBindingDigest: String = requireSpiSha256(
        providerBindingDigest,
        "Reranker provider binding digest is invalid.",
    )
    val scores: List<RerankScore> = immutableSpiList(
        scores,
        RetrievalSpiLimits.MAX_RERANK_ITEMS,
        "Rerank result contains too many scores.",
    )
    val providerRequestId: String = requireSpiText(
        providerRequestId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Reranker provider request id is invalid.",
    )
    val digest: String

    init {
        val writer = RetrievalSpiDigest("flowweft-rerank-result-v1")
            .text(requestId.value)
            .text(this.requestDigest)
            .text(this.descriptorDigest)
            .text(this.providerBindingDigest)
            .integer(this.scores.size)
        this.scores.forEach { score -> writer.text(score.digest) }
        digest = writer.text(this.providerRequestId).long(completedAtEpochMilli).finish()
    }

    override fun toString(): String = "RerankResult(scores=${scores.size})"

    companion object {
        @JvmStatic
        fun success(
            request: RerankRequest,
            scores: Collection<RerankScore>,
            providerRequestId: String,
            completedAtEpochMilli: Long,
        ): RerankResult {
            require(completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
                "Reranking completed outside its request window."
            }
            request.items.forEach { item -> item.candidate.requireUsableAt(completedAtEpochMilli) }
            val snapshot = immutableSpiList(
                scores,
                request.maximumResults,
                "Rerank result exceeds its requested limit.",
            )
            require(snapshot.isNotEmpty()) { "Rerank result must not be empty." }
            require(snapshot.map { score -> score.candidateDigest }.toSet().size == snapshot.size) {
                "Rerank result contains duplicate candidates."
            }
            val allowed = request.items.map { item -> item.candidate.digest }.toSet()
            require(snapshot.all { score -> score.candidateDigest in allowed }) {
                "Reranker introduced a candidate that was not authorized input."
            }
            require(snapshot.zipWithNext().all { (left, right) -> left.score >= right.score }) {
                "Rerank scores must be in non-increasing rank order."
            }
            return RerankResult(
                request.requestId,
                request.digest,
                request.descriptor.digest,
                request.descriptor.binding.digest,
                snapshot,
                providerRequestId,
                completedAtEpochMilli,
            )
        }
    }
}

interface Reranker {
    fun descriptor(): RerankerDescriptor
    fun rerank(request: RerankRequest): RetrievalCall<RerankResult>
}

/** Static checks used by plugin inventory and provider contract suites. */
object RetrievalProviderContracts {
    @JvmStatic
    fun requireText(provider: TextSearchProvider) {
        require(provider.descriptor().supportedModes.contains(RetrievalMode.FULL_TEXT)) {
            "Text provider must advertise full-text mode."
        }
    }

    @JvmStatic
    fun requireVector(provider: VectorSearchProvider) {
        require(provider.descriptor().supportedModes.contains(RetrievalMode.VECTOR)) {
            "Vector provider must advertise vector mode."
        }
    }

    @JvmStatic
    fun requireHybrid(provider: HybridSearchProvider) {
        require(provider.descriptor().supportedModes.contains(RetrievalMode.HYBRID)) {
            "Hybrid provider must advertise hybrid mode."
        }
    }
}
