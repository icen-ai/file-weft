package ai.icen.fw.retrieval.spi

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalProviderException
import ai.icen.fw.retrieval.api.RetrievalRetryability
import java.util.LinkedHashMap
import java.util.concurrent.CompletionStage

class RetrievalIndexProviderDescriptor private constructor(
    providerId: String,
    providerInstanceId: String,
    providerRevision: String,
    indexSchemaRevision: String,
    val maximumStageBatchSize: Int,
    val supportsText: Boolean,
    val supportsVectors: Boolean,
    val atomicGenerationActivation: Boolean,
    val supportsTombstones: Boolean,
    val supportsAuthorizationRefresh: Boolean,
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
    val indexSchemaRevision: String = requireSpiText(
        indexSchemaRevision,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Index schema revision is invalid.",
    )
    val digest: String

    init {
        require(maximumStageBatchSize in 1..RetrievalSpiLimits.MAX_CHUNKS) {
            "Index stage batch limit is invalid."
        }
        require(supportsText || supportsVectors) { "Index provider must support text or vectors." }
        require(atomicGenerationActivation) { "Index provider must support atomic generation activation." }
        require(supportsTombstones) { "Index provider must support convergent tombstones." }
        require(supportsAuthorizationRefresh) { "Index provider must support authorization refresh." }
        digest = RetrievalSpiDigest("flowweft-retrieval-index-provider-descriptor-v1")
            .text(this.providerId)
            .text(this.providerInstanceId)
            .text(this.providerRevision)
            .text(this.indexSchemaRevision)
            .integer(maximumStageBatchSize)
            .boolean(supportsText)
            .boolean(supportsVectors)
            .boolean(atomicGenerationActivation)
            .boolean(supportsTombstones)
            .boolean(supportsAuthorizationRefresh)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexProviderDescriptor(providerId=$providerId)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerInstanceId: String,
            providerRevision: String,
            indexSchemaRevision: String,
            maximumStageBatchSize: Int,
            supportsText: Boolean,
            supportsVectors: Boolean,
            atomicGenerationActivation: Boolean,
            supportsTombstones: Boolean,
            supportsAuthorizationRefresh: Boolean,
        ): RetrievalIndexProviderDescriptor = RetrievalIndexProviderDescriptor(
            providerId,
            providerInstanceId,
            providerRevision,
            indexSchemaRevision,
            maximumStageBatchSize,
            supportsText,
            supportsVectors,
            atomicGenerationActivation,
            supportsTombstones,
            supportsAuthorizationRefresh,
        )
    }
}

