package ai.icen.fw.application.upload

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserIdentity
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
        ApplicationTransactionBoundary.requireInactive(transaction)
        val requestIdentity = currentRequestIdentity()
        val tenantId = requestIdentity.tenantId
        // An owner-scoped replay can safely authorize its own existing resource without allocating
        // new identifiers. A miss is authorized against a fresh create resource before the
        // tenant-global occupancy query, so another owner's resource id is never exposed.
        findOwnedByIdempotency(tenantId, requestIdentity.ownerId, command.idempotencyKey)?.let { existing ->
            authorize(existing, requestIdentity.user)
            requireEquivalent(existing, command)
            return existing
        }
        val sessionId = identifiers.nextId()
        val fileObjectId = identifiers.nextId()
        authorization.requireActionAs(
            tenantId,
            fileObjectId,
            FILE_OBJECT_RESOURCE_TYPE,
            UPLOAD_ACTION,
            requestIdentity.user,
        )
        requireIdempotencyKeyAvailable(tenantId, command.idempotencyKey)
        requireQuarantineCapability()
        requireStagingCapability()
        val now = clock.millis()
        val fileAssetId = identifiers.nextId()
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
            status = ResumableUploadSessionStatus.ABORTING,
            expiresAt = Math.addExact(now, sessionTtl.toMillis()),
            lastError = START_CREATION_STAGING_MARKER,
            createdTime = now,
            updatedTime = now,
            ownerId = requestIdentity.ownerId,
        )
        val persisted = try {
            transaction.execute { persistAndActivateStart(session, requestIdentity.ownerId) }
        } catch (failure: Throwable) {
            return reconcileFailedStart(session, storageUpload, command, requestIdentity, failure)
        }
        if (persisted.status == ResumableUploadSessionStatus.QUARANTINED) {
            val contractFailure = startPersistenceContractFailure()
            abortFailedStart(storageUpload, contractFailure)
            throw contractFailure
        }
        return persisted
    }

    fun inspect(sessionId: Identifier): ResumableUploadSessionView {
        val requestIdentity = currentRequestIdentity()
        val session = requiredOwnedSession(requestIdentity.tenantId, requestIdentity.ownerId, sessionId)
        authorize(session, requestIdentity.user)
        return transaction.execute {
            ResumableUploadSessionView(session, sessions.findParts(session.tenantId, session.id))
        }
    }

    fun uploadPart(sessionId: Identifier, partNumber: Int, contentLength: Long, content: InputStream): ResumableUploadPart {
        ApplicationTransactionBoundary.requireInactive(transaction)
        require(partNumber in 1..ResumableUploadPart.MAX_PART_NUMBER) {
            "Multipart part number must be between 1 and ${ResumableUploadPart.MAX_PART_NUMBER}."
        }
        require(contentLength >= 0) { "Multipart part length must not be negative." }
        val requestIdentity = currentRequestIdentity()
        val session = requiredOwnedSession(requestIdentity.tenantId, requestIdentity.ownerId, sessionId)
        authorize(session, requestIdentity.user)
        requireActive(session, clock.millis())
        val acknowledged = storageAdapter.uploadPart(storageUpload(session), partNumber, content, contentLength)
        require(acknowledged.partNumber == partNumber) { "Storage adapter acknowledged a different multipart part number." }
        return transaction.execute {
            val current = requiredOwnedSession(session.tenantId, requestIdentity.ownerId, session.id)
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
        ApplicationTransactionBoundary.requireInactive(transaction)
        val requestIdentity = currentRequestIdentity()
        val existing = requiredOwnedSession(requestIdentity.tenantId, requestIdentity.ownerId, sessionId)
        authorize(existing, requestIdentity.user)
        if (existing.status == ResumableUploadSessionStatus.COMPLETED) return completedResult(existing)
        requireActive(existing, clock.millis())
        validateParts(existing, transaction.execute { sessions.findParts(existing.tenantId, existing.id) })
        val session = transaction.execute {
            sessions.claimForCompletion(existing.tenantId, existing.id, clock.millis())?.also { claimed ->
                requireClaimedSnapshot(
                    claimed = claimed,
                    original = existing,
                    expectedOwnerId = requestIdentity.ownerId,
                    expectedStatus = ResumableUploadSessionStatus.COMPLETING,
                )
            }
        }
            ?: return completionClaimFailure(existing, requestIdentity.ownerId)
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
            val result = transaction.execute {
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
            return reconcileFailedCompletion(session, stored, failure)
        }
    }

    fun abort(sessionId: Identifier): ResumableUploadSession {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val requestIdentity = currentRequestIdentity()
        val existing = requiredOwnedSession(requestIdentity.tenantId, requestIdentity.ownerId, sessionId)
        authorize(existing, requestIdentity.user)
        if (existing.status.isTerminal()) return existing
        val claimed = transaction.execute {
            sessions.claimForAbort(existing.tenantId, existing.id, clock.millis())?.also { candidate ->
                requireClaimedSnapshot(
                    claimed = candidate,
                    original = existing,
                    expectedOwnerId = requestIdentity.ownerId,
                    expectedStatus = ResumableUploadSessionStatus.ABORTING,
                )
            }
        }
            ?: return abortClaimFailure(existing, requestIdentity.ownerId)
        try {
            abortStorage(storageUpload(claimed), null)
            return transaction.execute {
                require(sessions.markAborted(claimed.tenantId, claimed.id, expired = false, updatedAt = clock.millis())) {
                    "Upload session abort state was changed concurrently."
                }
                requiredOwnedSession(claimed.tenantId, requestIdentity.ownerId, claimed.id)
            }
        } catch (failure: Throwable) {
            safeMarkFailed(claimed, failure)
            throw failure
        }
    }

    /** System-only maintenance operation. It never needs a request tenant or a current user. */
    @JvmOverloads
    fun cleanupExpired(limit: Int = DEFAULT_CLEANUP_LIMIT): ExpiredResumableUploadCleanupResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        require(limit in 1..MAX_CLEANUP_LIMIT) { "Upload cleanup limit must be between 1 and $MAX_CLEANUP_LIMIT." }
        val now = clock.millis()
        val candidates = transaction.execute { sessions.findExpired(now, limit) }
        var expired = 0
        var failed = 0
        candidates.forEach { candidate ->
            if (!isEligibleCleanupCandidate(candidate, now)) {
                failed++
                return@forEach
            }
            val mustRemainQuarantined =
                candidate.status == ResumableUploadSessionStatus.ABORTING ||
                    candidate.lastError == START_PERSISTENCE_ISOLATION_MARKER
            val claimed = try {
                if (mustRemainQuarantined) {
                    quarantineClaimedSession(
                        original = candidate,
                        expectedOwnerId = candidate.ownerId,
                        reason = ResumableUploadStateException(CLEANUP_QUARANTINE_MESSAGE),
                        requiredCleanupNow = now,
                    )
                } else {
                    transaction.execute {
                        check(isEligibleCleanupCandidate(candidate, now)) {
                            "Upload cleanup repository returned an ineligible session."
                        }
                        sessions.claimForAbort(candidate.tenantId, candidate.id, now)?.also { claimedSession ->
                            requireClaimedSnapshot(
                                claimed = claimedSession,
                                original = candidate,
                                expectedOwnerId = candidate.ownerId,
                                expectedStatus = ResumableUploadSessionStatus.ABORTING,
                            )
                        }
                    } ?: return@forEach
                }
            } catch (_: Throwable) {
                failed++
                return@forEach
            }
            try {
                abortStorage(storageUpload(claimed), null)
                if (mustRemainQuarantined) {
                    expired++
                } else {
                    val marked = transaction.execute {
                        sessions.markAborted(claimed.tenantId, claimed.id, expired = true, updatedAt = clock.millis())
                    }
                    if (marked) expired++
                }
            } catch (failure: Throwable) {
                if (!mustRemainQuarantined) safeMarkFailed(claimed, failure)
                failed++
            }
        }
        return ExpiredResumableUploadCleanupResult(candidates.size, expired, failed)
    }

    private fun isEligibleCleanupCandidate(session: ResumableUploadSession, now: Long): Boolean =
        session.expiresAt <= now &&
            (
                session.status == ResumableUploadSessionStatus.ACTIVE ||
                    session.status == ResumableUploadSessionStatus.FAILED ||
                    session.status == ResumableUploadSessionStatus.ABORTING
                )

    /**
     * Returns an intentionally redacted, cross-tenant maintenance view. A completed remote object may exist,
     * so this method reports stale completion work without attempting a destructive cleanup.
     */
    @JvmOverloads
    fun inspectStalledCompletionsAsSystem(limit: Int = DEFAULT_CLEANUP_LIMIT): List<StalledResumableUploadSession> {
        require(limit in 1..MAX_CLEANUP_LIMIT) { "Upload maintenance limit must be between 1 and $MAX_CLEANUP_LIMIT." }
        return transaction.execute {
            sessions.findExpiredCompleting(clock.millis(), limit).map(::stalledView)
        }
    }

    /** Tenant-safe maintenance view for an authorized tenant administrator. */
    @JvmOverloads
    fun inspectStalledCompletions(limit: Int = DEFAULT_CLEANUP_LIMIT): List<StalledResumableUploadSession> {
        require(limit in 1..MAX_CLEANUP_LIMIT) { "Upload maintenance limit must be between 1 and $MAX_CLEANUP_LIMIT." }
        val tenantId = tenantProvider.currentTenant().tenantId
        authorization.requireAction(tenantId, Identifier(MAINTENANCE_RESOURCE_ID), FILE_OBJECT_RESOURCE_TYPE, UPLOAD_MAINTENANCE_ACTION)
        return transaction.execute {
            sessions.findExpiredCompleting(tenantId, clock.millis(), limit).map(::stalledView)
        }
    }

    private fun completionClaimFailure(original: ResumableUploadSession, ownerId: String): UploadFileResult {
        val current = requiredOwnedSession(original.tenantId, ownerId, original.id)
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

    private fun abortClaimFailure(original: ResumableUploadSession, ownerId: String): ResumableUploadSession {
        val current = requiredOwnedSession(original.tenantId, ownerId, original.id)
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

    /**
     * Reconciles the durable side of a multipart completion before considering destructive compensation.
     * A final object is never deleted while the database outcome or any persisted reference is uncertain.
     */
    private fun reconcileFailedCompletion(
        session: ResumableUploadSession,
        stored: StoredObject,
        failure: Throwable,
    ): UploadFileResult {
        if (stored.location != session.storageLocation) {
            recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
            throw outcomeUnknown(failure, listOf(untrustedCompletedLocation()))
        }
        val expectedFileObject = expectedFileObject(session, stored)
        val expectedFileAsset = expectedFileAsset(session)
        val snapshot = try {
            transaction.execute {
                CompletionPersistenceSnapshot(
                    session = sessions.findById(session.tenantId, session.id),
                    fileObject = fileObjects.findById(session.tenantId, session.fileObjectId),
                    fileAsset = fileAssets.findById(session.tenantId, session.fileAssetId),
                )
            }
        } catch (reconciliationFailure: Throwable) {
            recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
            throw outcomeUnknown(failure, listOf(reconciliationFailure))
        }

        val persistedSession = snapshot.session
        val persistedFileObject = snapshot.fileObject
        val persistedFileAsset = snapshot.fileAsset
        if (
            persistedSession != null &&
            isSamePersistedSession(persistedSession, session) &&
            persistedSession.status == ResumableUploadSessionStatus.COMPLETED &&
            persistedFileObject != null &&
            isSameFileObject(persistedFileObject, expectedFileObject) &&
            persistedFileAsset != null &&
            isSameFileAsset(persistedFileAsset, expectedFileAsset)
        ) {
            recordMetric(FileWeftMetric.UPLOAD_COUNT, session.tenantId.value)
            return UploadFileResult(persistedFileObject, persistedFileAsset)
        }

        val knownUncommittedWithoutReferences =
            failure !is ApplicationTransactionOutcomeUnknownException &&
                persistedSession != null &&
                isSamePersistedSession(persistedSession, session) &&
                persistedSession.status == ResumableUploadSessionStatus.COMPLETING &&
                persistedFileObject == null &&
                persistedFileAsset == null
        if (!knownUncommittedWithoutReferences) {
            recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
            throw outcomeUnknown(failure, listOf(completionReconciliationMismatch()))
        }

        compensateCompletedObject(session.storageLocation, failure)
        safeMarkFailed(session, failure)
        recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
        throw failure
    }

    private fun expectedFileObject(session: ResumableUploadSession, stored: StoredObject): FileObject = FileObject(
        id = session.fileObjectId,
        tenantId = session.tenantId,
        fileName = session.fileName,
        contentLength = stored.contentLength,
        storageType = stored.location.storageType,
        storagePath = stored.location.path,
        contentType = stored.contentType,
        contentHash = stored.contentHash,
    )

    private fun expectedFileAsset(session: ResumableUploadSession): FileAsset = FileAsset(
        id = session.fileAssetId,
        tenantId = session.tenantId,
        fileObjectId = session.fileObjectId,
        assetType = session.assetType,
        metadata = session.metadata,
    )

    private fun isSameFileObject(candidate: FileObject, expected: FileObject): Boolean =
        candidate.id == expected.id &&
            candidate.tenantId == expected.tenantId &&
            candidate.fileName == expected.fileName &&
            candidate.contentLength == expected.contentLength &&
            candidate.storageType == expected.storageType &&
            candidate.storagePath == expected.storagePath &&
            candidate.contentType == expected.contentType &&
            candidate.contentHash == expected.contentHash

    private fun isSameFileAsset(candidate: FileAsset, expected: FileAsset): Boolean =
        candidate.id == expected.id &&
            candidate.tenantId == expected.tenantId &&
            candidate.fileObjectId == expected.fileObjectId &&
            candidate.assetType == expected.assetType &&
            candidate.metadata == expected.metadata

    private fun recoverStorageCompletionFailure(session: ResumableUploadSession, failure: Throwable) {
        val finalObjectDefinitelyAbsent = try {
            !storageAdapter.exists(session.storageLocation)
        } catch (existenceFailure: Throwable) {
            failure.addSuppressed(existenceFailure)
            false
        }
        if (finalObjectDefinitelyAbsent) {
            try {
                transaction.execute {
                    sessions.reactivateAfterCompletionFailure(session.tenantId, session.id, completionFailureMessage(failure), clock.millis())
                }
            } catch (stateFailure: Throwable) {
                failure.addSuppressed(stateFailure)
            }
        }
    }

    private fun currentRequestIdentity(): ResumableUploadRequestIdentity {
        val user = authorization.requireCurrentUser()
        val tenantId = tenantProvider.currentTenant().tenantId
        return ResumableUploadRequestIdentity(
            tenantId = tenantId,
            user = user,
            ownerId = validatedResumableUploadOwnerId(user.id.value),
        )
    }

    private fun requiredOwnedSession(
        tenantId: Identifier,
        ownerId: String,
        sessionId: Identifier,
    ): ResumableUploadSession = findOwnedSession(tenantId, ownerId, sessionId)
        ?: throw ResumableUploadNotFoundException(sessionId)

    private fun findOwnedSession(
        tenantId: Identifier,
        ownerId: String,
        sessionId: Identifier,
    ): ResumableUploadSession? = transaction.execute {
        val owned = when (val repository = sessions) {
            is OwnerScopedResumableUploadSessionRepository -> repository.findById(tenantId, ownerId, sessionId)
            else -> repository.findById(tenantId, sessionId)
        }?.takeIf {
            it.tenantId == tenantId && it.id == sessionId && it.ownerId == ownerId
        } ?: return@execute null
        val global = sessions.findById(tenantId, sessionId)
            ?.takeIf { it.tenantId == tenantId && it.id == sessionId }
        global?.takeIf {
            it.ownerId == ownerId &&
                isSamePersistedSession(owned, it) &&
                isUserVisible(it)
        }
    }

    private fun findOwnedByIdempotency(
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
            it.ownerId == ownerId &&
                isSamePersistedSession(owned, it) &&
                isUserVisible(it)
        }
    }

    private fun isUserVisible(session: ResumableUploadSession): Boolean =
        session.status != ResumableUploadSessionStatus.ABORTING &&
            session.status != ResumableUploadSessionStatus.QUARANTINED &&
            session.lastError != START_PERSISTENCE_ISOLATION_MARKER &&
            session.lastError != START_CREATION_STAGING_MARKER

    private fun requireIdempotencyKeyAvailable(
        tenantId: Identifier,
        idempotencyKey: String,
    ) {
        if (findIdempotencyKeyOccupant(tenantId, idempotencyKey) != null) throw idempotencyKeyUnavailable()
    }

    private fun findIdempotencyKeyOccupant(
        tenantId: Identifier,
        idempotencyKey: String,
    ): ResumableUploadSession? = transaction.execute {
        sessions.findByIdempotencyKey(tenantId, idempotencyKey)?.takeIf {
            it.tenantId == tenantId && it.idempotencyKey == idempotencyKey
        }
    }

    private fun idempotencyKeyUnavailable(cause: Throwable? = null): ResumableUploadStateException =
        ResumableUploadStateException(IDEMPOTENCY_KEY_UNAVAILABLE_MESSAGE).also { exception ->
            if (cause != null) exception.addSuppressed(cause)
        }

    /** Persists, verifies and publishes a new session without ever committing an unverified ACTIVE row. */
    private fun persistAndActivateStart(
        staged: ResumableUploadSession,
        intendedOwnerId: String,
    ): ResumableUploadSession {
        sessions.save(staged)
        return verifyAndActivateStagedStart(staged, intendedOwnerId)
    }

    private fun verifyAndActivateStagedStart(
        staged: ResumableUploadSession,
        intendedOwnerId: String,
    ): ResumableUploadSession {
        val stagedSnapshot = readStartPersistence(staged, intendedOwnerId, alreadyInTransaction = true)
        if (stagedSnapshot.failures.isNotEmpty()) throw stagedSnapshot.failures.first()
        if (stagedSnapshot.exactlyConfirmsStatus(
                staged,
                intendedOwnerId,
                ResumableUploadSessionStatus.ABORTING,
                START_CREATION_STAGING_MARKER,
            )
        ) {
            val activationTime = clock.millis()
            if (
                !requireStagingCapability().activateStaged(
                    staged.tenantId,
                    staged.id,
                    intendedOwnerId,
                    START_CREATION_STAGING_MARKER,
                    activationTime,
                )
            ) {
                throw startActivationRejected()
            }
            val activeSnapshot = readStartPersistence(staged, intendedOwnerId, alreadyInTransaction = true)
            if (activeSnapshot.failures.isNotEmpty()) throw activeSnapshot.failures.first()
            return activeSnapshot.confirmedSession(
                staged,
                intendedOwnerId,
                ResumableUploadSessionStatus.ACTIVE,
                expectedLastError = null,
            ) ?: throw startPersistenceContractFailure()
        }

        if (stagedSnapshot.deterministicallyUnusableForOwner(staged)) {
            val persistedOwnerId = stagedSnapshot.globalById!!.ownerId
            if (
                !requireQuarantineCapability().markQuarantined(
                    staged.tenantId,
                    staged.id,
                    START_PERSISTENCE_ISOLATION_MARKER,
                    clock.millis(),
                )
            ) {
                throw startFailureMarkRejected()
            }
            val quarantineSnapshot = readStartPersistence(staged, persistedOwnerId, alreadyInTransaction = true)
            if (quarantineSnapshot.failures.isNotEmpty()) throw quarantineSnapshot.failures.first()
            return quarantineSnapshot.confirmedSession(
                staged,
                persistedOwnerId,
                ResumableUploadSessionStatus.QUARANTINED,
                START_PERSISTENCE_ISOLATION_MARKER,
            ) ?: throw startPersistenceContractFailure()
        }
        throw startPersistenceContractFailure()
    }

    private fun reconcileFailedStart(
        attempted: ResumableUploadSession,
        storageUpload: MultipartUpload,
        command: StartResumableUploadCommand,
        requestIdentity: ResumableUploadRequestIdentity,
        failure: Throwable,
    ): ResumableUploadSession {
        val snapshot = readStartPersistence(attempted, requestIdentity.ownerId)
        if (snapshot.failures.isNotEmpty()) throw outcomeUnknown(failure, snapshot.failures)
        snapshot.confirmedSession(
            attempted,
            requestIdentity.ownerId,
            ResumableUploadSessionStatus.ACTIVE,
            expectedLastError = null,
        )?.let { return it }

        val persistedOwnerId = snapshot.globalById?.ownerId
        snapshot.confirmedGlobalSession(
            attempted,
            persistedOwnerId,
            ResumableUploadSessionStatus.QUARANTINED,
            START_PERSISTENCE_ISOLATION_MARKER,
        )?.let {
            val contractFailure = startPersistenceContractFailure().also { it.addSuppressed(failure) }
            abortFailedStart(storageUpload, contractFailure)
            throw contractFailure
        }

        if (
            snapshot.exactlyConfirmsStatus(
                attempted,
                requestIdentity.ownerId,
                ResumableUploadSessionStatus.ABORTING,
                START_CREATION_STAGING_MARKER,
            )
        ) {
            val recovered = try {
                transaction.execute { verifyAndActivateStagedStart(attempted, requestIdentity.ownerId) }
            } catch (activationFailure: Throwable) {
                val postActivation = readStartPersistence(attempted, requestIdentity.ownerId)
                if (postActivation.failures.isNotEmpty()) {
                    throw outcomeUnknown(activationFailure, postActivation.failures)
                }
                postActivation.confirmedSession(
                    attempted,
                    requestIdentity.ownerId,
                    ResumableUploadSessionStatus.ACTIVE,
                    expectedLastError = null,
                )?.let { return it }
                val ownerAfterActivation = postActivation.globalById?.ownerId
                postActivation.confirmedGlobalSession(
                    attempted,
                    ownerAfterActivation,
                    ResumableUploadSessionStatus.QUARANTINED,
                    START_PERSISTENCE_ISOLATION_MARKER,
                )?.let {
                    val contractFailure = startPersistenceContractFailure().also {
                        it.addSuppressed(failure)
                        it.addSuppressed(activationFailure)
                    }
                    abortFailedStart(storageUpload, contractFailure)
                    throw contractFailure
                }
                val contractFailure = startPersistenceContractFailure().also {
                    it.addSuppressed(failure)
                    it.addSuppressed(activationFailure)
                }
                if (
                    postActivation.confirmedGlobalSession(
                        attempted,
                        ownerAfterActivation,
                        ResumableUploadSessionStatus.ABORTING,
                        START_CREATION_STAGING_MARKER,
                    ) != null
                ) {
                    quarantineUnusableSavedStart(
                        attempted,
                        ownerAfterActivation,
                        storageUpload,
                        contractFailure,
                    )
                }
                throw outcomeUnknown(contractFailure, listOf(reconciliationMismatch()))
            }
            if (recovered.status == ResumableUploadSessionStatus.ACTIVE) return recovered
            val contractFailure = startPersistenceContractFailure().also { it.addSuppressed(failure) }
            abortFailedStart(storageUpload, contractFailure)
            throw contractFailure
        }
        if (snapshot.deterministicallyUnusableForOwner(attempted)) {
            val contractFailure = startPersistenceContractFailure().also { it.addSuppressed(failure) }
            quarantineUnusableSavedStart(
                attempted,
                snapshot.globalById!!.ownerId,
                storageUpload,
                contractFailure,
            )
        }

        val candidates = snapshot.candidates()
        if (candidates.any { !isDefinitelyDifferentUpload(it, attempted) }) {
            throw outcomeUnknown(failure, listOf(reconciliationMismatch()))
        }

        val globalKeyOccupant = snapshot.globalByKey
        val ownerKeyOccupant = snapshot.ownedByKey
        if (globalKeyOccupant != null) {
            if (globalKeyOccupant.ownerId == requestIdentity.ownerId) {
                if (
                    ownerKeyOccupant == null ||
                    !isSamePersistedSession(ownerKeyOccupant, globalKeyOccupant)
                ) {
                    throw outcomeUnknown(failure, listOf(reconciliationMismatch()))
                }
                abortFailedStart(storageUpload, failure)
                if (!isUserVisible(globalKeyOccupant)) throw idempotencyKeyUnavailable(failure)
                authorize(globalKeyOccupant, requestIdentity.user)
                requireEquivalent(globalKeyOccupant, command)
                return globalKeyOccupant
            }
            if (ownerKeyOccupant != null) {
                throw outcomeUnknown(failure, listOf(reconciliationMismatch()))
            }
            abortFailedStart(storageUpload, failure)
            throw idempotencyKeyUnavailable(failure)
        }
        if (ownerKeyOccupant != null) {
            throw outcomeUnknown(failure, listOf(reconciliationMismatch()))
        }
        if (failure is ApplicationTransactionOutcomeUnknownException) throw failure

        abortFailedStart(storageUpload, failure)
        throw failure
    }

    private fun readStartPersistence(
        attempted: ResumableUploadSession,
        ownerId: String?,
        alreadyInTransaction: Boolean = false,
    ): StartPersistenceSnapshot {
        val failures = ArrayList<Throwable>(4)
        fun <T> executeRead(action: () -> T): T =
            if (alreadyInTransaction) action() else transaction.execute(action)

        fun read(
            expected: (ResumableUploadSession) -> Boolean,
            action: () -> ResumableUploadSession?,
        ): ResumableUploadSession? {
            val candidate = try {
                action()
            } catch (failure: Throwable) {
                failures += failure
                return null
            }
            if (candidate != null && !expected(candidate)) {
                failures += reconciliationReadContractMismatch()
                return null
            }
            return candidate
        }

        val globalById = read(
            expected = { it.tenantId == attempted.tenantId && it.id == attempted.id },
        ) {
            executeRead { sessions.findById(attempted.tenantId, attempted.id) }
        }
        val globalByKey = read(
            expected = {
                it.tenantId == attempted.tenantId &&
                    it.idempotencyKey == attempted.idempotencyKey
            },
        ) {
            executeRead { sessions.findByIdempotencyKey(attempted.tenantId, attempted.idempotencyKey) }
        }
        val ownedById = if (ownerId == null) {
            null
        } else when (val repository = sessions) {
            is OwnerScopedResumableUploadSessionRepository -> try {
                executeRead {
                    repository.findById(attempted.tenantId, ownerId, attempted.id)
                }
            } catch (failure: Throwable) {
                failures += failure
                null
            }?.takeIf {
                it.tenantId == attempted.tenantId &&
                    it.id == attempted.id &&
                    it.ownerId == ownerId
            }
            else -> read(
                expected = { it.tenantId == attempted.tenantId && it.id == attempted.id },
            ) {
                executeRead { repository.findById(attempted.tenantId, attempted.id) }
            }?.takeIf { it.ownerId == ownerId }
        }
        val ownedByKey = if (ownerId == null) {
            null
        } else when (val repository = sessions) {
            is OwnerScopedResumableUploadSessionRepository -> try {
                executeRead {
                    repository.findByIdempotencyKey(attempted.tenantId, ownerId, attempted.idempotencyKey)
                }
            } catch (failure: Throwable) {
                failures += failure
                null
            }?.takeIf {
                it.tenantId == attempted.tenantId &&
                    it.idempotencyKey == attempted.idempotencyKey &&
                    it.ownerId == ownerId
            }
            else -> read(
                expected = {
                    it.tenantId == attempted.tenantId &&
                        it.idempotencyKey == attempted.idempotencyKey
                },
            ) {
                executeRead {
                    repository.findByIdempotencyKey(attempted.tenantId, attempted.idempotencyKey)
                }
            }?.takeIf { it.ownerId == ownerId }
        }
        return StartPersistenceSnapshot(globalById, globalByKey, ownedById, ownedByKey, failures)
    }

    private fun quarantineUnusableSavedStart(
        attempted: ResumableUploadSession,
        persistedOwnerId: String?,
        storageUpload: MultipartUpload,
        contractFailure: ResumableUploadStateException,
    ): Nothing {
        quarantineClaimedSession(attempted, persistedOwnerId, contractFailure)
        abortFailedStart(storageUpload, contractFailure)
        throw contractFailure
    }

    /**
     * First commits the exact ACTIVE/FAILED/STAGING row as hidden ABORTING, then attempts the
     * monotonic QUARANTINED transition in a second transaction. A quarantine rollback can therefore
     * never restore ACTIVE. Remote cleanup is allowed only after an authoritative Q confirmation.
     */
    private fun quarantineClaimedSession(
        original: ResumableUploadSession,
        expectedOwnerId: String?,
        reason: Throwable,
        requiredCleanupNow: Long? = null,
    ): ResumableUploadSession {
        val quarantineRepository = requireQuarantineCapability()
        val claimed = try {
            transaction.execute {
                if (requiredCleanupNow != null) {
                    check(isEligibleCleanupCandidate(original, requiredCleanupNow)) {
                        "Upload cleanup repository returned an ineligible session."
                    }
                }
                sessions.claimForAbort(original.tenantId, original.id, clock.millis())?.also { candidate ->
                    requireClaimedSnapshot(
                        claimed = candidate,
                        original = original,
                        expectedOwnerId = expectedOwnerId,
                        expectedStatus = ResumableUploadSessionStatus.ABORTING,
                    )
                } ?: throw startFailureMarkRejected()
            }
        } catch (claimFailure: Throwable) {
            val persisted = readQuarantinePersistence(original, expectedOwnerId)
            persisted.confirmedSession(original, expectedOwnerId)?.let { return it }
            persisted.confirmedAbortingSession(original, expectedOwnerId)
                ?: throw outcomeUnknown(
                    reason,
                    listOf(claimFailure) + persisted.failures + reconciliationMismatch(),
                )
        }

        val transitionFailure: Throwable
        try {
            return transaction.execute {
                if (
                    !quarantineRepository.markQuarantined(
                        claimed.tenantId,
                        claimed.id,
                        START_PERSISTENCE_ISOLATION_MARKER,
                        clock.millis(),
                    )
                ) {
                    throw startFailureMarkRejected()
                }
                val byId = sessions.findById(claimed.tenantId, claimed.id)
                val byKey = sessions.findByIdempotencyKey(claimed.tenantId, claimed.idempotencyKey)
                QuarantinePersistenceSnapshot(byId, byKey, expectedOwnerId, emptyList())
                    .confirmedSession(original, expectedOwnerId)
                    ?: throw reconciliationMismatch()
            }
        } catch (failure: Throwable) {
            transitionFailure = failure
        }

        val quarantine = readQuarantinePersistence(original, expectedOwnerId)
        quarantine.confirmedSession(original, expectedOwnerId)?.let { confirmed ->
            if (transitionFailure !== reason && transitionFailure !== reason.cause) {
                reason.addSuppressed(transitionFailure)
            }
            return confirmed
        }
        val failures = ArrayList<Throwable>(quarantine.failures.size + 2)
        failures += transitionFailure
        failures += quarantine.failures
        failures += reconciliationMismatch()
        throw outcomeUnknown(reason, failures)
    }

    private fun readQuarantinePersistence(
        attempted: ResumableUploadSession,
        persistedOwnerId: String?,
    ): QuarantinePersistenceSnapshot {
        val failures = ArrayList<Throwable>(2)
        fun read(
            expected: (ResumableUploadSession) -> Boolean,
            action: () -> ResumableUploadSession?,
        ): ResumableUploadSession? {
            val candidate = try {
                action()
            } catch (failure: Throwable) {
                failures += failure
                return null
            }
            if (candidate != null && !expected(candidate)) {
                failures += reconciliationReadContractMismatch()
                return null
            }
            return candidate
        }

        val globalById = read(
            expected = { it.tenantId == attempted.tenantId && it.id == attempted.id },
        ) {
            transaction.execute { sessions.findById(attempted.tenantId, attempted.id) }
        }
        val globalByKey = read(
            expected = {
                it.tenantId == attempted.tenantId &&
                    it.idempotencyKey == attempted.idempotencyKey
            },
        ) {
            transaction.execute { sessions.findByIdempotencyKey(attempted.tenantId, attempted.idempotencyKey) }
        }
        return QuarantinePersistenceSnapshot(globalById, globalByKey, persistedOwnerId, failures)
    }

    private fun startPersistenceContractFailure(): ResumableUploadStateException =
        ResumableUploadStateException(START_PERSISTENCE_CONTRACT_MESSAGE)

    private fun startFailureMarkRejected(): ResumableUploadStateException =
        ResumableUploadStateException(START_PERSISTENCE_MARK_REJECTED_MESSAGE)

    private fun startActivationRejected(): ResumableUploadStateException =
        ResumableUploadStateException(START_ACTIVATION_REJECTED_MESSAGE)

    private fun requireQuarantineCapability(): QuarantinableResumableUploadSessionRepository =
        sessions as? QuarantinableResumableUploadSessionRepository
            ?: throw ResumableUploadStateException(QUARANTINE_CAPABILITY_REQUIRED_MESSAGE)

    private fun requireStagingCapability(): StagedResumableUploadSessionRepository =
        sessions as? StagedResumableUploadSessionRepository
            ?: throw ResumableUploadStateException(STAGING_CAPABILITY_REQUIRED_MESSAGE)

    private fun isSamePersistedSession(
        candidate: ResumableUploadSession,
        attempted: ResumableUploadSession,
    ): Boolean =
        candidate.id == attempted.id &&
            candidate.tenantId == attempted.tenantId &&
            candidate.ownerId == attempted.ownerId &&
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

    private fun isSamePersistedSessionIgnoringOwner(
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

    private fun isDefinitelyDifferentUpload(
        candidate: ResumableUploadSession,
        attempted: ResumableUploadSession,
    ): Boolean =
        candidate.id != attempted.id &&
            candidate.storageUploadId != attempted.storageUploadId &&
            candidate.storageLocation != attempted.storageLocation &&
            candidate.fileObjectId != attempted.fileObjectId &&
            candidate.fileAssetId != attempted.fileAssetId

    private fun reconciliationMismatch(): ResumableUploadStateException =
        ResumableUploadStateException(RECONCILIATION_MISMATCH_MESSAGE)

    private fun reconciliationReadContractMismatch(): ResumableUploadStateException =
        ResumableUploadStateException(RECONCILIATION_READ_CONTRACT_MISMATCH_MESSAGE)

    private fun completionReconciliationMismatch(): ResumableUploadStateException =
        ResumableUploadStateException(COMPLETION_RECONCILIATION_MISMATCH_MESSAGE)

    private fun untrustedCompletedLocation(): ResumableUploadStateException =
        ResumableUploadStateException(UNTRUSTED_COMPLETED_LOCATION_MESSAGE)

    private fun outcomeUnknown(
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

    private fun abortFailedStart(upload: MultipartUpload, failure: Throwable) {
        try {
            abortStorage(upload, null)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
            throw failure
        }
    }

    private fun requireClaimedSnapshot(
        claimed: ResumableUploadSession,
        original: ResumableUploadSession,
        expectedOwnerId: String?,
        expectedStatus: ResumableUploadSessionStatus,
    ): ResumableUploadSession = claimed.takeIf {
        isSamePersistedSessionIgnoringOwner(it, original) &&
            it.ownerId == expectedOwnerId &&
            it.status == expectedStatus
    } ?: throw ResumableUploadNotFoundException(original.id)

    private fun authorize(session: ResumableUploadSession, user: UserIdentity) =
        authorization.requireActionAs(
            session.tenantId,
            session.fileObjectId,
            FILE_OBJECT_RESOURCE_TYPE,
            UPLOAD_ACTION,
            user,
        )

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

    private fun compensateCompletedObject(location: ai.icen.fw.spi.storage.StorageObjectLocation, originalFailure: Throwable) {
        try {
            storageAdapter.delete(location)
        } catch (cleanupFailure: Throwable) {
            originalFailure.addSuppressed(cleanupFailure)
        }
    }

    private fun safeMarkFailed(session: ResumableUploadSession, originalFailure: Throwable) {
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
                ) {
                    return@execute
                }
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
        const val IDEMPOTENCY_KEY_UNAVAILABLE_MESSAGE = "Resumable upload idempotency key is unavailable."
        const val RECONCILIATION_MISMATCH_MESSAGE =
            "The persisted resumable upload does not exactly match the attempted session."
        const val RECONCILIATION_READ_CONTRACT_MISMATCH_MESSAGE =
            "A resumable upload reconciliation lookup returned a record outside its requested key."
        const val START_PERSISTENCE_CONTRACT_MESSAGE =
            "Resumable upload persistence did not preserve the required owner-scoped session."
        const val START_PERSISTENCE_ISOLATION_MARKER =
            "fileweft:resumable-upload:owner-isolation:v1"
        const val START_CREATION_STAGING_MARKER =
            "fileweft:resumable-upload:creation-staging:v1"
        const val START_PERSISTENCE_MARK_REJECTED_MESSAGE =
            "The unusable resumable upload session could not be quarantined."
        const val QUARANTINE_CAPABILITY_REQUIRED_MESSAGE =
            "Resumable upload creation requires a repository with durable quarantine support."
        const val STAGING_CAPABILITY_REQUIRED_MESSAGE =
            "Resumable upload creation requires a repository with guarded staging activation support."
        const val START_ACTIVATION_REJECTED_MESSAGE =
            "The staged resumable upload session could not be activated."
        const val CLEANUP_QUARANTINE_MESSAGE =
            "An expired aborting upload session could not be durably quarantined."
        const val COMPLETION_RECONCILIATION_MISMATCH_MESSAGE =
            "The persisted multipart completion does not exactly match the completed storage object."
        const val UNTRUSTED_COMPLETED_LOCATION_MESSAGE =
            "The storage adapter returned a completed object outside the requested upload location."
        const val DEFAULT_CLEANUP_LIMIT = 100
        const val MAX_CLEANUP_LIMIT = 1_000
    }

    private class ResumableUploadRequestIdentity(
        val tenantId: Identifier,
        val user: UserIdentity,
        val ownerId: String,
    )

    private class CompletionPersistenceSnapshot(
        val session: ResumableUploadSession?,
        val fileObject: FileObject?,
        val fileAsset: FileAsset?,
    )

    private inner class QuarantinePersistenceSnapshot(
        private val globalById: ResumableUploadSession?,
        private val globalByKey: ResumableUploadSession?,
        private val persistedOwnerId: String?,
        val failures: List<Throwable>,
    ) {
        fun confirmedSession(
            attempted: ResumableUploadSession,
            expectedOwnerId: String?,
        ): ResumableUploadSession? = globalById?.takeIf {
            persistedOwnerId == expectedOwnerId &&
                isSamePersistedSessionIgnoringOwner(globalById, attempted) &&
                globalById.ownerId == expectedOwnerId &&
                globalById.status == ResumableUploadSessionStatus.QUARANTINED &&
                globalById.lastError == START_PERSISTENCE_ISOLATION_MARKER &&
                globalByKey != null &&
                isSamePersistedSessionIgnoringOwner(globalByKey, attempted) &&
                globalByKey.ownerId == expectedOwnerId &&
                globalByKey.status == ResumableUploadSessionStatus.QUARANTINED &&
                globalByKey.lastError == START_PERSISTENCE_ISOLATION_MARKER
        }

        fun confirmedAbortingSession(
            attempted: ResumableUploadSession,
            expectedOwnerId: String?,
        ): ResumableUploadSession? = globalById?.takeIf {
            persistedOwnerId == expectedOwnerId &&
                isSamePersistedSessionIgnoringOwner(globalById, attempted) &&
                globalById.ownerId == expectedOwnerId &&
                globalById.status == ResumableUploadSessionStatus.ABORTING &&
                globalByKey != null &&
                isSamePersistedSessionIgnoringOwner(globalByKey, attempted) &&
                globalByKey.ownerId == expectedOwnerId &&
                globalByKey.status == ResumableUploadSessionStatus.ABORTING
        }
    }

    private inner class StartPersistenceSnapshot(
        val globalById: ResumableUploadSession?,
        val globalByKey: ResumableUploadSession?,
        val ownedById: ResumableUploadSession?,
        val ownedByKey: ResumableUploadSession?,
        val failures: List<Throwable>,
    ) {
        fun confirmedSession(
            attempted: ResumableUploadSession,
            expectedOwnerId: String?,
            expectedStatus: ResumableUploadSessionStatus,
            expectedLastError: String?,
        ): ResumableUploadSession? {
            val global = confirmedGlobalSession(attempted, expectedOwnerId, expectedStatus, expectedLastError)
                ?: return null
            if (expectedOwnerId == null) return global
            return global.takeIf {
                ownedById.matches(attempted, expectedOwnerId, expectedStatus, expectedLastError) &&
                    ownedByKey.matches(attempted, expectedOwnerId, expectedStatus, expectedLastError)
            }
        }

        fun confirmedGlobalSession(
            attempted: ResumableUploadSession,
            expectedOwnerId: String?,
            expectedStatus: ResumableUploadSessionStatus,
            expectedLastError: String?,
        ): ResumableUploadSession? = globalById?.takeIf {
            it.matches(attempted, expectedOwnerId, expectedStatus, expectedLastError) &&
                globalByKey.matches(attempted, expectedOwnerId, expectedStatus, expectedLastError)
        }

        fun exactlyConfirmsStatus(
            attempted: ResumableUploadSession,
            expectedOwnerId: String?,
            expectedStatus: ResumableUploadSessionStatus,
            expectedLastError: String?,
        ): Boolean = confirmedSession(attempted, expectedOwnerId, expectedStatus, expectedLastError) != null

        fun deterministicallyUnusableForOwner(attempted: ResumableUploadSession): Boolean =
            globalById != null && isSamePersistedSessionIgnoringOwner(globalById, attempted) &&
                globalByKey != null && isSamePersistedSessionIgnoringOwner(globalByKey, attempted) &&
                globalById.ownerId == globalByKey.ownerId &&
                (
                    ownedById == null || !isSamePersistedSession(ownedById, attempted) ||
                        ownedByKey == null || !isSamePersistedSession(ownedByKey, attempted)
                    )

        fun candidates(): List<ResumableUploadSession> =
            listOfNotNull(globalById, globalByKey, ownedById, ownedByKey)

        private fun ResumableUploadSession?.matches(
            attempted: ResumableUploadSession,
            expectedOwnerId: String?,
            expectedStatus: ResumableUploadSessionStatus,
            expectedLastError: String?,
        ): Boolean = this != null &&
            isSamePersistedSessionIgnoringOwner(this, attempted) &&
            ownerId == expectedOwnerId &&
            status == expectedStatus &&
            lastError == expectedLastError
    }
}
