package com.fileweft.dev.api.web

import com.fileweft.application.archive.ArchiveDocumentService
import com.fileweft.application.document.AddDocumentVersionCommand
import com.fileweft.application.document.CreateDocumentDraftCommand
import com.fileweft.application.document.DocumentCommandService
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.publish.PublishDocumentService
import com.fileweft.application.workflow.DocumentReviewWorkflowService
import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.catalog.DevCatalogDocumentService
import com.fileweft.dev.api.service.DevDocumentDetail
import com.fileweft.dev.api.service.DevDocumentQueryService
import com.fileweft.dev.api.service.DevOperationsService
import com.fileweft.dev.api.service.DevReviewService
import com.fileweft.domain.workflow.WorkflowInstance
import org.springframework.http.HttpStatus
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

data class DevRenameDocumentRequest(val title: String)
data class DevSubmitDocumentRequest(val reviewerId: String? = null)
data class DevWorkflowDecisionRequest(val comment: String? = null)
data class DevWorkflowResponse(val workflowId: String, val state: String, val taskId: String)

@RestController
@RequestMapping("/api/documents")
class DevDocumentController(
    private val drafts: DocumentDraftService,
    private val catalogDrafts: DevCatalogDocumentService,
    private val commands: DocumentCommandService,
    private val reviews: DevReviewService,
    private val reviewWorkflow: DocumentReviewWorkflowService,
    private val publish: PublishDocumentService,
    private val offline: OfflineDocumentService,
    private val archive: ArchiveDocumentService,
    private val queries: DevDocumentQueryService,
    private val operations: DevOperationsService,
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

    @PatchMapping("/{documentId}")
    fun rename(@PathVariable documentId: String, @RequestBody request: DevRenameDocumentRequest): DevDocumentDetail {
        val document = drafts.rename(Identifier(documentId), request.title)
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
        reviews.submit(Identifier(documentId), request.reviewerId).toResponse()

    @PostMapping("/{documentId}/revise")
    fun revise(@PathVariable documentId: String): DevDocumentDetail {
        val document = commands.revise(Identifier(documentId))
        return queries.detail(document.id)
    }

    @PostMapping("/{documentId}/publish")
    fun publish(@PathVariable documentId: String): DevDocumentDetail {
        val document = publish.publish(Identifier(documentId))
        return queries.detail(document.id)
    }

    @PostMapping("/{documentId}/offline")
    fun offline(@PathVariable documentId: String): DevDocumentDetail {
        val document = offline.offline(Identifier(documentId))
        return queries.detail(document.id)
    }

    @PostMapping("/{documentId}/archive")
    fun archive(@PathVariable documentId: String): DevDocumentDetail {
        val document = archive.archive(Identifier(documentId))
        return queries.detail(document.id)
    }

    @GetMapping("/{documentId}/doctor")
    fun doctor(@PathVariable documentId: String) = operations.inspectDocument(Identifier(documentId))

    @PostMapping("/workflows/{workflowId}/tasks/{taskId}/approve")
    fun approve(
        @PathVariable workflowId: String,
        @PathVariable taskId: String,
        @RequestBody request: DevWorkflowDecisionRequest,
    ): DevDocumentDetail {
        val document = reviewWorkflow.approve(Identifier(workflowId), Identifier(taskId), request.comment)
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

    private fun WorkflowInstance.toResponse(): DevWorkflowResponse = DevWorkflowResponse(
        id.value,
        state.name,
        tasks.single().id.value,
    )

    private fun requiredFileName(file: MultipartFile): String = file.originalFilename?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("上传文件必须包含文件名。")
}
