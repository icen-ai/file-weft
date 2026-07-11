package com.fileweft.dev.api.web

import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.document.AddDocumentVersionCommand
import com.fileweft.application.document.CreateDocumentDraftCommand
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.document.DocumentDownload
import com.fileweft.application.document.DocumentDownloadService
import com.fileweft.application.agent.ConfirmAgentSuggestionService
import com.fileweft.application.catalog.DocumentCatalogBindingService
import com.fileweft.application.delivery.RetryDocumentDeliveryService
import com.fileweft.application.doctor.ScheduleDocumentDoctorService
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.offline.RestoreOfflineDocumentService
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.workflow.DocumentReviewWorkflowService
import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.catalog.DevCatalogDocumentService
import com.fileweft.dev.api.connector.DevPlatformMirrorService
import com.fileweft.dev.api.service.DevDocumentDetail
import com.fileweft.dev.api.service.DevDocumentLogEntry
import com.fileweft.dev.api.service.DevDocumentQueryService
import com.fileweft.dev.api.service.DevDocumentSyncStatus
import com.fileweft.dev.api.service.DevOperationsService
import com.fileweft.dev.api.service.DevReviewService
import com.fileweft.domain.workflow.WorkflowInstance
import org.springframework.http.HttpStatus
import org.springframework.http.ContentDisposition
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.nio.charset.StandardCharsets

data class DevRenameDocumentRequest(val title: String)
data class DevMoveDocumentRequest(val folderId: String)
data class DevSubmitDocumentRequest(val reviewerId: String? = null, val reviewRouteId: String? = null)
data class DevWorkflowDecisionRequest(val comment: String? = null, val deliveryProfileId: String? = null)
data class DevPublishDocumentRequest(val deliveryProfileId: String? = null)
data class DevWorkflowResponse(
    val workflowId: String,
    val state: String,
    /** Retained for existing development clients; use taskIds for a multi-task route. */
    val taskId: String,
    val taskIds: List<String>,
)
data class DevDoctorTaskResponse(val taskId: String, val status: String)
data class DevAgentSuggestionConfirmationResponse(val taskId: String, val suggestionId: String, val confirmedBy: String, val confirmedTime: Long)

