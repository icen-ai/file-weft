package ai.icen.fw.retrieval.spi

import ai.icen.fw.core.id.Identifier
import java.util.concurrent.CompletionStage

/** Stable similarity semantic; extension values remain fail-closed until a consumer supports them. */
class EmbeddingSimilarity private constructor(code: String) {
    val code: String = requireSpiText(code, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Embedding similarity is invalid.")

    override fun equals(other: Any?): Boolean = this === other || other is EmbeddingSimilarity && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "EmbeddingSimilarity(<redacted>)"

    companion object {
        @JvmField val COSINE = EmbeddingSimilarity("cosine")
        @JvmField val DOT_PRODUCT = EmbeddingSimilarity("dot-product")
        @JvmField val EUCLIDEAN = EmbeddingSimilarity("euclidean")

        @JvmStatic
        fun of(code: String): EmbeddingSimilarity = when (code) {
            COSINE.code -> COSINE
            DOT_PRODUCT.code -> DOT_PRODUCT
            EUCLIDEAN.code -> EUCLIDEAN
            else -> EmbeddingSimilarity(code)
        }
    }
}

class EmbeddingProviderDescriptor private constructor(
    providerId: String,
    providerInstanceId: String,
    providerRevision: String,
    modelId: String,
    modelVersion: String,
    val dimensions: Int,
    val maximumBatchSize: Int,
    val maximumInputCodePoints: Int,
    val similarity: EmbeddingSimilarity,
    val sendsContentOffHost: Boolean,
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
    val modelId: String = requireSpiText(modelId, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Embedding model id is invalid.")
    val modelVersion: String = requireSpiText(
        modelVersion,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Embedding model version is invalid.",
    )
    val digest: String

    init {
        require(dimensions in 1..RetrievalSpiLimits.MAX_VECTOR_DIMENSIONS) {
            "Embedding dimensions are invalid."
        }
        require(maximumBatchSize in 1..RetrievalSpiLimits.MAX_EMBEDDING_BATCH) {
            "Embedding batch limit is invalid."
        }
        require(maximumInputCodePoints in 1..RetrievalSpiLimits.MAX_TEXT_CODE_POINTS) {
            "Embedding input limit is invalid."
        }
        require(
            similarity == EmbeddingSimilarity.COSINE ||
                similarity == EmbeddingSimilarity.DOT_PRODUCT ||
                similarity == EmbeddingSimilarity.EUCLIDEAN,
        ) { "Unknown embedding similarity is unsupported." }
        digest = RetrievalSpiDigest("flowweft-embedding-provider-descriptor-v1")
            .text(this.providerId)
            .text(this.providerInstanceId)
            .text(this.providerRevision)
            .text(this.modelId)
            .text(this.modelVersion)
            .integer(dimensions)
            .integer(maximumBatchSize)
            .integer(maximumInputCodePoints)
            .text(similarity.code)
            .boolean(sendsContentOffHost)
            .finish()
    }

    override fun toString(): String = "EmbeddingProviderDescriptor(providerId=$providerId, modelId=$modelId)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerInstanceId: String,
            providerRevision: String,
            modelId: String,
            modelVersion: String,
            dimensions: Int,
            maximumBatchSize: Int,
            maximumInputCodePoints: Int,
            similarity: EmbeddingSimilarity,
            sendsContentOffHost: Boolean,
        ): EmbeddingProviderDescriptor = EmbeddingProviderDescriptor(
            providerId,
            providerInstanceId,
            providerRevision,
            modelId,
            modelVersion,
            dimensions,
            maximumBatchSize,
            maximumInputCodePoints,
            similarity,
            sendsContentOffHost,
        )
    }
}

class EmbeddingInput private constructor(
    val chunkOrdinal: Int,
    chunkDigest: String,
    text: String,
    textSha256: String,
) {
    val chunkDigest: String = requireSpiSha256(chunkDigest, "Embedding chunk digest is invalid.")
    val text: String = requireSpiContent(text, RetrievalSpiLimits.MAX_TEXT_CODE_POINTS, "Embedding text is invalid.")
    val textSha256: String = requireSpiSha256(textSha256, "Embedding text digest is invalid.")
    val digest: String

    init {
        require(chunkOrdinal >= 0) { "Embedding chunk ordinal must not be negative." }
        require(sha256Spi(this.text) == this.textSha256) { "Embedding text digest does not match its content." }
        digest = RetrievalSpiDigest("flowweft-embedding-input-v1")
            .integer(chunkOrdinal)
            .text(this.chunkDigest)
            .text(this.textSha256)
            .finish()
    }

    override fun toString(): String = "EmbeddingInput(chunkOrdinal=$chunkOrdinal)"

    companion object {
        @JvmStatic
        fun from(chunk: ContentChunk): EmbeddingInput = EmbeddingInput(
            chunk.ordinal,
            chunk.digest,
            chunk.text,
            chunk.textSha256,
        )
    }
}

