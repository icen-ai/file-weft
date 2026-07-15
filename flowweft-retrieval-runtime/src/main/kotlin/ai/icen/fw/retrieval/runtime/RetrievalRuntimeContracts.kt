package ai.icen.fw.retrieval.runtime

import ai.icen.fw.retrieval.api.AuthorizedRetrievalCandidate
import ai.icen.fw.retrieval.api.RetrievalAuthorizationRequest
import ai.icen.fw.retrieval.api.RetrievalCancellationOutcome
import ai.icen.fw.retrieval.api.RetrievalCancellationReason
import ai.icen.fw.retrieval.api.RetrievalDenialCode
import ai.icen.fw.retrieval.api.RetrievalExecutionPolicy
import ai.icen.fw.retrieval.api.RetrievalFailureCode
import ai.icen.fw.retrieval.api.RetrievalPageCursor
import ai.icen.fw.retrieval.api.RetrievalRequestSpec
import ai.icen.fw.retrieval.api.RetrievalRetryability
import ai.icen.fw.retrieval.api.RetrievedContent
import ai.icen.fw.retrieval.api.SecurityFilterReceipt
import java.util.concurrent.CompletionStage

/** Trusted runtime request. Tenant, principal, action and purpose come only from the authorization request. */
class RetrievalRuntimeRequest private constructor(
    val authorizationRequest: RetrievalAuthorizationRequest,
    val requestSpec: RetrievalRequestSpec,
    val executionPolicy: RetrievalExecutionPolicy,
    val rerankRequested: Boolean,
) {
    val digest: String

    init {
        require(requestSpec.deadlineEpochMilli > authorizationRequest.requestedAtEpochMilli) {
            "Retrieval deadline must follow the trusted authorization request."
        }
        digest = RetrievalRuntimeDigest("flowweft-retrieval-runtime-request-v1")
            .text(authorizationRequest.digest)
            .text(requestSpec.digest)
            .text(executionPolicy.digest)
            .bool(rerankRequested)
            .finish()
    }

    override fun toString(): String =
        "RetrievalRuntimeRequest(mode=${requestSpec.mode.id}, rerankRequested=$rerankRequested)"

    companion object {
        @JvmStatic
        fun create(
            authorizationRequest: RetrievalAuthorizationRequest,
            requestSpec: RetrievalRequestSpec,
            executionPolicy: RetrievalExecutionPolicy,
            rerankRequested: Boolean,
        ): RetrievalRuntimeRequest = RetrievalRuntimeRequest(
            authorizationRequest,
            requestSpec,
            executionPolicy,
            rerankRequested,
        )
    }
}

/** Hard runtime ceilings. They are independent of and never weaken provider-advertised limits. */
class RetrievalRuntimeConfiguration @JvmOverloads constructor(
    val maximumCandidates: Int = 100,
    val maximumContentCodePointsPerCandidate: Int = 32_768,
    val maximumTotalContentCodePoints: Int = 262_144,
    val maximumRerankItems: Int = 100,
    val maximumRerankResults: Int = 20,
    val contentEgressAllowed: Boolean = false,
    val rerankEgressAllowed: Boolean = false,
) {
    val digest: String

    init {
        require(maximumCandidates in 1..MAX_RUNTIME_CANDIDATES) { "Runtime candidate limit is invalid." }
        require(maximumContentCodePointsPerCandidate in 1..MAX_RUNTIME_CONTENT_CODE_POINTS) {
            "Runtime per-candidate content limit is invalid."
        }
        require(maximumTotalContentCodePoints in maximumContentCodePointsPerCandidate..
            MAX_RUNTIME_CONTENT_CODE_POINTS) { "Runtime total content limit is invalid." }
        require(maximumRerankItems in 1..maximumCandidates) { "Runtime rerank item limit is invalid." }
        require(maximumRerankResults in 1..maximumRerankItems) { "Runtime rerank result limit is invalid." }
        digest = RetrievalRuntimeDigest("flowweft-retrieval-runtime-configuration-v1")
            .integer(maximumCandidates)
            .integer(maximumContentCodePointsPerCandidate)
            .integer(maximumTotalContentCodePoints)
            .integer(maximumRerankItems)
            .integer(maximumRerankResults)
            .bool(contentEgressAllowed)
            .bool(rerankEgressAllowed)
            .finish()
    }

    companion object {
        @JvmStatic
        fun create(
            maximumCandidates: Int,
            maximumContentCodePointsPerCandidate: Int,
            maximumTotalContentCodePoints: Int,
            maximumRerankItems: Int,
            maximumRerankResults: Int,
            contentEgressAllowed: Boolean,
            rerankEgressAllowed: Boolean,
        ): RetrievalRuntimeConfiguration = RetrievalRuntimeConfiguration(
            maximumCandidates,
            maximumContentCodePointsPerCandidate,
            maximumTotalContentCodePoints,
            maximumRerankItems,
            maximumRerankResults,
            contentEgressAllowed,
            rerankEgressAllowed,
        )
    }
}

