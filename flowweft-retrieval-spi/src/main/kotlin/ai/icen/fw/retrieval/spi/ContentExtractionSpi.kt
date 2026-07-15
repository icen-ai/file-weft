package ai.icen.fw.retrieval.spi

import ai.icen.fw.core.id.Identifier
import java.io.InputStream
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean

/** Exact immutable lineage of one source object presented to extraction and indexing providers. */
class ContentSourceRef private constructor(
    val tenantId: Identifier,
    val catalogId: Identifier,
    val documentId: Identifier,
    val versionId: Identifier,
    val fileAssetId: Identifier,
    val fileObjectId: Identifier,
    sourceSha256: String,
    val sourceSizeBytes: Long,
    mediaType: String,
    contentRevision: String,
) {
    val sourceSha256: String = requireSpiSha256(sourceSha256, "Source digest is invalid.")
    val mediaType: String = requireSpiMediaType(mediaType)
    val contentRevision: String = requireSpiText(
        contentRevision,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Content revision is invalid.",
    )
    val digest: String

    init {
        requireSpiIdentifier(tenantId, "Tenant identifier is invalid.")
        requireSpiIdentifier(catalogId, "Catalog identifier is invalid.")
        requireSpiIdentifier(documentId, "Document identifier is invalid.")
        requireSpiIdentifier(versionId, "Version identifier is invalid.")
        requireSpiIdentifier(fileAssetId, "File asset identifier is invalid.")
        requireSpiIdentifier(fileObjectId, "File object identifier is invalid.")
        require(sourceSizeBytes >= 0L) { "Source size must not be negative." }
        digest = RetrievalSpiDigest("flowweft-content-source-ref-v1")
            .text(tenantId.value)
            .text(catalogId.value)
            .text(documentId.value)
            .text(versionId.value)
            .text(fileAssetId.value)
            .text(fileObjectId.value)
            .text(this.sourceSha256)
            .long(sourceSizeBytes)
            .text(this.mediaType)
            .text(this.contentRevision)
            .finish()
    }

    override fun toString(): String = "ContentSourceRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: Identifier,
            catalogId: Identifier,
            documentId: Identifier,
            versionId: Identifier,
            fileAssetId: Identifier,
            fileObjectId: Identifier,
            sourceSha256: String,
            sourceSizeBytes: Long,
            mediaType: String,
            contentRevision: String,
        ): ContentSourceRef = ContentSourceRef(
            tenantId,
            catalogId,
            documentId,
            versionId,
            fileAssetId,
            fileObjectId,
            sourceSha256,
            sourceSizeBytes,
            mediaType,
            contentRevision,
        )
    }
}

