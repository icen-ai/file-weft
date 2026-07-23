package ai.icen.fw.core.result

/**
 * Reserved result model. It is not used by any framework layer; the domain and
 * application layers throw typed business exceptions instead. Kept public only
 * for binary compatibility.
 */
@Deprecated(
    message = "Reserved model that no framework layer adopted; kept only for ABI compatibility and will be removed or redesigned in a future major release.",
    level = DeprecationLevel.WARNING,
)
class Result<T> private constructor(
    val value: T?,
    val error: ErrorDetail?,
) {
    init {
        require((value == null) != (error == null)) {
            "Result must contain exactly one of value or error."
        }
    }

    fun isSuccess(): Boolean = error == null

    fun isFailure(): Boolean = error != null

    fun getOrThrow(): T = value ?: throw FileWeftException(checkNotNull(error))

    companion object {
        @JvmStatic
        fun <T : Any> success(value: T): Result<T> = Result(value, null)

        @JvmStatic
        fun <T> failure(error: ErrorDetail): Result<T> = Result(null, error)
    }
}
