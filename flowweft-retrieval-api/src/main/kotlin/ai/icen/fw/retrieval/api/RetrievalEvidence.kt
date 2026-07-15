package ai.icen.fw.retrieval.api

import ai.icen.fw.core.id.Identifier

/** Exact projection, ACL and content-processing lineage revisions. */
class RetrievalLineageRevisions private constructor(
    val projectionSchemaRevision: String,
    val authorizationProjectionRevision: String,
    val extractorRevision: String?,
    val chunkerRevision: String?,
    val embeddingRevision: String?,
) {
    val digest: String

    init {
        requireRetrievalText(
            projectionSchemaRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Projection schema revision is invalid.",
        )
        requireRetrievalText(
            authorizationProjectionRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Authorization projection revision is invalid.",
        )
        extractorRevision?.let {
            requireRetrievalText(it, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Extractor revision is invalid.")
        }
        chunkerRevision?.let {
            requireRetrievalText(it, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Chunker revision is invalid.")
        }
        embeddingRevision?.let {
            requireRetrievalText(it, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Embedding revision is invalid.")
        }
        require(chunkerRevision == null || extractorRevision != null) {
            "Chunker revision requires an extractor revision."
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-lineage-revisions-v1")
            text(projectionSchemaRevision)
            text(authorizationProjectionRevision)
            optionalText(extractorRevision)
            optionalText(chunkerRevision)
            optionalText(embeddingRevision)
        }
    }

    companion object {
        @JvmStatic
        fun create(
            projectionSchemaRevision: String,
            authorizationProjectionRevision: String,
            extractorRevision: String?,
            chunkerRevision: String?,
            embeddingRevision: String?,
        ): RetrievalLineageRevisions = RetrievalLineageRevisions(
            projectionSchemaRevision,
            authorizationProjectionRevision,
            extractorRevision,
            chunkerRevision,
            embeddingRevision,
        )
    }
}

/**
 * Content-free provider evidence. It binds catalog, document, immutable version/asset/object,
 * source hash, index generation, ACL projection and optional chunk lineage.
 */
class RetrievalEvidenceRef private constructor(
    val tenantId: Identifier,
    val catalogId: Identifier,
    val projectionId: Identifier,
    val documentId: Identifier,
    val versionId: Identifier,
    val fileAssetId: Identifier,
    val fileObjectId: Identifier,
    val sourceSha256: String,
    val indexGeneration: String,
    val lineageRevisions: RetrievalLineageRevisions,
    val chunkId: Identifier?,
    val chunkOrdinal: Int?,
    val byteStartInclusive: Long?,
    val byteEndExclusive: Long?,
) {
    val digest: String

    init {
        listOf(
            tenantId to "Retrieval evidence tenant identifier is invalid.",
            catalogId to "Retrieval evidence catalog identifier is invalid.",
            projectionId to "Retrieval evidence projection identifier is invalid.",
            documentId to "Retrieval evidence document identifier is invalid.",
            versionId to "Retrieval evidence version identifier is invalid.",
            fileAssetId to "Retrieval evidence asset identifier is invalid.",
            fileObjectId to "Retrieval evidence object identifier is invalid.",
        ).forEach { (identifier, message) -> requireRetrievalIdentifier(identifier, message) }
        requireDigest(sourceSha256, "Retrieval evidence source digest is invalid.")
        requireRetrievalText(
            indexGeneration,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Retrieval evidence index generation is invalid.",
        )
        require((chunkId == null) == (chunkOrdinal == null)) {
            "Chunk identifier and ordinal must be supplied together."
        }
        chunkId?.let { requireRetrievalIdentifier(it, "Retrieval evidence chunk identifier is invalid.") }
        chunkOrdinal?.let { require(it >= 0) { "Retrieval evidence chunk ordinal must not be negative." } }
        require((byteStartInclusive == null) == (byteEndExclusive == null)) {
            "Retrieval evidence byte offsets must be supplied together."
        }
        byteStartInclusive?.let { start ->
            require(chunkId != null) { "Document-level evidence cannot contain byte offsets." }
            require(start >= 0L) { "Retrieval evidence byte start must not be negative." }
            require(checkNotNull(byteEndExclusive) > start) { "Retrieval evidence byte range must not be empty." }
        }
        require(chunkId == null || lineageRevisions.extractorRevision != null) {
            "Chunk evidence requires extractor lineage."
        }
        require(chunkId == null || lineageRevisions.chunkerRevision != null) {
            "Chunk evidence requires chunker lineage."
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-evidence-v1")
            writeDigest(this)
        }
    }

    internal fun identityKey(): RetrievalEvidenceIdentity = RetrievalEvidenceIdentity(
        tenantId,
        catalogId,
        projectionId,
        documentId,
        versionId,
        chunkId,
        chunkOrdinal,
    )

    internal fun writeDigest(writer: RetrievalDigestWriter) = with(writer) {
        text(tenantId.value)
        text(catalogId.value)
        text(projectionId.value)
        text(documentId.value)
        text(versionId.value)
        text(fileAssetId.value)
        text(fileObjectId.value)
        text(sourceSha256)
        text(indexGeneration)
        text(lineageRevisions.digest)
        optionalText(chunkId?.value)
        optionalText(chunkOrdinal?.toString())
        optionalText(byteStartInclusive?.toString())
        optionalText(byteEndExclusive?.toString())
    }

    override fun toString(): String = "RetrievalEvidenceRef(chunk=${chunkId != null})"

    companion object {
        @JvmStatic
        fun document(
            tenantId: Identifier,
            catalogId: Identifier,
            projectionId: Identifier,
            documentId: Identifier,
            versionId: Identifier,
            fileAssetId: Identifier,
            fileObjectId: Identifier,
            sourceSha256: String,
            indexGeneration: String,
            lineageRevisions: RetrievalLineageRevisions,
        ): RetrievalEvidenceRef = RetrievalEvidenceRef(
            tenantId,
            catalogId,
            projectionId,
            documentId,
            versionId,
            fileAssetId,
            fileObjectId,
            sourceSha256,
            indexGeneration,
            lineageRevisions,
            null,
            null,
            null,
            null,
        )

        @JvmStatic
        fun chunk(
            tenantId: Identifier,
            catalogId: Identifier,
            projectionId: Identifier,
            documentId: Identifier,
            versionId: Identifier,
            fileAssetId: Identifier,
            fileObjectId: Identifier,
            sourceSha256: String,
            indexGeneration: String,
            lineageRevisions: RetrievalLineageRevisions,
            chunkId: Identifier,
            chunkOrdinal: Int,
            byteStartInclusive: Long?,
            byteEndExclusive: Long?,
        ): RetrievalEvidenceRef = RetrievalEvidenceRef(
            tenantId,
            catalogId,
            projectionId,
            documentId,
            versionId,
            fileAssetId,
            fileObjectId,
            sourceSha256,
            indexGeneration,
            lineageRevisions,
            chunkId,
            chunkOrdinal,
            byteStartInclusive,
            byteEndExclusive,
        )
    }
}

internal class RetrievalEvidenceIdentity(
    private val tenantId: Identifier,
    private val catalogId: Identifier,
    private val projectionId: Identifier,
    private val documentId: Identifier,
    private val versionId: Identifier,
    private val chunkId: Identifier?,
    private val chunkOrdinal: Int?,
) {
    override fun equals(other: Any?): Boolean = other is RetrievalEvidenceIdentity &&
        tenantId == other.tenantId &&
        catalogId == other.catalogId &&
        projectionId == other.projectionId &&
        documentId == other.documentId &&
        versionId == other.versionId &&
        chunkId == other.chunkId &&
        chunkOrdinal == other.chunkOrdinal

    override fun hashCode(): Int {
        var result = tenantId.hashCode()
        result = 31 * result + catalogId.hashCode()
        result = 31 * result + projectionId.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + versionId.hashCode()
        result = 31 * result + (chunkId?.hashCode() ?: 0)
        return 31 * result + (chunkOrdinal ?: 0)
    }
}

/** Ranked, content-free provider candidate. */
class RetrievalCandidate private constructor(
    val evidence: RetrievalEvidenceRef,
    val sourceMode: RetrievalMode,
    val providerScore: Double,
    val providerRank: Int,
) {
    val digest: String

    init {
        require(providerScore.isFinite()) { "Retrieval candidate score must be finite." }
        require(providerRank > 0) { "Retrieval candidate rank must be positive." }
        if (sourceMode == RetrievalMode.VECTOR || sourceMode == RetrievalMode.HYBRID) {
            require(evidence.lineageRevisions.embeddingRevision != null) {
                "Vector and hybrid evidence require embedding lineage."
            }
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-candidate-v1")
            writeDigest(this)
        }
    }

    internal fun writeDigest(writer: RetrievalDigestWriter) = with(writer) {
        evidence.writeDigest(this)
        text(sourceMode.id)
        long(java.lang.Double.doubleToRawLongBits(providerScore))
        integer(providerRank)
    }

    companion object {
        @JvmStatic
        fun create(
            evidence: RetrievalEvidenceRef,
            sourceMode: RetrievalMode,
            providerScore: Double,
            providerRank: Int,
        ): RetrievalCandidate = RetrievalCandidate(evidence, sourceMode, providerScore, providerRank)
    }
}

/** Untrusted resolver answer for one exact candidate; it is not yet authoritative evidence. */
class RetrievalLineageResolution private constructor(
    val candidateDigest: String,
    val authoritativeEvidence: RetrievalEvidenceRef,
    val lineageAuthorityId: String,
    val lineageAuthorityRevision: String,
) {
    init {
        requireDigest(candidateDigest, "Lineage resolution candidate digest is invalid.")
        requireRetrievalText(
            lineageAuthorityId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Lineage authority identifier is invalid.",
        )
        requireRetrievalText(
            lineageAuthorityRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Lineage authority revision is invalid.",
        )
    }

    override fun toString(): String = "RetrievalLineageResolution(authority=$lineageAuthorityId)"

    companion object {
        @JvmStatic
        fun create(
            candidate: RetrievalCandidate,
            authoritativeEvidence: RetrievalEvidenceRef,
            lineageAuthorityId: String,
            lineageAuthorityRevision: String,
        ): RetrievalLineageResolution {
            require(candidate.evidence.identityKey() == authoritativeEvidence.identityKey()) {
                "Authoritative lineage belongs to another evidence reference."
            }
            require(candidate.evidence.digest == authoritativeEvidence.digest) {
                "Provider evidence no longer matches authoritative lineage."
            }
            return RetrievalLineageResolution(
                candidate.digest,
                authoritativeEvidence,
                lineageAuthorityId,
                lineageAuthorityRevision,
            )
        }
    }
}

/** Stable identity and capability snapshot for one authoritative lineage resolver. */
class RetrievalLineageResolverDescriptor private constructor(
    val providerTypeId: String,
    val providerInstanceId: String,
    val configurationDigest: String,
    val capabilityDigest: String,
    val capabilityRevision: String,
    val supportsCancellation: Boolean,
) {
    val digest: String
    val binding: RetrievalStageProviderBinding

    init {
        listOf(providerTypeId, providerInstanceId, capabilityRevision).forEach { value ->
            requireRetrievalText(
                value,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Lineage resolver descriptor text is invalid.",
            )
        }
        requireDigest(configurationDigest, "Lineage resolver configuration digest is invalid.")
        requireDigest(capabilityDigest, "Lineage resolver capability digest is invalid.")
        digest = retrievalDigest {
            text("flowweft-retrieval-lineage-resolver-descriptor-v1")
            text(providerTypeId)
            text(providerInstanceId)
            text(configurationDigest)
            text(capabilityDigest)
            text(capabilityRevision)
            boolean(supportsCancellation)
        }
        binding = RetrievalStageProviderBinding.create(
            "lineage-resolution",
            providerTypeId,
            providerInstanceId,
            configurationDigest,
            capabilityDigest,
            capabilityRevision,
            digest,
            supportsCancellation,
        )
    }

    override fun toString(): String = "RetrievalLineageResolverDescriptor(providerType=$providerTypeId)"

    companion object {
        @JvmStatic
        fun create(
            providerTypeId: String,
            providerInstanceId: String,
            configurationDigest: String,
            capabilityDigest: String,
            capabilityRevision: String,
            supportsCancellation: Boolean,
        ): RetrievalLineageResolverDescriptor = RetrievalLineageResolverDescriptor(
            providerTypeId,
            providerInstanceId,
            configurationDigest,
            capabilityDigest,
            capabilityRevision,
            supportsCancellation,
        )
    }
}

/** Exact lineage workload bound to one verified batch and one descriptor snapshot. */
class RetrievalLineageResolutionRequest private constructor(
    val requestId: Identifier,
    val source: PrefilteredCandidateBatch,
    val providerBinding: RetrievalStageProviderBinding,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val digest: String

    init {
        requireRetrievalIdentifier(requestId, "Lineage request identifier is invalid.")
        require(providerBinding.stageId == "lineage-resolution") {
            "Lineage request has the wrong provider stage binding."
        }
        require(requestedAtEpochMilli >= source.verifiedAtEpochMilli && deadlineEpochMilli > requestedAtEpochMilli) {
            "Lineage request time window is invalid."
        }
        require(deadlineEpochMilli <= source.sourceDeadlineEpochMilli &&
            deadlineEpochMilli <= source.sourceAccessPlanExpiresAtEpochMilli) {
            "Lineage request exceeds the verified source validity window."
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-lineage-resolution-request-v1")
            text(requestId.value)
            text(source.requestDigest)
            text(source.securityFilterReceipt.digest)
            text(providerBinding.digest)
            long(requestedAtEpochMilli)
            long(deadlineEpochMilli)
        }
    }

    override fun toString(): String = "RetrievalLineageResolutionRequest(<redacted>)"

    companion object {
        @JvmSynthetic
        internal fun prepare(
            requestId: Identifier,
            source: PrefilteredCandidateBatch,
            descriptor: RetrievalLineageResolverDescriptor,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): RetrievalLineageResolutionRequest = RetrievalLineageResolutionRequest(
            requestId,
            source,
            descriptor.binding,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

/** Exact ordered resolver output bound to one request and provider descriptor. */
class RetrievalLineageResolutionBatch private constructor(
    val requestId: Identifier,
    val requestDigest: String,
    val sourceRequestDigest: String,
    val sourceReceiptDigest: String,
    val providerBindingDigest: String,
    resolutions: Collection<RetrievalLineageResolution>,
    val completedAtEpochMilli: Long,
) {
    val resolutions: List<RetrievalLineageResolution> = immutableRetrievalList(
        resolutions,
        RetrievalContractLimits.MAX_CANDIDATES,
        "Lineage resolution batch contains too many entries.",
    )
    val digest: String = retrievalDigest {
        text("flowweft-retrieval-lineage-resolution-batch-v2")
        text(requestId.value)
        text(requestDigest)
        text(sourceRequestDigest)
        text(sourceReceiptDigest)
        text(providerBindingDigest)
        integer(this@RetrievalLineageResolutionBatch.resolutions.size)
        this@RetrievalLineageResolutionBatch.resolutions.forEach { resolution ->
            text(resolution.candidateDigest)
            text(resolution.authoritativeEvidence.digest)
            text(resolution.lineageAuthorityId)
            text(resolution.lineageAuthorityRevision)
        }
        long(completedAtEpochMilli)
    }

    init {
        requireRetrievalIdentifier(requestId, "Lineage response request identifier is invalid.")
        listOf(requestDigest, sourceRequestDigest, sourceReceiptDigest, providerBindingDigest).forEach { value ->
            requireDigest(value, "Lineage response digest binding is invalid.")
        }
        require(completedAtEpochMilli >= 0L) { "Lineage response completion time is invalid." }
    }

    internal fun requireExactFor(request: RetrievalLineageResolutionRequest) {
        require(
            requestId == request.requestId &&
                requestDigest == request.digest &&
                sourceRequestDigest == request.source.requestDigest &&
                sourceReceiptDigest == request.source.securityFilterReceipt.digest &&
                providerBindingDigest == request.providerBinding.digest &&
                completedAtEpochMilli in request.requestedAtEpochMilli until request.deadlineEpochMilli,
        ) { "Lineage resolver response belongs to another exact request or descriptor." }
    }

    companion object {
        @JvmStatic
        fun success(
            request: RetrievalLineageResolutionRequest,
            resolutions: Collection<RetrievalLineageResolution>,
            completedAtEpochMilli: Long,
        ): RetrievalLineageResolutionBatch {
            val snapshot = immutableRetrievalList(
                resolutions,
                RetrievalContractLimits.MAX_CANDIDATES,
                "Lineage resolution batch contains too many entries.",
            )
            require(snapshot.size == request.source.candidates.size) {
                "Lineage resolver must return exactly one answer per candidate."
            }
            request.source.candidates.zip(snapshot).forEach { (candidate, resolution) ->
                require(candidate.digest == resolution.candidateDigest) {
                    "Lineage resolver changed candidate order or identity."
                }
            }
            return RetrievalLineageResolutionBatch(
                request.requestId,
                request.digest,
                request.source.requestDigest,
                request.source.securityFilterReceipt.digest,
                request.providerBinding.digest,
                snapshot,
                completedAtEpochMilli,
            ).also { it.requireExactFor(request) }
        }
    }
}

/** Candidate whose complete lineage was resolved against current local authoritative state. */
class ResolvedRetrievalCandidate private constructor(
    val candidate: RetrievalCandidate,
    val lineageAuthorityId: String,
    val lineageAuthorityRevision: String,
    val lineageProviderBindingDigest: String,
    val resolvedAtEpochMilli: Long,
) {
    val digest: String

    init {
        requireRetrievalText(
            lineageAuthorityId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Lineage authority identifier is invalid.",
        )
        requireDigest(lineageProviderBindingDigest, "Lineage provider binding digest is invalid.")
        requireRetrievalText(
            lineageAuthorityRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Lineage authority revision is invalid.",
        )
        require(resolvedAtEpochMilli >= 0L) { "Lineage resolution time must not be negative." }
        digest = retrievalDigest {
            text("flowweft-resolved-retrieval-candidate-v1")
            text(candidate.digest)
            text(lineageAuthorityId)
            text(lineageAuthorityRevision)
            text(lineageProviderBindingDigest)
            long(resolvedAtEpochMilli)
        }
    }

    companion object {
        @JvmSynthetic
        internal fun resolve(
            candidate: RetrievalCandidate,
            authoritativeEvidence: RetrievalEvidenceRef,
            lineageAuthorityId: String,
            lineageAuthorityRevision: String,
            lineageProviderBindingDigest: String,
            resolvedAtEpochMilli: Long,
        ): ResolvedRetrievalCandidate {
            require(candidate.evidence.identityKey() == authoritativeEvidence.identityKey()) {
                "Authoritative lineage belongs to another evidence reference."
            }
            require(candidate.evidence.digest == authoritativeEvidence.digest) {
                "Provider evidence no longer matches authoritative lineage."
            }
            return ResolvedRetrievalCandidate(
                candidate,
                lineageAuthorityId,
                lineageAuthorityRevision,
                lineageProviderBindingDigest,
                resolvedAtEpochMilli,
            )
        }
    }
}

/** Exact one-to-one lineage-verified form of a prefiltered batch. */
class ResolvedCandidateBatch private constructor(
    val requestDigest: String,
    val securityFilterReceipt: SecurityFilterReceipt,
    val lineageRequestId: Identifier,
    val lineageRequestDigest: String,
    val lineageProviderBindingDigest: String,
    val sourceDeadlineEpochMilli: Long,
    val sourceAccessPlanExpiresAtEpochMilli: Long,
    val prefilterVerifiedAtEpochMilli: Long,
    candidates: Collection<ResolvedRetrievalCandidate>,
) {
    val candidates: List<ResolvedRetrievalCandidate> = immutableRetrievalList(candidates)
    val digest: String = retrievalDigest {
        text("flowweft-resolved-candidate-batch-v1")
        text(requestDigest)
        text(securityFilterReceipt.digest)
        text(lineageRequestId.value)
        text(lineageRequestDigest)
        text(lineageProviderBindingDigest)
        long(sourceDeadlineEpochMilli)
        long(sourceAccessPlanExpiresAtEpochMilli)
        long(prefilterVerifiedAtEpochMilli)
        integer(this@ResolvedCandidateBatch.candidates.size)
        this@ResolvedCandidateBatch.candidates.forEach { text(it.digest) }
    }

    companion object {
        @JvmSynthetic
        internal fun create(
            request: ExecutableRetrievalRequest,
            prefilteredBatch: PrefilteredCandidateBatch,
            lineageRequest: RetrievalLineageResolutionRequest,
            candidates: Collection<ResolvedRetrievalCandidate>,
        ): ResolvedCandidateBatch {
            require(prefilteredBatch.requestDigest == request.digest) {
                "Prefiltered batch belongs to another request."
            }
            require(
                prefilteredBatch.sourceDeadlineEpochMilli == request.deadlineEpochMilli &&
                    prefilteredBatch.sourceAccessPlanExpiresAtEpochMilli == request.accessPlanExpiresAtEpochMilli,
            ) { "Prefiltered batch validity window does not match its request." }
            require(lineageRequest.source === prefilteredBatch ||
                (lineageRequest.source.requestDigest == prefilteredBatch.requestDigest &&
                    lineageRequest.source.securityFilterReceipt.digest == prefilteredBatch.securityFilterReceipt.digest)) {
                "Lineage request belongs to another prefiltered batch."
            }
            val snapshot = immutableRetrievalList(
                candidates,
                RetrievalContractLimits.MAX_CANDIDATES,
                "Resolved candidate batch contains too many candidates.",
            )
            require(snapshot.size == prefilteredBatch.candidates.size) {
                "Lineage resolution must account for every prefiltered candidate."
            }
            prefilteredBatch.candidates.zip(snapshot).forEach { (prefiltered, resolved) ->
                require(prefiltered.digest == resolved.candidate.digest) {
                    "Lineage resolution changed candidate order, identity, mode, score, or rank."
                }
                require(resolved.resolvedAtEpochMilli >= prefilteredBatch.verifiedAtEpochMilli &&
                    resolved.resolvedAtEpochMilli < request.deadlineEpochMilli &&
                    resolved.resolvedAtEpochMilli < request.accessPlanExpiresAtEpochMilli) {
                    "Lineage resolution is outside the source request validity window."
                }
            }
            return ResolvedCandidateBatch(
                request.digest,
                prefilteredBatch.securityFilterReceipt,
                lineageRequest.requestId,
                lineageRequest.digest,
                lineageRequest.providerBinding.digest,
                request.deadlineEpochMilli,
                request.accessPlanExpiresAtEpochMilli,
                prefilteredBatch.verifiedAtEpochMilli,
                snapshot,
            )
        }
    }
}

/** Trusted local resolver returns assertions only; the runtime gate creates final resolved types. */
interface RetrievalLineageResolver {
    fun descriptor(): RetrievalLineageResolverDescriptor
    fun resolve(request: RetrievalLineageResolutionRequest): RetrievalCall<RetrievalLineageResolutionBatch>
}

/** Runtime-owned lineage gate with trusted time and exact request/receipt validation. */
class RetrievalLineageResolutionGate private constructor(
    private val resolver: RetrievalLineageResolver,
    private val clock: java.util.function.LongSupplier,
) {
    fun resolve(
        request: ExecutableRetrievalRequest,
        batch: PrefilteredCandidateBatch,
        requestId: Identifier,
        descriptor: RetrievalLineageResolverDescriptor,
    ): RetrievalCall<ResolvedCandidateBatch> {
        require(batch.requestDigest == request.digest) { "Prefiltered batch belongs to another request." }
        val startedAt = clock.asLong
        require(startedAt >= batch.verifiedAtEpochMilli &&
            startedAt < request.deadlineEpochMilli &&
            startedAt < request.accessPlanExpiresAtEpochMilli) {
            "Lineage resolution cannot start outside the source request validity window."
        }
        val exactRequest = RetrievalLineageResolutionRequest.prepare(
            requestId,
            batch,
            descriptor,
            startedAt,
            minOf(request.deadlineEpochMilli, request.accessPlanExpiresAtEpochMilli),
        )
        val providerCall = requireNotNull(resolver.resolve(exactRequest)) {
            "Lineage resolver returned no call handle."
        }
        return mapRetrievalCall(providerCall) { answers ->
            val resolvedAt = clock.asLong
            require(resolvedAt >= startedAt &&
                resolvedAt < request.deadlineEpochMilli &&
                resolvedAt < request.accessPlanExpiresAtEpochMilli) {
                "Lineage resolution completed outside the source request validity window."
            }
            answers.requireExactFor(exactRequest)
            require(answers.completedAtEpochMilli <= resolvedAt) {
                "Lineage resolver response completion is in the future."
            }
            require(
                answers.sourceRequestDigest == batch.requestDigest &&
                    answers.sourceReceiptDigest == batch.securityFilterReceipt.digest &&
                    answers.resolutions.size == batch.candidates.size,
            ) { "Lineage resolver answers belong to another verified batch." }
            val resolved = batch.candidates.zip(answers.resolutions).map { (candidate, answer) ->
                require(candidate.digest == answer.candidateDigest) {
                    "Lineage resolver answers changed candidate order or identity."
                }
                ResolvedRetrievalCandidate.resolve(
                    candidate,
                    answer.authoritativeEvidence,
                    answer.lineageAuthorityId,
                    answer.lineageAuthorityRevision,
                    exactRequest.providerBinding.digest,
                    resolvedAt,
                )
            }
            ResolvedCandidateBatch.create(request, batch, exactRequest, resolved)
        }
    }

    companion object {
        @JvmStatic
        fun create(
            resolver: RetrievalLineageResolver,
            clock: java.util.function.LongSupplier,
        ): RetrievalLineageResolutionGate = RetrievalLineageResolutionGate(resolver, clock)
    }
}

/**
 * Exact per-candidate authorization question assembled after authoritative lineage resolution.
 * It binds the trusted subject and purpose to one source request, receipt, resolved batch and
 * immutable content lineage. A query-level authorization request cannot be reused as this type.
 */
class RetrievalCandidateAuthorizationRequest private constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val subject: RetrievalAuthorizationSubject,
    val action: String,
    val purposeCode: String,
    val queryAuthorizationRequestId: Identifier,
    val queryAuthorizationRequestDigest: String,
    val sourceRequestDigest: String,
    val sourceReceiptDigest: String,
    val resolvedBatchDigest: String,
    val authorizationProviderBindingDigest: String,
    val sourceDeadlineEpochMilli: Long,
    val sourceAccessPlanExpiresAtEpochMilli: Long,
    val resolvedCandidateDigest: String,
    val evidenceDigest: String,
    val catalogId: Identifier,
    val projectionId: Identifier,
    val documentId: Identifier,
    val versionId: Identifier,
    val fileAssetId: Identifier,
    val fileObjectId: Identifier,
    val chunkId: Identifier?,
    val lineageAuthorityId: String,
    val lineageAuthorityRevision: String,
    val sourceSha256: String,
    val requestedAtEpochMilli: Long,
) {
    val digest: String

    init {
        requireRetrievalIdentifier(id, "Candidate authorization request identifier is invalid.")
        requireRetrievalIdentifier(tenantId, "Candidate authorization tenant identifier is invalid.")
        requireRetrievalIdentifier(
            queryAuthorizationRequestId,
            "Query authorization request identifier is invalid.",
        )
        listOf(
            queryAuthorizationRequestDigest,
            sourceRequestDigest,
            sourceReceiptDigest,
            resolvedBatchDigest,
            authorizationProviderBindingDigest,
            resolvedCandidateDigest,
            evidenceDigest,
            sourceSha256,
        ).forEach { value -> requireDigest(value, "Candidate authorization binding digest is invalid.") }
        listOf(catalogId, projectionId, documentId, versionId, fileAssetId, fileObjectId).forEach { identifier ->
            requireRetrievalIdentifier(identifier, "Candidate authorization resource identifier is invalid.")
        }
        chunkId?.let { requireRetrievalIdentifier(it, "Candidate authorization chunk identifier is invalid.") }
        requireRetrievalText(
            lineageAuthorityId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Candidate authorization lineage authority identifier is invalid.",
        )
        requireRetrievalText(
            lineageAuthorityRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Candidate authorization lineage authority revision is invalid.",
        )
        requireRetrievalText(action, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Candidate authorization action is invalid.")
        requireRetrievalText(
            purposeCode,
            RetrievalContractLimits.MAX_PURPOSE_CODE_POINTS,
            "Candidate authorization purpose is invalid.",
        )
        require(requestedAtEpochMilli >= 0L) { "Candidate authorization request time must not be negative." }
        require(requestedAtEpochMilli < sourceDeadlineEpochMilli &&
            requestedAtEpochMilli < sourceAccessPlanExpiresAtEpochMilli) {
            "Candidate authorization request is outside the source request validity window."
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-candidate-authorization-request-v1")
            text(id.value)
            text(tenantId.value)
            text(subject.digest)
            text(action)
            text(purposeCode)
            text(queryAuthorizationRequestId.value)
            text(queryAuthorizationRequestDigest)
            text(sourceRequestDigest)
            text(sourceReceiptDigest)
            text(resolvedBatchDigest)
            text(authorizationProviderBindingDigest)
            long(sourceDeadlineEpochMilli)
            long(sourceAccessPlanExpiresAtEpochMilli)
            text(resolvedCandidateDigest)
            text(evidenceDigest)
            text(catalogId.value)
            text(projectionId.value)
            text(documentId.value)
            text(versionId.value)
            text(fileAssetId.value)
            text(fileObjectId.value)
            optionalText(chunkId?.value)
            text(lineageAuthorityId)
            text(lineageAuthorityRevision)
            text(sourceSha256)
            long(requestedAtEpochMilli)
        }
    }

    override fun toString(): String = "RetrievalCandidateAuthorizationRequest(action=$action)"

    companion object {
        @JvmSynthetic
        internal fun create(
            id: Identifier,
            queryAuthorizationRequest: RetrievalAuthorizationRequest,
            resolvedBatch: ResolvedCandidateBatch,
            resolvedCandidate: ResolvedRetrievalCandidate,
            providerBinding: RetrievalStageProviderBinding,
            requestedAtEpochMilli: Long,
        ): RetrievalCandidateAuthorizationRequest {
            val receipt = resolvedBatch.securityFilterReceipt
            require(
                receipt.authorizationRequestId == queryAuthorizationRequest.id &&
                    receipt.authorizationRequestDigest == queryAuthorizationRequest.digest,
            ) { "Resolved batch belongs to another query authorization request." }
            require(resolvedBatch.candidates.any { it.digest == resolvedCandidate.digest }) {
                "Candidate authorization target is not in the resolved batch."
            }
            val evidence = resolvedCandidate.candidate.evidence
            require(evidence.tenantId == queryAuthorizationRequest.tenantId) {
                "Candidate authorization target belongs to another tenant."
            }
            require(requestedAtEpochMilli >= resolvedCandidate.resolvedAtEpochMilli) {
                "Candidate authorization request predates lineage resolution."
            }
            return RetrievalCandidateAuthorizationRequest(
                id,
                queryAuthorizationRequest.tenantId,
                queryAuthorizationRequest.subject,
                queryAuthorizationRequest.action,
                queryAuthorizationRequest.purposeCode,
                queryAuthorizationRequest.id,
                queryAuthorizationRequest.digest,
                resolvedBatch.requestDigest,
                receipt.digest,
                resolvedBatch.digest,
                providerBinding.digest,
                resolvedBatch.sourceDeadlineEpochMilli,
                resolvedBatch.sourceAccessPlanExpiresAtEpochMilli,
                resolvedCandidate.digest,
                evidence.digest,
                evidence.catalogId,
                evidence.projectionId,
                evidence.documentId,
                evidence.versionId,
                evidence.fileAssetId,
                evidence.fileObjectId,
                evidence.chunkId,
                resolvedCandidate.lineageAuthorityId,
                resolvedCandidate.lineageAuthorityRevision,
                evidence.sourceSha256,
                requestedAtEpochMilli,
            )
        }
    }
}

/** Stable identity and capability snapshot for the current candidate authorization bridge. */
class RetrievalCandidateAuthorizerDescriptor private constructor(
    val providerTypeId: String,
    val providerInstanceId: String,
    val configurationDigest: String,
    val capabilityDigest: String,
    val capabilityRevision: String,
    val supportsCancellation: Boolean,
) {
    val digest: String
    val binding: RetrievalStageProviderBinding

    init {
        listOf(providerTypeId, providerInstanceId, capabilityRevision).forEach { value ->
            requireRetrievalText(
                value,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Candidate authorizer descriptor text is invalid.",
            )
        }
        requireDigest(configurationDigest, "Candidate authorizer configuration digest is invalid.")
        requireDigest(capabilityDigest, "Candidate authorizer capability digest is invalid.")
        digest = retrievalDigest {
            text("flowweft-retrieval-candidate-authorizer-descriptor-v1")
            text(providerTypeId)
            text(providerInstanceId)
            text(configurationDigest)
            text(capabilityDigest)
            text(capabilityRevision)
            boolean(supportsCancellation)
        }
        binding = RetrievalStageProviderBinding.create(
            "candidate-authorization",
            providerTypeId,
            providerInstanceId,
            configurationDigest,
            capabilityDigest,
            capabilityRevision,
            digest,
            supportsCancellation,
        )
    }

    override fun toString(): String = "RetrievalCandidateAuthorizerDescriptor(providerType=$providerTypeId)"

    companion object {
        @JvmStatic
        fun create(
            providerTypeId: String,
            providerInstanceId: String,
            configurationDigest: String,
            capabilityDigest: String,
            capabilityRevision: String,
            supportsCancellation: Boolean,
        ): RetrievalCandidateAuthorizerDescriptor = RetrievalCandidateAuthorizerDescriptor(
            providerTypeId,
            providerInstanceId,
            configurationDigest,
            capabilityDigest,
            capabilityRevision,
            supportsCancellation,
        )
    }
}

/** Runtime-assembled, one-to-one candidate authorization workload. */
class RetrievalCandidateAuthorizationBatch private constructor(
    val batchId: Identifier,
    val queryAuthorizationRequestId: Identifier,
    val queryAuthorizationRequestDigest: String,
    val sourceRequestDigest: String,
    val sourceReceiptDigest: String,
    val resolvedBatchDigest: String,
    val providerBinding: RetrievalStageProviderBinding,
    requests: Collection<RetrievalCandidateAuthorizationRequest>,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val requests: List<RetrievalCandidateAuthorizationRequest> = immutableRetrievalList(
        requests,
        RetrievalContractLimits.MAX_CANDIDATES,
        "Candidate authorization batch contains too many requests.",
    )
    val digest: String

    init {
        requireRetrievalIdentifier(batchId, "Candidate authorization batch identifier is invalid.")
        require(providerBinding.stageId == "candidate-authorization") {
            "Candidate authorization batch has the wrong provider stage binding."
        }
        require(deadlineEpochMilli > requestedAtEpochMilli) {
            "Candidate authorization deadline must follow its request time."
        }
        require(this.requests.all { it.authorizationProviderBindingDigest == providerBinding.digest }) {
            "Candidate authorization requests belong to another provider binding."
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-candidate-authorization-batch-v2")
            text(batchId.value)
            text(queryAuthorizationRequestId.value)
            text(queryAuthorizationRequestDigest)
            text(sourceRequestDigest)
            text(sourceReceiptDigest)
            text(resolvedBatchDigest)
            text(providerBinding.digest)
            integer(this@RetrievalCandidateAuthorizationBatch.requests.size)
            this@RetrievalCandidateAuthorizationBatch.requests.forEach { text(it.digest) }
            long(requestedAtEpochMilli)
            long(deadlineEpochMilli)
        }
    }

    companion object {
        @JvmSynthetic
        internal fun prepare(
            batchId: Identifier,
            queryAuthorizationRequest: RetrievalAuthorizationRequest,
            resolvedBatch: ResolvedCandidateBatch,
            requestIds: Collection<Identifier>,
            descriptor: RetrievalCandidateAuthorizerDescriptor,
            requestedAtEpochMilli: Long,
        ): RetrievalCandidateAuthorizationBatch {
            val ids = immutableRetrievalList(
                requestIds,
                RetrievalContractLimits.MAX_CANDIDATES,
                "Candidate authorization batch contains too many request identifiers.",
            )
            require(ids.size == resolvedBatch.candidates.size) {
                "Candidate authorization requires exactly one request identifier per resolved candidate."
            }
            require(ids.toSet().size == ids.size) { "Candidate authorization request identifiers must be unique." }
            val requests = ids.zip(resolvedBatch.candidates).map { (id, candidate) ->
                RetrievalCandidateAuthorizationRequest.create(
                    id,
                    queryAuthorizationRequest,
                    resolvedBatch,
                    candidate,
                    descriptor.binding,
                    requestedAtEpochMilli,
                )
            }
            return RetrievalCandidateAuthorizationBatch(
                batchId,
                queryAuthorizationRequest.id,
                queryAuthorizationRequest.digest,
                resolvedBatch.requestDigest,
                resolvedBatch.securityFilterReceipt.digest,
                resolvedBatch.digest,
                descriptor.binding,
                requests,
                requestedAtEpochMilli,
                minOf(resolvedBatch.sourceDeadlineEpochMilli, resolvedBatch.sourceAccessPlanExpiresAtEpochMilli),
            )
        }
    }
}

/** Explicit candidate-level authorization outcome, cryptographically bound to its exact request. */
class RetrievalCandidateAuthorizationDecision private constructor(
    val decisionId: Identifier,
    val requestId: Identifier,
    val requestDigest: String,
    val authorizationAuthorityId: String,
    val policyRevision: String,
    val decidedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
    val allowed: Boolean,
    val denialCode: RetrievalDenialCode?,
) {
    init {
        requireRetrievalIdentifier(decisionId, "Candidate authorization decision identifier is invalid.")
        requireRetrievalIdentifier(requestId, "Candidate authorization request identifier is invalid.")
        requireDigest(requestDigest, "Candidate authorization request digest is invalid.")
        requireRetrievalText(
            authorizationAuthorityId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Candidate authorization authority identifier is invalid.",
        )
        requireRetrievalText(
            policyRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Candidate authorization policy revision is invalid.",
        )
        require(decidedAtEpochMilli >= 0L) { "Candidate authorization decision time must not be negative." }
        require(expiresAtEpochMilli > decidedAtEpochMilli) {
            "Candidate authorization expiration must follow the decision."
        }
        require(expiresAtEpochMilli - decidedAtEpochMilli <= AuthorizedRetrievalCandidate.MAX_AUTHORIZED_CANDIDATE_TTL_MILLIS) {
            "Candidate authorization decision lifetime is too long."
        }
        require(allowed == (denialCode == null)) {
            "Candidate authorization decision must contain exactly one allow or deny outcome."
        }
    }

    internal fun requireValidFor(request: RetrievalCandidateAuthorizationRequest, nowEpochMilli: Long) {
        require(requestId == request.id && requestDigest == request.digest) {
            "Candidate authorization decision belongs to another request."
        }
        require(decidedAtEpochMilli >= request.requestedAtEpochMilli) {
            "Candidate authorization decision predates its request."
        }
        require(decidedAtEpochMilli <= nowEpochMilli && nowEpochMilli < expiresAtEpochMilli) {
            "Candidate authorization decision is not currently valid."
        }
        require(expiresAtEpochMilli <= request.sourceDeadlineEpochMilli &&
            expiresAtEpochMilli <= request.sourceAccessPlanExpiresAtEpochMilli) {
            "Candidate authorization decision exceeds the source request validity window."
        }
    }

    override fun toString(): String = "RetrievalCandidateAuthorizationDecision(allowed=$allowed)"

    companion object {
        @JvmStatic
        fun allow(
            decisionId: Identifier,
            request: RetrievalCandidateAuthorizationRequest,
            authorizationAuthorityId: String,
            policyRevision: String,
            decidedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): RetrievalCandidateAuthorizationDecision = RetrievalCandidateAuthorizationDecision(
            decisionId,
            request.id,
            request.digest,
            authorizationAuthorityId,
            policyRevision,
            decidedAtEpochMilli,
            expiresAtEpochMilli,
            true,
            null,
        )

        @JvmStatic
        fun deny(
            decisionId: Identifier,
            request: RetrievalCandidateAuthorizationRequest,
            authorizationAuthorityId: String,
            policyRevision: String,
            decidedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
            denialCode: RetrievalDenialCode,
        ): RetrievalCandidateAuthorizationDecision = RetrievalCandidateAuthorizationDecision(
            decisionId,
            request.id,
            request.digest,
            authorizationAuthorityId,
            policyRevision,
            decidedAtEpochMilli,
            expiresAtEpochMilli,
            false,
            denialCode,
        )
    }
}

/** Exact, ordered answer set returned by the trusted candidate authorizer. */
class RetrievalCandidateAuthorizationDecisionBatch private constructor(
    val requestBatchId: Identifier,
    val requestBatchDigest: String,
    val providerBindingDigest: String,
    decisions: Collection<RetrievalCandidateAuthorizationDecision>,
    val completedAtEpochMilli: Long,
) {
    val decisions: List<RetrievalCandidateAuthorizationDecision> = immutableRetrievalList(
        decisions,
        RetrievalContractLimits.MAX_CANDIDATES,
        "Candidate authorization decision batch contains too many decisions.",
    )
    val digest: String = retrievalDigest {
        text("flowweft-retrieval-candidate-authorization-decision-batch-v2")
        text(requestBatchId.value)
        text(requestBatchDigest)
        text(providerBindingDigest)
        integer(this@RetrievalCandidateAuthorizationDecisionBatch.decisions.size)
        this@RetrievalCandidateAuthorizationDecisionBatch.decisions.forEach { decision ->
            text(decision.decisionId.value)
            text(decision.requestId.value)
            text(decision.requestDigest)
            text(decision.authorizationAuthorityId)
            text(decision.policyRevision)
            long(decision.decidedAtEpochMilli)
            long(decision.expiresAtEpochMilli)
            boolean(decision.allowed)
            optionalText(decision.denialCode?.id)
        }
        long(completedAtEpochMilli)
    }

    init {
        requireRetrievalIdentifier(requestBatchId, "Candidate authorization response batch identifier is invalid.")
        requireDigest(requestBatchDigest, "Candidate authorization response request digest is invalid.")
        requireDigest(providerBindingDigest, "Candidate authorization response provider binding is invalid.")
        require(completedAtEpochMilli >= 0L) { "Candidate authorization response completion time is invalid." }
    }

    internal fun requireExactFor(requests: RetrievalCandidateAuthorizationBatch) {
        require(
            requestBatchId == requests.batchId &&
                requestBatchDigest == requests.digest &&
                providerBindingDigest == requests.providerBinding.digest &&
                completedAtEpochMilli in requests.requestedAtEpochMilli until requests.deadlineEpochMilli,
        ) { "Candidate authorization response belongs to another exact request or descriptor." }
    }

    companion object {
        @JvmStatic
        fun success(
            requests: RetrievalCandidateAuthorizationBatch,
            decisions: Collection<RetrievalCandidateAuthorizationDecision>,
            completedAtEpochMilli: Long,
        ): RetrievalCandidateAuthorizationDecisionBatch {
            val snapshot = immutableRetrievalList(
                decisions,
                RetrievalContractLimits.MAX_CANDIDATES,
                "Candidate authorization decision batch contains too many decisions.",
            )
            require(snapshot.size == requests.requests.size) {
                "Candidate authorization must return exactly one decision per request."
            }
            requests.requests.zip(snapshot).forEach { (request, decision) ->
                require(decision.requestId == request.id && decision.requestDigest == request.digest) {
                    "Candidate authorization decisions must preserve exact request order and identity."
                }
            }
            require(snapshot.map { it.decisionId }.toSet().size == snapshot.size) {
                "Candidate authorization decision identifiers must be unique."
            }
            require(snapshot.all { decision -> decision.decidedAtEpochMilli <= completedAtEpochMilli }) {
                "Candidate authorization response predates one of its decisions."
            }
            return RetrievalCandidateAuthorizationDecisionBatch(
                requests.batchId,
                requests.digest,
                requests.providerBinding.digest,
                snapshot,
                completedAtEpochMilli,
            ).also { it.requireExactFor(requests) }
        }
    }
}

/** Candidate that passed a fresh, authoritative, resource-bound authorization recheck. */
class AuthorizedRetrievalCandidate private constructor(
    val resolvedCandidate: ResolvedRetrievalCandidate,
    val candidateAuthorizationRequestId: Identifier,
    val candidateAuthorizationRequestDigest: String,
    val queryAuthorizationRequestId: Identifier,
    val queryAuthorizationRequestDigest: String,
    val sourceRequestDigest: String,
    val sourceReceiptDigest: String,
    val resolvedBatchDigest: String,
    val authorizationProviderBindingDigest: String,
    val sourceDeadlineEpochMilli: Long,
    val sourceAccessPlanExpiresAtEpochMilli: Long,
    val authorizationDecisionId: Identifier,
    val authorizationAuthorityId: String,
    val policyRevision: String,
    val authorizedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val digest: String

    init {
        requireRetrievalIdentifier(
            candidateAuthorizationRequestId,
            "Candidate authorization request identifier is invalid.",
        )
        requireRetrievalIdentifier(authorizationDecisionId, "Authorization decision identifier is invalid.")
        listOf(
            candidateAuthorizationRequestDigest,
            queryAuthorizationRequestDigest,
            sourceRequestDigest,
            sourceReceiptDigest,
            resolvedBatchDigest,
            authorizationProviderBindingDigest,
        ).forEach { value -> requireDigest(value, "Authorization binding digest is invalid.") }
        requireRetrievalText(
            authorizationAuthorityId,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Authorization authority identifier is invalid.",
        )
        requireRetrievalText(
            policyRevision,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Authorization policy revision is invalid.",
        )
        require(authorizedAtEpochMilli >= resolvedCandidate.resolvedAtEpochMilli) {
            "Authorization recheck predates lineage resolution."
        }
        require(expiresAtEpochMilli > authorizedAtEpochMilli) {
            "Authorization recheck expiration must follow its decision."
        }
        require(expiresAtEpochMilli - authorizedAtEpochMilli <= MAX_AUTHORIZED_CANDIDATE_TTL_MILLIS) {
            "Authorization recheck lifetime is too long."
        }
        require(authorizedAtEpochMilli < sourceDeadlineEpochMilli &&
            authorizedAtEpochMilli < sourceAccessPlanExpiresAtEpochMilli &&
            expiresAtEpochMilli <= sourceDeadlineEpochMilli &&
            expiresAtEpochMilli <= sourceAccessPlanExpiresAtEpochMilli) {
            "Authorization recheck exceeds the source request validity window."
        }
        digest = retrievalDigest {
            text("flowweft-authorized-retrieval-candidate-v1")
            text(resolvedCandidate.digest)
            text(candidateAuthorizationRequestId.value)
            text(candidateAuthorizationRequestDigest)
            text(queryAuthorizationRequestId.value)
            text(queryAuthorizationRequestDigest)
            text(sourceRequestDigest)
            text(sourceReceiptDigest)
            text(resolvedBatchDigest)
            text(authorizationProviderBindingDigest)
            long(sourceDeadlineEpochMilli)
            long(sourceAccessPlanExpiresAtEpochMilli)
            text(authorizationDecisionId.value)
            text(authorizationAuthorityId)
            text(policyRevision)
            long(authorizedAtEpochMilli)
            long(expiresAtEpochMilli)
        }
    }

    fun requireUsableAt(nowEpochMilli: Long) {
        require(nowEpochMilli in authorizedAtEpochMilli until expiresAtEpochMilli) {
            "Authorized retrieval candidate has expired or is not yet valid."
        }
    }

    companion object {
        const val MAX_AUTHORIZED_CANDIDATE_TTL_MILLIS: Long = 60_000L

        @JvmSynthetic
        internal fun authorize(
            authorizationRequest: RetrievalCandidateAuthorizationRequest,
            resolvedCandidate: ResolvedRetrievalCandidate,
            decision: RetrievalCandidateAuthorizationDecision,
        ): AuthorizedRetrievalCandidate {
            check(decision.allowed) { "Candidate authorization decision denied content access." }
            val evidence = resolvedCandidate.candidate.evidence
            require(authorizationRequest.resolvedCandidateDigest == resolvedCandidate.digest) {
                "Authorization request belongs to another resolved candidate."
            }
            require(
                authorizationRequest.tenantId == evidence.tenantId &&
                    authorizationRequest.evidenceDigest == evidence.digest &&
                    authorizationRequest.catalogId == evidence.catalogId &&
                    authorizationRequest.projectionId == evidence.projectionId &&
                    authorizationRequest.documentId == evidence.documentId &&
                    authorizationRequest.versionId == evidence.versionId &&
                    authorizationRequest.fileAssetId == evidence.fileAssetId &&
                    authorizationRequest.fileObjectId == evidence.fileObjectId &&
                    authorizationRequest.chunkId == evidence.chunkId &&
                    authorizationRequest.lineageAuthorityId == resolvedCandidate.lineageAuthorityId &&
                    authorizationRequest.lineageAuthorityRevision == resolvedCandidate.lineageAuthorityRevision &&
                    authorizationRequest.sourceSha256 == evidence.sourceSha256,
            ) { "Authorization request resource binding does not match the resolved candidate." }
            return AuthorizedRetrievalCandidate(
                resolvedCandidate,
                authorizationRequest.id,
                authorizationRequest.digest,
                authorizationRequest.queryAuthorizationRequestId,
                authorizationRequest.queryAuthorizationRequestDigest,
                authorizationRequest.sourceRequestDigest,
                authorizationRequest.sourceReceiptDigest,
                authorizationRequest.resolvedBatchDigest,
                authorizationRequest.authorizationProviderBindingDigest,
                authorizationRequest.sourceDeadlineEpochMilli,
                authorizationRequest.sourceAccessPlanExpiresAtEpochMilli,
                decision.decisionId,
                decision.authorizationAuthorityId,
                decision.policyRevision,
                decision.decidedAtEpochMilli,
                decision.expiresAtEpochMilli,
            )
        }
    }
}

/** Authorized subset. Omitted candidates remain opaque and cannot be hydrated. */
class AuthorizedCandidateBatch private constructor(
    val authorizationRequestId: Identifier,
    val authorizationRequestDigest: String,
    val sourceRequestDigest: String,
    val candidateAuthorizationBatchId: Identifier,
    val candidateAuthorizationBatchDigest: String,
    val authorizationProviderBindingDigest: String,
    candidates: Collection<AuthorizedRetrievalCandidate>,
) {
    val candidates: List<AuthorizedRetrievalCandidate> = immutableRetrievalList(
        candidates,
        RetrievalContractLimits.MAX_CANDIDATES,
        "Authorized candidate batch contains too many candidates.",
    )
    val digest: String = retrievalDigest {
        text("flowweft-authorized-candidate-batch-v2")
        text(authorizationRequestId.value)
        text(authorizationRequestDigest)
        text(sourceRequestDigest)
        text(candidateAuthorizationBatchId.value)
        text(candidateAuthorizationBatchDigest)
        text(authorizationProviderBindingDigest)
        integer(this@AuthorizedCandidateBatch.candidates.size)
        this@AuthorizedCandidateBatch.candidates.forEach { text(it.digest) }
    }

    companion object {
        @JvmSynthetic
        internal fun create(
            authorizationRequest: RetrievalAuthorizationRequest,
            resolvedBatch: ResolvedCandidateBatch,
            authorizationBatch: RetrievalCandidateAuthorizationBatch,
            candidates: Collection<AuthorizedRetrievalCandidate>,
        ): AuthorizedCandidateBatch {
            require(
                authorizationBatch.queryAuthorizationRequestId == authorizationRequest.id &&
                    authorizationBatch.queryAuthorizationRequestDigest == authorizationRequest.digest &&
                    authorizationBatch.sourceRequestDigest == resolvedBatch.requestDigest &&
                    authorizationBatch.sourceReceiptDigest == resolvedBatch.securityFilterReceipt.digest &&
                    authorizationBatch.resolvedBatchDigest == resolvedBatch.digest,
            ) { "Candidate authorization batch belongs to another query or resolved batch." }
            val snapshot = immutableRetrievalList(
                candidates,
                RetrievalContractLimits.MAX_CANDIDATES,
                "Authorized candidate batch contains too many candidates.",
            )
            require(snapshot.map { it.resolvedCandidate.candidate.evidence.identityKey() }.toSet().size == snapshot.size) {
                "Authorized candidate batch contains duplicates."
            }
            snapshot.forEach { authorized ->
                require(
                    authorized.queryAuthorizationRequestId == authorizationRequest.id &&
                        authorized.queryAuthorizationRequestDigest == authorizationRequest.digest &&
                        authorized.sourceRequestDigest == resolvedBatch.requestDigest &&
                        authorized.sourceReceiptDigest == resolvedBatch.securityFilterReceipt.digest &&
                        authorized.resolvedBatchDigest == resolvedBatch.digest &&
                        authorized.authorizationProviderBindingDigest == authorizationBatch.providerBinding.digest &&
                        authorized.sourceDeadlineEpochMilli == resolvedBatch.sourceDeadlineEpochMilli &&
                        authorized.sourceAccessPlanExpiresAtEpochMilli ==
                        resolvedBatch.sourceAccessPlanExpiresAtEpochMilli,
                ) { "Authorized candidate belongs to another authorization request." }
                require(resolvedBatch.candidates.any { it.digest == authorized.resolvedCandidate.digest }) {
                    "Authorization recheck introduced a candidate that was not resolved."
                }
            }
            val order = resolvedBatch.candidates.mapIndexed { index, candidate ->
                candidate.digest to index
            }.toMap()
            require(snapshot.zipWithNext().all { (left, right) ->
                checkNotNull(order[left.resolvedCandidate.digest]) <
                    checkNotNull(order[right.resolvedCandidate.digest])
            }) { "Authorized candidates must preserve resolved ranking order." }
            return AuthorizedCandidateBatch(
                authorizationRequest.id,
                authorizationRequest.digest,
                resolvedBatch.requestDigest,
                authorizationBatch.batchId,
                authorizationBatch.digest,
                authorizationBatch.providerBinding.digest,
                snapshot,
            )
        }
    }
}

/** Trusted host bridge answers exact runtime-assembled candidate authorization questions. */
interface RetrievalCandidateAuthorizer {
    fun descriptor(): RetrievalCandidateAuthorizerDescriptor
    fun authorize(requests: RetrievalCandidateAuthorizationBatch):
        RetrievalCall<RetrievalCandidateAuthorizationDecisionBatch>
}

/**
 * Runtime-owned assembler. Public authorization DTOs are not permission proofs; only this gate
 * converts current, exact authorizer decisions into hydration-capable candidates.
 */
class RetrievalCandidateAuthorizationGate private constructor(
    private val authorizer: RetrievalCandidateAuthorizer,
    private val clock: java.util.function.LongSupplier,
) {
    fun authorize(
        queryAuthorizationRequest: RetrievalAuthorizationRequest,
        resolvedBatch: ResolvedCandidateBatch,
        batchId: Identifier,
        requestIds: Collection<Identifier>,
        descriptor: RetrievalCandidateAuthorizerDescriptor,
    ): RetrievalCall<AuthorizedCandidateBatch> {
        val requestedAt = clock.asLong
        require(requestedAt >= 0L) { "Candidate authorization clock returned a negative time." }
        val requestBatch = RetrievalCandidateAuthorizationBatch.prepare(
            batchId,
            queryAuthorizationRequest,
            resolvedBatch,
            requestIds,
            descriptor,
            requestedAt,
        )
        val providerCall = requireNotNull(authorizer.authorize(requestBatch)) {
            "Candidate authorizer returned no call handle."
        }
        return mapRetrievalCall(providerCall) { decisions ->
            val verifiedAt = clock.asLong
            require(verifiedAt >= requestedAt) { "Candidate authorization clock moved backwards." }
            decisions.requireExactFor(requestBatch)
            require(decisions.completedAtEpochMilli <= verifiedAt) {
                "Candidate authorization response completion is in the future."
            }
            require(decisions.decisions.size == requestBatch.requests.size) {
                "Candidate authorization decision count is inconsistent."
            }
            val byDigest = resolvedBatch.candidates.associateBy { it.digest }
            val authorized = ArrayList<AuthorizedRetrievalCandidate>()
            requestBatch.requests.zip(decisions.decisions).forEach { (request, decision) ->
                decision.requireValidFor(request, verifiedAt)
                if (decision.allowed) {
                    val resolved = checkNotNull(byDigest[request.resolvedCandidateDigest]) {
                        "Candidate authorization request belongs to another resolved batch."
                    }
                    authorized.add(AuthorizedRetrievalCandidate.authorize(request, resolved, decision))
                }
            }
            AuthorizedCandidateBatch.create(queryAuthorizationRequest, resolvedBatch, requestBatch, authorized)
        }
    }

    companion object {
        @JvmStatic
        fun create(
            authorizer: RetrievalCandidateAuthorizer,
            clock: java.util.function.LongSupplier,
        ): RetrievalCandidateAuthorizationGate = RetrievalCandidateAuthorizationGate(authorizer, clock)
    }
}

/** Exact runtime policy decision governing whether this content provider may receive data off-host. */
class RetrievalContentEgressDecision private constructor(
    val decisionId: Identifier,
    val candidateDigest: String,
    val providerBindingDigest: String,
    val policyDigest: String,
    val decisionAuthorityId: String,
    val policyRevision: String,
    val egressRequired: Boolean,
    val egressAllowed: Boolean,
    val decidedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val digest: String

    init {
        requireRetrievalIdentifier(decisionId, "Content egress decision identifier is invalid.")
        listOf(candidateDigest, providerBindingDigest, policyDigest).forEach { value ->
            requireDigest(value, "Content egress decision digest binding is invalid.")
        }
        listOf(decisionAuthorityId, policyRevision).forEach { value ->
            requireRetrievalText(
                value,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Content egress decision authority binding is invalid.",
            )
        }
        require(!egressRequired || egressAllowed) { "Required content egress was not authorized." }
        require(expiresAtEpochMilli > decidedAtEpochMilli) { "Content egress decision window is invalid." }
        digest = retrievalDigest {
            text("flowweft-retrieval-content-egress-decision-v1")
            text(decisionId.value)
            text(candidateDigest)
            text(providerBindingDigest)
            text(policyDigest)
            text(decisionAuthorityId)
            text(policyRevision)
            boolean(egressRequired)
            boolean(egressAllowed)
            long(decidedAtEpochMilli)
            long(expiresAtEpochMilli)
        }
    }

    internal fun requireExactFor(
        candidate: AuthorizedRetrievalCandidate,
        providerBinding: RetrievalStageProviderBinding,
        nowEpochMilli: Long,
    ) {
        require(
            candidateDigest == candidate.digest &&
                providerBindingDigest == providerBinding.digest &&
                nowEpochMilli in decidedAtEpochMilli until expiresAtEpochMilli,
        ) { "Content egress decision does not match the exact candidate and provider." }
    }

    companion object {
        @JvmStatic
        fun create(
            decisionId: Identifier,
            candidate: AuthorizedRetrievalCandidate,
            providerBinding: RetrievalStageProviderBinding,
            policyDigest: String,
            decisionAuthorityId: String,
            policyRevision: String,
            egressRequired: Boolean,
            egressAllowed: Boolean,
            decidedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): RetrievalContentEgressDecision {
            require(providerBinding.stageId == "content-hydration") {
                "Content egress decision has the wrong provider stage binding."
            }
            candidate.requireUsableAt(decidedAtEpochMilli)
            require(expiresAtEpochMilli <= candidate.expiresAtEpochMilli &&
                expiresAtEpochMilli <= candidate.sourceDeadlineEpochMilli &&
                expiresAtEpochMilli <= candidate.sourceAccessPlanExpiresAtEpochMilli) {
                "Content egress decision exceeds candidate authorization validity."
            }
            return RetrievalContentEgressDecision(
                decisionId,
                candidate.digest,
                providerBinding.digest,
                policyDigest,
                decisionAuthorityId,
                policyRevision,
                egressRequired,
                egressAllowed,
                decidedAtEpochMilli,
                expiresAtEpochMilli,
            )
        }
    }
}

/** Hydration input can only be constructed from an authorized candidate and exact egress decision. */
class RetrievalHydrationRequest private constructor(
    val id: Identifier,
    val candidate: AuthorizedRetrievalCandidate,
    val providerBinding: RetrievalStageProviderBinding,
    val egressDecision: RetrievalContentEgressDecision,
    val maximumContentCodePoints: Int,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val providerInstanceId: String = providerBinding.providerInstanceId
    val providerConfigurationDigest: String = providerBinding.configurationDigest
    val providerCapabilityDigest: String = providerBinding.capabilityDigest
    val providerRevision: String = providerBinding.capabilityRevision
    val providerDescriptorDigest: String = providerBinding.descriptorDigest
    val egressDecisionDigest: String = egressDecision.digest
    val digest: String

    init {
        requireRetrievalIdentifier(id, "Retrieval hydration request identifier is invalid.")
        require(providerBinding.stageId == "content-hydration") {
            "Hydration request has the wrong provider stage binding."
        }
        require(maximumContentCodePoints in 1..RetrievalContractLimits.MAX_CONTENT_CODE_POINTS) {
            "Retrieval hydration content limit is invalid."
        }
        candidate.requireUsableAt(requestedAtEpochMilli)
        egressDecision.requireExactFor(candidate, providerBinding, requestedAtEpochMilli)
        require(deadlineEpochMilli > requestedAtEpochMilli &&
            deadlineEpochMilli <= egressDecision.expiresAtEpochMilli &&
            deadlineEpochMilli <= candidate.expiresAtEpochMilli &&
            deadlineEpochMilli <= candidate.sourceDeadlineEpochMilli &&
            deadlineEpochMilli <= candidate.sourceAccessPlanExpiresAtEpochMilli) {
            "Retrieval hydration deadline must be within authorization and egress validity."
        }
        digest = retrievalDigest {
            text("flowweft-retrieval-hydration-request-v2")
            text(id.value)
            text(candidate.digest)
            text(providerBinding.digest)
            text(egressDecision.digest)
            integer(maximumContentCodePoints)
            long(requestedAtEpochMilli)
            long(deadlineEpochMilli)
        }
    }

    override fun toString(): String = "RetrievalHydrationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun create(
            id: Identifier,
            candidate: AuthorizedRetrievalCandidate,
            providerBinding: RetrievalStageProviderBinding,
            egressDecision: RetrievalContentEgressDecision,
            maximumContentCodePoints: Int,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): RetrievalHydrationRequest = RetrievalHydrationRequest(
            id,
            candidate,
            providerBinding,
            egressDecision,
            maximumContentCodePoints,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

/** Untrusted adapter payload, exactly bound to one hydration request and descriptor snapshot. */
class RetrievedContentPayload private constructor(
    val hydrationRequestId: Identifier,
    val hydrationRequestDigest: String,
    val providerBindingDigest: String,
    val providerInstanceId: String,
    val providerConfigurationDigest: String,
    val providerCapabilityDigest: String,
    val providerRevision: String,
    val providerDescriptorDigest: String,
    val egressDecisionDigest: String,
    val text: String,
    val mediaType: String,
    val sourceSha256: String,
) {
    val contentSha256: String
    val digest: String

    init {
        requireRetrievalIdentifier(hydrationRequestId, "Hydration response request identifier is invalid.")
        listOf(
            hydrationRequestDigest,
            providerBindingDigest,
            providerConfigurationDigest,
            providerCapabilityDigest,
            providerDescriptorDigest,
            egressDecisionDigest,
            sourceSha256,
        ).forEach { value ->
            requireDigest(value, "Hydration response digest binding is invalid.")
        }
        listOf(providerInstanceId, providerRevision).forEach { value ->
            requireRetrievalText(
                value,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Hydration response provider binding is invalid.",
            )
        }
        requireRetrievalContent(text, RetrievalContractLimits.MAX_CONTENT_CODE_POINTS, "Retrieved content is invalid.")
        requireMediaType(mediaType)
        contentSha256 = sha256Hex(text.toByteArray(Charsets.UTF_8))
        digest = retrievalDigest {
            text("flowweft-retrieved-content-payload-v2")
            text(hydrationRequestId.value)
            text(hydrationRequestDigest)
            text(providerBindingDigest)
            text(providerInstanceId)
            text(providerConfigurationDigest)
            text(providerCapabilityDigest)
            text(providerRevision)
            text(providerDescriptorDigest)
            text(egressDecisionDigest)
            text(contentSha256)
            text(mediaType)
            text(sourceSha256)
        }
    }

    internal fun requireExactFor(request: RetrievalHydrationRequest) {
        require(
            hydrationRequestId == request.id &&
                hydrationRequestDigest == request.digest &&
                providerBindingDigest == request.providerBinding.digest &&
                providerInstanceId == request.providerInstanceId &&
                providerConfigurationDigest == request.providerConfigurationDigest &&
                providerCapabilityDigest == request.providerCapabilityDigest &&
                providerRevision == request.providerRevision &&
                providerDescriptorDigest == request.providerDescriptorDigest &&
                egressDecisionDigest == request.egressDecision.digest,
        ) { "Hydration response belongs to another exact request, provider, or egress decision." }
    }

    override fun toString(): String = "RetrievedContentPayload(mediaType=$mediaType)"

    companion object {
        @JvmStatic
        fun success(
            request: RetrievalHydrationRequest,
            text: String,
            mediaType: String,
            sourceSha256: String,
        ): RetrievedContentPayload = RetrievedContentPayload(
            request.id,
            request.digest,
            request.providerBinding.digest,
            request.providerInstanceId,
            request.providerConfigurationDigest,
            request.providerCapabilityDigest,
            request.providerRevision,
            request.providerDescriptorDigest,
            request.egressDecision.digest,
            text,
            mediaType,
            sourceSha256,
        )
    }
}

/** Authorized extracted content. It remains untrusted data for Agent prompt and rendering boundaries. */
class RetrievedContent private constructor(
    val hydrationRequestId: Identifier,
    val hydrationRequestDigest: String,
    val providerBindingDigest: String,
    val providerInstanceId: String,
    val providerConfigurationDigest: String,
    val providerCapabilityDigest: String,
    val providerRevision: String,
    val providerDescriptorDigest: String,
    val egressDecisionDigest: String,
    val payloadDigest: String,
    val evidenceDigest: String,
    val text: String,
    val mediaType: String,
    val sourceSha256: String,
    val hydratedAtEpochMilli: Long,
) {
    val contentSha256: String
    val digest: String

    init {
        requireRetrievalContent(text, RetrievalContractLimits.MAX_CONTENT_CODE_POINTS, "Retrieved content is invalid.")
        requireMediaType(mediaType)
        listOf(
            providerBindingDigest,
            providerConfigurationDigest,
            providerCapabilityDigest,
            providerDescriptorDigest,
            egressDecisionDigest,
            payloadDigest,
            evidenceDigest,
            sourceSha256,
        ).forEach { value ->
            requireDigest(value, "Retrieved content binding is invalid.")
        }
        listOf(providerInstanceId, providerRevision).forEach { value ->
            requireRetrievalText(
                value,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Retrieved content provider binding is invalid.",
            )
        }
        require(hydratedAtEpochMilli >= 0L) { "Hydration time must not be negative." }
        contentSha256 = sha256Hex(text.toByteArray(Charsets.UTF_8))
        digest = retrievalDigest {
            text("flowweft-retrieved-content-v2")
            text(hydrationRequestId.value)
            text(hydrationRequestDigest)
            text(providerBindingDigest)
            text(providerInstanceId)
            text(providerConfigurationDigest)
            text(providerCapabilityDigest)
            text(providerRevision)
            text(providerDescriptorDigest)
            text(egressDecisionDigest)
            text(payloadDigest)
            text(evidenceDigest)
            text(contentSha256)
            text(mediaType)
            text(sourceSha256)
            long(hydratedAtEpochMilli)
        }
    }

    fun requireValidFor(request: RetrievalHydrationRequest) {
        require(hydrationRequestId == request.id && hydrationRequestDigest == request.digest &&
            providerBindingDigest == request.providerBinding.digest &&
            providerInstanceId == request.providerInstanceId &&
            providerConfigurationDigest == request.providerConfigurationDigest &&
            providerCapabilityDigest == request.providerCapabilityDigest &&
            providerRevision == request.providerRevision &&
            providerDescriptorDigest == request.providerDescriptorDigest &&
            egressDecisionDigest == request.egressDecision.digest) {
            "Retrieved content belongs to another exact hydration request."
        }
        val evidence = request.candidate.resolvedCandidate.candidate.evidence
        require(evidenceDigest == evidence.digest && sourceSha256 == evidence.sourceSha256) {
            "Retrieved content does not match the authorized evidence."
        }
        request.candidate.requireUsableAt(hydratedAtEpochMilli)
        request.egressDecision.requireExactFor(request.candidate, request.providerBinding, hydratedAtEpochMilli)
        require(hydratedAtEpochMilli < request.deadlineEpochMilli) {
            "Retrieved content completed after its hydration deadline."
        }
        require(text.codePointCount(0, text.length) <= request.maximumContentCodePoints) {
            "Retrieved content exceeds the authorized size."
        }
    }

    companion object {
        @JvmSynthetic
        internal fun create(
            request: RetrievalHydrationRequest,
            payload: RetrievedContentPayload,
            hydratedAtEpochMilli: Long,
        ): RetrievedContent {
            payload.requireExactFor(request)
            request.candidate.requireUsableAt(hydratedAtEpochMilli)
            request.egressDecision.requireExactFor(request.candidate, request.providerBinding, hydratedAtEpochMilli)
            val evidence = request.candidate.resolvedCandidate.candidate.evidence
            require(payload.sourceSha256 == evidence.sourceSha256) {
                "Hydrated content source digest does not match authorized evidence."
            }
            require(payload.text.codePointCount(0, payload.text.length) <= request.maximumContentCodePoints) {
                "Hydrated content exceeds the authorized size."
            }
            require(hydratedAtEpochMilli < request.deadlineEpochMilli) { "Hydration completed after its deadline." }
            return RetrievedContent(
                request.id,
                request.digest,
                request.providerBinding.digest,
                request.providerInstanceId,
                request.providerConfigurationDigest,
                request.providerCapabilityDigest,
                request.providerRevision,
                request.providerDescriptorDigest,
                request.egressDecision.digest,
                payload.digest,
                evidence.digest,
                payload.text,
                payload.mediaType,
                payload.sourceSha256,
                hydratedAtEpochMilli,
            ).also { it.requireValidFor(request) }
        }
    }
}

private fun requireMediaType(mediaType: String) {
    requireRetrievalText(mediaType, RetrievalContractLimits.MAX_ID_CODE_POINTS, "Retrieved media type is invalid.")
    val token = "[A-Za-z0-9][A-Za-z0-9!#$&^_.+\\-]{0,126}"
    require(Regex("^$token/$token\$").matches(mediaType)) {
        "Retrieved media type must be an RFC token type/subtype without parameters."
    }
}

/** Content provider receives no prefiltered or merely resolved candidate and returns no permit type. */
interface RetrievalContentHydrator {
    fun hydrate(request: RetrievalHydrationRequest): RetrievalCall<RetrievedContentPayload>
}

/** Runtime-owned hydration gate that applies a trusted clock and validates the exact response binding. */
class RetrievalContentHydrationGate private constructor(
    private val hydrator: RetrievalContentHydrator,
    private val clock: java.util.function.LongSupplier,
) {
    fun hydrate(request: RetrievalHydrationRequest): RetrievalCall<RetrievedContent> {
        val startedAt = clock.asLong
        require(startedAt >= request.requestedAtEpochMilli && startedAt < request.deadlineEpochMilli) {
            "Hydration cannot start outside its deadline."
        }
        request.candidate.requireUsableAt(startedAt)
        request.egressDecision.requireExactFor(request.candidate, request.providerBinding, startedAt)
        val providerCall = requireNotNull(hydrator.hydrate(request)) {
            "Content provider returned no call handle."
        }
        return mapRetrievalCall(providerCall) { payload ->
            val completedAt = clock.asLong
            require(completedAt >= startedAt) { "Hydration clock moved backwards." }
            RetrievedContent.create(request, payload, completedAt)
        }
    }

    companion object {
        @JvmStatic
        fun create(
            hydrator: RetrievalContentHydrator,
            clock: java.util.function.LongSupplier,
        ): RetrievalContentHydrationGate = RetrievalContentHydrationGate(hydrator, clock)
    }
}