/** Provider capabilities are immutable and digest-bound into every extraction request. */
class ContentExtractorDescriptor private constructor(
    providerId: String,
    providerInstanceId: String,
    providerRevision: String,
    extractorVersion: String,
    supportedMediaTypes: Collection<String>,
    val maximumSourceBytes: Long,
    val maximumOutputCodePoints: Int,
    val supportsPageNumbers: Boolean,
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
    val extractorVersion: String = requireSpiText(
        extractorVersion,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Extractor version is invalid.",
    )
    val supportedMediaTypes: List<String> = immutableSpiStrings(
        supportedMediaTypes,
        256,
        "Too many supported media types.",
    )
    val digest: String

    init {
        require(this.supportedMediaTypes.isNotEmpty()) { "At least one media type must be supported." }
        this.supportedMediaTypes.forEach(::requireSpiMediaType)
        require(maximumSourceBytes > 0L) { "Maximum source size must be positive." }
        require(maximumOutputCodePoints in 1..RetrievalSpiLimits.MAX_TEXT_CODE_POINTS) {
            "Maximum extraction output is invalid."
        }
        val writer = RetrievalSpiDigest("flowweft-content-extractor-descriptor-v1")
            .text(this.providerId)
            .text(this.providerInstanceId)
            .text(this.providerRevision)
            .text(this.extractorVersion)
            .integer(this.supportedMediaTypes.size)
        this.supportedMediaTypes.forEach(writer::text)
        digest = writer.long(maximumSourceBytes)
            .integer(maximumOutputCodePoints)
            .boolean(supportsPageNumbers)
            .boolean(sendsContentOffHost)
            .finish()
    }

    fun requireSupports(source: ContentSourceRef) {
        require(source.sourceSizeBytes <= maximumSourceBytes) { "Source exceeds extractor size limit." }
        require(supportedMediaTypes.contains(source.mediaType)) { "Source media type is unsupported." }
    }

    override fun toString(): String = "ContentExtractorDescriptor(providerId=$providerId)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerInstanceId: String,
            providerRevision: String,
            extractorVersion: String,
            supportedMediaTypes: Collection<String>,
            maximumSourceBytes: Long,
            maximumOutputCodePoints: Int,
            supportsPageNumbers: Boolean,
            sendsContentOffHost: Boolean,
        ): ContentExtractorDescriptor = ContentExtractorDescriptor(
            providerId,
            providerInstanceId,
            providerRevision,
            extractorVersion,
            supportedMediaTypes,
            maximumSourceBytes,
            maximumOutputCodePoints,
            supportsPageNumbers,
            sendsContentOffHost,
        )
    }
}

/** Host-owned byte bridge. It must not expose storage keys or vendor clients to extractors. */
fun interface ContentBinarySource {
    fun open(source: ContentSourceRef, deadlineEpochMilli: Long): CompletionStage<ContentInputHandle>
}

/** One-use stream whose advertised lineage is checked before the extractor can observe it. */
class ContentInputHandle private constructor(
    private val input: InputStream,
    val sourceDigest: String,
    sourceSha256: String,
    val contentLength: Long,
) : AutoCloseable {
    val sourceSha256: String = requireSpiSha256(sourceSha256, "Input source digest is invalid.")
    private val opened = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    init {
        requireSpiSha256(sourceDigest, "Input source reference digest is invalid.")
        require(contentLength >= 0L) { "Input content length must not be negative." }
    }

    fun stream(): InputStream {
        check(!closed.get()) { "Content input has already been closed." }
        check(opened.compareAndSet(false, true)) { "Content input stream can only be acquired once." }
        return input
    }

    internal fun requireMatches(source: ContentSourceRef): ContentInputHandle {
        try {
            require(
                sourceDigest == source.digest &&
                    sourceSha256 == source.sourceSha256 &&
                    contentLength == source.sourceSizeBytes,
            ) { "Content input does not match the requested source lineage." }
        } catch (failure: RuntimeException) {
            try {
                close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
        return this
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) input.close()
    }

    override fun toString(): String = "ContentInputHandle(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            input: InputStream,
            sourceDigest: String,
            sourceSha256: String,
            contentLength: Long,
        ): ContentInputHandle = ContentInputHandle(input, sourceDigest, sourceSha256, contentLength)
    }
}

