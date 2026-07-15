package ai.icen.fw.agent.api

/**
 * Safe provider failure for exceptional [java.util.concurrent.CompletionStage] completion.
 * Raw SDK exceptions, response bodies, headers and credentials must remain adapter-internal.
 */
class AgentProviderException @JvmOverloads constructor(
    val providerId: ProviderId,
    val category: AgentFailureCategory,
    code: String,
    safeMessage: String? = null,
    val retryAfterMillis: Long? = null,
) : RuntimeException(
    requireOptionalAgentContent(
        safeMessage,
        AgentContractLimits.MAX_DESCRIPTION_CODE_POINTS,
        "Agent provider failure message is invalid.",
    ) ?: requireAgentCode(code, "Agent provider failure code is invalid."),
) {
    val code: String = requireAgentCode(code, "Agent provider failure code is invalid.")

    init {
        require(retryAfterMillis == null || retryAfterMillis > 0) {
            "Agent provider retry delay must be positive when provided."
        }
        require(
            retryAfterMillis == null ||
                category == AgentFailureCategory.RETRYABLE ||
                category == AgentFailureCategory.RATE_LIMITED,
        ) { "Agent provider retry delay is only valid for retryable or rate-limited failures." }
    }

    override fun toString(): String = "AgentProviderException(category=$category, code=$code)"
}
