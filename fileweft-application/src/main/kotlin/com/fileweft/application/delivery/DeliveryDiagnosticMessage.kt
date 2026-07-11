package com.fileweft.application.delivery

/**
 * Bounds connector diagnostics before they enter durable delivery state,
 * audit details, or a host-facing diagnostic view.
 */
internal object DeliveryDiagnosticMessage {
    const val MAX_LENGTH = 1024
    private const val TRUNCATION_SUFFIX = "…[truncated]"

    fun normalize(message: String?): String? {
        val meaningful = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (meaningful.length <= MAX_LENGTH) {
            meaningful
        } else {
            meaningful.take(MAX_LENGTH - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX
        }
    }
}
