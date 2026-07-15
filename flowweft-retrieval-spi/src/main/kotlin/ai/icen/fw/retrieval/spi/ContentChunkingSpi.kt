package ai.icen.fw.retrieval.spi

import ai.icen.fw.core.id.Identifier
import java.util.concurrent.CompletionStage

class ContentChunkerDescriptor private constructor(
    providerId: String,
    providerInstanceId: String,
    providerRevision: String,
    chunkerVersion: String,
    val maximumInputCodePoints: Int,
    val maximumChunks: Int,
    val maximumChunkCodePoints: Int,
    val maximumOverlapCodePoints: Int,
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
    val chunkerVersion: String = requireSpiText(
        chunkerVersion,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Chunker version is invalid.",
    )
    val digest: String

    init {
        require(maximumInputCodePoints in 1..RetrievalSpiLimits.MAX_TEXT_CODE_POINTS) {
            "Maximum chunker input is invalid."
        }
        require(maximumChunks in 1..RetrievalSpiLimits.MAX_CHUNKS) { "Maximum chunk count is invalid." }
        require(maximumChunkCodePoints in 1..maximumInputCodePoints) { "Maximum chunk size is invalid." }
        require(maximumOverlapCodePoints in 0 until maximumChunkCodePoints) { "Maximum chunk overlap is invalid." }
        digest = RetrievalSpiDigest("flowweft-content-chunker-descriptor-v1")
            .text(this.providerId)
            .text(this.providerInstanceId)
            .text(this.providerRevision)
            .text(this.chunkerVersion)
            .integer(maximumInputCodePoints)
            .integer(maximumChunks)
            .integer(maximumChunkCodePoints)
            .integer(maximumOverlapCodePoints)
            .finish()
    }

    override fun toString(): String = "ContentChunkerDescriptor(providerId=$providerId)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerInstanceId: String,
            providerRevision: String,
            chunkerVersion: String,
            maximumInputCodePoints: Int,
            maximumChunks: Int,
            maximumChunkCodePoints: Int,
            maximumOverlapCodePoints: Int,
        ): ContentChunkerDescriptor = ContentChunkerDescriptor(
            providerId,
            providerInstanceId,
            providerRevision,
            chunkerVersion,
            maximumInputCodePoints,
            maximumChunks,
            maximumChunkCodePoints,
            maximumOverlapCodePoints,
        )
    }
}

