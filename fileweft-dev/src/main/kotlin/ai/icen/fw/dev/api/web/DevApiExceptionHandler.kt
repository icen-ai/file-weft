package ai.icen.fw.dev.api.web

import ai.icen.fw.application.publish.ActiveDocumentReviewWorkflowException
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.application.workflow.DocumentReviewConflictException
import ai.icen.fw.domain.document.DocumentNumberAlreadyExistsException
import ai.icen.fw.domain.document.InvalidLifecycleTransitionException
import ai.icen.fw.domain.workflow.WorkflowConflictException
import ai.icen.fw.application.upload.ResumableUploadStateException
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

    @ExceptionHandler(ActiveDocumentReviewWorkflowException::class)
    fun activeWorkflowConflict(failure: ActiveDocumentReviewWorkflowException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            DevApiError("ACTIVE_REVIEW_WORKFLOW", failure.message ?: "Document has an active local review workflow."),
        )

    @ExceptionHandler(InvalidLifecycleTransitionException::class)
    fun lifecycleConflict(failure: InvalidLifecycleTransitionException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            DevApiError("INVALID_LIFECYCLE_TRANSITION", failure.message ?: "Document lifecycle does not allow this operation."),
        )

    @ExceptionHandler(DocumentReviewConflictException::class)
    fun documentReviewConflict(
        @Suppress("UNUSED_PARAMETER") failure: DocumentReviewConflictException,
    ): ResponseEntity<DevApiError> = ResponseEntity.status(HttpStatus.CONFLICT).body(
        DevApiError("DOCUMENT_REVIEW_CONFLICT", "Document review conflicts with the current workflow state."),
    )

    @ExceptionHandler(WorkflowConflictException::class)
    fun workflowConflict(
        @Suppress("UNUSED_PARAMETER") failure: WorkflowConflictException,
    ): ResponseEntity<DevApiError> = ResponseEntity.status(HttpStatus.CONFLICT).body(
        DevApiError("WORKFLOW_CONFLICT", "Workflow command conflicts with the current workflow state."),
    )

    @ExceptionHandler(ApplicationUnauthenticatedException::class)
    fun unauthenticated(@Suppress("UNUSED_PARAMETER") failure: ApplicationUnauthenticatedException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(DevApiError("UNAUTHENTICATED", "Authentication is required."))

    @ExceptionHandler(ApplicationForbiddenException::class)
    fun applicationForbidden(@Suppress("UNUSED_PARAMETER") failure: ApplicationForbiddenException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(DevApiError("FORBIDDEN", "Access denied."))

    @ExceptionHandler(SecurityException::class)
    fun forbidden(@Suppress("UNUSED_PARAMETER") failure: SecurityException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(DevApiError("FORBIDDEN", "Access denied."))

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(@Suppress("UNUSED_PARAMETER") failure: NoSuchElementException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(DevApiError("NOT_FOUND", "Resource was not found."))

    @ExceptionHandler(ResumableUploadStateException::class)
    fun uploadStateConflict(failure: ResumableUploadStateException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(DevApiError("RESUMABLE_UPLOAD_STATE_CONFLICT", failure.message ?: "上传会话状态不允许该操作。"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(@Suppress("UNUSED_PARAMETER") failure: IllegalArgumentException): ResponseEntity<DevApiError> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(DevApiError("INVALID_REQUEST", "Request is invalid."))
}
