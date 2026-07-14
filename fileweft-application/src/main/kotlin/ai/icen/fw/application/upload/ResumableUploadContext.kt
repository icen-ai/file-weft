package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import java.time.Clock
import java.time.Duration

/**
 * Internal dependencies and safety primitives shared by resumable-upload collaborators.
 *
 * This type deliberately contains no start, part, completion, abort, reconciliation,
 * inspection, or cleanup workflow.
 */
internal class ResumableUploadContext(
    internal val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    internal val storageAdapter: StorageAdapter,
    internal val sessions: ResumableUploadSessionRepository,
    internal val fileObjects: FileObjectRepository,
    internal val fileAssets: FileAssetRepository,
    internal val outbox: OutboxEventRepository,
    internal val identifiers: IdentifierGenerator,
    internal val transaction: ApplicationTransaction,
    internal val clock: Clock,
    internal val sessionTtl: Duration,
    private val metrics: FileWeftMetrics?,
) {
    internal val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    init {
        require(!sessionTtl.isNegative && !sessionTtl.isZero && sessionTtl.toMillis() > 0) {
            "Resumable upload session TTL must be at least one millisecond."
        }
    }

    internal fun currentRequestIdentity(): ResumableUploadRequestIdentity {
        val user = authorization.requireCurrentUser()
        val tenantId = tenantProvider.currentTenant().tenantId
        return ResumableUploadRequestIdentity(
            tenantId = tenantId,
            user = user,
            ownerId = validatedResumableUploadOwnerId(user.id.value),
        )
    }

    internal fun requiredOwnedSession(
        tenantId: Identifier,
        ownerId: String,
        sessionId: Identifier,
    ): ResumableUploadSession = transaction.execute {
        requiredOwnedSessionInTransaction(tenantId, ownerId, sessionId)
    }

    internal fun requiredOwnedSessionInTransaction(
        tenantId: Identifier,
        ownerId: String,
        sessionId: Identifier,
    ): ResumableUploadSession = findOwnedSessionInTransaction(tenantId, ownerId, sessionId)
        ?: throw ResumableUploadNotFoundException(sessionId)

    internal fun findOwnedByIdempotency(
        tenantId: Identifier,
        ownerId: String,
        idempotencyKey: String,
    ): ResumableUploadSession? = transaction.execute {
        val owned = when (val repository = sessions) {
            is OwnerScopedResumableUploadSessionRepository ->
                repository.findByIdempotencyKey(tenantId, ownerId, idempotencyKey)
            else -> repository.findByIdempotencyKey(tenantId, idempotencyKey)
        }?.takeIf {
            it.tenantId == tenantId && it.ownerId == ownerId && it.idempotencyKey == idempotencyKey
        } ?: return@execute null
        val global = sessions.findByIdempotencyKey(tenantId, idempotencyKey)
            ?.takeIf { it.tenantId == tenantId && it.idempotencyKey == idempotencyKey }
        global?.takeIf {
            it.ownerId == ownerId && isSamePersistedSession(owned, it) && isUserVisible(it)
        }
    }

    internal fun findIdempotencyKeyOccupant(
        tenantId: Identifier,
        idempotencyKey: String,
    ): ResumableUploadSession? = transaction.execute {
        sessions.findByIdempotencyKey(tenantId, idempotencyKey)?.takeIf {
            it.tenantId == tenantId && it.idempotencyKey == idempotencyKey
        }
    }

    internal fun requireIdempotencyKeyAvailable(tenantId: Identifier, idempotencyKey: String) {
        if (findIdempotencyKeyOccupant(tenantId, idempotencyKey) != null) throw idempotencyKeyUnavailable()
    }

    internal fun isUserVisible(session: ResumableUploadSession): Boolean =
        session.status != ResumableUploadSessionStatus.ABORTING &&
            session.status != ResumableUploadSessionStatus.QUARANTINED &&
            session.lastError != START_PERSISTENCE_ISOLATION_MARKER &&
            session.lastError != START_CREATION_STAGING_MARKER

    internal fun requireQuarantineCapability(): QuarantinableResumableUploadSessionRepository =
        sessions as? QuarantinableResumableUploadSessionRepository
            ?: throw ResumableUploadStateException(QUARANTINE_CAPABILITY_REQUIRED_MESSAGE)

    internal fun requireStagingCapability(): StagedResumableUploadSessionRepository =
        sessions as? StagedResumableUploadSessionRepository
            ?: throw ResumableUploadStateException(STAGING_CAPABILITY_REQUIRED_MESSAGE)

    internal fun requireFormalStartRepositoryCapabilities() {
        if (
            sessions !is StagedResumableUploadSessionRepository ||
            sessions !is CompletionRejectionResettableResumableUploadSessionRepository
        ) {
            throw ResumableUploadUnavailableException(
                ResumableUploadStateException(FORMAL_REPOSITORY_CAPABILITY_MESSAGE),
            )
        }
    }

    internal fun requireFormalResourceId(sessionId: Identifier) {
        check(FORMAL_RESOURCE_ID_PATTERN.matches(sessionId.value)) {
            "The application issued an upload identifier that cannot be represented as a formal resource path."
        }
    }

    internal fun isSamePersistedSession(
        candidate: ResumableUploadSession,
        attempted: ResumableUploadSession,
    ): Boolean = isSamePersistedSessionIgnoringOwner(candidate, attempted) && candidate.ownerId == attempted.ownerId

    internal fun isSameSessionSnapshot(
        first: ResumableUploadSession,
        second: ResumableUploadSession,
    ): Boolean =
        isSamePersistedSession(first, second) &&
            first.status == second.status &&
            first.lastError == second.lastError &&
            first.completedAt == second.completedAt &&
            first.updatedTime == second.updatedTime

    internal fun isSamePersistedSessionIgnoringOwner(
        candidate: ResumableUploadSession,
        attempted: ResumableUploadSession,
    ): Boolean =
        candidate.id == attempted.id &&
            candidate.tenantId == attempted.tenantId &&
            candidate.idempotencyKey == attempted.idempotencyKey &&
            candidate.storageUploadId == attempted.storageUploadId &&
            candidate.storageLocation == attempted.storageLocation &&
            candidate.fileObjectId == attempted.fileObjectId &&
            candidate.fileAssetId == attempted.fileAssetId &&
            candidate.fileName == attempted.fileName &&
            candidate.contentLength == attempted.contentLength &&
            candidate.assetType == attempted.assetType &&
            candidate.contentType == attempted.contentType &&
            candidate.expectedContentHash == attempted.expectedContentHash &&
            candidate.metadata == attempted.metadata &&
            candidate.expiresAt == attempted.expiresAt &&
            candidate.createdTime == attempted.createdTime

    internal fun isDefinitelyDifferentUpload(
        candidate: ResumableUploadSession,
        attempted: ResumableUploadSession,
    ): Boolean =
        candidate.id != attempted.id &&
            candidate.storageUploadId != attempted.storageUploadId &&
            candidate.storageLocation != attempted.storageLocation &&
            candidate.fileObjectId != attempted.fileObjectId &&
            candidate.fileAssetId != attempted.fileAssetId

    internal fun idempotencyKeyUnavailable(cause: Throwable? = null): ResumableUploadStateException =
        ResumableUploadStateException(IDEMPOTENCY_KEY_UNAVAILABLE_MESSAGE).also { exception ->
            if (cause != null) exception.addSuppressed(cause)
        }

    internal fun reconciliationMismatch() = ResumableUploadStateException(RECONCILIATION_MISMATCH_MESSAGE)

    internal fun reconciliationReadContractMismatch() =
        ResumableUploadStateException(RECONCILIATION_READ_CONTRACT_MISMATCH_MESSAGE)

    internal fun startPersistenceContractFailure() =
        ResumableUploadStateException(START_PERSISTENCE_CONTRACT_MESSAGE)

    internal fun startFailureMarkRejected() =
        ResumableUploadStateException(START_PERSISTENCE_MARK_REJECTED_MESSAGE)

    internal fun startActivationRejected() = ResumableUploadStateException(START_ACTIVATION_REJECTED_MESSAGE)

    internal fun completionReconciliationMismatch() =
        ResumableUploadStateException(COMPLETION_RECONCILIATION_MISMATCH_MESSAGE)

    internal fun completionRejectionResetUnavailable() =
        ResumableUploadStateException(COMPLETION_REJECTION_RESET_CAPABILITY_MESSAGE)

    internal fun untrustedCompletedLocation() = ResumableUploadStateException(UNTRUSTED_COMPLETED_LOCATION_MESSAGE)

    internal fun outcomeUnknown(
        failure: Throwable,
        reconciliationFailures: List<Throwable>,
    ): ApplicationTransactionOutcomeUnknownException {
        val unknown = failure as? ApplicationTransactionOutcomeUnknownException
            ?: ApplicationTransactionOutcomeUnknownException(failure)
        reconciliationFailures.forEach { reconciliationFailure ->
            if (reconciliationFailure !== unknown && reconciliationFailure !== unknown.cause) {
                unknown.addSuppressed(reconciliationFailure)
            }
        }
        return unknown
    }

    internal fun abortFailedStart(upload: MultipartUpload, failure: Throwable) {
        try {
            abortStorage(upload, null)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
            throw failure
        }
    }

    internal fun requireClaimedSnapshot(
        claimed: ResumableUploadSession,
        original: ResumableUploadSession,
        expectedOwnerId: String?,
        expectedStatus: ResumableUploadSessionStatus,
    ): ResumableUploadSession = claimed.takeIf {
        isSamePersistedSessionIgnoringOwner(it, original) &&
            it.ownerId == expectedOwnerId &&
            it.status == expectedStatus
    } ?: throw ResumableUploadNotFoundException(original.id)

    internal fun authorize(session: ResumableUploadSession, user: UserIdentity) = requireUploadAction(
        session.tenantId,
        session.fileObjectId,
        user,
    )

    internal fun requireUploadAction(tenantId: Identifier, fileObjectId: Identifier, user: UserIdentity) =
        authorization.requireActionAs(tenantId, fileObjectId, FILE_OBJECT_RESOURCE_TYPE, UPLOAD_ACTION, user)

    internal fun requireMaintenanceAction(tenantId: Identifier) =
        authorization.requireAction(
            tenantId,
            Identifier(MAINTENANCE_RESOURCE_ID),
            FILE_OBJECT_RESOURCE_TYPE,
            UPLOAD_MAINTENANCE_ACTION,
        )

    internal fun requireActive(session: ResumableUploadSession, now: Long) {
        if (session.isExpired(now)) throw ResumableUploadStateException("Upload session ${session.id.value} has expired.")
        if (session.status != ResumableUploadSessionStatus.ACTIVE) {
            throw ResumableUploadStateException("Upload session ${session.id.value} is ${session.status.name}.")
        }
    }

    internal fun validateParts(session: ResumableUploadSession, parts: List<ResumableUploadPart>) {
        if (parts.isEmpty()) throw ResumableUploadStateException("At least one multipart part is required before completion.")
        if (parts.map { it.partNumber }.distinct().size != parts.size) {
            throw ResumableUploadStateException("Multipart upload parts are duplicated.")
        }
        if (parts.sortedBy { it.partNumber }.map { it.partNumber } != (1..parts.size).toList()) {
            throw ResumableUploadStateException("Multipart upload parts must form one consecutive sequence starting at part 1.")
        }
        var total = 0L
        parts.forEach { part -> total = Math.addExact(total, part.contentLength) }
        if (total != session.contentLength) {
            throw ResumableUploadStateException(
                "Multipart uploaded part length $total does not match expected content length ${session.contentLength}.",
            )
        }
    }

    internal fun validateStored(session: ResumableUploadSession, stored: StoredObject) {
        require(stored.location == session.storageLocation) { "Storage adapter completed a different upload location." }
        require(stored.contentLength == session.contentLength) {
            "Completed object length ${stored.contentLength} does not match expected ${session.contentLength}."
        }
        require(session.expectedContentHash == null || session.expectedContentHash == stored.contentHash) {
            "Completed object hash does not match the expected content hash."
        }
    }

    internal fun requireEquivalent(session: ResumableUploadSession, command: StartResumableUploadCommand) {
        if (
            session.fileName != command.fileName ||
            session.contentLength != command.contentLength ||
            session.assetType != command.assetType ||
            session.contentType != command.contentType ||
            session.expectedContentHash != command.contentHash ||
            session.metadata != command.metadata
        ) {
            throw ResumableUploadStateException(
                "Resumable upload idempotency key was reused with different upload content.",
            )
        }
    }

    internal fun storageUpload(session: ResumableUploadSession): MultipartUpload =
        MultipartUpload(session.storageUploadId, session.storageLocation)

    internal fun abortStorage(upload: MultipartUpload, originalFailure: Throwable?) {
        try {
            storageAdapter.abortMultipartUpload(upload)
            storageAdapter.delete(upload.location)
        } catch (cleanupFailure: Throwable) {
            originalFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
        }
    }

    internal fun compensateCompletedObject(location: StorageObjectLocation, originalFailure: Throwable) {
        try {
            storageAdapter.delete(location)
        } catch (cleanupFailure: Throwable) {
            originalFailure.addSuppressed(cleanupFailure)
        }
    }

    internal fun safeMarkFailed(session: ResumableUploadSession, originalFailure: Throwable) {
        if (
            session.status == ResumableUploadSessionStatus.QUARANTINED ||
            session.lastError == START_CREATION_STAGING_MARKER
        ) return
        try {
            transaction.execute {
                val current = sessions.findById(session.tenantId, session.id)
                if (
                    current?.status == ResumableUploadSessionStatus.QUARANTINED ||
                    current?.lastError == START_CREATION_STAGING_MARKER
                ) return@execute
                sessions.markFailed(
                    session.tenantId,
                    session.id,
                    completionFailureMessage(originalFailure),
                    clock.millis(),
                )
            }
        } catch (stateFailure: Throwable) {
            originalFailure.addSuppressed(stateFailure)
        }
    }

    internal fun completionFailureMessage(failure: Throwable): String =
        (failure.message?.takeIf { it.isNotBlank() } ?: "Multipart upload could not complete.")
            .take(MAX_LAST_ERROR_LENGTH)

    internal fun recordMetric(metric: FileWeftMetric, tenantId: String) {
        try {
            metrics?.increment(metric, mapOf("tenantId" to tenantId))
        } catch (_: Exception) {
            // Metrics must not alter resumable-upload state or cleanup semantics.
        }
    }

    private fun findOwnedSessionInTransaction(
        tenantId: Identifier,
        ownerId: String,
        sessionId: Identifier,
    ): ResumableUploadSession? {
        val owned = when (val repository = sessions) {
            is OwnerScopedResumableUploadSessionRepository -> repository.findById(tenantId, ownerId, sessionId)
            else -> repository.findById(tenantId, sessionId)
        }?.takeIf {
            it.tenantId == tenantId && it.id == sessionId && it.ownerId == ownerId
        } ?: return null
        val global = sessions.findById(tenantId, sessionId)
            ?.takeIf { it.tenantId == tenantId && it.id == sessionId }
        return global?.takeIf {
            it.ownerId == ownerId && isSamePersistedSession(owned, it) && isUserVisible(it)
        }
    }

    private companion object {
        const val FILE_OBJECT_RESOURCE_TYPE = "FILE_OBJECT"
        const val UPLOAD_ACTION = "file:upload"
        const val UPLOAD_MAINTENANCE_ACTION = "file:upload:maintenance"
        const val MAINTENANCE_RESOURCE_ID = "resumable-upload-maintenance"
        val FORMAL_RESOURCE_ID_PATTERN: Regex = Regex("[A-Za-z0-9_~-](?:[A-Za-z0-9._~-]{0,127})")
        const val IDEMPOTENCY_KEY_UNAVAILABLE_MESSAGE = "Resumable upload idempotency key is unavailable."
        const val RECONCILIATION_MISMATCH_MESSAGE =
            "The persisted resumable upload does not exactly match the attempted session."
        const val RECONCILIATION_READ_CONTRACT_MISMATCH_MESSAGE =
            "A resumable upload reconciliation lookup returned a record outside its requested key."
        const val START_PERSISTENCE_CONTRACT_MESSAGE =
            "Resumable upload persistence did not preserve the required owner-scoped session."
        const val START_PERSISTENCE_ISOLATION_MARKER = "fileweft:resumable-upload:owner-isolation:v1"
        const val START_CREATION_STAGING_MARKER = "fileweft:resumable-upload:creation-staging:v1"
        const val START_PERSISTENCE_MARK_REJECTED_MESSAGE =
            "The unusable resumable upload session could not be quarantined."
        const val QUARANTINE_CAPABILITY_REQUIRED_MESSAGE =
            "Resumable upload creation requires a repository with durable quarantine support."
        const val STAGING_CAPABILITY_REQUIRED_MESSAGE =
            "Resumable upload creation requires a repository with guarded staging activation support."
        const val START_ACTIVATION_REJECTED_MESSAGE = "The staged resumable upload session could not be activated."
        const val COMPLETION_RECONCILIATION_MISMATCH_MESSAGE =
            "The persisted multipart completion does not exactly match the completed storage object."
        const val COMPLETION_REJECTION_RESET_CAPABILITY_MESSAGE =
            "A definitive multipart rejection requires atomic checkpoint reset support."
        const val FORMAL_REPOSITORY_CAPABILITY_MESSAGE =
            "The resumable upload repository lacks a capability required by the formal resource."
        const val UNTRUSTED_COMPLETED_LOCATION_MESSAGE =
            "The storage adapter returned a completed object outside the requested upload location."
        const val MAX_LAST_ERROR_LENGTH = 2_048
    }
}

