package ai.icen.fw.application.idempotency

import ai.icen.fw.core.id.Identifier

/**
 * A validated request binding. The caller-provided key is deliberately not
 * retained: only its tenant-scoped digest can cross the persistence boundary.
 */
class RequestIdempotency private constructor(
    val tenantId: Identifier,
    val keyDigest: String,
    val operatorId: Identifier,
    val action: String,
    val resourceType: String,
    val resourceId: Identifier,
    val subresourceId: Identifier?,
    val requestFingerprint: String,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(
            tenantId: Identifier,
            operatorId: Identifier,
            idempotencyKey: String,
            action: String,
            resourceType: String,
            resourceId: Identifier,
            requestFingerprint: String,
            subresourceId: Identifier? = null,
        ): RequestIdempotency {
            val validatedTenantId = boundedIdentifier(tenantId, "Tenant id", TENANT_ID_MAX_LENGTH)
            val validatedOperatorId = boundedIdentifier(operatorId, "Operator id", BOUNDARY_ID_MAX_LENGTH)
            val validatedResourceId = boundedIdentifier(resourceId, "Resource id", BOUNDARY_ID_MAX_LENGTH)
            val validatedSubresourceId = subresourceId?.let {
                boundedIdentifier(it, "Subresource id", BOUNDARY_ID_MAX_LENGTH)
            }
            if (!IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
                throw InvalidIdempotencyRequestException("Idempotency key has an invalid format.")
            }
            val validatedAction = boundaryToken(action, "Idempotency action")
            val validatedResourceType = boundaryToken(resourceType, "Idempotency resource type")
            val validatedFingerprint = digestValue(requestFingerprint, "Request fingerprint")
            return RequestIdempotency(
                tenantId = validatedTenantId,
                keyDigest = Sha256Digest.digest(
                    namespace = "fileweft-idempotency-key-v1",
                    components = arrayOf(validatedTenantId.value, idempotencyKey),
                ),
                operatorId = validatedOperatorId,
                action = validatedAction,
                resourceType = validatedResourceType,
                resourceId = validatedResourceId,
                subresourceId = validatedSubresourceId,
                requestFingerprint = validatedFingerprint,
            )
        }
    }
}

enum class RequestIdempotencyStatus {
    IN_PROGRESS,
    COMPLETED,
}

/** Only stable resource identifiers may be replayed; mutable domain state is never persisted here. */
class IdempotencyResult(
    resourceType: String,
    resourceId: Identifier,
    relatedResourceType: String?,
    relatedResourceId: Identifier?,
) {
    val resourceType: String = boundaryToken(resourceType, "Idempotency result resource type")
    val resourceId: Identifier = boundedIdentifier(resourceId, "Idempotency result resource id", BOUNDARY_ID_MAX_LENGTH)
    val relatedResourceType: String?
    val relatedResourceId: Identifier?

    init {
        if ((relatedResourceType == null) != (relatedResourceId == null)) {
            throw IllegalArgumentException("Related idempotency result type and id must be supplied together.")
        }
        this.relatedResourceType = relatedResourceType?.let {
            boundaryToken(it, "Related idempotency result resource type")
        }
        this.relatedResourceId = relatedResourceId?.let {
            boundedIdentifier(it, "Related idempotency result resource id", BOUNDARY_ID_MAX_LENGTH)
        }
    }

    constructor(resourceType: String, resourceId: Identifier) : this(
        resourceType = resourceType,
        resourceId = resourceId,
        relatedResourceType = null,
        relatedResourceId = null,
    )
}

