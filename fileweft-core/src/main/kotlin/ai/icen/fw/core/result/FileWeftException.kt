package ai.icen.fw.core.result

/**
 * Reserved exception type. The domain and application layers throw their own
 * typed business exceptions, which intentionally do not inherit from it.
 */
@Deprecated(
    message = "Reserved model that no framework layer adopted; kept only for ABI compatibility and will be removed or redesigned in a future major release.",
    level = DeprecationLevel.WARNING,
)
class FileWeftException(
    val error: ErrorDetail,
) : RuntimeException("${error.code}: ${error.message}")