internal class ResumableUploadRequestIdentity(
    internal val tenantId: Identifier,
    internal val user: UserIdentity,
    internal val ownerId: String,
)

internal const val FILE_UPLOADED_EVENT_TYPE = "file.uploaded"
internal const val FORMAL_IDEMPOTENCY_HASH_DOMAIN = "fileweft-resumable-upload-idempotency-v1"
internal const val FORMAL_IDEMPOTENCY_STORAGE_PREFIX = "v1:sha256:"
internal const val FORMAL_IDEMPOTENCY_FRAME_SEPARATOR: Byte = 0x3a
internal const val FORMAL_IDEMPOTENCY_FRAME_TERMINATOR: Byte = 0x00
internal val FORMAL_CALLER_KEY_PATTERN: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}")
internal const val START_PERSISTENCE_ISOLATION_MARKER = "fileweft:resumable-upload:owner-isolation:v1"
internal const val START_CREATION_STAGING_MARKER = "fileweft:resumable-upload:creation-staging:v1"
internal const val CLEANUP_QUARANTINE_MESSAGE =
    "An expired aborting upload session could not be durably quarantined."
internal const val COMPLETION_REJECTION_RESET_CAPABILITY_MESSAGE =
    "A definitive multipart rejection requires atomic checkpoint reset support."
internal const val COMPLETION_NOT_YET_VISIBLE_MESSAGE =
    "The multipart completion outcome is still fenced because no final object is visible."
internal const val COMPLETION_RECONCILIATION_DELAY_MILLIS = 30_000L
internal const val RECONCILIATION_BUFFER_SIZE = 64 * 1024
internal const val MAX_STABLE_VIEW_ATTEMPTS = 3
internal const val MAX_CLEANUP_LIMIT = 1_000
