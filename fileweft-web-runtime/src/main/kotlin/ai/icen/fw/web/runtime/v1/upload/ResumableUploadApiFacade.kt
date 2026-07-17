package ai.icen.fw.web.runtime.v1.upload

import ai.icen.fw.application.upload.ResumableUploadNotFoundException
import ai.icen.fw.application.upload.ResumableUploadCompletionResult
import ai.icen.fw.application.upload.ResumableUploadPart
import ai.icen.fw.application.upload.ResumableUploadService
import ai.icen.fw.application.upload.ResumableUploadSession
import ai.icen.fw.application.upload.ResumableUploadSessionStatus
import ai.icen.fw.application.upload.ResumableUploadSessionView
import ai.icen.fw.application.upload.StartResumableUploadCommand as ApplicationStartResumableUploadCommand
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.web.api.v1.upload.ResumableUploadCompletionDto
import ai.icen.fw.web.api.v1.upload.ResumableUploadDto
import ai.icen.fw.web.api.v1.upload.ResumableUploadPartDto
import ai.icen.fw.web.api.v1.upload.ResumableUploadStatuses
import ai.icen.fw.web.api.v1.upload.StartResumableUploadCommand
import ai.icen.fw.web.api.v1.upload.StartResumableUploadRequest
import ai.icen.fw.web.runtime.v1.IdempotencyKeyParser
import java.io.InputStream

/**
 * Pure-JVM boundary for the five formal v1 resumable-upload operations.
 *
 * Trusted tenant and user identity remain inside FlowWeft. The only
 * caller-supplied identity token is the start request's `Idempotency-Key`; it
 * is validated here; the application service immediately replaces it with a
 * tenant-scoped SHA-256 digest before any storage or persistence operation.
 */
class ResumableUploadApiFacade private constructor(
    private val uploads: ResumableUploadFacadeOperations,
) {
    constructor(uploads: ResumableUploadService) : this(DefaultResumableUploadFacadeOperations(uploads))

    fun start(
        idempotencyKeyHeaderValues: List<String>?,
        request: StartResumableUploadRequest,
    ): ResumableUploadDto {
        val command = request.toCommand()
        val callerKey = IdempotencyKeyParser.parse(idempotencyKeyHeaderValues)
        val view = uploads.startAndInspectWithCallerKey(
            ApplicationStartResumableUploadCommand(
                fileName = command.fileName,
                contentLength = command.contentLength,
                assetType = DOCUMENT_ASSET_TYPE,
                idempotencyKey = callerKey,
                contentType = command.contentType,
                contentHash = command.contentHash,
                metadata = emptyMap(),
            ),
        )
        return view.toPublicDto()
    }

    fun inspect(uploadId: String): ResumableUploadDto {
        val identifier = ResumableUploadApiInputs.uploadId(uploadId)
        return uploads.inspect(identifier).toPublicDto()
    }

    fun uploadPart(
        uploadId: String,
        partNumber: Int,
        contentLength: Long,
        content: InputStream,
    ): ResumableUploadPartDto {
        val identifier = ResumableUploadApiInputs.uploadId(uploadId)
        require(partNumber in 1..ResumableUploadPartDto.MAX_PART_NUMBER) {
            "Upload part number must be between 1 and ${ResumableUploadPartDto.MAX_PART_NUMBER}."
        }
        require(contentLength > 0) { "Upload part content length must be positive." }
        return uploads
            .uploadPart(identifier, partNumber, contentLength, content)
            .toPublicDto(uploadId)
    }

    fun complete(uploadId: String): ResumableUploadCompletionDto {
        val identifier = ResumableUploadApiInputs.uploadId(uploadId)
        val completion = uploads.completeAndInspect(identifier)
        val result = completion.result
        return ResumableUploadCompletionDto(
            uploadId = uploadId,
            fileObjectId = result.fileObject.id.value,
            fileAssetId = result.fileAsset.id.value,
            completedAt = completion.completedAt,
        )
    }

    fun abort(uploadId: String): ResumableUploadDto {
        val identifier = ResumableUploadApiInputs.uploadId(uploadId)
        return uploads.abortAndInspect(identifier).toPublicDto()
    }

    private fun StartResumableUploadRequest.toCommand(): StartResumableUploadCommand =
        StartResumableUploadCommand(
            fileName = requireNotNull(fileName) { "Upload file name is required." },
            contentLength = requireNotNull(contentLength) { "Upload content length is required." },
            contentType = contentType,
            contentHash = contentHash,
        )

    private fun ResumableUploadSessionView.toPublicDto(): ResumableUploadDto =
        session.toPublicDto(parts)

    private fun ResumableUploadSession.toPublicDto(parts: List<ResumableUploadPart>): ResumableUploadDto {
        val publicStatus = status.toPublicStatus(id)
        val completion = if (publicStatus == ResumableUploadStatuses.COMPLETED) {
            ResumableUploadCompletionDto(
                uploadId = id.value,
                fileObjectId = fileObjectId.value,
                fileAssetId = fileAssetId.value,
                completedAt = requireNotNull(completedAt) { "Completed upload session has no completion time." },
            )
        } else {
            null
        }
        return ResumableUploadDto(
            uploadId = id.value,
            fileName = fileName,
            contentLength = contentLength,
            status = publicStatus,
            expiresAt = expiresAt,
            createdTime = createdTime,
            updatedTime = updatedTime,
            uploadedParts = parts
                .sortedBy { part -> part.partNumber }
                .map { part -> part.toPublicDto(id.value) },
            contentType = contentType,
            contentHash = expectedContentHash,
            completion = completion,
        )
    }

    private fun ResumableUploadPart.toPublicDto(uploadId: String): ResumableUploadPartDto =
        ResumableUploadPartDto(
            uploadId = uploadId,
            partNumber = partNumber,
            contentLength = contentLength,
            uploadedTime = updatedTime,
        )

    private fun ResumableUploadSessionStatus.toPublicStatus(uploadId: Identifier): String = when (this) {
        ResumableUploadSessionStatus.ACTIVE -> ResumableUploadStatuses.UPLOADING
        ResumableUploadSessionStatus.COMPLETING -> ResumableUploadStatuses.FINALIZING
        ResumableUploadSessionStatus.COMPLETED -> ResumableUploadStatuses.COMPLETED
        ResumableUploadSessionStatus.FAILED -> ResumableUploadStatuses.FAILED
        ResumableUploadSessionStatus.ABORTED -> ResumableUploadStatuses.ABORTED
        ResumableUploadSessionStatus.EXPIRED -> ResumableUploadStatuses.EXPIRED
        ResumableUploadSessionStatus.ABORTING,
        ResumableUploadSessionStatus.QUARANTINED,
        -> throw ResumableUploadNotFoundException(uploadId)
    }

    companion object {
        private const val DOCUMENT_ASSET_TYPE: String = "DOCUMENT"

        internal fun forTesting(
            uploads: ResumableUploadFacadeOperations,
        ): ResumableUploadApiFacade = ResumableUploadApiFacade(uploads)
    }
}

