package ai.icen.fw.application.upload

import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.spi.storage.StorageUploadRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Owns session creation, caller-key normalization, replay and failed-create recovery. */
internal class ResumableUploadStarter(
    private val context: ResumableUploadContext,
    private val reconciler: ResumableUploadReconciler,
) {
    fun start(command: StartResumableUploadCommand): ResumableUploadSession {
        ApplicationTransactionBoundary.requireInactive(context.transaction)
        val requestIdentity = context.currentRequestIdentity()
        return start(command, requestIdentity, requireFormalResourceId = false)
    }

    /**
     * Creates or replays a session and returns its durable checkpoint from one trusted request identity.
     *
     * This is a compatibility primitive for callers whose idempotency key is already an internal,
     * tenant-scoped value. Formal transports must use [startAndInspectWithCallerKey], which additionally
     * validates and hashes the caller key and verifies the completion-reset capability before side effects.
     */
    fun startAndInspect(command: StartResumableUploadCommand): ResumableUploadSessionView {
        ApplicationTransactionBoundary.requireInactive(context.transaction)
        val requestIdentity = context.currentRequestIdentity()
        return startAndInspect(command, requestIdentity, requireFormalResourceId = false)
    }

    /**
     * Formal-boundary variant that derives one trusted identity snapshot and replaces the validated
     * caller key with a tenant-scoped one-way value before any storage or persistence operation.
     */
    fun startAndInspectWithCallerKey(command: StartResumableUploadCommand): ResumableUploadSessionView {
        ApplicationTransactionBoundary.requireInactive(context.transaction)
        require(FORMAL_CALLER_KEY_PATTERN.matches(command.idempotencyKey)) {
            "Resumable upload caller idempotency key is invalid."
        }
        val requestIdentity = context.currentRequestIdentity()
        context.requireFormalStartRepositoryCapabilities()
        val internalCommand = command.copy(
            idempotencyKey = digestCallerIdempotencyKey(requestIdentity.tenantId.value, command.idempotencyKey),
        )
        return startAndInspect(internalCommand, requestIdentity, requireFormalResourceId = true)
    }

    private fun startAndInspect(
        command: StartResumableUploadCommand,
        requestIdentity: ResumableUploadRequestIdentity,
        requireFormalResourceId: Boolean,
    ): ResumableUploadSessionView {
        val session = start(command, requestIdentity, requireFormalResourceId)
        return reconciler.stableOwnedView(session.tenantId, requestIdentity.ownerId, session.id)
    }

    private fun start(
        command: StartResumableUploadCommand,
        requestIdentity: ResumableUploadRequestIdentity,
        requireFormalResourceId: Boolean,
    ): ResumableUploadSession {
        val tenantId = requestIdentity.tenantId
        // An owner-scoped replay can safely authorize its own existing resource without allocating
        // new identifiers. A miss is authorized against a fresh create resource before the
        // tenant-global occupancy query, so another owner's resource id is never exposed.
        context.findOwnedByIdempotency(tenantId, requestIdentity.ownerId, command.idempotencyKey)?.let { existing ->
            if (requireFormalResourceId) context.requireFormalResourceId(existing.id)
            context.authorize(existing, requestIdentity.user)
            context.requireEquivalent(existing, command)
            return existing
        }
        val sessionId = context.identifiers.nextId()
        if (requireFormalResourceId) context.requireFormalResourceId(sessionId)
        val fileObjectId = context.identifiers.nextId()
        context.requireUploadAction(tenantId, fileObjectId, requestIdentity.user)
        context.requireIdempotencyKeyAvailable(tenantId, command.idempotencyKey)
        context.requireQuarantineCapability()
        context.requireStagingCapability()
        val now = context.clock.millis()
        val fileAssetId = context.identifiers.nextId()
        val storageUpload = try {
            context.storageAdapter.beginMultipartUpload(
                StorageUploadRequest(
                    tenantId = tenantId,
                    objectName = command.fileName,
                    contentLength = command.contentLength,
                    contentType = command.contentType,
                    contentHash = command.contentHash,
                    metadata = command.metadata,
                ),
            )
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: Throwable) {
            throw ResumableUploadUnavailableException(failure)
        }
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
            status = ResumableUploadSessionStatus.ABORTING,
            expiresAt = Math.addExact(now, context.sessionTtl.toMillis()),
            lastError = START_CREATION_STAGING_MARKER,
            createdTime = now,
            updatedTime = now,
            ownerId = requestIdentity.ownerId,
        )
        val persisted = try {
            context.transaction.execute { reconciler.persistAndActivateStart(session, requestIdentity.ownerId) }
        } catch (failure: Throwable) {
            return reconciler.reconcileFailedStart(session, storageUpload, command, requestIdentity, failure)
        }
        if (persisted.status == ResumableUploadSessionStatus.QUARANTINED) {
            val contractFailure = context.startPersistenceContractFailure()
            context.abortFailedStart(storageUpload, contractFailure)
            throw contractFailure
        }
        return persisted
    }

    private fun digestCallerIdempotencyKey(trustedTenantId: String, callerKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateDigestFrame(digest, FORMAL_IDEMPOTENCY_HASH_DOMAIN)
        updateDigestFrame(digest, trustedTenantId)
        updateDigestFrame(digest, callerKey)
        return FORMAL_IDEMPOTENCY_STORAGE_PREFIX + digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun updateDigestFrame(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(FORMAL_IDEMPOTENCY_FRAME_SEPARATOR)
        digest.update(bytes)
        digest.update(FORMAL_IDEMPOTENCY_FRAME_TERMINATOR)
    }
}
