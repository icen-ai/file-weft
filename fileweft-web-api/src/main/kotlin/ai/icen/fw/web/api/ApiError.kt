package ai.icen.fw.web.api

/**
 * Stable, transport-neutral description of an API failure.
 *
 * Error codes remain strings so applications can introduce documented extension
 * codes without having to couple their public HTTP contract to FlowWeft internals.
 */
class ApiError constructor(
    code: String,
    message: String,
) {
    val code: String = requiredText(code, "API error code", 64)
    val message: String = requiredText(message, "API error message", 512)
}
