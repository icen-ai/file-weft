package com.fileweft.dev.api.web

import com.fileweft.domain.document.DocumentNumberAlreadyExistsException
import com.fileweft.application.upload.ResumableUploadStateException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class DevApiError(val code: String, val message: String)

@RestControllerAdvice
class DevApiExceptionHandler {
    @ExceptionHandler(DocumentNumberAlreadyExistsException::class)
    fun documentNumberConflict(failure: DocumentNumberAlreadyExistsException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            DevApiError("DOCUMENT_NUMBER_CONFLICT", failure.message ?: "Document number already exists in the current tenant."),
        )

    @ExceptionHandler(SecurityException::class)
    fun forbidden(failure: SecurityException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(DevApiError("FORBIDDEN", failure.message ?: "无权执行该操作。"))

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(failure: NoSuchElementException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(DevApiError("NOT_FOUND", failure.message ?: "资源不存在。"))

    @ExceptionHandler(ResumableUploadStateException::class)
    fun uploadStateConflict(failure: ResumableUploadStateException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(DevApiError("RESUMABLE_UPLOAD_STATE_CONFLICT", failure.message ?: "上传会话状态不允许该操作。"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(DevApiError("INVALID_REQUEST", failure.message ?: "请求无效。"))
}
