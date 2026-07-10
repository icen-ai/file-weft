package com.fileweft.dev.api.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class DevApiError(val code: String, val message: String)

@RestControllerAdvice
class DevApiExceptionHandler {
    @ExceptionHandler(SecurityException::class)
    fun forbidden(failure: SecurityException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(DevApiError("FORBIDDEN", failure.message ?: "无权执行该操作。"))

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(failure: NoSuchElementException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(DevApiError("NOT_FOUND", failure.message ?: "资源不存在。"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(DevApiError("INVALID_REQUEST", failure.message ?: "请求无效。"))
}
