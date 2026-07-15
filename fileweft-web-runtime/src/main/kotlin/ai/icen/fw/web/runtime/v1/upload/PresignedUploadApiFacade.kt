package ai.icen.fw.web.runtime.v1.upload

import ai.icen.fw.application.upload.CancelPresignedUploadCommand
import ai.icen.fw.application.upload.CompletePresignedUploadAssetCommand
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimResult
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimService
import ai.icen.fw.application.upload.InspectPresignedUploadCommand
import ai.icen.fw.application.upload.PresignedUploadGrantResult
import ai.icen.fw.application.upload.PresignedUploadService
import ai.icen.fw.application.upload.PresignedUploadSessionStatus
import ai.icen.fw.application.upload.PresignedUploadStatusResult
import ai.icen.fw.application.upload.ReissuePresignedUploadCommand
import ai.icen.fw.application.upload.StartPresignedUploadCommand as ApplicationStartPresignedUploadCommand
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.web.api.v1.upload.PresignedUploadDto
import ai.icen.fw.web.api.v1.upload.PresignedUploadGrantDto
import ai.icen.fw.web.api.v1.upload.PresignedUploadFinalizationDto
import ai.icen.fw.web.api.v1.upload.PresignedUploadStatuses
import ai.icen.fw.web.api.v1.upload.StartPresignedUploadCommand
import ai.icen.fw.web.api.v1.upload.StartPresignedUploadRequest
import ai.icen.fw.web.runtime.v1.IdempotencyKeyParser
import java.time.Duration

/** Transport-neutral boundary for the additive FlowWeft v1 direct-upload resource. */
class PresignedUploadApiFacade private constructor(
    private val uploads: PresignedUploadFacadeOperations,
    private val grantDuration: Duration = Duration.ofMinutes(15),
) {
    @JvmOverloads
    constructor(
        uploads: PresignedUploadService,
        claims: CompletedPresignedUploadAssetClaimService,
        grantDuration: Duration = Duration.ofMinutes(15),
    ) : this(DefaultPresignedUploadFacadeOperations(uploads, claims), grantDuration)

    init {
        require(!grantDuration.isNegative && !grantDuration.isZero && grantDuration.toMillis() > 0) {
            "Presigned upload grant duration must be positive."
        }
    }

    fun start(
        idempotencyKeyHeaderValues: List<String>?,
        request: StartPresignedUploadRequest,
    ): PresignedUploadGrantDto {
        val input = request.toCommand()
        val callerKey = IdempotencyKeyParser.parse(idempotencyKeyHeaderValues)
        return uploads.startWithCallerKey(
            callerKey,
            ApplicationStartPresignedUploadCommand(
                fileName = input.fileName,
                contentLength = input.contentLength,
                contentType = input.contentType,
                contentHash = input.contentHash,
                checksumAlgorithm = input.checksumAlgorithm,
                checksumValue = input.checksumValue,
                metadata = emptyMap(),
                grantDuration = grantDuration,
            ),
        ).toPublicDto()
    }

    fun reissue(uploadId: String): PresignedUploadGrantDto = uploads.reissue(
        ReissuePresignedUploadCommand(PresignedUploadApiInputs.uploadId(uploadId)),
    ).toPublicDto()

    fun inspect(uploadId: String): PresignedUploadDto = uploads.inspect(
        InspectPresignedUploadCommand(PresignedUploadApiInputs.uploadId(uploadId)),
    ).toPublicDto()

    fun cancel(uploadId: String): PresignedUploadDto = uploads.cancel(
        CancelPresignedUploadCommand(PresignedUploadApiInputs.uploadId(uploadId)),
    ).toPublicDto()

    fun finalizeUpload(
        uploadId: String,
        idempotencyKeyHeaderValues: List<String>?,
    ): PresignedUploadFinalizationDto {
        val id = PresignedUploadApiInputs.uploadId(uploadId)
        val callerKey = IdempotencyKeyParser.parse(idempotencyKeyHeaderValues)
        return uploads.finalize(CompletePresignedUploadAssetCommand(id, callerKey)).toPublicDto()
    }

    private fun StartPresignedUploadRequest.toCommand(): StartPresignedUploadCommand =
        StartPresignedUploadCommand(
            fileName = requireNotNull(fileName) { "Presigned upload file name is required." },
            contentLength = requireNotNull(contentLength) { "Presigned upload content length is required." },
            contentType = requireNotNull(contentType) { "Presigned upload content type is required." },
            contentHash = requireNotNull(contentHash) { "Presigned upload content hash is required." },
            checksumAlgorithm = requireNotNull(checksumAlgorithm) {
                "Presigned upload checksum algorithm is required."
            },
            checksumValue = requireNotNull(checksumValue) { "Presigned upload checksum value is required." },
        )

    private fun PresignedUploadGrantResult.toPublicDto(): PresignedUploadGrantDto = PresignedUploadGrantDto(
        uploadId = sessionId.value,
        uploadUrl = uploadUri,
        requiredHeaders = requiredHeaders,
        expiresAt = expiresAt,
        created = created,
    )

    private fun PresignedUploadStatusResult.toPublicDto(): PresignedUploadDto = PresignedUploadDto(
        uploadId = sessionId.value,
        fileName = fileName,
        contentLength = contentLength,
        contentType = contentType,
        contentHash = contentHash,
        status = status.toPublicStatus(),
        grantExpiresAt = grantExpiresAt,
        sessionExpiresAt = sessionExpiresAt,
        createdTime = createdTime,
        updatedTime = updatedTime,
        completedTime = completedTime,
        cancelledTime = cancelledTime,
        cleanupTime = cleanupTime,
    )

    private fun CompletedPresignedUploadAssetClaimResult.toPublicDto(): PresignedUploadFinalizationDto =
        PresignedUploadFinalizationDto(
            uploadId = uploadId.value,
            fileObjectId = fileObjectId.value,
            fileAssetId = fileAssetId.value,
            replayed = replayed,
        )

    private fun PresignedUploadSessionStatus.toPublicStatus(): String = when (this) {
        PresignedUploadSessionStatus.READY -> PresignedUploadStatuses.READY
        PresignedUploadSessionStatus.FINALIZING -> PresignedUploadStatuses.FINALIZING
        PresignedUploadSessionStatus.COMPLETED -> PresignedUploadStatuses.COMPLETED
        PresignedUploadSessionStatus.CANCELLED -> PresignedUploadStatuses.CANCELLED
        PresignedUploadSessionStatus.EXPIRED -> PresignedUploadStatuses.EXPIRED
    }

    companion object {
        internal fun forTesting(
            uploads: PresignedUploadFacadeOperations,
            grantDuration: Duration = Duration.ofMinutes(15),
        ): PresignedUploadApiFacade = PresignedUploadApiFacade(uploads, grantDuration)
    }
}

