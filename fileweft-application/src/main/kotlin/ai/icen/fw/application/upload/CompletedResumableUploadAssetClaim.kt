package ai.icen.fw.application.upload

import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.core.id.Identifier

/**
 * Creates a draft document from one completed, owner-bound resumable upload.
 *
 * The upload remains a standalone asset until this command succeeds. The
 * caller key is request idempotency for the claim operation; it is independent
 * from the key that created the upload session.
 */
class CreateDocumentFromCompletedUploadCommand(
    val uploadId: Identifier,
    val documentNumber: String,
    val title: String,
    val idempotencyKey: String,
) {
    init {
        requireBoundaryId(uploadId, "Upload id")
        requireBoundedText(documentNumber, "Document number", DOCUMENT_NUMBER_MAX_LENGTH)
        requireBoundedText(title, "Document title", DOCUMENT_TITLE_MAX_LENGTH)
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank." }
    }

    internal fun fingerprint(): String = RequestFingerprint.sha256(
        CREATE_FINGERPRINT_VERSION,
        uploadId.value,
        documentNumber,
        title,
    )
}

/** Adds one version to an editable document from a completed upload asset. */
class AddDocumentVersionFromCompletedUploadCommand(
    val uploadId: Identifier,
    val documentId: Identifier,
    val versionNumber: String,
    val idempotencyKey: String,
) {
    init {
        requireBoundaryId(uploadId, "Upload id")
        requireBoundaryId(documentId, "Document id")
        requireBoundedText(versionNumber, "Document version number", VERSION_NUMBER_MAX_LENGTH)
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank." }
    }

    internal fun fingerprint(): String = RequestFingerprint.sha256(
        ADD_VERSION_FINGERPRINT_VERSION,
        uploadId.value,
        documentId.value,
        versionNumber,
    )
}

/** Stable identifiers returned by a fresh claim or an authorized replay. */
class CompletedResumableUploadAssetClaimResult(
    val uploadId: Identifier,
    val fileObjectId: Identifier,
    val fileAssetId: Identifier,
    val documentId: Identifier,
    val versionId: Identifier,
    val replayed: Boolean,
)

/** Durable consumption marker written in the same transaction as the document mutation. */
class CompletedResumableUploadAssetClaim(
    val tenantId: Identifier,
    val uploadId: Identifier,
    val fileObjectId: Identifier,
    val fileAssetId: Identifier,
    val idempotencyKeyDigest: String,
    val resourceType: String,
    val resourceId: Identifier,
    val subresourceId: Identifier,
    val claimedBy: String,
    val claimedTime: Long,
) {
    init {
        requireBoundaryId(tenantId, "Claim tenant id")
        requireBoundaryId(uploadId, "Claim upload id")
        requireBoundaryId(fileObjectId, "Claim file object id")
        requireBoundaryId(fileAssetId, "Claim file asset id")
        require(IDEMPOTENCY_DIGEST_PATTERN.matches(idempotencyKeyDigest)) {
            "Claim idempotency key digest must be a versioned SHA-256 digest."
        }
        require(resourceType == DOCUMENT_RESOURCE_TYPE) { "Completed upload assets may only be claimed by documents." }
        requireBoundaryId(resourceId, "Claim document id")
        requireBoundaryId(subresourceId, "Claim document version id")
        requireSafeOpaqueText(claimedBy, "Claim owner id", OWNER_ID_MAX_LENGTH)
        require(claimedTime >= 0) { "Claim time must not be negative." }
    }
}

/** One row-locked upload state and its optional, already committed claim. */
class CompletedResumableUploadAssetClaimState(
    val session: ResumableUploadSession,
    val claim: CompletedResumableUploadAssetClaim?,
) {
    init {
        claim?.let { existing ->
            requireClaimState(existing.tenantId == session.tenantId, "Claim tenant does not match its upload session.")
            requireClaimState(existing.uploadId == session.id, "Claim upload id does not match its upload session.")
            requireClaimState(
                existing.fileObjectId == session.fileObjectId,
                "Claim file object does not match its upload session.",
            )
            requireClaimState(
                existing.fileAssetId == session.fileAssetId,
                "Claim file asset does not match its upload session.",
            )
            requireClaimState(
                session.status == ResumableUploadSessionStatus.COMPLETED,
                "Only a completed upload session may contain an asset claim.",
            )
            requireClaimState(
                session.assetType == COMPLETED_UPLOAD_DOCUMENT_ASSET_TYPE,
                "Only a DOCUMENT upload asset may contain a document claim.",
            )
            requireClaimState(session.lastError == null, "A claimed completed upload session must not retain an error.")
            val completedAt = session.completedAt ?: throw CompletedResumableUploadAssetClaimStateException(
                "A claimed upload session must contain its completion time.",
            )
            requireClaimState(
                session.ownerId != null && existing.claimedBy == session.ownerId,
                "Claim owner does not match its upload session.",
            )
            requireClaimState(
                existing.claimedTime >= completedAt && existing.claimedTime < session.expiresAt,
                "Claim time is outside the completed upload consumption window.",
            )
            requireClaimState(
                existing.claimedTime == session.updatedTime,
                "Claim time does not match the upload session update time.",
            )
        }
    }
}