/** Extensible sanitized runtime failure identifier. */
class RetrievalRuntimeFailureCode private constructor(val id: String) {
    init {
        requireRuntimeText(id, 64, "Retrieval runtime failure code is invalid.")
        require(Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$").matches(id)) {
            "Retrieval runtime failure code is invalid."
        }
    }

    override fun equals(other: Any?): Boolean = other is RetrievalRuntimeFailureCode && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        @JvmField val AUTHORIZATION_FAILED = RetrievalRuntimeFailureCode("authorization-failed")
        @JvmField val POLICY_REJECTED = RetrievalRuntimeFailureCode("policy-rejected")
        @JvmField val PROVIDER_FAILED = RetrievalRuntimeFailureCode("provider-failed")
        @JvmField val INVALID_PROVIDER_RESPONSE = RetrievalRuntimeFailureCode("invalid-provider-response")
        @JvmField val LINEAGE_FAILED = RetrievalRuntimeFailureCode("lineage-failed")
        @JvmField val DESCRIPTOR_CHANGED = RetrievalRuntimeFailureCode("descriptor-changed")
        @JvmField val EGRESS_FORBIDDEN = RetrievalRuntimeFailureCode("egress-forbidden")
        @JvmField val UNSUPPORTED = RetrievalRuntimeFailureCode("unsupported")
        @JvmField val CONTENT_LIMIT_EXCEEDED = RetrievalRuntimeFailureCode("content-limit-exceeded")
        @JvmField val DEADLINE_EXCEEDED = RetrievalRuntimeFailureCode("deadline-exceeded")
        @JvmField val CANCELLED = RetrievalRuntimeFailureCode("cancelled")
        @JvmField val INTERNAL_FAILURE = RetrievalRuntimeFailureCode("internal-failure")

        @JvmStatic
        fun of(id: String): RetrievalRuntimeFailureCode = RetrievalRuntimeFailureCode(id)
    }
}

/** Sanitized terminal failure. Provider exceptions, messages, causes and credentials are never retained. */
class RetrievalRuntimeException private constructor(
    val code: RetrievalRuntimeFailureCode,
    val retryability: RetrievalRetryability,
    val providerFailureCode: RetrievalFailureCode?,
    val providerRequestId: String?,
) : RuntimeException(safeMessage(code)) {
    companion object {
        internal fun create(
            code: RetrievalRuntimeFailureCode,
            retryability: RetrievalRetryability = RetrievalRetryability.NOT_RETRYABLE,
            providerFailureCode: RetrievalFailureCode? = null,
            providerRequestId: String? = null,
        ): RetrievalRuntimeException = RetrievalRuntimeException(
            code,
            retryability,
            providerFailureCode,
            providerRequestId,
        )

        private fun safeMessage(code: RetrievalRuntimeFailureCode): String = when (code) {
            RetrievalRuntimeFailureCode.AUTHORIZATION_FAILED -> "Retrieval authorization failed."
            RetrievalRuntimeFailureCode.POLICY_REJECTED -> "Retrieval execution policy rejected the request."
            RetrievalRuntimeFailureCode.PROVIDER_FAILED -> "A retrieval provider operation failed."
            RetrievalRuntimeFailureCode.INVALID_PROVIDER_RESPONSE -> "A retrieval provider response was invalid."
            RetrievalRuntimeFailureCode.LINEAGE_FAILED -> "Retrieval lineage verification failed."
            RetrievalRuntimeFailureCode.DESCRIPTOR_CHANGED -> "A retrieval provider capability changed during execution."
            RetrievalRuntimeFailureCode.EGRESS_FORBIDDEN -> "Retrieval egress is forbidden by policy."
            RetrievalRuntimeFailureCode.UNSUPPORTED -> "The requested retrieval capability is unavailable."
            RetrievalRuntimeFailureCode.CONTENT_LIMIT_EXCEEDED -> "Retrieved content exceeded the runtime budget."
            RetrievalRuntimeFailureCode.DEADLINE_EXCEEDED -> "The retrieval deadline was exceeded."
            RetrievalRuntimeFailureCode.CANCELLED -> "The retrieval operation was cancelled."
            else -> "The retrieval operation failed."
        }
    }
}

enum class RetrievalRuntimeStatus {
    DENIED,
    COMPLETED,
}

/** One authorized, lineage-verified and hydrated result item. */
class RetrievalRuntimeItem private constructor(
    val candidate: AuthorizedRetrievalCandidate,
    val content: RetrievedContent,
    val rerankScore: Double?,
    val rerankProviderEvidenceDigest: String?,
) {
    val digest: String

    init {
        val evidence = candidate.resolvedCandidate.candidate.evidence
        require(content.evidenceDigest == evidence.digest && content.sourceSha256 == evidence.sourceSha256) {
            "Runtime content does not match its authorized candidate."
        }
        require((rerankScore == null) == (rerankProviderEvidenceDigest == null)) {
            "Rerank score and evidence must be supplied together."
        }
        rerankScore?.let { require(it.isFinite()) { "Rerank score must be finite." } }
        rerankProviderEvidenceDigest?.let {
            requireRuntimeDigest(it, "Rerank provider evidence digest is invalid.")
        }
        digest = RetrievalRuntimeDigest("flowweft-retrieval-runtime-item-v1")
            .text(candidate.digest)
            .text(content.digest)
            .optionalText(rerankScore?.let(java.lang.Double::toHexString))
            .optionalText(rerankProviderEvidenceDigest)
            .finish()
    }

    override fun toString(): String = "RetrievalRuntimeItem(<redacted>)"

    companion object {
        internal fun hydrated(
            candidate: AuthorizedRetrievalCandidate,
            content: RetrievedContent,
        ): RetrievalRuntimeItem = RetrievalRuntimeItem(candidate, content, null, null)

        internal fun reranked(
            source: RetrievalRuntimeItem,
            score: Double,
            providerEvidenceDigest: String,
        ): RetrievalRuntimeItem = RetrievalRuntimeItem(
            source.candidate,
            source.content,
            score,
            providerEvidenceDigest,
        )
    }
}

