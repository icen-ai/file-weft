package com.fileweft.web.spring.boot2

import com.fileweft.web.api.ApiResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Maps only synchronous failures deliberately wrapped by
 * [DocumentV1ContentController]. It must never catch general asynchronous
 * stream failures, because the response can already be committed then.
 */
@RestControllerAdvice(assignableTypes = [DocumentV1ContentController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class DocumentV1ContentControllerAdvice {
    @ExceptionHandler(DocumentV1ContentTransportFailure::class)
    internal fun contentFailure(
        failure: DocumentV1ContentTransportFailure,
    ): ResponseEntity<ApiResponse<*>> {
        val builder = ResponseEntity.status(failure.mapped.status.statusCode)
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
            .header(X_CONTENT_TYPE_OPTIONS, NO_SNIFF)
        if (failure.allowGet) {
            builder.header(HttpHeaders.ALLOW, GET_METHOD)
        }
        val response: ApiResponse<*> = failure.mapped.response
        return builder.body(response)
    }

    private companion object {
        const val CACHE_CONTROL: String = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS: String = "X-Content-Type-Options"
        const val NO_SNIFF: String = "nosniff"
        const val GET_METHOD: String = "GET"
    }
}
