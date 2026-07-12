package ai.icen.fw.web.runtime.v1

/**
 * Transport-neutral policy for the formal v1 `Idempotency-Key` header.
 *
 * HTTP adapters must pass every received header value rather than allowing a
 * framework to silently choose the first one. The validated caller value is
 * returned unchanged and is never included in validation messages.
 */
object IdempotencyKeyParser {
    @JvmStatic
    fun parse(values: List<String>?): String {
        if (values?.size != 1) {
            throw IllegalArgumentException("Idempotency-Key header must be supplied exactly once.")
        }
        val value = values[0]
        if (
            value.length !in 1..MAX_LENGTH ||
            value.any { character -> character.code !in ASCII_MIN..ASCII_MAX } ||
            !KEY_PATTERN.matches(value)
        ) {
            throw IllegalArgumentException("Idempotency-Key header is invalid.")
        }
        return value
    }

    private const val MAX_LENGTH = 128
    private const val ASCII_MIN = 0x20
    private const val ASCII_MAX = 0x7e
    private val KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}")
}
