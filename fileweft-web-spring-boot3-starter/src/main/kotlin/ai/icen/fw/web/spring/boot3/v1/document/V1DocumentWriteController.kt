package ai.icen.fw.web.spring.boot3.v1.document

import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.api.ApiResponse
import ai.icen.fw.web.api.v1.document.AddDocumentVersionCommand
import ai.icen.fw.web.api.v1.document.CreateDocumentDraftCommand
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto
import ai.icen.fw.web.api.v1.document.RenameDocumentCommand
import ai.icen.fw.web.api.v1.document.RenameDocumentRequest
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.document.DocumentApiLocations
import ai.icen.fw.web.runtime.v1.document.DocumentMetadataApiInputs
import ai.icen.fw.web.runtime.v1.document.DocumentApiWriteFacade
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Spring Boot 3 MVC edge for the first formal v1 document write routes.
 *
 * Multipart values remain untrusted until their exact cardinality and public
 * command validation have succeeded. Only then is the caller-owned file
 * stream opened and delegated to the transport-neutral application facade.
 */
@RestController
@RequestMapping(
    value = ["/fileweft/v1/documents"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class V1DocumentWriteController(
    private val documents: DocumentApiWriteFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider?,
) {
    fun create(
        @RequestParam(name = "documentNumber", required = false) documentNumbers: List<String>?,
        @RequestParam(name = "title", required = false) titles: List<String>?,
        @RequestParam(name = "folderId", required = false) folderIds: List<String>?,
        @RequestParam(name = "file", required = false) files: List<MultipartFile>?,
    ): ResponseEntity<ApiResponse<Any?>> = createRequest(
        documentNumbers,
        titles,
        folderIds,
        files,
        null,
        null,
    )

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createWithMetadata(
        @RequestParam(name = "documentNumber", required = false) documentNumbers: List<String>?,
        @RequestParam(name = "title", required = false) titles: List<String>?,
        @RequestParam(name = "folderId", required = false) folderIds: List<String>?,
        @RequestParam(name = "file", required = false) files: List<MultipartFile>?,
        @RequestParam(name = "metadataSchemaId", required = false) metadataSchemaIds: List<String>?,
        @RequestParam(name = "metadata", required = false) metadataEntries: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = createRequest(
        documentNumbers,
        titles,
        folderIds,
        files,
        metadataSchemaIds,
        metadataEntries,
    )

    private fun createRequest(
        documentNumbers: List<String>?,
        titles: List<String>?,
        folderIds: List<String>?,
        files: List<MultipartFile>?,
        metadataSchemaIds: List<String>?,
        metadataEntries: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = executeCreated {
        val documentNumber = requiredSingle(documentNumbers, "documentNumber")
        val title = requiredSingle(titles, "title")
        val folderId = optionalSingle(folderIds, "folderId")
        val file = requiredSingle(files, "file")
        val command = CreateDocumentDraftCommand(
            documentNumber = documentNumber,
            title = title,
            fileName = requiredOriginalFileName(file),
            contentLength = file.size,
            contentType = file.contentType,
            folderId = folderId,
        )
        val metadata = DocumentMetadataApiInputs.parse(metadataSchemaIds, metadataEntries)
        file.inputStream.use { content ->
            if (metadata == null) documents.create(command, content) else documents.create(command, metadata, content)
        }
    }

    fun addVersion(
        @PathVariable("documentId") documentId: String,
        @RequestParam(name = "versionNumber", required = false) versionNumbers: List<String>?,
        @RequestParam(name = "file", required = false) files: List<MultipartFile>?,
    ): ResponseEntity<ApiResponse<Any?>> = addVersionRequest(
        documentId,
        versionNumbers,
        files,
        null,
        null,
    )

    @PostMapping(
        path = ["/{documentId}/versions"],
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    fun addVersionWithMetadata(
        @PathVariable("documentId") documentId: String,
        @RequestParam(name = "versionNumber", required = false) versionNumbers: List<String>?,
        @RequestParam(name = "file", required = false) files: List<MultipartFile>?,
        @RequestParam(name = "metadataSchemaId", required = false) metadataSchemaIds: List<String>?,
        @RequestParam(name = "metadata", required = false) metadataEntries: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = addVersionRequest(
        documentId,
        versionNumbers,
        files,
        metadataSchemaIds,
        metadataEntries,
    )

    private fun addVersionRequest(
        documentId: String,
        versionNumbers: List<String>?,
        files: List<MultipartFile>?,
        metadataSchemaIds: List<String>?,
        metadataEntries: List<String>?,
    ): ResponseEntity<ApiResponse<Any?>> = executeCreated {
        val versionNumber = requiredSingle(versionNumbers, "versionNumber")
        val file = requiredSingle(files, "file")
        val command = AddDocumentVersionCommand(
            versionNumber = versionNumber,
            fileName = requiredOriginalFileName(file),
            contentLength = file.size,
            contentType = file.contentType,
        )
        val metadata = DocumentMetadataApiInputs.parse(metadataSchemaIds, metadataEntries)
        file.inputStream.use { content ->
            if (metadata == null) {
                documents.addVersion(documentId, command, content)
            } else {
                documents.addVersion(documentId, command, metadata, content)
            }
        }
    }

    @PatchMapping(
        path = ["/{documentId}"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun rename(
        @PathVariable("documentId") documentId: String,
        @RequestBody(required = false) request: RenameDocumentRequest?,
    ): ResponseEntity<ApiResponse<Any?>> = execute {
        val title = requireNotNull(request?.title) { "Document title is required." }
        documents.rename(documentId, RenameDocumentCommand(title))
    }

    private fun executeCreated(action: () -> DocumentCommandResultDto): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            val result = action()
            val response = ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
            DocumentApiLocations.detailIfRoutable(result.documentId)?.let { location -> response.location(location) }
            response.body(responses.success<Any?>(result, traceId))
        } catch (failure: Exception) {
            failureResponse(failure, traceId)
        }
    }

    private fun execute(action: () -> DocumentCommandResultDto): ResponseEntity<ApiResponse<Any?>> {
        val traceId = currentTraceId()
        return try {
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(responses.success<Any?>(action(), traceId))
        } catch (failure: Exception) {
            failureResponse(failure, traceId)
        }
    }

    private fun failureResponse(failure: Exception, traceId: String?): ResponseEntity<ApiResponse<Any?>> {
        val mapped = responses.failure(failure, traceId)
        return ResponseEntity.status(mapped.status.statusCode)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapped.response)
    }

    private fun <T> requiredSingle(values: List<T>?, field: String): T {
        val supplied = requireNotNull(values) { "$field must be provided exactly once." }
        require(supplied.size == 1) { "$field must be provided exactly once." }
        return supplied[0]
    }

    private fun optionalSingle(values: List<String>?, field: String): String? {
        if (values.isNullOrEmpty()) {
            return null
        }
        require(values.size == 1) { "$field must not be repeated." }
        return values[0]
    }

    private fun requiredOriginalFileName(file: MultipartFile): String =
        file.originalFilename?.takeIf { name -> name.isNotBlank() }
            ?: throw IllegalArgumentException("Document file name must be supplied.")

    /** Observability must not make a committed or safely executable command fail. */
    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        null
    }
}