class ContentChunkingRequest private constructor(
    val requestId: Identifier,
    val extraction: ContentExtractionResult,
    val descriptor: ContentChunkerDescriptor,
    chunkingProfileDigest: String,
    val maximumChunks: Int,
    val maximumChunkCodePoints: Int,
    val maximumOverlapCodePoints: Int,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val chunkingProfileDigest: String = requireSpiSha256(
        chunkingProfileDigest,
        "Chunking profile digest is invalid.",
    )
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Chunking request id is invalid.")
        val inputCodePoints = extraction.segments.sumOf { segment ->
            segment.text.codePointCount(0, segment.text.length).toLong()
        }
        require(inputCodePoints <= descriptor.maximumInputCodePoints.toLong()) {
            "Extraction exceeds chunker input capability."
        }
        require(maximumChunks in 1..descriptor.maximumChunks) { "Requested chunk count is invalid." }
        require(maximumChunkCodePoints in 1..descriptor.maximumChunkCodePoints) {
            "Requested chunk size is invalid."
        }
        require(maximumOverlapCodePoints in 0..descriptor.maximumOverlapCodePoints &&
            maximumOverlapCodePoints < maximumChunkCodePoints
        ) { "Requested chunk overlap is invalid." }
        requireSpiTime(requestedAtEpochMilli, "Chunking request time is invalid.")
        require(requestedAtEpochMilli >= extraction.completedAtEpochMilli) {
            "Chunking request predates extraction completion."
        }
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Chunking deadline must follow request time." }
        digest = RetrievalSpiDigest("flowweft-content-chunking-request-v1")
            .text(requestId.value)
            .text(extraction.digest)
            .text(descriptor.digest)
            .text(this.chunkingProfileDigest)
            .integer(maximumChunks)
            .integer(maximumChunkCodePoints)
            .integer(maximumOverlapCodePoints)
            .long(requestedAtEpochMilli)
            .long(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "ContentChunkingRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            extraction: ContentExtractionResult,
            descriptor: ContentChunkerDescriptor,
            chunkingProfileDigest: String,
            maximumChunks: Int,
            maximumChunkCodePoints: Int,
            maximumOverlapCodePoints: Int,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): ContentChunkingRequest = ContentChunkingRequest(
            requestId,
            extraction,
            descriptor,
            chunkingProfileDigest,
            maximumChunks,
            maximumChunkCodePoints,
            maximumOverlapCodePoints,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

/** One chunk with exact offsets into one extracted segment; text is untrusted content. */
class ContentChunk private constructor(
    val ordinal: Int,
    val segmentOrdinal: Int,
    val startCodePoint: Int,
    val endCodePointExclusive: Int,
    text: String,
    mediaType: String,
    sourceSha256: String,
    val pageNumber: Int?,
) {
    val text: String = requireSpiContent(text, RetrievalSpiLimits.MAX_TEXT_CODE_POINTS, "Chunk text is invalid.")
    val mediaType: String = requireSpiMediaType(mediaType)
    val sourceSha256: String = requireSpiSha256(sourceSha256, "Chunk source digest is invalid.")
    val textSha256: String = sha256Spi(this.text)
    val digest: String

    init {
        require(ordinal >= 0) { "Chunk ordinal must not be negative." }
        require(segmentOrdinal >= 0) { "Chunk segment ordinal must not be negative." }
        require(startCodePoint >= 0 && endCodePointExclusive > startCodePoint) { "Chunk offsets are invalid." }
        require(pageNumber == null || pageNumber >= 1) { "Chunk page number is invalid." }
        digest = RetrievalSpiDigest("flowweft-content-chunk-v1")
            .integer(ordinal)
            .integer(segmentOrdinal)
            .integer(startCodePoint)
            .integer(endCodePointExclusive)
            .text(textSha256)
            .text(this.mediaType)
            .text(this.sourceSha256)
            .optionalText(pageNumber?.toString())
            .finish()
    }

    override fun toString(): String = "ContentChunk(ordinal=$ordinal, segmentOrdinal=$segmentOrdinal)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            ordinal: Int,
            segmentOrdinal: Int,
            startCodePoint: Int,
            endCodePointExclusive: Int,
            text: String,
            mediaType: String,
            sourceSha256: String,
            pageNumber: Int? = null,
        ): ContentChunk = ContentChunk(
            ordinal,
            segmentOrdinal,
            startCodePoint,
            endCodePointExclusive,
            text,
            mediaType,
            sourceSha256,
            pageNumber,
        )
    }
}

