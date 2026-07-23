package ai.icen.fw.core.result

/**
 * Reserved error code catalog, referenced only by the equally reserved
 * [ErrorDetail] type.
 */
@Deprecated(
    message = "Reserved model that no framework layer adopted; kept only for ABI compatibility and will be removed or redesigned in a future major release.",
    level = DeprecationLevel.WARNING,
)
enum class ErrorCode {
    INVALID_ARGUMENT,
    TENANT_CONTEXT_MISSING,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    STATE_TRANSITION_NOT_ALLOWED,
    EXTERNAL_SYSTEM_FAILURE,
    INTERNAL_ERROR,
}
