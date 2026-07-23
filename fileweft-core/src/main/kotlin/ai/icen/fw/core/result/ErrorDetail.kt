package ai.icen.fw.core.result

import java.util.Collections
import java.util.LinkedHashMap

/**
 * Reserved error detail model, referenced only by the equally reserved
 * [Result] and [FileWeftException] types.
 */
@Deprecated(
    message = "Reserved model that no framework layer adopted; kept only for ABI compatibility and will be removed or redesigned in a future major release.",
    level = DeprecationLevel.WARNING,
)
class ErrorDetail @JvmOverloads constructor(
    val code: ErrorCode,
    message: String,
    attributes: Map<String, String> = emptyMap(),
) {
    val message: String = message.also {
        require(it.isNotBlank()) { "Error message must not be blank." }
    }
    val attributes: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(attributes))
}