/** Terminal result. It never reports pre-authorization hit counts or denied candidate identities. */
class RetrievalRuntimeResult private constructor(
    val runtimeRequestDigest: String,
    val status: RetrievalRuntimeStatus,
    val denialCode: RetrievalDenialCode?,
    val providerDescriptorDigest: String?,
    val securityFilterReceipt: SecurityFilterReceipt?,
    val nextCursor: RetrievalPageCursor?,
    items: Collection<RetrievalRuntimeItem>,
    val reranked: Boolean,
    val partial: Boolean,
    val timedOut: Boolean,
    val startedAtEpochMilli: Long,
    val completedAtEpochMilli: Long,
) {
    val items: List<RetrievalRuntimeItem> = immutableRuntimeList(items)
    val digest: String

    init {
        requireRuntimeDigest(runtimeRequestDigest, "Runtime request digest is invalid.")
        require(startedAtEpochMilli >= 0L && completedAtEpochMilli >= startedAtEpochMilli) {
            "Retrieval runtime result time is invalid."
        }
        when (status) {
            RetrievalRuntimeStatus.DENIED -> require(
                denialCode != null && providerDescriptorDigest == null && securityFilterReceipt == null &&
                    nextCursor == null && this.items.isEmpty() && !reranked && !partial && !timedOut,
            ) { "Denied retrieval result contains provider data." }
            RetrievalRuntimeStatus.COMPLETED -> {
                require(denialCode == null && providerDescriptorDigest != null && securityFilterReceipt != null) {
                    "Completed retrieval result is missing exact provider evidence."
                }
                requireRuntimeDigest(providerDescriptorDigest, "Provider descriptor digest is invalid.")
                require(securityFilterReceipt.nextCursorDigest == nextCursor?.digest) {
                    "Completed retrieval cursor does not match its security receipt."
                }
                require(!timedOut || partial) { "A timed-out retrieval result must be partial." }
            }
        }
        digest = RetrievalRuntimeDigest("flowweft-retrieval-runtime-result-v2")
            .text(runtimeRequestDigest)
            .text(status.name)
            .optionalText(denialCode?.id)
            .optionalText(providerDescriptorDigest)
            .optionalText(securityFilterReceipt?.digest)
            .optionalText(nextCursor?.digest)
            .integer(this.items.size)
            .apply { this@RetrievalRuntimeResult.items.forEach { text(it.digest) } }
            .bool(reranked)
            .bool(partial)
            .bool(timedOut)
            .long(startedAtEpochMilli)
            .long(completedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "RetrievalRuntimeResult(status=$status, items=${items.size})"

    companion object {
        internal fun denied(
            request: RetrievalRuntimeRequest,
            denialCode: RetrievalDenialCode,
            startedAtEpochMilli: Long,
            completedAtEpochMilli: Long,
        ): RetrievalRuntimeResult = RetrievalRuntimeResult(
            request.digest,
            RetrievalRuntimeStatus.DENIED,
            denialCode,
            null,
            null,
            null,
            emptyList(),
            false,
            false,
            false,
            startedAtEpochMilli,
            completedAtEpochMilli,
        )

        internal fun completed(
            request: RetrievalRuntimeRequest,
            providerDescriptorDigest: String,
            receipt: SecurityFilterReceipt,
            nextCursor: RetrievalPageCursor?,
            items: Collection<RetrievalRuntimeItem>,
            reranked: Boolean,
            partial: Boolean,
            timedOut: Boolean,
            startedAtEpochMilli: Long,
            completedAtEpochMilli: Long,
        ): RetrievalRuntimeResult = RetrievalRuntimeResult(
            request.digest,
            RetrievalRuntimeStatus.COMPLETED,
            null,
            providerDescriptorDigest,
            receipt,
            nextCursor,
            items,
            reranked,
            partial,
            timedOut,
            startedAtEpochMilli,
            completedAtEpochMilli,
        )
    }
}

/** JDK 8 compatible runtime handle. Cancellation is idempotent and fail-closed. */
interface RetrievalRuntimeCall {
    fun completion(): CompletionStage<RetrievalRuntimeResult>
    fun cancel(reason: RetrievalCancellationReason): CompletionStage<RetrievalCancellationOutcome>
}
