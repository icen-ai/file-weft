package ai.icen.fw.application.upload

import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.core.id.Identifier

/** Idempotent owner command that turns verified provider evidence into one local content asset. */
class CompletePresignedUploadAssetCommand(
    val uploadId: Identifier,
    val idempotencyKey: String,
) {
    init {
        require(uploadId.value.isNotBlank() && uploadId.value.length <= 64) { "Presigned upload id is invalid." }
        require(PRESIGNED_UPLOAD_IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            "Idempotency key has an invalid format."
        }
    }

    internal fun fingerprint(): String = RequestFingerprint.sha256(
        FINGERPRINT_VERSION,
        uploadId.value,
        PRESIGNED_UPLOAD_ASSET_PURPOSE,
    )
}

class CompletedPresignedUploadAssetClaimResult(
    val uploadId: Identifier,
    val fileObjectId: Identifier,
    val fileAssetId: Identifier,
    val replayed: Boolean,
)

/** One-time claim marker committed with FileObject and FileAsset. */
class CompletedPresignedUploadAssetClaim(
    val tenantId: Identifier,
    val uploadId: Identifier,
    val fileObjectId: Identifier,
    val fileAssetId: Identifier,
    val idempotencyKeyDigest: String,
    val purpose: String,
    val claimedBy: String,
    val claimedTime: Long,
) {
    init {
        require(tenantId.value.isNotBlank() && tenantId.value.length <= 64) { "Claim tenant id is invalid." }
        require(uploadId.value.isNotBlank() && uploadId.value.length <= 64) { "Claim upload id is invalid." }
        require(fileObjectId.value.isNotBlank() && fileObjectId.value.length <= 64) {
            "Claim file object id is invalid."
        }
        require(fileAssetId.value.isNotBlank() && fileAssetId.value.length <= 64) {
            "Claim file asset id is invalid."
        }
        require(PRESIGNED_UPLOAD_DIGEST_PATTERN.matches(idempotencyKeyDigest)) {
            "Claim idempotency key digest is invalid."
        }
        require(purpose == PRESIGNED_UPLOAD_ASSET_PURPOSE) { "Presigned upload claim purpose is invalid." }
        require(claimedBy.isNotBlank() && claimedBy.length <= 256) { "Claim owner id is invalid." }
        require(claimedTime >= 0) { "Claim time must not be negative." }
    }
}

class CompletedPresignedUploadAssetClaimState(
    val session: PresignedUploadSession,
    val claim: CompletedPresignedUploadAssetClaim?,
) {
    init {
        claim?.let { existing ->
            requireClaimState(existing.tenantId == session.tenantId, "Claim tenant does not match its upload.")
            requireClaimState(existing.uploadId == session.id, "Claim id does not match its upload.")
            requireClaimState(existing.claimedBy == session.ownerId, "Claim owner does not match its upload.")
            requireClaimState(
                session.status == PresignedUploadSessionStatus.COMPLETED && session.finalization != null,
                "Only a provider-verified completed upload may be claimed.",
            )
            val completedTime = session.completedTime
                ?: throw CompletedPresignedUploadAssetClaimStateException("Claimed upload completion time is missing.")
            requireClaimState(
                existing.claimedTime >= completedTime && existing.claimedTime < session.sessionExpiresAt,
                "Claim time is outside the verified upload lifetime.",
            )
        }
    }
}

/** Additive JDBC capability used inside the request-idempotency transaction. */
interface CompletedPresignedUploadAssetClaimRepository : PresignedUploadSessionRepository {
    fun lockCompletedAssetClaim(
        tenantId: Identifier,
        ownerId: String,
        uploadId: Identifier,
    ): CompletedPresignedUploadAssetClaimState?

    fun findCompletedAssetClaim(
        tenantId: Identifier,
        ownerId: String,
        uploadId: Identifier,
    ): CompletedPresignedUploadAssetClaimState?

    /**
     * Claims only the exact unclaimed completed row. Implementations must fence
     * on row version, immutable declaration/finalization evidence and expiry.
     */
    fun markCompletedAssetClaimed(
        expected: PresignedUploadSession,
        claim: CompletedPresignedUploadAssetClaim,
    ): CompletedPresignedUploadAssetClaimState?
}

class CompletedPresignedUploadAssetNotFoundException(uploadId: Identifier) : NoSuchElementException(
    "Completed presigned upload ${uploadId.value} is unavailable to the current owner.",
)

class CompletedPresignedUploadAssetClaimConflictException : IllegalStateException(
    "The completed presigned upload has already been claimed by another request.",
)

open class CompletedPresignedUploadAssetClaimStateException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class CompletedPresignedUploadAssetClaimUnavailableException : CompletedPresignedUploadAssetClaimStateException(
    "The presigned upload repository does not support atomic completed-asset claims.",
)

private fun requireClaimState(condition: Boolean, message: String) {
    if (!condition) throw CompletedPresignedUploadAssetClaimStateException(message)
}

internal const val PRESIGNED_UPLOAD_ASSET_PURPOSE: String = "DOCUMENT"
internal const val PRESIGNED_UPLOAD_ASSET_TYPE: String = "DOCUMENT"
internal const val PRESIGNED_UPLOAD_CLAIM_RESOURCE_TYPE: String = "PRESIGNED_UPLOAD"
internal const val PRESIGNED_UPLOAD_CLAIM_ACTION: String = "file:upload:consume"
internal const val PRESIGNED_UPLOAD_FINALIZE_ACTION: String = "file:upload:complete"
private const val FINGERPRINT_VERSION: String = "flowweft:presigned-upload:asset-claim:v1"
private val PRESIGNED_UPLOAD_DIGEST_PATTERN = Regex("sha256:[0-9a-f]{64}")
private val PRESIGNED_UPLOAD_IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}")
