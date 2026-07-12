package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import java.io.InputStream
import java.time.Clock
import java.time.Duration

/**
 * Tenant-isolated, durable multipart upload orchestration. Storage calls remain
 * outside database transactions; the persisted session makes interruption and
 * retry explicit instead of relying on a process-local upload handle.
 */
class ResumableUploadService @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val storageAdapter: StorageAdapter,
    private val sessions: ResumableUploadSessionRepository,
    private val fileObjects: FileObjectRepository,
    private val fileAssets: FileAssetRepository,
    private val outbox: OutboxEventRepository,
    private val identifiers: IdentifierGenerator,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
    private val sessionTtl: Duration = Duration.ofHours(24),
    private val metrics: FileWeftMetrics? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    init {
        require(!sessionTtl.isNegative && !sessionTtl.isZero && sessionTtl.toMillis() > 0) {
            "Resumable upload session TTL must be at least one millisecond."
        }
    }

    fun start(command: StartResumableUploadCommand): ResumableUploadSession {
        val tenantId = tenantProvider.currentTenant().tenantId
        findByIdempotency(tenantId, command)?.let { existing ->
            authorize(existing)
            requireEquivalent(existing, command)
            return existing
        }
        val now = clock.millis()
        val sessionId = identifiers.nextId()
        val fileObjectId = identifiers.nextId()
        val fileAssetId = identifiers.nextId()
        authorization.requireAction(tenantId, fileObjectId, FILE_OBJECT_RESOURCE_TYPE, UPLOAD_ACTION)
        val storageUpload = storageAdapter.beginMultipartUpload(
            StorageUploadRequest(
                tenantId = tenantId,
                objectName = command.fileName,
                contentLength = command.contentLength,
                contentType = command.contentType,
                contentHash = command.contentHash,
                metadata = command.metadata,
            ),
        )
        val session = ResumableUploadSession(
            id = sessionId,
            tenantId = tenantId,
            idempotencyKey = command.idempotencyKey,
            storageUploadId = storageUpload.uploadId,
            storageLocation = storageUpload.location,
            fileObjectId = fileObjectId,
            fileAssetId = fileAssetId,
            fileName = command.fileName,
            contentLength = command.contentLength,
            assetType = command.assetType,
            contentType = command.contentType,
            expectedContentHash = command.contentHash,
            metadata = command.metadata,
            expiresAt = Math.addExact(now, sessionTtl.toMillis()),
            createdTime = now,
            updatedTime = now,
        )
        try {
            transaction.execute { sessions.save(session) }
            return session
        } catch (failure: Throwable) {
            abortStorage(storageUpload, failure)
            val existing = findByIdempotency(tenantId, command)
            if (existing != null) {
                authorize(existing)
                requireEquivalent(existing, command)
                return existing
            }
            throw failure
        }
    }

    fun inspect(sessionId: Identifier): ResumableUploadSessionView {
        val session = findForCurrentTenant(sessionId)
        authorize(session)
        return transaction.execute {
            ResumableUploadSessionView(session, sessions.findParts(session.tenantId, session.id))
        }
    }

    fun uploadPart(sessionId: Identifier, partNumber: Int, contentLength: Long, content: InputStream): ResumableUploadPart {
        require(partNumber in 1..ResumableUploadPart.MAX_PART_NUMBER) {
            "Multipart part number must be between 1 and ${ResumableUploadPart.MAX_PART_NUMBER}."
        }
        require(contentLength >= 0) { "Multipart part length must not be negative." }
        val session = findForCurrentTenant(sessionId)
        authorize(session)
        requireActive(session, clock.millis())
        val acknowledged = storageAdapter.uploadPart(storageUpload(session), partNumber, content, contentLength)
        require(acknowledged.partNumber == partNumber) { "Storage adapter acknowledged a different multipart part number." }
        return transaction.execute {
            val current = requiredSession(session.tenantId, session.id)
            requireActive(current, clock.millis())
            val existing = sessions.findParts(current.tenantId, current.id).firstOrNull { it.partNumber == partNumber }
            ResumableUploadPart(
                id = existing?.id ?: identifiers.nextId(),
                tenantId = current.tenantId,
                sessionId = current.id,
                partNumber = partNumber,
                eTag = acknowledged.eTag,
                contentLength = contentLength,
                createdTime = existing?.createdTime ?: clock.millis(),
                updatedTime = clock.millis(),
            ).also(sessions::savePart)
        }
    }

    fun complete(sessionId: Identifier): UploadFileResult {
        val existing = findForCurrentTenant(sessionId)
        authorize(existing)
        if (existing.status == ResumableUploadSessionStatus.COMPLETED) return completedResult(existing)
        requireActive(existing, clock.millis())
        validateParts(existing, transaction.execute { sessions.findParts(existing.tenantId, existing.id) })
        val session = transaction.execute {
            sessions.claimForCompletion(existing.tenantId, existing.id, clock.millis())
        } ?: return completionClaimFailure(existing)
        val parts = transaction.execute { sessions.findParts(session.tenantId, session.id) }
        try {
            validateParts(session, parts)
        } catch (failure: Throwable) {
            transaction.execute {
                sessions.reactivateAfterCompletionFailure(session.tenantId, session.id, completionFailureMessage(failure), clock.millis())
            }
            throw failure
        }
        val stored = try {
            storageAdapter.completeMultipartUpload(storageUpload(session), parts.map { ai.icen.fw.spi.storage.MultipartPart(it.partNumber, it.eTag) })
        } catch (failure: Throwable) {
            recoverStorageCompletionFailure(session, failure)
            recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
            throw failure
        }
        try {
            validateStored(session, stored)
            val result = transaction.execute {
                val fileObject = FileObject(
                    id = session.fileObjectId,
                    tenantId = session.tenantId,
                    fileName = session.fileName,
                    contentLength = stored.contentLength,
                    storageType = stored.location.storageType,
                    storagePath = stored.location.path,
                    contentType = stored.contentType,
                    contentHash = stored.contentHash,
                )
                val fileAsset = FileAsset(
                    id = session.fileAssetId,
                    tenantId = session.tenantId,
                    fileObjectId = fileObject.id,
                    assetType = session.assetType,
                    metadata = session.metadata,
                )
                fileObjects.save(fileObject)
                fileAssets.save(fileAsset)
                outbox.append(
                    OutboxEvent(
                        id = identifiers.nextId(),
                        tenantId = session.tenantId,
                        type = FILE_UPLOADED_EVENT_TYPE,
                        payload = mapOf("fileObjectId" to fileObject.id.value, "fileAssetId" to fileAsset.id.value),
                        timestamp = clock.millis(),
                    ),
                )
                require(sessions.markCompleted(session.tenantId, session.id, clock.millis())) {
                    "Upload session completion state was changed concurrently."
                }
                UploadFileResult(fileObject, fileAsset)
            }
            recordMetric(FileWeftMetric.UPLOAD_COUNT, session.tenantId.value)
            return result
        } catch (failure: Throwable) {
            compensateCompletedObject(stored, failure)
            safeMarkFailed(session, failure)
            recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
            throw failure
        }
    }

    fun abort(sessionId: Identifier): ResumableUploadSession {
        val existing = findForCurrentTenant(sessionId)
        authorize(existing)
        if (existing.status.isTerminal()) return existing
        val claimed = transaction.execute { sessions.claimForAbort(existing.tenantId, existing.id, clock.millis()) }
            ?: return abortClaimFailure(existing)
        try {
            abortStorage(storageUpload(claimed), null)
            return transaction.execute {
                require(sessions.markAborted(claimed.tenantId, claimed.id, expired = false, updatedAt = clock.millis())) {
                    "Upload session abort state was changed concurrently."
                }
                requiredSession(claimed.tenantId, claimed.id)
            }
        } catch (failure: Throwable) {
            safeMarkFailed(claimed, failure)
            throw failure
        }
    }

    /** System-only maintenance operation. It never needs a request tenant or a current user. */
    fun cleanupExpired(limit: Int = DEFAULT_CLEANUP_LIMIT): ExpiredResumableUploadCleanupResult {
        require(limit in 1..MAX_CLEANUP_LIMIT) { "Upload cleanup limit must be between 1 and $MAX_CLEANUP_LIMIT." }
        val now = clock.millis()
        val candidates = transaction.execute { sessions.findExpired(now, limit) }
        var expired = 0
        var failed = 0
        candidates.forEach { candidate ->
            val claimed = transaction.execute { sessions.claimForAbort(candidate.tenantId, candidate.id, now) } ?: return@forEach
            try {
                abortStorage(storageUpload(claimed), null)
                val marked = transaction.execute {
                    sessions.markAborted(claimed.tenantId, claimed.id, expired = true, updatedAt = clock.millis())
                }
                if (marked) expired++
            } catch (failure: Throwable) {
                safeMarkFailed(claimed, failure)
                failed++
            }
        }
        return ExpiredResumableUploadCleanupResult(candidates.size, expired, failed)
    }

    /**
     * Returns an intentionally redacted, cross-tenant maintenance view. A completed remote object may exist,
     * so this method reports stale completion work without attempting a destructive cleanup.
     */
    fun inspectStalledCompletionsAsSystem(limit: Int = DEFAULT_CLEANUP_LIMIT): List<StalledResumableUploadSession> {
        require(limit in 1..MAX_CLEANUP_LIMIT) { "Upload maintenance limit must be between 1 and $MAX_CLEANUP_LIMIT." }
        return transaction.execute {
            sessions.findExpiredCompleting(clock.millis(), limit).map(::stalledView)
        }
    }

    /** Tenant-safe maintenance view for an authorized tenant administrator. */
    fun inspectStalledCompletions(limit: Int = DEFAULT_CLEANUP_LIMIT): List<StalledResumableUploadSession> {
        require(limit in 1..MAX_CLEANUP_LIMIT) { "Upload maintenance limit must be between 1 and $MAX_CLEANUP_LIMIT." }
        val tenantId = tenantProvider.currentTenant().tenantId
        authorization.requireAction(tenantId, Identifier(MAINTENANCE_RESOURCE_ID), FILE_OBJECT_RESOURCE_TYPE, UPLOAD_MAINTENANCE_ACTION)
        return transaction.execute {
            sessions.findExpiredCompleting(tenantId, clock.millis(), limit).map(::stalledView)
        }
    }

    private fun completionClaimFailure(original: ResumableUploadSession): UploadFileResult {
        val current = requiredSession(original.tenantId, original.id)
        if (current.status == ResumableUploadSessionStatus.COMPLETED) return completedResult(current)
        throw ResumableUploadStateException("Upload session ${current.id.value} cannot be completed from ${current.status.name}.")
    }

    private fun stalledView(session: ResumableUploadSession): StalledResumableUploadSession = StalledResumableUploadSession(
        id = session.id,
        tenantId = session.tenantId,
        fileName = session.fileName,
        contentLength = session.contentLength,
        expiresAt = session.expiresAt,
        updatedTime = session.updatedTime,
        lastError = session.lastError,
    )

    private fun abortClaimFailure(original: ResumableUploadSession): ResumableUploadSession {
        val current = requiredSession(original.tenantId, original.id)
        if (current.status.isTerminal()) return current
        throw ResumableUploadStateException(
            "Upload session ${current.id.value} cannot be aborted from ${current.status.name}; its final object state may still be changing.",
        )
    }

    private fun completedResult(session: ResumableUploadSession): UploadFileResult = transaction.execute {
        val fileObject = fileObjects.findById(session.tenantId, session.fileObjectId)
            ?: throw ResumableUploadStateException("Completed upload session file object is missing.")
        val fileAsset = fileAssets.findById(session.tenantId, session.fileAssetId)
            ?: throw ResumableUploadStateException("Completed upload session file asset is missing.")
        UploadFileResult(fileObject, fileAsset)
    }

    private fun recoverStorageCompletionFailure(session: ResumableUploadSession, failure: Throwable) {
        val finalObjectExists = try {
            storageAdapter.exists(session.storageLocation)
        } catch (existenceFailure: Throwable) {
            failure.addSuppressed(existenceFailure)
            true
        }
        try {
            transaction.execute {
                if (finalObjectExists) {
                    sessions.markFailed(session.tenantId, session.id, completionFailureMessage(failure), clock.millis())
                } else {
                    sessions.reactivateAfterCompletionFailure(session.tenantId, session.id, completionFailureMessage(failure), clock.millis())
                }
            }
        } catch (stateFailure: Throwable) {
            failure.addSuppressed(stateFailure)
        }
    }

    private fun findForCurrentTenant(sessionId: Identifier): ResumableUploadSession {
        val tenantId = tenantProvider.currentTenant().tenantId
        return requiredSession(tenantId, sessionId)
    }

    private fun requiredSession(tenantId: Identifier, sessionId: Identifier): ResumableUploadSession = transaction.execute {
        sessions.findById(tenantId, sessionId) ?: throw ResumableUploadNotFoundException(sessionId)
    }

    private fun findByIdempotency(tenantId: Identifier, command: StartResumableUploadCommand): ResumableUploadSession? = transaction.execute {
        sessions.findByIdempotencyKey(tenantId, command.idempotencyKey)
    }

    private fun authorize(session: ResumableUploadSession) =
        authorization.requireAction(session.tenantId, session.fileObjectId, FILE_OBJECT_RESOURCE_TYPE, UPLOAD_ACTION)

    private fun requireActive(session: ResumableUploadSession, now: Long) {
        if (session.isExpired(now)) throw ResumableUploadStateException("Upload session ${session.id.value} has expired.")
        if (session.status != ResumableUploadSessionStatus.ACTIVE) {
            throw ResumableUploadStateException("Upload session ${session.id.value} is ${session.status.name}.")
        }
    }

    private fun validateParts(session: ResumableUploadSession, parts: List<ResumableUploadPart>) {
        require(parts.isNotEmpty()) { "At least one multipart part is required before completion." }
        require(parts.map { it.partNumber }.distinct().size == parts.size) { "Multipart upload parts are duplicated." }
        var total = 0L
        parts.forEach { part -> total = Math.addExact(total, part.contentLength) }
        require(total == session.contentLength) {
            "Multipart uploaded part length $total does not match expected content length ${session.contentLength}."
        }
    }

    private fun validateStored(session: ResumableUploadSession, stored: StoredObject) {
        require(stored.location == session.storageLocation) { "Storage adapter completed a different upload location." }
        require(stored.contentLength == session.contentLength) {
            "Completed object length ${stored.contentLength} does not match expected ${session.contentLength}."
        }
        require(session.expectedContentHash == null || session.expectedContentHash == stored.contentHash) {
            "Completed object hash does not match the expected content hash."
        }
    }

    private fun requireEquivalent(session: ResumableUploadSession, command: StartResumableUploadCommand) {
        require(
            session.fileName == command.fileName &&
                session.contentLength == command.contentLength &&
                session.assetType == command.assetType &&
                session.contentType == command.contentType &&
                session.expectedContentHash == command.contentHash &&
                session.metadata == command.metadata,
        ) { "Resumable upload idempotency key was reused with different upload content." }
    }

    private fun storageUpload(session: ResumableUploadSession): MultipartUpload =
        MultipartUpload(session.storageUploadId, session.storageLocation)

    private fun abortStorage(upload: MultipartUpload, originalFailure: Throwable?) {
        try {
            storageAdapter.abortMultipartUpload(upload)
            storageAdapter.delete(upload.location)
        } catch (cleanupFailure: Throwable) {
            originalFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
        }
    }

    private fun compensateCompletedObject(stored: StoredObject, originalFailure: Throwable) {
        try {
            storageAdapter.delete(stored.location)
        } catch (cleanupFailure: Throwable) {
            originalFailure.addSuppressed(cleanupFailure)
        }
    }

    private fun safeMarkFailed(session: ResumableUploadSession, originalFailure: Throwable) {
        try {
            transaction.execute {
                sessions.markFailed(session.tenantId, session.id, completionFailureMessage(originalFailure), clock.millis())
            }
        } catch (stateFailure: Throwable) {
            originalFailure.addSuppressed(stateFailure)
        }
    }

    private fun completionFailureMessage(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: "Multipart upload could not complete."

    private fun recordMetric(metric: FileWeftMetric, tenantId: String) {
        try {
            metrics?.increment(metric, mapOf("tenantId" to tenantId))
        } catch (_: Exception) {
            // Metrics must not alter resumable upload state or cleanup semantics.
        }
    }

    private companion object {
        const val FILE_OBJECT_RESOURCE_TYPE = "FILE_OBJECT"
        const val UPLOAD_ACTION = "file:upload"
        const val UPLOAD_MAINTENANCE_ACTION = "file:upload:maintenance"
        const val MAINTENANCE_RESOURCE_ID = "resumable-upload-maintenance"
        const val FILE_UPLOADED_EVENT_TYPE = "file.uploaded"
        const val DEFAULT_CLEANUP_LIMIT = 100
        const val MAX_CLEANUP_LIMIT = 1_000
    }
}
