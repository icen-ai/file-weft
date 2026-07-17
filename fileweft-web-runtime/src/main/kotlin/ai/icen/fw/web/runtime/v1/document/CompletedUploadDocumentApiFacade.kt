package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.upload.AddDocumentVersionFromCompletedUploadCommand
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimResult
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimService
import ai.icen.fw.application.upload.CreateDocumentFromCompletedUploadCommand
import ai.icen.fw.web.api.v1.document.AddDocumentVersionFromCompletedUploadRequest
import ai.icen.fw.web.api.v1.document.CreateDocumentFromCompletedUploadRequest
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto
import ai.icen.fw.web.runtime.v1.IdempotencyKeyParser
import ai.icen.fw.web.runtime.v1.upload.ResumableUploadApiInputs

/**
 * Transport-neutral v1 boundary for consuming an already completed upload.
 *
 * The facade accepts no tenant, owner, storage location, file-object id or
 * file-asset id. Those security bindings are reloaded and verified by the
 * application service in the same transaction that writes the document and
 * one-time consumption marker.
 */
class CompletedUploadDocumentApiFacade private constructor(
    private val operations: CompletedUploadDocumentFacadeOperations,
) {
    constructor(service: CompletedResumableUploadAssetClaimService) : this(
        DefaultCompletedUploadDocumentFacadeOperations(service),
    )

    fun createDocument(
        uploadId: String,
        idempotencyKeyHeaderValues: List<String>?,
        request: CreateDocumentFromCompletedUploadRequest,
    ): DocumentCommandResultDto {
        val key = IdempotencyKeyParser.parse(idempotencyKeyHeaderValues)
        val result = operations.createDocument(
            CreateDocumentFromCompletedUploadCommand(
                uploadId = ResumableUploadApiInputs.uploadId(uploadId),
                documentNumber = requireNotNull(request.documentNumber) {
                    "Document number must be supplied."
                },
                title = requireNotNull(request.title) { "Document title must be supplied." },
                idempotencyKey = key,
            ),
        )
        return result.toDocumentResult()
    }

    fun addDocumentVersion(
        documentId: String,
        uploadId: String,
        idempotencyKeyHeaderValues: List<String>?,
        request: AddDocumentVersionFromCompletedUploadRequest,
    ): DocumentCommandResultDto {
        val key = IdempotencyKeyParser.parse(idempotencyKeyHeaderValues)
        val result = operations.addDocumentVersion(
            AddDocumentVersionFromCompletedUploadCommand(
                uploadId = ResumableUploadApiInputs.uploadId(uploadId),
                documentId = DocumentApiInputs.documentId(documentId),
                versionNumber = requireNotNull(request.versionNumber) {
                    "Document version number must be supplied."
                },
                idempotencyKey = key,
            ),
        )
        return result.toDocumentResult()
    }

    private fun CompletedResumableUploadAssetClaimResult.toDocumentResult(): DocumentCommandResultDto =
        DocumentCommandResultDto(documentId.value, versionId.value)

    companion object {
        internal fun forTesting(
            operations: CompletedUploadDocumentFacadeOperations,
        ): CompletedUploadDocumentApiFacade = CompletedUploadDocumentApiFacade(operations)
    }
}

internal interface CompletedUploadDocumentFacadeOperations {
    fun createDocument(
        command: CreateDocumentFromCompletedUploadCommand,
    ): CompletedResumableUploadAssetClaimResult

    fun addDocumentVersion(
        command: AddDocumentVersionFromCompletedUploadCommand,
    ): CompletedResumableUploadAssetClaimResult
}

private class DefaultCompletedUploadDocumentFacadeOperations(
    private val service: CompletedResumableUploadAssetClaimService,
) : CompletedUploadDocumentFacadeOperations {
    override fun createDocument(
        command: CreateDocumentFromCompletedUploadCommand,
    ): CompletedResumableUploadAssetClaimResult = service.createDocument(command)

    override fun addDocumentVersion(
        command: AddDocumentVersionFromCompletedUploadCommand,
    ): CompletedResumableUploadAssetClaimResult = service.addDocumentVersion(command)
}