/** Small internal seam that keeps facade tests independent of storage infrastructure. */
internal interface ResumableUploadFacadeOperations {
    fun startAndInspectWithCallerKey(command: ApplicationStartResumableUploadCommand): ResumableUploadSessionView

    fun inspect(uploadId: Identifier): ResumableUploadSessionView

    fun uploadPart(
        uploadId: Identifier,
        partNumber: Int,
        contentLength: Long,
        content: InputStream,
    ): ResumableUploadPart

    fun completeAndInspect(uploadId: Identifier): ResumableUploadCompletionResult

    fun abortAndInspect(uploadId: Identifier): ResumableUploadSessionView
}

private class DefaultResumableUploadFacadeOperations(
    private val delegate: ResumableUploadService,
) : ResumableUploadFacadeOperations {
    override fun startAndInspectWithCallerKey(
        command: ApplicationStartResumableUploadCommand,
    ): ResumableUploadSessionView = delegate.startAndInspectWithCallerKey(command)

    override fun inspect(uploadId: Identifier): ResumableUploadSessionView = delegate.inspect(uploadId)

    override fun uploadPart(
        uploadId: Identifier,
        partNumber: Int,
        contentLength: Long,
        content: InputStream,
    ): ResumableUploadPart = delegate.uploadPart(uploadId, partNumber, contentLength, content)

    override fun completeAndInspect(uploadId: Identifier): ResumableUploadCompletionResult =
        delegate.completeAndInspect(uploadId)

    override fun abortAndInspect(uploadId: Identifier): ResumableUploadSessionView = delegate.abortAndInspect(uploadId)
}

/** Shared validation for opaque upload identifiers entering the v1 boundary. */
internal object ResumableUploadApiInputs {
    fun uploadId(value: String): Identifier {
        require(value.isNotBlank()) { "Upload id must not be blank." }
        require(value.length <= MAX_IDENTIFIER_LENGTH) {
            "Upload id must not exceed $MAX_IDENTIFIER_LENGTH characters."
        }
        require(value.none(::isUnsafeControlCharacter)) { "Upload id must not contain control characters." }
        return Identifier(value)
    }

    private fun isUnsafeControlCharacter(character: Char): Boolean =
        character.code in 0..31 || character.code in 127..159

    private const val MAX_IDENTIFIER_LENGTH: Int = 128
}