class ContentExtractionRequest private constructor(
    val requestId: Identifier,
    val source: ContentSourceRef,
    val descriptor: ContentExtractorDescriptor,
    extractionProfileDigest: String,
    val maximumOutputCodePoints: Int,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
    private val binarySource: ContentBinarySource,
) {
    val extractionProfileDigest: String = requireSpiSha256(
        extractionProfileDigest,
        "Extraction profile digest is invalid.",
    )
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Extraction request id is invalid.")
        descriptor.requireSupports(source)
        require(maximumOutputCodePoints in 1..descriptor.maximumOutputCodePoints) {
            "Extraction output limit exceeds provider capability."
        }
        requireSpiTime(requestedAtEpochMilli, "Extraction request time is invalid.")
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Extraction deadline must follow request time." }
        digest = RetrievalSpiDigest("flowweft-content-extraction-request-v1")
            .text(requestId.value)
            .text(source.digest)
            .text(descriptor.digest)
            .text(this.extractionProfileDigest)
            .integer(maximumOutputCodePoints)
            .long(requestedAtEpochMilli)
            .long(deadlineEpochMilli)
            .finish()
    }

    fun openSource(): CompletionStage<ContentInputHandle> = binarySource
        .open(source, deadlineEpochMilli)
        .thenApply { handle -> handle.requireMatches(source) }

    override fun toString(): String = "ContentExtractionRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            source: ContentSourceRef,
            descriptor: ContentExtractorDescriptor,
            extractionProfileDigest: String,
            maximumOutputCodePoints: Int,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
            binarySource: ContentBinarySource,
        ): ContentExtractionRequest = ContentExtractionRequest(
            requestId,
            source,
            descriptor,
            extractionProfileDigest,
            maximumOutputCodePoints,
            requestedAtEpochMilli,
            deadlineEpochMilli,
            binarySource,
        )
    }
}

class ExtractedContentSegment private constructor(
    val ordinal: Int,
    text: String,
    mediaType: String,
    val sourceStartByte: Long?,
    val sourceEndByteExclusive: Long?,
    val pageNumber: Int?,
) {
    val text: String = requireSpiContent(text, RetrievalSpiLimits.MAX_TEXT_CODE_POINTS, "Extracted text is invalid.")
    val mediaType: String = requireSpiMediaType(mediaType)
    val textSha256: String = sha256Spi(this.text)
    val digest: String

    init {
        require(ordinal >= 0) { "Extracted segment ordinal must not be negative." }
        require((sourceStartByte == null) == (sourceEndByteExclusive == null)) {
            "Extracted segment source byte range must be complete or absent."
        }
        if (sourceStartByte != null && sourceEndByteExclusive != null) {
            require(sourceStartByte >= 0L && sourceEndByteExclusive > sourceStartByte) {
                "Extracted segment source byte range is invalid."
            }
        }
        require(pageNumber == null || pageNumber >= 1) { "Extracted page number is invalid." }
        digest = RetrievalSpiDigest("flowweft-extracted-content-segment-v1")
            .integer(ordinal)
            .text(textSha256)
            .text(this.mediaType)
            .optionalText(sourceStartByte?.toString())
            .optionalText(sourceEndByteExclusive?.toString())
            .optionalText(pageNumber?.toString())
            .finish()
    }

    override fun toString(): String = "ExtractedContentSegment(ordinal=$ordinal, mediaType=$mediaType)"

    companion object {
        @JvmStatic
        fun of(
            ordinal: Int,
            text: String,
            mediaType: String,
            sourceStartByte: Long? = null,
            sourceEndByteExclusive: Long? = null,
            pageNumber: Int? = null,
        ): ExtractedContentSegment = ExtractedContentSegment(
            ordinal,
            text,
            mediaType,
            sourceStartByte,
            sourceEndByteExclusive,
            pageNumber,
        )

        @JvmStatic
        fun of(ordinal: Int, text: String, mediaType: String): ExtractedContentSegment =
            ExtractedContentSegment(ordinal, text, mediaType, null, null, null)

        @JvmStatic
        fun of(
            ordinal: Int,
            text: String,
            mediaType: String,
            sourceStartByte: Long,
            sourceEndByteExclusive: Long,
        ): ExtractedContentSegment = ExtractedContentSegment(
            ordinal,
            text,
            mediaType,
            sourceStartByte,
            sourceEndByteExclusive,
            null,
        )
    }
}