class ContentChunkingResult private constructor(
    val requestId: Identifier,
    requestDigest: String,
    extractionDigest: String,
    descriptorDigest: String,
    chunks: Collection<ContentChunk>,
    val completedAtEpochMilli: Long,
) {
    val requestDigest: String = requireSpiSha256(requestDigest, "Chunking request digest is invalid.")
    val extractionDigest: String = requireSpiSha256(extractionDigest, "Extraction digest is invalid.")
    val descriptorDigest: String = requireSpiSha256(descriptorDigest, "Chunker descriptor digest is invalid.")
    val chunks: List<ContentChunk> = immutableSpiList(
        chunks,
        RetrievalSpiLimits.MAX_CHUNKS,
        "Chunking produced too many chunks.",
    )
    val manifestDigest: String
    val digest: String

    init {
        require(this.chunks.isNotEmpty()) { "Chunking must produce at least one chunk." }
        require(this.chunks.map { chunk -> chunk.ordinal } == this.chunks.indices.toList()) {
            "Chunks must be complete and ordered from zero."
        }
        val manifest = RetrievalSpiDigest("flowweft-content-chunk-manifest-v1").integer(this.chunks.size)
        this.chunks.forEach { chunk -> manifest.text(chunk.digest) }
        manifestDigest = manifest.finish()
        digest = RetrievalSpiDigest("flowweft-content-chunking-result-v1")
            .text(requestId.value)
            .text(this.requestDigest)
            .text(this.extractionDigest)
            .text(this.descriptorDigest)
            .text(manifestDigest)
            .long(completedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ContentChunkingResult(chunks=${chunks.size})"

    companion object {
        @JvmStatic
        fun success(
            request: ContentChunkingRequest,
            chunks: Collection<ContentChunk>,
            completedAtEpochMilli: Long,
        ): ContentChunkingResult {
            require(completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
                "Chunking completed outside its request window."
            }
            val snapshot = immutableSpiList(
                chunks,
                request.maximumChunks,
                "Chunking produced too many chunks.",
            )
            require(snapshot.isNotEmpty()) { "Chunking must produce at least one chunk." }
            require(snapshot.map { chunk -> chunk.ordinal } == snapshot.indices.toList()) {
                "Chunks must be complete and ordered from zero."
            }
            require(snapshot.map { chunk -> chunk.digest }.toSet().size == snapshot.size) {
                "Chunks must not be duplicated."
            }
            require(snapshot.zipWithNext().all { (left, right) ->
                left.segmentOrdinal <= right.segmentOrdinal
            }) { "Chunks must preserve extracted segment order." }

            request.extraction.segments.forEach { segment ->
                val segmentSize = segment.text.codePointCount(0, segment.text.length)
                val segmentChunks = snapshot.filter { chunk -> chunk.segmentOrdinal == segment.ordinal }
                require(segmentChunks.isNotEmpty()) { "Every extracted segment must be covered by chunks." }
                require(segmentChunks.first().startCodePoint == 0 &&
                    segmentChunks.last().endCodePointExclusive == segmentSize
                ) { "Chunk coverage must include the complete extracted segment." }
                var previous: ContentChunk? = null
                segmentChunks.forEach { chunk ->
                    require(chunk.endCodePointExclusive <= segmentSize) { "Chunk exceeds extracted segment bounds." }
                    require(chunk.endCodePointExclusive - chunk.startCodePoint <= request.maximumChunkCodePoints) {
                        "Chunk exceeds its requested size."
                    }
                    require(chunk.text == codePointSlice(segment.text, chunk.startCodePoint, chunk.endCodePointExclusive)) {
                        "Chunk text does not match its exact extracted offsets."
                    }
                    require(chunk.mediaType == segment.mediaType &&
                        chunk.sourceSha256 == request.extraction.sourceSha256
                    ) { "Chunk lineage does not match extracted content." }
                    require(chunk.pageNumber == segment.pageNumber) { "Chunk page does not match extracted content." }
                    previous?.let { prior ->
                        require(chunk.startCodePoint <= prior.endCodePointExclusive) {
                            "Chunk coverage contains a gap."
                        }
                        require(
                            chunk.startCodePoint > prior.startCodePoint &&
                                chunk.endCodePointExclusive > prior.endCodePointExclusive,
                        ) { "Chunks within a segment must make strict forward progress." }
                        val overlap = prior.endCodePointExclusive - chunk.startCodePoint
                        require(overlap <= request.maximumOverlapCodePoints) { "Chunk overlap exceeds its request limit." }
                    }
                    previous = chunk
                }
            }
            require(snapshot.all { chunk -> chunk.segmentOrdinal in request.extraction.segments.indices }) {
                "Chunk references an unknown extracted segment."
            }
            return ContentChunkingResult(
                request.requestId,
                request.digest,
                request.extraction.digest,
                request.descriptor.digest,
                snapshot,
                completedAtEpochMilli,
            )
        }
    }
}

interface ContentChunker {
    fun descriptor(): ContentChunkerDescriptor
    fun chunk(request: ContentChunkingRequest): CompletionStage<ContentChunkingResult>
}
