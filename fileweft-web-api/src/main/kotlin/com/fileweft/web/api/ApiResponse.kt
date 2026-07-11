package com.fileweft.web.api

/**
 * Unified public API envelope. HTTP adapters own status-code selection while
 * this contract keeps the JSON shape stable across Spring Boot generations.
 */
class ApiResponse<T> private constructor(
    code: String,
    message: String,
    val data: T?,
    val error: ApiError?,
    traceId: String?,
) {
    val code: String = requiredText(code, "API response code", 64)
    val message: String = requiredText(message, "API response message", 512)
    val traceId: String? = optionalText(traceId, "API response trace id", 128)

    init {
        require(error == null || data == null) {
            "A failed API response must not contain data."
        }
        require(error == null || (code == error.code && message == error.message)) {
            "A failed API response must expose its error code and message."
        }
    }

    fun isSuccess(): Boolean = error == null

    fun isFailure(): Boolean = error != null

    companion object {
        const val SUCCESS_CODE: String = ApiErrorCodes.OK
        const val SUCCESS_MESSAGE: String = ApiErrorCodes.OK

        @JvmStatic
        @JvmOverloads
        fun <T> success(
            data: T? = null,
            message: String = SUCCESS_MESSAGE,
            traceId: String? = null,
        ): ApiResponse<T> = ApiResponse(SUCCESS_CODE, message, data, null, traceId)

        @JvmStatic
        @JvmOverloads
        fun <T> failure(error: ApiError, traceId: String? = null): ApiResponse<T> =
            ApiResponse(error.code, error.message, null, error, traceId)
    }
}