class RequestIdempotencyRecord(
    id: Identifier,
    tenantId: Identifier,
    keyDigest: String,
    operatorId: Identifier,
    action: String,
    resourceType: String,
    resourceId: Identifier,
    subresourceId: Identifier?,
    requestFingerprint: String,
    val status: RequestIdempotencyStatus,
    val result: IdempotencyResult?,
    val completedTime: Long?,
    val createdTime: Long,
    val updatedTime: Long,
) {
    val id: Identifier = boundedIdentifier(id, "Idempotency record id", RECORD_ID_MAX_LENGTH)
    val tenantId: Identifier = boundedIdentifier(tenantId, "Tenant id", TENANT_ID_MAX_LENGTH)
    val keyDigest: String = digestValue(keyDigest, "Idempotency key digest")
    val operatorId: Identifier = boundedIdentifier(operatorId, "Operator id", BOUNDARY_ID_MAX_LENGTH)
    val action: String = boundaryToken(action, "Idempotency action")
    val resourceType: String = boundaryToken(resourceType, "Idempotency resource type")
    val resourceId: Identifier = boundedIdentifier(resourceId, "Resource id", BOUNDARY_ID_MAX_LENGTH)
    val subresourceId: Identifier? = subresourceId?.let {
        boundedIdentifier(it, "Subresource id", BOUNDARY_ID_MAX_LENGTH)
    }
    val requestFingerprint: String = digestValue(requestFingerprint, "Request fingerprint")

    init {
        require(createdTime >= 0) { "Idempotency record creation time must not be negative." }
        require(updatedTime >= createdTime) { "Idempotency record update time must not precede its creation time." }
        when (status) {
            RequestIdempotencyStatus.IN_PROGRESS -> {
                require(result == null && completedTime == null) {
                    "An in-progress idempotency record must not contain a completed result."
                }
                require(updatedTime == createdTime) {
                    "An in-progress idempotency record must not have a later update time."
                }
            }
            RequestIdempotencyStatus.COMPLETED -> {
                require(result != null && completedTime != null) {
                    "A completed idempotency record must contain its result and completion time."
                }
                require(completedTime >= createdTime && completedTime == updatedTime) {
                    "Idempotency completion time must be the final record update time."
                }
            }
        }
    }
}

class RequestIdempotencyClaim(
    val record: RequestIdempotencyRecord,
    val acquired: Boolean,
)

class InvalidIdempotencyRequestException(message: String) : IllegalArgumentException(message)

open class IdempotencyConflictException(message: String) : IllegalStateException(message)

class IdempotencyKeyConflictException : IdempotencyConflictException(
    "Idempotency key is already bound to a different request.",
)

open class IdempotencyStoreException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class IdempotencyInProgressException : IdempotencyStoreException(
    "A committed idempotency request is unexpectedly still in progress.",
)

internal fun sameResult(first: IdempotencyResult, second: IdempotencyResult): Boolean =
    first.resourceType == second.resourceType &&
        first.resourceId == second.resourceId &&
        first.relatedResourceType == second.relatedResourceType &&
        first.relatedResourceId == second.relatedResourceId

private fun boundaryToken(value: String, label: String): String {
    if (!BOUNDARY_TOKEN_PATTERN.matches(value)) {
        throw IllegalArgumentException("$label has an invalid format.")
    }
    return value
}

private fun digestValue(value: String, label: String): String {
    if (!SHA256_PATTERN.matches(value)) {
        throw IllegalArgumentException("$label must be a versioned SHA-256 digest.")
    }
    return value
}

private fun boundedIdentifier(identifier: Identifier, label: String, maxLength: Int): Identifier {
    val value = identifier.value
    requireWellFormedUtf16(value, label)
    if (
        value.length > maxLength ||
        value.first().isBoundaryWhitespace() ||
        value.last().isBoundaryWhitespace() ||
        value.any { character ->
            Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
        }
    ) {
        throw IllegalArgumentException("$label has an invalid format.")
    }
    return identifier
}

private fun Char.isBoundaryWhitespace(): Boolean = isWhitespace() || Character.isSpaceChar(this)

private const val RECORD_ID_MAX_LENGTH = 64
private const val TENANT_ID_MAX_LENGTH = 64
private const val BOUNDARY_ID_MAX_LENGTH = 256
private val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}")
private val BOUNDARY_TOKEN_PATTERN = Regex("[A-Za-z][A-Za-z0-9._:-]{0,127}")
private val SHA256_PATTERN = Regex("sha256:[0-9a-f]{64}")
