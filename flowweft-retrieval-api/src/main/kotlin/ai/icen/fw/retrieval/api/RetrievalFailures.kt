package ai.icen.fw.retrieval.api

/** Extensible stable failure identifier; provider SDK and transport exceptions never cross this boundary. */
class RetrievalFailureCode private constructor(val id: String) {
    init {
        require(id.length <= 64 && Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*\$").matches(id)) {
            "Retrieval failure code must be a stable lower-case ASCII identifier."
        }
    }

    override fun equals(other: Any?): Boolean = other is RetrievalFailureCode && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    companion object {
        @JvmField val TEMPORARY_UNAVAILABLE = RetrievalFailureCode("temporary-unavailable")
        @JvmField val RATE_LIMITED = RetrievalFailureCode("rate-limited")
        @JvmField val AUTHENTICATION_FAILED = RetrievalFailureCode("authentication-failed")
        @JvmField val AUTHORIZATION_FAILED = RetrievalFailureCode("authorization-failed")
        @JvmField val QUOTA_EXCEEDED = RetrievalFailureCode("quota-exceeded")
        @JvmField val INVALID_RESPONSE = RetrievalFailureCode("invalid-response")
        @JvmField val CANCELLED = RetrievalFailureCode("cancelled")
        @JvmField val PERMANENT_FAILURE = RetrievalFailureCode("permanent-failure")
        /** The authoritative active generation or projection revision did not match the request CAS precondition. */
        @JvmField val INDEX_PROJECTION_CONFLICT = RetrievalFailureCode("index.projection-conflict")
        /** The same index operation/request id was already bound to a different request digest. */
        @JvmField val INDEX_REQUEST_REPLAY_MISMATCH = RetrievalFailureCode("index.request-replay-mismatch")
        /** The request descriptor, provider instance, schema, source, generation, or receipt belongs elsewhere. */
        @JvmField val INDEX_PROVIDER_BINDING_MISMATCH = RetrievalFailureCode("index.provider-binding-mismatch")
        /** The host did not configure an authoritative deletion visibility bridge. */
        @JvmField val DELETION_VISIBILITY_UNAVAILABLE =
            RetrievalFailureCode("deletion-visibility.unavailable")
        /** A deletion visibility answer did not bind to the exact request, provider or resource revision. */
        @JvmField val DELETION_VISIBILITY_MISMATCH =
            RetrievalFailureCode("deletion-visibility.mismatch")
        /** The requested immutable resource revision is covered by an authoritative tombstone. */
        @JvmField val RESOURCE_TOMBSTONED = RetrievalFailureCode("resource-tombstoned")

        @JvmStatic
        fun of(id: String): RetrievalFailureCode = RetrievalFailureCode(id)
    }
}

enum class RetrievalRetryability {
    RETRYABLE,
    NOT_RETRYABLE,
}

/** Sanitized provider failure. SDK causes and free-form provider messages never cross this API. */
class RetrievalProviderException @JvmOverloads constructor(
    val code: RetrievalFailureCode,
    val retryability: RetrievalRetryability,
    val providerRequestId: String? = null,
) : RuntimeException(safeMessage(code)) {
    init {
        require(
            retryability == RetrievalRetryability.NOT_RETRYABLE ||
                code != RetrievalFailureCode.INDEX_PROJECTION_CONFLICT &&
                code != RetrievalFailureCode.INDEX_REQUEST_REPLAY_MISMATCH &&
                code != RetrievalFailureCode.INDEX_PROVIDER_BINDING_MISMATCH,
        ) {
            "Index conflict, replay, and provider-binding failures are not retryable for the same request."
        }
        providerRequestId?.let { requestId ->
            requireRetrievalText(
                requestId,
                RetrievalContractLimits.MAX_ID_CODE_POINTS,
                "Retrieval provider request identifier is invalid.",
            )
            require(requestId.all { character ->
                character.isLetterOrDigit() || character == '.' || character == '_' || character == ':' || character == '-'
            }) { "Retrieval provider request identifier contains unsupported characters." }
        }
    }

    companion object {
        private fun safeMessage(code: RetrievalFailureCode): String = when (code) {
            RetrievalFailureCode.TEMPORARY_UNAVAILABLE -> "The retrieval provider is temporarily unavailable."
            RetrievalFailureCode.RATE_LIMITED -> "The retrieval provider rate limit was reached."
            RetrievalFailureCode.AUTHENTICATION_FAILED -> "The retrieval provider rejected its configured identity."
            RetrievalFailureCode.AUTHORIZATION_FAILED -> "The retrieval provider rejected the requested operation."
            RetrievalFailureCode.QUOTA_EXCEEDED -> "The retrieval provider quota was exhausted."
            RetrievalFailureCode.INVALID_RESPONSE -> "The retrieval provider returned an invalid response."
            RetrievalFailureCode.CANCELLED -> "The retrieval operation was cancelled."
            RetrievalFailureCode.PERMANENT_FAILURE -> "The retrieval provider rejected the operation permanently."
            RetrievalFailureCode.INDEX_PROJECTION_CONFLICT ->
                "The retrieval index projection changed before the requested operation could be applied."
            RetrievalFailureCode.INDEX_REQUEST_REPLAY_MISMATCH ->
                "The retrieval index request identifier is already bound to another request."
            RetrievalFailureCode.INDEX_PROVIDER_BINDING_MISMATCH ->
                "The retrieval index request does not belong to the selected provider binding."
            RetrievalFailureCode.DELETION_VISIBILITY_UNAVAILABLE ->
                "The authoritative deletion visibility capability is unavailable."
            RetrievalFailureCode.DELETION_VISIBILITY_MISMATCH ->
                "The deletion visibility provider returned an invalid response."
            RetrievalFailureCode.RESOURCE_TOMBSTONED ->
                "The requested resource revision is no longer visible."
            else -> "The retrieval provider operation failed."
        }
    }
}

/** Stable, non-sensitive cancellation reason code. Free-form user text is intentionally forbidden. */
class RetrievalCancellationReason private constructor(val code: String) {
    init {
        requireRetrievalText(
            code,
            RetrievalContractLimits.MAX_ID_CODE_POINTS,
            "Retrieval cancellation reason code is invalid.",
        )
        require(code.length <= 64 && Regex("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*\$").matches(code)) {
            "Retrieval cancellation reason must be a stable lower-case ASCII code."
        }
    }

    override fun equals(other: Any?): Boolean = other is RetrievalCancellationReason && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = code

    companion object {
        @JvmField val CALLER_CANCELLED = RetrievalCancellationReason("caller-cancelled")
        @JvmField val DEADLINE_EXCEEDED = RetrievalCancellationReason("deadline-exceeded")
        @JvmField val RUNTIME_SHUTDOWN = RetrievalCancellationReason("runtime-shutdown")
        @JvmField val AUTHORIZATION_REVOKED = RetrievalCancellationReason("authorization-revoked")
        @JvmField val BUDGET_EXHAUSTED = RetrievalCancellationReason("budget-exhausted")

        @JvmStatic
        fun of(code: String): RetrievalCancellationReason = RetrievalCancellationReason(code)
    }
}

enum class RetrievalCancellationOutcome {
    ACCEPTED,
    ALREADY_COMPLETED,
    UNSUPPORTED,
}
