package ai.icen.fw.core.result

import java.util.Collections
import java.util.LinkedHashMap

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
