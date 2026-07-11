package com.fileweft.web.runtime.v1

/** Fixed public failure for HTTP methods unsupported by the v1 contract. */
class V1MethodNotAllowedException : RuntimeException(DEFAULT_MESSAGE) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Method is not allowed."
    }
}

/** Fixed public failure because the initial v1 download contract has no ranges. */
class V1RangeNotSupportedException : RuntimeException(DEFAULT_MESSAGE) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Range requests are not supported."
    }
}