@RestController
@RequestMapping("/api/documents")
class DevDocumentController(
    private val drafts: DocumentDraftService,
    private val downloads: DocumentDownloadService,
    private val catalogDrafts: DevCatalogDocumentService,
    private val catalogBindings: DocumentCatalogBindingService,
    private val commands: DocumentCommandService,
    private val reviews: DevReviewService,
    private val reviewWorkflow: DocumentReviewWorkflowService,
    private val publish: PublishDocumentService,
    private val offline: OfflineDocumentService,
    private val restoreOffline: RestoreOfflineDocumentService,
    private val archive: ArchiveDocumentService,
    private val queries: DevDocumentQueryService,
    private val operations: DevOperationsService,
    private val retryDeliveries: RetryDocumentDeliveryService,
    private val doctorScheduler: ScheduleDocumentDoctorService,
    private val agentSuggestions: ConfirmAgentSuggestionService,
    private val platformMirror: DevPlatformMirrorService,
) {
    @PostMapping(consumes = ["multipart/form-data"])
    @ResponseStatus(HttpStatus.CREATED)
    fun createDraft(
        @RequestParam documentNumber: String,
        @RequestParam title: String,
        @RequestParam(required = false) folderId: String?,
        @RequestParam file: MultipartFile,
    ): DevDocumentDetail = file.inputStream.use { content ->
        val document = catalogDrafts.create(
            CreateDocumentDraftCommand(documentNumber, title, requiredFileName(file), file.size, file.contentType),
            folderId,
            content,
        )
        queries.detail(document.id)
    }

    @GetMapping
    fun page(
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) lifecycleState: String?,
    ) = queries.page(limit, lifecycleState)

    @GetMapping("/{documentId}")
    fun detail(@PathVariable documentId: String): DevDocumentDetail = queries.detail(Identifier(documentId))

    @GetMapping("/{documentId}/sync-status")
    fun syncStatus(@PathVariable documentId: String): DevDocumentSyncStatus = queries.syncStatus(Identifier(documentId))

    @GetMapping("/{documentId}/logs")
    fun logs(
        @PathVariable documentId: String,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<DevDocumentLogEntry> = queries.logs(Identifier(documentId), limit)

    @GetMapping("/{documentId}/content")
    fun downloadCurrent(@PathVariable documentId: String): ResponseEntity<StreamingResponseBody> =
        downloadResponse(downloads.download(Identifier(documentId)))

    @GetMapping("/{documentId}/versions/{versionId}/content")
    fun downloadVersion(
        @PathVariable documentId: String,
        @PathVariable versionId: String,
    ): ResponseEntity<StreamingResponseBody> = downloadResponse(downloads.download(Identifier(documentId), Identifier(versionId)))

    @PatchMapping("/{documentId}")
    fun rename(@PathVariable documentId: String, @RequestBody request: DevRenameDocumentRequest): DevDocumentDetail {
        val document = drafts.rename(Identifier(documentId), request.title)
        return queries.detail(document.id)
    }

    @PostMapping("/{documentId}/folder")
    fun moveToFolder(
        @PathVariable documentId: String,
        @RequestBody request: DevMoveDocumentRequest,
    ): DevDocumentDetail {
        val document = catalogBindings.move(Identifier(documentId), request.folderId)
        return queries.detail(document.id)
    }

    @PostMapping("/{documentId}/versions", consumes = ["multipart/form-data"])
    fun addVersion(
        @PathVariable documentId: String,
        @RequestParam versionNumber: String,
        @RequestParam file: MultipartFile,
    ): DevDocumentDetail = file.inputStream.use { content ->
        val document = drafts.addVersion(
            Identifier(documentId),
            AddDocumentVersionCommand(versionNumber, requiredFileName(file), file.size, file.contentType),
            content,
        )
        queries.detail(document.id)
    }

    @PostMapping("/{documentId}/submit")
    fun submit(@PathVariable documentId: String, @RequestBody request: DevSubmitDocumentRequest): DevWorkflowResponse =
        reviews.submit(Identifier(documentId), request.reviewerId, request.reviewRouteId).toResponse()

    @PostMapping("/{documentId}/revise")
    fun revise(@PathVariable documentId: String): DevDocumentDetail {
        val document = commands.revise(Identifier(documentId))
        return queries.detail(document.id)
    }

    @PostMapping("/{documentId}/publish")
    fun publish(
        @PathVariable documentId: String,
        @RequestBody(required = false) request: DevPublishDocumentRequest?,
    ): DevDocumentDetail {
        val document = publish.publish(Identifier(documentId), request?.deliveryProfileId)
        return queries.detail(document.id)
    }

    @PostMapping("/{documentId}/offline")
    fun offline(@PathVariable documentId: String): DevDocumentDetail {
        val document = offline.offline(Identifier(documentId))
        return queries.detail(document.id)
    }

    @PostMapping("/{documentId}/restore")
    fun restore(@PathVariable documentId: String): DevDocumentDetail {
        val document = restoreOffline.restore(Identifier(documentId))
        return queries.detail(document.id)
    }

    @PostMapping("/{documentId}/archive")
    fun archive(@PathVariable documentId: String): DevDocumentDetail {
        val document = archive.archive(Identifier(documentId))
        return queries.detail(document.id)
    }

    @GetMapping("/{documentId}/doctor")
    fun doctor(@PathVariable documentId: String) = operations.inspectDocument(Identifier(documentId))

    @GetMapping("/{documentId}/platform-mirror")
    fun platformMirror(@PathVariable documentId: String) = Identifier(documentId).let { identifier ->
        val detail = queries.detail(identifier)
        platformMirror.readDocument(identifier, detail.deliveries)
    }

    @PostMapping("/{documentId}/doctor/tasks")
    fun scheduleDoctor(@PathVariable documentId: String): DevDoctorTaskResponse {
        val task = doctorScheduler.schedule(Identifier(documentId))
        return DevDoctorTaskResponse(task.id.value, task.status.name)
    }

    @PostMapping("/workflows/{workflowId}/tasks/{taskId}/approve")
    fun approve(
        @PathVariable workflowId: String,
        @PathVariable taskId: String,
        @RequestBody request: DevWorkflowDecisionRequest,
    ): DevDocumentDetail {
        val document = reviewWorkflow.approve(Identifier(workflowId), Identifier(taskId), request.comment, request.deliveryProfileId)
        return queries.detail(document.id)
    }

    @PostMapping("/workflows/{workflowId}/tasks/{taskId}/reject")
    fun reject(
        @PathVariable workflowId: String,
        @PathVariable taskId: String,
        @RequestBody request: DevWorkflowDecisionRequest,
    ): DevDocumentDetail {
        val document = reviewWorkflow.reject(Identifier(workflowId), Identifier(taskId), request.comment)
        return queries.detail(document.id)
    }

    @PostMapping("/delivery-targets/{deliveryId}/retry")
    fun retryDelivery(@PathVariable deliveryId: String): DevDocumentDetail {
        val delivery = retryDeliveries.retry(Identifier(deliveryId))
        return queries.detail(delivery.documentId)
    }

    @PostMapping("/agent-results/{taskId}/suggestions/{suggestionId}/confirm")
    fun confirmAgentSuggestion(
        @PathVariable taskId: String,
        @PathVariable suggestionId: String,
    ): DevAgentSuggestionConfirmationResponse {
        val confirmation = agentSuggestions.confirm(Identifier(taskId), Identifier(suggestionId))
        return DevAgentSuggestionConfirmationResponse(
            confirmation.taskId.value, confirmation.suggestionId.value, confirmation.confirmedBy.value, confirmation.confirmedAt,
        )
    }

    private fun WorkflowInstance.toResponse(): DevWorkflowResponse = DevWorkflowResponse(
        id.value,
        state.name,
        tasks.first().id.value,
        tasks.map { it.id.value },
    )

    private fun requiredFileName(file: MultipartFile): String = file.originalFilename?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("上传文件必须包含文件名。")

    private fun downloadResponse(download: DocumentDownload): ResponseEntity<StreamingResponseBody> {
        val contentType = download.contentType?.let { candidate ->
            runCatching { MediaType.parseMediaType(candidate) }.getOrNull()
        } ?: MediaType.APPLICATION_OCTET_STREAM
        val disposition = ContentDisposition.attachment().filename(download.fileName, StandardCharsets.UTF_8).build().toString()
        return ResponseEntity.ok()
            .contentType(contentType)
            .contentLength(download.contentLength)
            .header("Content-Disposition", disposition)
            .body(StreamingResponseBody { output -> download.use { it.content.copyTo(output) } })
    }
}
