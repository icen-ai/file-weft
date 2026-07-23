package ai.icen.fw.web.spring.boot2

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
 * Boot 2 MVC edge for the first formal v1 document write routes.
 *
 * Multipart request values stay untrusted until this controller has verified
 * their exact cardinality and built a pure public command. The binary stream
 * is passed only to the transport-neutral write facade; tenant, user, catalog
 * ACL, storage metadata, and persistence are all derived behind that boundary.
 */
@RestController
@RequestMapping(
    value = ["/fileweft/v1/documents"],
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class DocumentV1WriteController(
    private val documents: DocumentApiWriteFacade,
    private val responses: V1ApiResponseFactory,
    private val traceContextProvider: TraceContextProvider? = null,
) {
    /**
     * Not an HTTP endpoint: this method carries no route mapping and is never
     * dispatched by Spring MVC. The mapped create route is [createWithMetadata].
     * Retained only for binary compatibility with hosts compiled against the
     * first v1 write controller; it will be removed in a future major release.
     */
    @Deprecated(
        "Not an HTTP endpoint; retained for binary compatibility. " +
            "The mapped create route is createWithMetadata.",
    )
    fun create(
        @RequestParam(name = "documentNumber", required = false) documentNumbers: List<String>?,
        @RequestParam(name = "title", required = false) titles: List<String>?,
        @RequestParam(name = "folderId", required = false) folderIds: List<String>?,
        @RequestParam(name = "file", required = false) files: List<MultipartFile>?,
    ): ResponseEntity<ApiResponse<*>> = createRequest(
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
    ): ResponseEntity<ApiResponse<*>> = createRequest(
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
    ): ResponseEntity<ApiResponse<*>> = executeCreated {
        val file = requiredSingle(files, "Document file")
        val command = CreateDocumentDraftCommand(
            documentNumber = requiredSingle(documentNumbers, "Document number"),
            title = requiredSingle(titles, "Document title"),
            fileName = requiredFileName(file),
            contentLength = file.size,
            contentType = file.contentType,
            folderId = optionalSingle(folderIds, "Document folder id"),
        )
        val metadata = DocumentMetadataApiInputs.parse(metadataSchemaIds, metadataEntries)
        file.inputStream.use { content ->
            if (metadata == null) documents.create(command, content) else documents.create(command, metadata, content)
        }
    }

    /**
     * Not an HTTP endpoint: this method carries no route mapping and is never
     * dispatched by Spring MVC. The mapped version route is [addVersionWithMetadata].
     * Retained only for binary compatibility with hosts compiled against the
     * first v1 write controller; it will be removed in a future major release.
     */
    @Deprecated(
        "Not an HTTP endpoint; retained for binary compatibility. " +
            "The mapped version route is addVersionWithMetadata.",
    )
    fun addVersion(
        @PathVariable("documentId") documentId: String,
        @RequestParam(name = "versionNumber", required = false) versionNumbers: List<String>?,
        @RequestParam(name = "file", required = false) files: List<MultipartFile>?,
    ): ResponseEntity<ApiResponse<*>> = addVersionRequest(
        documentId,
        versionNumbers,
        files,
        null,
        null,
    )

    @PostMapping(
        value = ["/{documentId}/versions"],
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
    )
    fun addVersionWithMetadata(
        @PathVariable("documentId") documentId: String,
        @RequestParam(name = "versionNumber", required = false) versionNumbers: List<String>?,
        @RequestParam(name = "file", required = false) files: List<MultipartFile>?,
        @RequestParam(name = "metadataSchemaId", required = false) metadataSchemaIds: List<String>?,
        @RequestParam(name = "metadata", required = false) metadataEntries: List<String>?,
    ): ResponseEntity<ApiResponse<*>> = addVersionRequest(
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
    ): ResponseEntity<ApiResponse<*>> = executeCreated {
        val file = requiredSingle(files, "Document version file")
        val command = AddDocumentVersionCommand(
            versionNumber = requiredSingle(versionNumbers, "Document version number"),
            fileName = requiredFileName(file),
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
        value = ["/{documentId}"],
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun rename(
        @PathVariable("documentId") documentId: String,
        @RequestBody(required = false) request: RenameDocumentRequest?,
    ): ResponseEntity<ApiResponse<*>> = executeOk {
        val command = RenameDocumentCommand(
            requireNotNull(request?.title) { "Document title must be supplied." },
        )
        documents.rename(documentId, command)
    }

    private fun executeCreated(action: () -> DocumentCommandResultDto): ResponseEntity<ApiResponse<*>> {
        val traceId = currentTraceId()
        return try {
            val result = action()
            val response: ApiResponse<*> = responses.success(result, traceId)
            val builder = ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
            DocumentApiLocations.detailIfRoutable(result.documentId)?.let { location -> builder.location(location) }
            builder.body(response)
        } catch (failure: Exception) {
            failureResponse(failure, traceId)
        }
    }

    private fun executeOk(action: () -> DocumentCommandResultDto): ResponseEntity<ApiResponse<*>> {
        val traceId = currentTraceId()
        return try {
            val response: ApiResponse<*> = responses.success(action(), traceId)
            ResponseEntity.ok(response)
        } catch (failure: Exception) {
            failureResponse(failure, traceId)
        }
    }

    private fun failureResponse(failure: Exception, traceId: String?): ResponseEntity<ApiResponse<*>> {
        val mapped = responses.failure(failure, traceId)
        val response: ApiResponse<*> = mapped.response
        return ResponseEntity.status(mapped.status.statusCode)
            .contentType(MediaType.APPLICATION_JSON)
            .body(response)
    }

    private fun requiredFileName(file: MultipartFile): String =
        file.originalFilename?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Document file name must be supplied.")

    private fun <T> requiredSingle(values: List<T>?, field: String): T {
        require(values?.size == 1) { "$field must be supplied exactly once." }
        return values[0]
    }

    private fun optionalSingle(values: List<String>?, field: String): String? {
        require(values == null || values.size <= 1) { "$field must be supplied at most once." }
        return values?.singleOrNull()
    }

    private fun currentTraceId(): String? = try {
        traceContextProvider?.currentTraceContext()?.traceId?.value
    } catch (_: Exception) {
        // Observability must not make a safely executable API operation fail.
        null
    }
}