class EmbeddingRequest private constructor(
    val requestId: Identifier,
    val descriptor: EmbeddingProviderDescriptor,
    inputs: Collection<EmbeddingInput>,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val inputs: List<EmbeddingInput> = immutableSpiList(
        inputs,
        RetrievalSpiLimits.MAX_EMBEDDING_BATCH,
        "Embedding request contains too many inputs.",
    )
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Embedding request id is invalid.")
        require(this.inputs.isNotEmpty() && this.inputs.size <= descriptor.maximumBatchSize) {
            "Embedding input batch is invalid."
        }
        require(this.inputs.map { input -> input.chunkOrdinal }.toSet().size == this.inputs.size) {
            "Embedding inputs must reference unique chunks."
        }
        require(this.inputs.all { input ->
            input.text.codePointCount(0, input.text.length) <= descriptor.maximumInputCodePoints
        }) { "Embedding input exceeds provider capability." }
        requireSpiTime(requestedAtEpochMilli, "Embedding request time is invalid.")
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Embedding deadline must follow request time." }
        val writer = RetrievalSpiDigest("flowweft-embedding-request-v1")
            .text(requestId.value)
            .text(descriptor.digest)
            .integer(this.inputs.size)
        this.inputs.forEach { input -> writer.text(input.digest) }
        digest = writer.long(requestedAtEpochMilli).long(deadlineEpochMilli).finish()
    }

    override fun toString(): String = "EmbeddingRequest(inputs=${inputs.size})"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            descriptor: EmbeddingProviderDescriptor,
            inputs: Collection<EmbeddingInput>,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): EmbeddingRequest = EmbeddingRequest(
            requestId,
            descriptor,
            inputs,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

class EmbeddingVector private constructor(
    inputDigest: String,
    values: Collection<Double>,
) {
    val inputDigest: String = requireSpiSha256(inputDigest, "Embedding input digest is invalid.")
    val values: List<Double> = immutableSpiList(
        values,
        RetrievalSpiLimits.MAX_VECTOR_DIMENSIONS,
        "Embedding vector has too many dimensions.",
    )
    val digest: String

    init {
        require(this.values.isNotEmpty()) { "Embedding vector must not be empty." }
        require(this.values.all { value -> value.isFinite() }) { "Embedding vector values must be finite." }
        val writer = RetrievalSpiDigest("flowweft-embedding-vector-v1")
            .text(this.inputDigest)
            .integer(this.values.size)
        this.values.forEach { value -> writer.floating(value) }
        digest = writer.finish()
    }

    override fun toString(): String = "EmbeddingVector(dimensions=${values.size})"

    companion object {
        @JvmStatic
        fun of(input: EmbeddingInput, values: Collection<Double>): EmbeddingVector =
            EmbeddingVector(input.digest, values)
    }
}

class EmbeddingResult private constructor(
    val requestId: Identifier,
    requestDigest: String,
    descriptorDigest: String,
    vectors: Collection<EmbeddingVector>,
    providerRequestId: String,
    val completedAtEpochMilli: Long,
) {
    val requestDigest: String = requireSpiSha256(requestDigest, "Embedding request digest is invalid.")
    val descriptorDigest: String = requireSpiSha256(descriptorDigest, "Embedding descriptor digest is invalid.")
    val vectors: List<EmbeddingVector> = immutableSpiList(
        vectors,
        RetrievalSpiLimits.MAX_EMBEDDING_BATCH,
        "Embedding result contains too many vectors.",
    )
    val providerRequestId: String = requireSpiText(
        providerRequestId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Embedding provider request id is invalid.",
    )
    val digest: String

    init {
        val writer = RetrievalSpiDigest("flowweft-embedding-result-v1")
            .text(requestId.value)
            .text(this.requestDigest)
            .text(this.descriptorDigest)
            .integer(this.vectors.size)
        this.vectors.forEach { vector -> writer.text(vector.digest) }
        digest = writer.text(this.providerRequestId).long(completedAtEpochMilli).finish()
    }

    override fun toString(): String = "EmbeddingResult(vectors=${vectors.size})"

    companion object {
        @JvmStatic
        fun success(
            request: EmbeddingRequest,
            vectors: Collection<EmbeddingVector>,
            providerRequestId: String,
            completedAtEpochMilli: Long,
        ): EmbeddingResult {
            require(completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
                "Embedding completed outside its request window."
            }
            val snapshot = immutableSpiList(
                vectors,
                request.descriptor.maximumBatchSize,
                "Embedding result contains too many vectors.",
            )
            require(snapshot.size == request.inputs.size) { "Embedding result must cover every input exactly once." }
            request.inputs.zip(snapshot).forEach { (input, vector) ->
                require(vector.inputDigest == input.digest) { "Embedding result order or input binding is invalid." }
                require(vector.values.size == request.descriptor.dimensions) {
                    "Embedding vector dimensions do not match the provider descriptor."
                }
            }
            return EmbeddingResult(
                request.requestId,
                request.digest,
                request.descriptor.digest,
                snapshot,
                providerRequestId,
                completedAtEpochMilli,
            )
        }
    }
}

interface EmbeddingProvider {
    fun descriptor(): EmbeddingProviderDescriptor
    fun embed(request: EmbeddingRequest): CompletionStage<EmbeddingResult>
}