internal interface PresignedUploadFacadeOperations {
    fun startWithCallerKey(
        callerKey: String,
        command: ApplicationStartPresignedUploadCommand,
    ): PresignedUploadGrantResult

    fun reissue(command: ReissuePresignedUploadCommand): PresignedUploadGrantResult

    fun inspect(command: InspectPresignedUploadCommand): PresignedUploadStatusResult

    fun cancel(command: CancelPresignedUploadCommand): PresignedUploadStatusResult

    fun finalize(command: CompletePresignedUploadAssetCommand): CompletedPresignedUploadAssetClaimResult
}

private class DefaultPresignedUploadFacadeOperations(
    private val delegate: PresignedUploadService,
    private val claims: CompletedPresignedUploadAssetClaimService,
) : PresignedUploadFacadeOperations {
    override fun startWithCallerKey(
        callerKey: String,
        command: ApplicationStartPresignedUploadCommand,
    ): PresignedUploadGrantResult = delegate.startWithCallerKey(callerKey, command)

    override fun reissue(command: ReissuePresignedUploadCommand): PresignedUploadGrantResult = delegate.reissue(command)

    override fun inspect(command: InspectPresignedUploadCommand): PresignedUploadStatusResult = delegate.inspect(command)

    override fun cancel(command: CancelPresignedUploadCommand): PresignedUploadStatusResult = delegate.cancel(command)

    override fun finalize(command: CompletePresignedUploadAssetCommand): CompletedPresignedUploadAssetClaimResult =
        claims.finalizeUpload(command)
}

private object PresignedUploadApiInputs {
    fun uploadId(value: String): Identifier {
        require(value.length <= 128 && ROUTABLE_SEGMENT.matches(value)) {
            "Presigned upload id must be one safe opaque path segment."
        }
        return Identifier(value)
    }

    private val ROUTABLE_SEGMENT: Regex = Regex("[A-Za-z0-9_~-](?:[A-Za-z0-9._~-]{0,127})")
}
