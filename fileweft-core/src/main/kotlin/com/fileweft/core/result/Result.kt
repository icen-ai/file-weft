package com.fileweft.core.result

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