/**
 * Additive persistence capability for one-time consumption of completed upload
 * assets. Every method is called from the request idempotency transaction.
 */
interface CompletedResumableUploadAssetClaimRepository : ResumableUploadSessionRepository {
    /** Loads the exact owner-scoped upload row with a mutation lock. */
    fun lockCompletedAssetClaim(
        tenantId: Identifier,
        ownerId: String,
        uploadId: Identifier,
    ): CompletedResumableUploadAssetClaimState?

    /** Reads a committed claim for replay verification without acquiring a mutation lock. */
    fun findCompletedAssetClaim(
        tenantId: Identifier,
        ownerId: String,
        uploadId: Identifier,
    ): CompletedResumableUploadAssetClaimState?

    /**
     * Conditionally marks only the supplied unclaimed COMPLETED DOCUMENT asset.
     * Implementations must also require `expires_at > claimedTime` and preserve
     * every immutable session/file binding supplied by [expected].
     */
    fun markCompletedAssetClaimed(
        expected: ResumableUploadSession,
        claim: CompletedResumableUploadAssetClaim,
    ): CompletedResumableUploadAssetClaimState?
}

class CompletedResumableUploadAssetNotFoundException(uploadId: Identifier) : NoSuchElementException(
    "Completed upload asset ${uploadId.value} is unavailable to the current owner.",
)

class CompletedResumableUploadAssetClaimConflictException : IllegalStateException(
    "The completed upload asset has already been consumed.",
)

open class CompletedResumableUploadAssetClaimStateException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class CompletedResumableUploadAssetClaimUnavailableException : CompletedResumableUploadAssetClaimStateException(
    "The resumable upload repository does not support atomic completed-asset claims.",
)

private fun requireClaimState(condition: Boolean, message: String) {
    if (!condition) throw CompletedResumableUploadAssetClaimStateException(message)
}

internal const val DOCUMENT_RESOURCE_TYPE: String = "DOCUMENT"
internal const val DOCUMENT_VERSION_RESOURCE_TYPE: String = "DOCUMENT_VERSION"
internal const val COMPLETED_UPLOAD_RESOURCE_TYPE: String = "RESUMABLE_UPLOAD"
internal const val COMPLETED_UPLOAD_CLAIM_ACTION: String = "file:upload:consume"
internal const val CREATE_FROM_UPLOAD_ACTION: String = "document:create"
internal const val ADD_VERSION_FROM_UPLOAD_ACTION: String = "document:version:add"
internal const val COMPLETED_UPLOAD_DOCUMENT_ASSET_TYPE: String = "DOCUMENT"

private const val CREATE_FINGERPRINT_VERSION = "fileweft:completed-upload:create-document:v1"
private const val ADD_VERSION_FINGERPRINT_VERSION = "fileweft:completed-upload:add-version:v1"
private const val DOCUMENT_NUMBER_MAX_LENGTH = 128
private const val DOCUMENT_TITLE_MAX_LENGTH = 512
private const val VERSION_NUMBER_MAX_LENGTH = 32
private const val OWNER_ID_MAX_LENGTH = 256
private const val BOUNDARY_ID_MAX_LENGTH = 64
private val IDEMPOTENCY_DIGEST_PATTERN = Regex("sha256:[0-9a-f]{64}")

private fun requireBoundaryId(identifier: Identifier, label: String) {
    requireSafeOpaqueText(identifier.value, label, BOUNDARY_ID_MAX_LENGTH)
}

private fun requireSafeOpaqueText(value: String, label: String, maximumLength: Int) {
    requireBoundedText(value, label, maximumLength)
    require(!value.first().isBoundaryWhitespace() && !value.last().isBoundaryWhitespace()) {
        "$label must not contain boundary whitespace."
    }
    require(value.none { character ->
        Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
    }) { "$label contains an unsafe character." }
}

private fun requireBoundedText(value: String, label: String, maximumLength: Int) {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value.length <= maximumLength) { "$label is too long." }
    require(value.none { character -> Character.isISOControl(character) }) {
        "$label must not contain control characters."
    }
    var offset = 0
    while (offset < value.length) {
        val character = value[offset]
        when {
            Character.isHighSurrogate(character) -> {
                require(offset + 1 < value.length && Character.isLowSurrogate(value[offset + 1])) {
                    "$label must contain well-formed Unicode text."
                }
                offset += 2
            }
            Character.isLowSurrogate(character) -> throw IllegalArgumentException(
                "$label must contain well-formed Unicode text.",
            )
            else -> offset++
        }
    }
}

private fun Char.isBoundaryWhitespace(): Boolean = isWhitespace() || Character.isSpaceChar(this)