class RetrievalIndexRecord private constructor(
    recordId: String,
    val chunkOrdinal: Int,
    chunkDigest: String,
    sourceSha256: String,
    text: String?,
    textSha256: String,
    vector: Collection<Double>?,
    vectorDigest: String?,
    authorizationPolicyRevision: String,
    authorizationScopeDigest: String,
) {
    val recordId: String = requireSpiText(recordId, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Index record id is invalid.")
    val chunkDigest: String = requireSpiSha256(chunkDigest, "Index chunk digest is invalid.")
    val sourceSha256: String = requireSpiSha256(sourceSha256, "Index source digest is invalid.")
    val text: String? = text?.let { value ->
        requireSpiContent(value, RetrievalSpiLimits.MAX_TEXT_CODE_POINTS, "Index text is invalid.")
    }
    val textSha256: String = requireSpiSha256(textSha256, "Index text digest is invalid.")
    val vector: List<Double>? = vector?.let { values ->
        immutableSpiList(values, RetrievalSpiLimits.MAX_VECTOR_DIMENSIONS, "Index vector is too large.")
    }
    val vectorDigest: String? = vectorDigest?.let { value -> requireSpiSha256(value, "Index vector digest is invalid.") }
    val authorizationPolicyRevision: String = requireSpiText(
        authorizationPolicyRevision,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Authorization policy revision is invalid.",
    )
    val authorizationScopeDigest: String = requireSpiSha256(
        authorizationScopeDigest,
        "Authorization scope digest is invalid.",
    )
    val digest: String

    init {
        require(chunkOrdinal >= 0) { "Index chunk ordinal must not be negative." }
        this.text?.let { value ->
            require(sha256Spi(value) == this.textSha256) { "Index text digest does not match its content." }
        }
        require((this.vector == null) == (this.vectorDigest == null)) {
            "Index vector and vector digest must both be present or absent."
        }
        this.vector?.let { values ->
            require(values.isNotEmpty() && values.all { value -> value.isFinite() }) {
                "Index vector values must be finite and non-empty."
            }
            val writer = RetrievalSpiDigest("flowweft-index-vector-values-v1").integer(values.size)
            values.forEach { value -> writer.floating(value) }
            require(writer.finish() == this.vectorDigest) { "Index vector digest does not match its values." }
        }
        val writer = RetrievalSpiDigest("flowweft-retrieval-index-record-v1")
            .text(this.recordId)
            .integer(chunkOrdinal)
            .text(this.chunkDigest)
            .text(this.sourceSha256)
            .boolean(this.text != null)
            .text(this.textSha256)
            .boolean(this.vector != null)
        this.vector?.let { values ->
            writer.integer(values.size)
            values.forEach { value -> writer.floating(value) }
        }
        digest = writer.optionalText(this.vectorDigest)
            .text(this.authorizationPolicyRevision)
            .text(this.authorizationScopeDigest)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexRecord(chunkOrdinal=$chunkOrdinal)"

    companion object {
        internal fun from(
            generationId: String,
            chunk: ContentChunk,
            vector: EmbeddingVector?,
            includeText: Boolean,
            authorizationPolicyRevision: String,
            authorizationScopeDigest: String,
        ): RetrievalIndexRecord {
            val recordId = RetrievalSpiDigest("flowweft-retrieval-index-record-id-v1")
                .text(generationId)
                .integer(chunk.ordinal)
                .text(chunk.digest)
                .finish()
            val vectorDigest = vector?.let { value ->
                val writer = RetrievalSpiDigest("flowweft-index-vector-values-v1").integer(value.values.size)
                value.values.forEach { item -> writer.floating(item) }
                writer.finish()
            }
            return RetrievalIndexRecord(
                recordId,
                chunk.ordinal,
                chunk.digest,
                chunk.sourceSha256,
                if (includeText) chunk.text else null,
                chunk.textSha256,
                vector?.values,
                vectorDigest,
                authorizationPolicyRevision,
                authorizationScopeDigest,
            )
        }
    }
}

/** Complete, immutable generation manifest. Staging this manifest must never make records queryable. */
class RetrievalIndexGenerationManifest private constructor(
    generationId: String,
    val source: ContentSourceRef,
    val extraction: ContentExtractionResult,
    val chunking: ContentChunkingResult,
    val embeddingRequest: EmbeddingRequest?,
    val embeddingResult: EmbeddingResult?,
    val descriptor: RetrievalIndexProviderDescriptor,
    authorizationPolicyRevision: String,
    authorizationScopeDigest: String,
    records: Collection<RetrievalIndexRecord>,
) {
    val generationId: String = requireSpiText(
        generationId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Index generation id is invalid.",
    )
    val authorizationPolicyRevision: String = requireSpiText(
        authorizationPolicyRevision,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Authorization policy revision is invalid.",
    )
    val authorizationScopeDigest: String = requireSpiSha256(
        authorizationScopeDigest,
        "Authorization scope digest is invalid.",
    )
    val records: List<RetrievalIndexRecord> = immutableSpiList(
        records,
        RetrievalSpiLimits.MAX_CHUNKS,
        "Index manifest contains too many records.",
    )
    val recordManifestDigest: String
    val digest: String

    init {
        require(this.records.isNotEmpty()) { "Index generation must contain at least one record." }
        require(this.records.map { record -> record.chunkOrdinal } == this.records.indices.toList()) {
            "Index records must preserve complete chunk order."
        }
        val recordWriter = RetrievalSpiDigest("flowweft-retrieval-index-record-manifest-v1")
            .integer(this.records.size)
        this.records.forEach { record -> recordWriter.text(record.digest) }
        recordManifestDigest = recordWriter.finish()
        digest = RetrievalSpiDigest("flowweft-retrieval-index-generation-manifest-v1")
            .text(this.generationId)
            .text(source.digest)
            .text(extraction.digest)
            .text(chunking.digest)
            .optionalText(embeddingRequest?.digest)
            .optionalText(embeddingResult?.digest)
            .text(descriptor.digest)
            .text(this.authorizationPolicyRevision)
            .text(this.authorizationScopeDigest)
            .text(recordManifestDigest)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexGenerationManifest(records=${records.size})"

    companion object {
        @JvmStatic
        fun of(
            generationId: String,
            source: ContentSourceRef,
            extraction: ContentExtractionResult,
            chunking: ContentChunkingResult,
            descriptor: RetrievalIndexProviderDescriptor,
            authorizationPolicyRevision: String,
            authorizationScopeDigest: String,
            embeddingRequest: EmbeddingRequest? = null,
            embeddingResult: EmbeddingResult? = null,
        ): RetrievalIndexGenerationManifest {
            require(extraction.sourceDigest == source.digest && extraction.sourceSha256 == source.sourceSha256) {
                "Extraction does not belong to the indexed source."
            }
            require(chunking.extractionDigest == extraction.digest) {
                "Chunking does not belong to the indexed extraction."
            }
            require((embeddingRequest == null) == (embeddingResult == null)) {
                "Embedding request and result must both be present or absent."
            }
            val vectorsByOrdinal = LinkedHashMap<Int, EmbeddingVector>()
            if (embeddingRequest != null && embeddingResult != null) {
                require(embeddingResult.requestDigest == embeddingRequest.digest) {
                    "Embedding result does not belong to its request."
                }
                require(embeddingRequest.requestedAtEpochMilli >= chunking.completedAtEpochMilli) {
                    "Embedding request predates chunking completion."
                }
                require(embeddingRequest.inputs.size == chunking.chunks.size) {
                    "Embedding request must cover every chunk for a vector generation."
                }
                chunking.chunks.zip(embeddingRequest.inputs).forEach { (chunk, input) ->
                    require(input.chunkOrdinal == chunk.ordinal && input.chunkDigest == chunk.digest) {
                        "Embedding input does not match chunk order and lineage."
                    }
                }
                embeddingRequest.inputs.zip(embeddingResult.vectors).forEach { (input, vector) ->
                    require(vector.inputDigest == input.digest) {
                        "Embedding vector does not belong to its indexed input."
                    }
                    require(vectorsByOrdinal.put(input.chunkOrdinal, vector) == null) {
                        "Embedding result contains duplicate chunk bindings."
                    }
                }
            }
            require(descriptor.supportsVectors == (embeddingRequest != null)) {
                "Index vector generation does not match the provider's enabled modality."
            }
            val records = chunking.chunks.map { chunk ->
                RetrievalIndexRecord.from(
                    generationId,
                    chunk,
                    vectorsByOrdinal[chunk.ordinal],
                    descriptor.supportsText,
                    authorizationPolicyRevision,
                    authorizationScopeDigest,
                )
            }
            require(records.all { record ->
                (record.text != null) == descriptor.supportsText &&
                    (record.vector != null) == descriptor.supportsVectors
            }) { "Index records do not completely cover the provider's enabled modalities." }
            return RetrievalIndexGenerationManifest(
                generationId,
                source,
                extraction,
                chunking,
                embeddingRequest,
                embeddingResult,
                descriptor,
                authorizationPolicyRevision,
                authorizationScopeDigest,
                records,
            )
        }

        @JvmStatic
        fun of(
            generationId: String,
            source: ContentSourceRef,
            extraction: ContentExtractionResult,
            chunking: ContentChunkingResult,
            descriptor: RetrievalIndexProviderDescriptor,
            authorizationPolicyRevision: String,
            authorizationScopeDigest: String,
        ): RetrievalIndexGenerationManifest = of(
            generationId,
            source,
            extraction,
            chunking,
            descriptor,
            authorizationPolicyRevision,
            authorizationScopeDigest,
            null,
            null,
        )
    }
}

private fun requireIndexStageRecords(
    manifest: RetrievalIndexGenerationManifest,
    fromRecordInclusive: Int,
    toRecordExclusive: Int,
): List<RetrievalIndexRecord> {
    require(
        fromRecordInclusive >= 0 &&
            toRecordExclusive > fromRecordInclusive &&
            toRecordExclusive <= manifest.records.size,
    ) { "Index stage record range is invalid." }
    return immutableSpiList(
        manifest.records.subList(fromRecordInclusive, toRecordExclusive),
        manifest.descriptor.maximumStageBatchSize,
        "Index stage batch exceeds provider capability.",
    )
}

class RetrievalIndexStageBatch private constructor(
    val requestId: Identifier,
    val manifest: RetrievalIndexGenerationManifest,
    val batchOrdinal: Int,
    val fromRecordInclusive: Int,
    val toRecordExclusive: Int,
    val finalBatch: Boolean,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val records: List<RetrievalIndexRecord> = requireIndexStageRecords(
        manifest,
        fromRecordInclusive,
        toRecordExclusive,
    )
    val recordManifestDigest: String
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Index stage request id is invalid.")
        require(batchOrdinal >= 0) { "Index stage batch ordinal must not be negative." }
        require(fromRecordInclusive >= 0 && toRecordExclusive > fromRecordInclusive &&
            toRecordExclusive <= manifest.records.size
        ) { "Index stage record range is invalid." }
        require(finalBatch == (toRecordExclusive == manifest.records.size)) {
            "Only the batch ending at the manifest boundary can be final."
        }
        require(this.records.size <= manifest.descriptor.maximumStageBatchSize) {
            "Index stage batch exceeds provider capability."
        }
        requireSpiTime(requestedAtEpochMilli, "Index stage request time is invalid.")
        val preparedAtEpochMilli = manifest.embeddingResult?.completedAtEpochMilli ?: manifest.chunking.completedAtEpochMilli
        require(requestedAtEpochMilli >= preparedAtEpochMilli) {
            "Index stage request predates generation preparation."
        }
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Index stage deadline must follow request time." }
        val recordWriter = RetrievalSpiDigest("flowweft-index-stage-record-manifest-v1")
            .integer(this.records.size)
        this.records.forEach { record -> recordWriter.text(record.digest) }
        recordManifestDigest = recordWriter.finish()
        digest = RetrievalSpiDigest("flowweft-retrieval-index-stage-batch-v1")
            .text(requestId.value)
            .text(manifest.digest)
            .integer(batchOrdinal)
            .integer(fromRecordInclusive)
            .integer(toRecordExclusive)
            .boolean(finalBatch)
            .text(recordManifestDigest)
            .long(requestedAtEpochMilli)
            .long(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexStageBatch(batchOrdinal=$batchOrdinal, records=${records.size})"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            manifest: RetrievalIndexGenerationManifest,
            batchOrdinal: Int,
            fromRecordInclusive: Int,
            toRecordExclusive: Int,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): RetrievalIndexStageBatch = RetrievalIndexStageBatch(
            requestId,
            manifest,
            batchOrdinal,
            fromRecordInclusive,
            toRecordExclusive,
            toRecordExclusive == manifest.records.size,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

class RetrievalIndexStageReceipt private constructor(
    val requestId: Identifier,
    requestDigest: String,
    manifestDigest: String,
    descriptorDigest: String,
    val batchOrdinal: Int,
    val fromRecordInclusive: Int,
    val toRecordExclusive: Int,
    val finalBatch: Boolean,
    recordManifestDigest: String,
    providerRequestId: String,
    val completedAtEpochMilli: Long,
) {
    val requestDigest: String = requireSpiSha256(requestDigest, "Index stage request digest is invalid.")
    val manifestDigest: String = requireSpiSha256(manifestDigest, "Index generation manifest digest is invalid.")
    val descriptorDigest: String = requireSpiSha256(descriptorDigest, "Index descriptor digest is invalid.")
    val recordManifestDigest: String = requireSpiSha256(
        recordManifestDigest,
        "Index stage record manifest digest is invalid.",
    )
    val providerRequestId: String = requireSpiText(
        providerRequestId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Index provider request id is invalid.",
    )
    val visibleToQueries: Boolean = false
    val digest: String = RetrievalSpiDigest("flowweft-retrieval-index-stage-receipt-v1")
        .text(requestId.value)
        .text(this.requestDigest)
        .text(this.manifestDigest)
        .text(this.descriptorDigest)
        .integer(batchOrdinal)
        .integer(fromRecordInclusive)
        .integer(toRecordExclusive)
        .boolean(finalBatch)
        .text(this.recordManifestDigest)
        .text(this.providerRequestId)
        .long(completedAtEpochMilli)
        .boolean(false)
        .finish()

    override fun toString(): String = "RetrievalIndexStageReceipt(batchOrdinal=$batchOrdinal)"

    companion object {
        @JvmStatic
        fun staged(
            request: RetrievalIndexStageBatch,
            providerRequestId: String,
            completedAtEpochMilli: Long,
        ): RetrievalIndexStageReceipt {
            require(completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
                "Index stage completed outside its request window."
            }
            return RetrievalIndexStageReceipt(
                request.requestId,
                request.digest,
                request.manifest.digest,
                request.manifest.descriptor.digest,
                request.batchOrdinal,
                request.fromRecordInclusive,
                request.toRecordExclusive,
                request.finalBatch,
                request.recordManifestDigest,
                providerRequestId,
                completedAtEpochMilli,
            )
        }
    }
}

class RetrievalIndexSealRequest private constructor(
    val requestId: Identifier,
    val manifest: RetrievalIndexGenerationManifest,
    stageReceipts: Collection<RetrievalIndexStageReceipt>,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val stageReceipts: List<RetrievalIndexStageReceipt> = immutableSpiList(
        stageReceipts,
        RetrievalSpiLimits.MAX_CHUNKS,
        "Index seal contains too many stage receipts.",
    )
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Index seal request id is invalid.")
        require(this.stageReceipts.isNotEmpty()) { "Index seal requires stage receipts." }
        require(this.stageReceipts.map { receipt -> receipt.batchOrdinal } == this.stageReceipts.indices.toList()) {
            "Index stage receipts must be complete and ordered from zero."
        }
        require(this.stageReceipts.map { receipt -> receipt.requestId }.toSet().size == this.stageReceipts.size) {
            "Index stage request identifiers must be unique."
        }
        var nextRecord = 0
        this.stageReceipts.forEachIndexed { index, receipt ->
            require(receipt.manifestDigest == manifest.digest &&
                receipt.descriptorDigest == manifest.descriptor.digest &&
                receipt.fromRecordInclusive == nextRecord &&
                receipt.toRecordExclusive > receipt.fromRecordInclusive &&
                receipt.toRecordExclusive <= manifest.records.size &&
                receipt.finalBatch == (index == this.stageReceipts.lastIndex) &&
                !receipt.visibleToQueries
            ) { "Index stage receipt does not belong to the sealed generation." }
            val expectedRecordWriter = RetrievalSpiDigest("flowweft-index-stage-record-manifest-v1")
                .integer(receipt.toRecordExclusive - receipt.fromRecordInclusive)
            manifest.records.subList(receipt.fromRecordInclusive, receipt.toRecordExclusive)
                .forEach { record -> expectedRecordWriter.text(record.digest) }
            require(receipt.recordManifestDigest == expectedRecordWriter.finish()) {
                "Index stage receipt does not attest its exact manifest slice."
            }
            nextRecord = receipt.toRecordExclusive
        }
        require(nextRecord == manifest.records.size && this.stageReceipts.last().finalBatch) {
            "Index stage receipts do not cover the complete generation."
        }
        requireSpiTime(requestedAtEpochMilli, "Index seal request time is invalid.")
        require(requestedAtEpochMilli >= this.stageReceipts.maxOf { receipt -> receipt.completedAtEpochMilli }) {
            "Index seal request predates staging."
        }
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Index seal deadline must follow request time." }
        val writer = RetrievalSpiDigest("flowweft-retrieval-index-seal-request-v1")
            .text(requestId.value)
            .text(manifest.digest)
            .integer(this.stageReceipts.size)
        this.stageReceipts.forEach { receipt -> writer.text(receipt.digest) }
        digest = writer.long(requestedAtEpochMilli).long(deadlineEpochMilli).finish()
    }

    override fun toString(): String = "RetrievalIndexSealRequest(stages=${stageReceipts.size})"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            manifest: RetrievalIndexGenerationManifest,
            stageReceipts: Collection<RetrievalIndexStageReceipt>,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): RetrievalIndexSealRequest = RetrievalIndexSealRequest(
            requestId,
            manifest,
            stageReceipts,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

class RetrievalIndexSealReceipt private constructor(
    val request: RetrievalIndexSealRequest,
    providerRequestId: String,
    val completedAtEpochMilli: Long,
) {
    val providerRequestId: String = requireSpiText(
        providerRequestId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Index provider request id is invalid.",
    )
    val visibleToQueries: Boolean = false
    val digest: String = RetrievalSpiDigest("flowweft-retrieval-index-seal-receipt-v1")
        .text(request.digest)
        .text(request.manifest.recordManifestDigest)
        .integer(request.manifest.records.size)
        .text(this.providerRequestId)
        .long(completedAtEpochMilli)
        .boolean(false)
        .finish()

    override fun toString(): String = "RetrievalIndexSealReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun sealed(
            request: RetrievalIndexSealRequest,
            providerRequestId: String,
            completedAtEpochMilli: Long,
        ): RetrievalIndexSealReceipt {
            require(completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
                "Index seal completed outside its request window."
            }
            return RetrievalIndexSealReceipt(request, providerRequestId, completedAtEpochMilli)
        }
    }
}

class RetrievalIndexActivationRequest private constructor(
    val requestId: Identifier,
    val sealReceipt: RetrievalIndexSealReceipt,
    expectedPreviousGenerationId: String?,
    val expectedProjectionRevision: Long,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val expectedPreviousGenerationId: String? = expectedPreviousGenerationId?.let { value ->
        requireSpiText(value, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Previous index generation id is invalid.")
    }
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Index activation request id is invalid.")
        require(expectedProjectionRevision >= 0L) { "Expected index projection revision is invalid." }
        require(!sealReceipt.visibleToQueries) { "Unsealed or visible staging receipt cannot be activated." }
        require(requestedAtEpochMilli >= sealReceipt.completedAtEpochMilli) {
            "Index activation request predates sealing."
        }
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Index activation deadline must follow request time." }
        digest = RetrievalSpiDigest("flowweft-retrieval-index-activation-request-v1")
            .text(requestId.value)
            .text(sealReceipt.digest)
            .optionalText(this.expectedPreviousGenerationId)
            .long(expectedProjectionRevision)
            .long(requestedAtEpochMilli)
            .long(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexActivationRequest(<redacted>)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            requestId: Identifier,
            sealReceipt: RetrievalIndexSealReceipt,
            expectedProjectionRevision: Long,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
            expectedPreviousGenerationId: String? = null,
        ): RetrievalIndexActivationRequest = RetrievalIndexActivationRequest(
            requestId,
            sealReceipt,
            expectedPreviousGenerationId,
            expectedProjectionRevision,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

class RetrievalIndexActivationReceipt private constructor(
    val requestId: Identifier,
    requestDigest: String,
    previousGenerationId: String?,
    activeGenerationId: String,
    val previousProjectionRevision: Long,
    val activeProjectionRevision: Long,
    providerRequestId: String,
    val activatedAtEpochMilli: Long,
) {
    val requestDigest: String = requireSpiSha256(requestDigest, "Index activation request digest is invalid.")
    val previousGenerationId: String? = previousGenerationId?.let { value ->
        requireSpiText(value, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Previous index generation id is invalid.")
    }
    val activeGenerationId: String = requireSpiText(
        activeGenerationId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Active index generation id is invalid.",
    )
    val providerRequestId: String = requireSpiText(
        providerRequestId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Index provider request id is invalid.",
    )
    val atomicSwitch: Boolean = true
    val digest: String

    init {
        require(previousProjectionRevision >= 0L && activeProjectionRevision == previousProjectionRevision + 1L) {
            "Index activation projection revision is invalid."
        }
        digest = RetrievalSpiDigest("flowweft-retrieval-index-activation-receipt-v1")
            .text(requestId.value)
            .text(this.requestDigest)
            .optionalText(this.previousGenerationId)
            .text(this.activeGenerationId)
            .long(previousProjectionRevision)
            .long(activeProjectionRevision)
            .text(this.providerRequestId)
            .long(activatedAtEpochMilli)
            .boolean(true)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexActivationReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun activated(
            request: RetrievalIndexActivationRequest,
            previousGenerationId: String?,
            previousProjectionRevision: Long,
            providerRequestId: String,
            activatedAtEpochMilli: Long,
        ): RetrievalIndexActivationReceipt {
            require(previousGenerationId == request.expectedPreviousGenerationId &&
                previousProjectionRevision == request.expectedProjectionRevision
            ) { "Index activation compare-and-set precondition did not match." }
            require(activatedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
                "Index activation completed outside its request window."
            }
            return RetrievalIndexActivationReceipt(
                request.requestId,
                request.digest,
                previousGenerationId,
                request.sealReceipt.request.manifest.generationId,
                previousProjectionRevision,
                previousProjectionRevision + 1L,
                providerRequestId,
                activatedAtEpochMilli,
            )
        }
    }
}

class RetrievalIndexMutationKind private constructor(code: String) {
    val code: String = requireSpiText(code, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Index mutation kind is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is RetrievalIndexMutationKind && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "RetrievalIndexMutationKind(<redacted>)"

    companion object {
        @JvmField val TOMBSTONE = RetrievalIndexMutationKind("tombstone")
        @JvmField val AUTHORIZATION_REFRESH = RetrievalIndexMutationKind("authorization-refresh")

        @JvmStatic
        fun of(code: String): RetrievalIndexMutationKind = when (code) {
            TOMBSTONE.code -> TOMBSTONE
            AUTHORIZATION_REFRESH.code -> AUTHORIZATION_REFRESH
            else -> RetrievalIndexMutationKind(code)
        }
    }
}

class RetrievalIndexMutationRequest private constructor(
    val requestId: Identifier,
    val descriptor: RetrievalIndexProviderDescriptor,
    val source: ContentSourceRef,
    activeGenerationId: String,
    val expectedProjectionRevision: Long,
    val kind: RetrievalIndexMutationKind,
    policyRevision: String,
    policyScopeDigest: String,
    reasonCode: String,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val activeGenerationId: String = requireSpiText(
        activeGenerationId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Active index generation id is invalid.",
    )
    val policyRevision: String = requireSpiText(
        policyRevision,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Index mutation policy revision is invalid.",
    )
    val policyScopeDigest: String = requireSpiSha256(
        policyScopeDigest,
        "Index mutation policy scope digest is invalid.",
    )
    val reasonCode: String = requireSpiText(
        reasonCode,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Index mutation reason is invalid.",
    )
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Index mutation request id is invalid.")
        require(expectedProjectionRevision >= 0L) { "Expected index projection revision is invalid." }
        require(kind == RetrievalIndexMutationKind.TOMBSTONE || kind == RetrievalIndexMutationKind.AUTHORIZATION_REFRESH) {
            "Unknown index mutation kind is unsupported."
        }
        requireSpiTime(requestedAtEpochMilli, "Index mutation request time is invalid.")
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Index mutation deadline must follow request time." }
        digest = RetrievalSpiDigest("flowweft-retrieval-index-mutation-request-v1")
            .text(requestId.value)
            .text(descriptor.digest)
            .text(source.digest)
            .text(this.activeGenerationId)
            .long(expectedProjectionRevision)
            .text(kind.code)
            .text(this.policyRevision)
            .text(this.policyScopeDigest)
            .text(this.reasonCode)
            .long(requestedAtEpochMilli)
            .long(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexMutationRequest(kind=${kind.code})"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            descriptor: RetrievalIndexProviderDescriptor,
            source: ContentSourceRef,
            activeGenerationId: String,
            expectedProjectionRevision: Long,
            kind: RetrievalIndexMutationKind,
            policyRevision: String,
            policyScopeDigest: String,
            reasonCode: String,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): RetrievalIndexMutationRequest = RetrievalIndexMutationRequest(
            requestId,
            descriptor,
            source,
            activeGenerationId,
            expectedProjectionRevision,
            kind,
            policyRevision,
            policyScopeDigest,
            reasonCode,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

class RetrievalIndexMutationReceipt private constructor(
    val requestId: Identifier,
    requestDigest: String,
    val kind: RetrievalIndexMutationKind,
    generationId: String,
    val previousProjectionRevision: Long,
    val activeProjectionRevision: Long,
    val affectedRecordCount: Int,
    convergenceDigest: String,
    providerRequestId: String,
    val completedAtEpochMilli: Long,
) {
    val requestDigest: String = requireSpiSha256(requestDigest, "Index mutation request digest is invalid.")
    val generationId: String = requireSpiText(
        generationId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Index generation id is invalid.",
    )
    val convergenceDigest: String = requireSpiSha256(convergenceDigest, "Index convergence digest is invalid.")
    val providerRequestId: String = requireSpiText(
        providerRequestId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Index provider request id is invalid.",
    )
    val digest: String

    init {
        require(kind == RetrievalIndexMutationKind.TOMBSTONE || kind == RetrievalIndexMutationKind.AUTHORIZATION_REFRESH) {
            "Unknown index mutation kind is unsupported."
        }
        require(previousProjectionRevision >= 0L && activeProjectionRevision == previousProjectionRevision + 1L) {
            "Index mutation projection revision is invalid."
        }
        require(affectedRecordCount > 0) { "A successful index mutation must affect at least one record." }
        digest = RetrievalSpiDigest("flowweft-retrieval-index-mutation-receipt-v1")
            .text(requestId.value)
            .text(this.requestDigest)
            .text(kind.code)
            .text(this.generationId)
            .long(previousProjectionRevision)
            .long(activeProjectionRevision)
            .integer(affectedRecordCount)
            .text(this.convergenceDigest)
            .text(this.providerRequestId)
            .long(completedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexMutationReceipt(kind=${kind.code})"

    companion object {
        @JvmStatic
        fun applied(
            request: RetrievalIndexMutationRequest,
            previousProjectionRevision: Long,
            affectedRecordCount: Int,
            convergenceDigest: String,
            providerRequestId: String,
            completedAtEpochMilli: Long,
        ): RetrievalIndexMutationReceipt {
            require(previousProjectionRevision == request.expectedProjectionRevision) {
                "Index mutation compare-and-set precondition did not match."
            }
            require(completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
                "Index mutation completed outside its request window."
            }
            return RetrievalIndexMutationReceipt(
                request.requestId,
                request.digest,
                request.kind,
                request.activeGenerationId,
                previousProjectionRevision,
                previousProjectionRevision + 1L,
                affectedRecordCount,
                convergenceDigest,
                providerRequestId,
                completedAtEpochMilli,
            )
        }
    }
}

class RetrievalIndexStateRequest private constructor(
    val requestId: Identifier,
    val descriptor: RetrievalIndexProviderDescriptor,
    val source: ContentSourceRef,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val digest: String

    init {
        requireSpiIdentifier(requestId, "Index state request id is invalid.")
        requireSpiTime(requestedAtEpochMilli, "Index state request time is invalid.")
        require(deadlineEpochMilli > requestedAtEpochMilli) { "Index state deadline must follow request time." }
        digest = RetrievalSpiDigest("flowweft-retrieval-index-state-request-v1")
            .text(requestId.value)
            .text(descriptor.digest)
            .text(source.digest)
            .long(requestedAtEpochMilli)
            .long(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexStateRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            requestId: Identifier,
            descriptor: RetrievalIndexProviderDescriptor,
            source: ContentSourceRef,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): RetrievalIndexStateRequest = RetrievalIndexStateRequest(
            requestId,
            descriptor,
            source,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

class RetrievalIndexState private constructor(
    val requestId: Identifier,
    requestDigest: String,
    descriptorDigest: String,
    sourceDigest: String,
    activeGenerationId: String?,
    val projectionRevision: Long,
    authorizationPolicyRevision: String?,
    authorizationScopeDigest: String?,
    val tombstoned: Boolean,
    val observedAtEpochMilli: Long,
) {
    val requestDigest: String = requireSpiSha256(requestDigest, "Index state request digest is invalid.")
    val descriptorDigest: String = requireSpiSha256(descriptorDigest, "Index state descriptor digest is invalid.")
    val sourceDigest: String = requireSpiSha256(sourceDigest, "Index state source digest is invalid.")
    val activeGenerationId: String? = activeGenerationId?.let { value ->
        requireSpiText(value, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Active index generation id is invalid.")
    }
    val authorizationPolicyRevision: String? = authorizationPolicyRevision?.let { value ->
        requireSpiText(value, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Authorization policy revision is invalid.")
    }
    val authorizationScopeDigest: String? = authorizationScopeDigest?.let { value ->
        requireSpiSha256(value, "Authorization scope digest is invalid.")
    }
    val digest: String

    init {
        require(projectionRevision >= 0L) { "Index projection revision is invalid." }
        require((this.authorizationPolicyRevision == null) == (this.authorizationScopeDigest == null)) {
            "Index authorization revision and scope digest must both be present or absent."
        }
        require((this.activeGenerationId == null) == (this.authorizationPolicyRevision == null)) {
            "Only an active generation may advertise complete authorization state."
        }
        require(!tombstoned || this.activeGenerationId == null) {
            "Tombstoned index state cannot advertise an active generation."
        }
        digest = RetrievalSpiDigest("flowweft-retrieval-index-state-v1")
            .text(requestId.value)
            .text(this.requestDigest)
            .text(this.descriptorDigest)
            .text(this.sourceDigest)
            .optionalText(this.activeGenerationId)
            .long(projectionRevision)
            .optionalText(this.authorizationPolicyRevision)
            .optionalText(this.authorizationScopeDigest)
            .boolean(tombstoned)
            .long(observedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexState(active=${activeGenerationId != null}, tombstoned=$tombstoned)"

    companion object {
        @JvmStatic
        fun observed(
            request: RetrievalIndexStateRequest,
            projectionRevision: Long,
            observedAtEpochMilli: Long,
            activeGenerationId: String? = null,
            authorizationPolicyRevision: String? = null,
            authorizationScopeDigest: String? = null,
            tombstoned: Boolean = false,
        ): RetrievalIndexState {
            require(observedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli) {
                "Index state observation completed outside its request window."
            }
            return RetrievalIndexState(
                request.requestId,
                request.digest,
                request.descriptor.digest,
                request.source.digest,
                activeGenerationId,
                projectionRevision,
                authorizationPolicyRevision,
                authorizationScopeDigest,
                tombstoned,
                observedAtEpochMilli,
            )
        }

        @JvmStatic
        fun observed(
            request: RetrievalIndexStateRequest,
            projectionRevision: Long,
            observedAtEpochMilli: Long,
        ): RetrievalIndexState = observed(
            request,
            projectionRevision,
            observedAtEpochMilli,
            null,
            null,
            null,
            false,
        )
    }
}

class RetrievalIndexGenerationOperation private constructor(code: String) {
    val code: String = requireSpiText(code, RetrievalSpiLimits.MAX_ID_CODE_POINTS, "Index generation operation is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is RetrievalIndexGenerationOperation && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "RetrievalIndexGenerationOperation(<redacted>)"

    companion object {
        @JvmField val STAGE = RetrievalIndexGenerationOperation("stage")
        @JvmField val SEAL = RetrievalIndexGenerationOperation("seal")
        @JvmField val ACTIVATE = RetrievalIndexGenerationOperation("activate")
    }
}

/**
 * Runtime-created evidence that a failed generation operation left the complete active projection
 * unchanged. Both observations are provider state receipts for the same source and descriptor.
 */
class RetrievalIndexGenerationFailureEvidence private constructor(
    val operation: RetrievalIndexGenerationOperation,
    val operationRequestId: Identifier,
    operationRequestDigest: String,
    generationId: String,
    val baselineState: RetrievalIndexState,
    val observedState: RetrievalIndexState,
    val failureCode: RetrievalFailureCode,
    val retryability: RetrievalRetryability,
) {
    val operationRequestDigest: String = requireSpiSha256(
        operationRequestDigest,
        "Failed index operation request digest is invalid.",
    )
    val generationId: String = requireSpiText(
        generationId,
        RetrievalSpiLimits.MAX_ID_CODE_POINTS,
        "Failed index generation id is invalid.",
    )
    val retryable: Boolean = retryability == RetrievalRetryability.RETRYABLE
    val digest: String

    init {
        requireSpiIdentifier(operationRequestId, "Failed index operation request id is invalid.")
        require(
            baselineState.descriptorDigest == observedState.descriptorDigest &&
                baselineState.sourceDigest == observedState.sourceDigest,
        ) { "Index failure observations belong to different provider state scopes." }
        require(
            baselineState.activeGenerationId == observedState.activeGenerationId &&
                baselineState.projectionRevision == observedState.projectionRevision &&
                baselineState.authorizationPolicyRevision == observedState.authorizationPolicyRevision &&
                baselineState.authorizationScopeDigest == observedState.authorizationScopeDigest &&
                baselineState.tombstoned == observedState.tombstoned,
        ) { "Failed index generation operation changed the active projection." }
        require(generationId != observedState.activeGenerationId) {
            "A failed generation must not be reported as the active generation."
        }
        require(observedState.observedAtEpochMilli >= baselineState.observedAtEpochMilli) {
            "Index failure observation predates its baseline."
        }
        digest = RetrievalSpiDigest("flowweft-retrieval-index-generation-failure-evidence-v1")
            .text(operation.code)
            .text(operationRequestId.value)
            .text(this.operationRequestDigest)
            .text(this.generationId)
            .text(baselineState.digest)
            .text(observedState.digest)
            .text(failureCode.id)
            .boolean(retryable)
            .finish()
    }

    override fun toString(): String = "RetrievalIndexGenerationFailureEvidence(operation=${operation.code})"

    companion object {
        @JvmStatic
        fun afterStageFailure(
            request: RetrievalIndexStageBatch,
            baselineState: RetrievalIndexState,
            observedState: RetrievalIndexState,
            failureCode: RetrievalFailureCode,
            retryability: RetrievalRetryability,
        ): RetrievalIndexGenerationFailureEvidence = create(
            RetrievalIndexGenerationOperation.STAGE,
            request.requestId,
            request.digest,
            request.manifest,
            request.requestedAtEpochMilli,
            baselineState,
            observedState,
            failureCode,
            retryability,
        )

        @JvmStatic
        fun afterSealFailure(
            request: RetrievalIndexSealRequest,
            baselineState: RetrievalIndexState,
            observedState: RetrievalIndexState,
            failureCode: RetrievalFailureCode,
            retryability: RetrievalRetryability,
        ): RetrievalIndexGenerationFailureEvidence = create(
            RetrievalIndexGenerationOperation.SEAL,
            request.requestId,
            request.digest,
            request.manifest,
            request.requestedAtEpochMilli,
            baselineState,
            observedState,
            failureCode,
            retryability,
        )

        @JvmStatic
        fun afterActivationFailure(
            request: RetrievalIndexActivationRequest,
            baselineState: RetrievalIndexState,
            observedState: RetrievalIndexState,
            failureCode: RetrievalFailureCode,
            retryability: RetrievalRetryability,
        ): RetrievalIndexGenerationFailureEvidence {
            require(
                baselineState.activeGenerationId == request.expectedPreviousGenerationId &&
                    baselineState.projectionRevision == request.expectedProjectionRevision,
            ) { "Index activation failure baseline does not match the compare-and-set precondition." }
            return create(
                RetrievalIndexGenerationOperation.ACTIVATE,
                request.requestId,
                request.digest,
                request.sealReceipt.request.manifest,
                request.requestedAtEpochMilli,
                baselineState,
                observedState,
                failureCode,
                retryability,
            )
        }

        private fun create(
            operation: RetrievalIndexGenerationOperation,
            operationRequestId: Identifier,
            operationRequestDigest: String,
            manifest: RetrievalIndexGenerationManifest,
            requestedAtEpochMilli: Long,
            baselineState: RetrievalIndexState,
            observedState: RetrievalIndexState,
            failureCode: RetrievalFailureCode,
            retryability: RetrievalRetryability,
        ): RetrievalIndexGenerationFailureEvidence {
            require(
                baselineState.descriptorDigest == manifest.descriptor.digest &&
                    observedState.descriptorDigest == manifest.descriptor.digest &&
                    baselineState.sourceDigest == manifest.source.digest &&
                    observedState.sourceDigest == manifest.source.digest,
            ) { "Index failure observations do not belong to the attempted generation." }
            require(baselineState.observedAtEpochMilli <= requestedAtEpochMilli) {
                "Index generation failure baseline was observed after the operation began."
            }
            require(observedState.observedAtEpochMilli >= requestedAtEpochMilli) {
                "Index generation failure was not observed after the operation began."
            }
            return RetrievalIndexGenerationFailureEvidence(
                operation,
                operationRequestId,
                operationRequestDigest,
                manifest.generationId,
                baselineState,
                observedState,
                failureCode,
                retryability,
            )
        }
    }
}

/**
 * Generation protocol: stage and seal are invisible; activate is the only atomic read-path switch.
 * A failed stage/seal/activation must preserve the previous active generation. Tombstone and ACL
 * refresh operations use compare-and-set projection revisions and return convergence evidence.
 *
 * Every write is idempotent in the scope `(operation, requestId)`. An exact replay returns the same
 * terminal receipt, including its provider request id and digest. Reusing that identity with another
 * request digest fails with [RetrievalFailureCode.INDEX_REQUEST_REPLAY_MISMATCH]. Implementations
 * validate the complete provider/descriptor/source/generation binding before any side effect and use
 * [RetrievalFailureCode.INDEX_PROVIDER_BINDING_MISMATCH] when it does not match.
 *
 * Activation linearizes the expected active generation and projection revision in one compare-and-set.
 * Mutation linearizes the expected active generation and projection revision in the same way. A stale
 * precondition fails with [RetrievalFailureCode.INDEX_PROJECTION_CONFLICT], leaves the complete active
 * projection unchanged, and requires the caller to observe state and create a new request rather than
 * retrying the same request. Provider failures are returned by exceptionally completing a non-null
 * [CompletionStage] with a sanitized [RetrievalProviderException]; raw SDK failures never cross this SPI.
 */
interface RetrievalIndexProvider {
    fun descriptor(): RetrievalIndexProviderDescriptor
    fun stage(request: RetrievalIndexStageBatch): CompletionStage<RetrievalIndexStageReceipt>
    fun seal(request: RetrievalIndexSealRequest): CompletionStage<RetrievalIndexSealReceipt>
    fun activate(request: RetrievalIndexActivationRequest): CompletionStage<RetrievalIndexActivationReceipt>
    fun mutate(request: RetrievalIndexMutationRequest): CompletionStage<RetrievalIndexMutationReceipt>
    fun state(request: RetrievalIndexStateRequest): CompletionStage<RetrievalIndexState>
}
