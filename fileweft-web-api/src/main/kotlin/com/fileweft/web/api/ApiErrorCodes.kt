package com.fileweft.web.api

/**
 * Stable public response and failure codes.
 *
 * This is a regular class with a private constructor rather than a Kotlin
 * singleton so Java consumers use ordinary static constants such as
 * {@code ApiErrorCodes.NOT_FOUND}.
 */
class ApiErrorCodes private constructor() {
    companion object {
        const val OK: String = "OK"
        const val INVALID_REQUEST: String = "INVALID_REQUEST"
        const val UNAUTHENTICATED: String = "UNAUTHENTICATED"
        const val FORBIDDEN: String = "FORBIDDEN"
        const val NOT_FOUND: String = "NOT_FOUND"
        const val CONFLICT: String = "CONFLICT"
        const val INTERNAL_ERROR: String = "INTERNAL_ERROR"
    }
}