class ContentExtractionResult private constructor(
    val requestId: Identifier,
    requestDigest: String,
    sourceDigest: String,
    sourceSha256: String,
    descriptorDigest: String,
    segments: Collection<ExtractedContentSegment>,
    providerRequestId: String,
    val completedAtEpochMilli: Long,
) {
    val requestDigest: String = requireSpiSha256(requestDigest, "Extraction request digest is invalid.")
    val sourceDigest: String = requireSpiSha256(sourceDigest, "Extraction source digest is invalid.")
    val sourceSha256: String = requireSpiSha256(sourceSha256, "Extraction source content digest is invalid.")
    val descriptorDigest: String = requireSpiSha256(descriptorDigest, "Extractor descriptor digest is invalid.")
    val segments: List<ExtractedContentSegment> = immutableSpiList(
        segments,
        RetrievalSpiLimits.MAX_SEGMENTS,
        "Extraction produced too many segments.",
    )
    val providerRequestId: String = requireSpiText(
        providerRequestId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Provider request id is invalid.",
    )
    val digest: String

    init {
        require(this.segments.isNotEmpty()) { "Extraction must produce at least one segment." }
        require(this.segments.map { segment -> segment.ordinal } == this.segments.indices.toList()) {
            "Extraction segments must be complete and ordered from zero."
        }
        val writer = RetrievalSpiDigest("flowweft-content-extraction-result-v1")
            .text(requestId.value)
            .text(this.requestDigest)
            .text(this.sourceDigest)
            .text(this.sourceSha256)
            .text(this.descriptorDigest)
            .integer(this.segments.size)
        this.segments.forEach { segment -> writer.text(segment.digest) }
        digest = writer.text(this.providerRequestId).long(completedAtEpochMilli).finish()
    }

    override fun toString(): String = "ContentExtractionResult(segments=${segments.size})"

    companion object {
        @JvmStatic
        fun success(
            request: ContentExtractionRequest,
            segments: Collection<ExtractedContentSegment>,
            providerRequestId: String,
            completedAtEpochMilli: Long,
        ): ContentExtractionResult {
            require(completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
                "Extraction completed outside its request window."
            }
            val snapshot = immutableSpiList(
                segments,
                RetrievalSpiLimits.MAX_SEGMENTS,
                "Extraction produced too many segments.",
            )
            require(snapshot.sumOf { segment -> segment.text.codePointCount(0, segment.text.length).toLong() } <=
                request.maximumOutputCodePoints.toLong()
            ) { "Extraction output exceeds its request limit." }
            require(snapshot.map { segment -> segment.ordinal } == snapshot.indices.toList()) {
                "Extraction segments must be complete and ordered from zero."
            }
            var previousSourceEndByteExclusive: Long? = null
            var previousPageNumber: Int? = null
            snapshot.forEach { segment ->
                val sourceStartByte = segment.sourceStartByte
                val sourceEndByteExclusive = segment.sourceEndByteExclusive
                if (sourceStartByte != null && sourceEndByteExclusive != null) {
                    require(sourceEndByteExclusive <= request.source.sourceSizeBytes) {
                        "Extracted segment exceeds source byte range."
                    }
                    previousSourceEndByteExclusive?.let { previousEnd ->
                        require(sourceStartByte >= previousEnd) {
                            "Extracted segment source byte ranges must be ordered and non-overlapping."
                        }
                    }
                    previousSourceEndByteExclusive = sourceEndByteExclusive
                }
                require(request.descriptor.supportsPageNumbers || segment.pageNumber == null) {
                    "Extractor reported page numbers without declaring support."
                }
                segment.pageNumber?.let { pageNumber ->
                    previousPageNumber?.let { previousPage ->
                        require(pageNumber >= previousPage) { "Extracted page numbers must be ordered." }
                    }
                    previousPageNumber = pageNumber
                }
            }
            return ContentExtractionResult(
                request.requestId,
                request.digest,
                request.source.digest,
                request.source.sourceSha256,
                request.descriptor.digest,
                snapshot,
                providerRequestId,
                completedAtEpochMilli,
            )
        }
    }
}

interface ContentExtractor {
    fun descriptor(): ContentExtractorDescriptor
    fun extract(request: ContentExtractionRequest): CompletionStage<ContentExtractionResult>
}
