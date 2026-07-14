package ai.icen.fw.application.upload

import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StoredObject
import java.security.MessageDigest

/**
 * Owns stable checkpoint reads and the durable reconciliation paths shared by start and completion.
 *
 * The class deliberately has no dependency on the command handlers. This keeps recovery usable from
 * more than one entry point without recreating a central use-case engine.
 */
internal class ResumableUploadReconciler(
    private val context: ResumableUploadContext,
) {
    fun inspect(sessionId: Identifier): ResumableUploadSessionView {
        val requestIdentity = context.currentRequestIdentity()
        val session = context.requiredOwnedSession(requestIdentity.tenantId, requestIdentity.ownerId, sessionId)
        context.authorize(session, requestIdentity.user)
        return stableOwnedView(requestIdentity.tenantId, requestIdentity.ownerId, sessionId)
    }

    /** Returns one owner-safe session/parts checkpoint that was stable across the repository read. */
    internal fun stableOwnedView(
        tenantId: Identifier,
        ownerId: String,
        sessionId: Identifier,
    ): ResumableUploadSessionView {
        repeat(MAX_STABLE_VIEW_ATTEMPTS) {
            val view = context.transaction.execute {
                val before = context.requiredOwnedSessionInTransaction(tenantId, ownerId, sessionId)
                val parts = context.sessions.findParts(tenantId, sessionId)
                val after = context.requiredOwnedSessionInTransaction(tenantId, ownerId, sessionId)
                if (context.isSameSessionSnapshot(before, after)) ResumableUploadSessionView(after, parts) else null
            }
            if (view != null) return view
        }
        throw ResumableUploadStateException("Upload checkpoint changed concurrently; retry inspection.")
    }

    internal fun completedResult(session: ResumableUploadSession): UploadFileResult = context.transaction.execute {
        val fileObject = context.fileObjects.findById(session.tenantId, session.fileObjectId)
            ?: throw ResumableUploadStateException("Completed upload session file object is missing.")
        val fileAsset = context.fileAssets.findById(session.tenantId, session.fileAssetId)
            ?: throw ResumableUploadStateException("Completed upload session file asset is missing.")
        UploadFileResult(fileObject, fileAsset)
    }

    internal fun persistCompleted(session: ResumableUploadSession, stored: StoredObject): UploadFileResult {
        val fileObject = expectedFileObject(session, stored)
        val fileAsset = expectedFileAsset(session)
        return context.transaction.execute {
            context.fileObjects.save(fileObject)
            context.fileAssets.save(fileAsset)
            context.outbox.append(
                OutboxEvent(
                    id = context.identifiers.nextId(),
                    tenantId = session.tenantId,
                    type = FILE_UPLOADED_EVENT_TYPE,
                    payload = mapOf("fileObjectId" to fileObject.id.value, "fileAssetId" to fileAsset.id.value),
                    timestamp = context.clock.millis(),
                ),
            )
            require(context.sessions.markCompleted(session.tenantId, session.id, context.clock.millis())) {
                "Upload session completion state was changed concurrently."
            }
            UploadFileResult(fileObject, fileAsset)
        }
    }

    /**
     * Recovers a stale completion whose storage acknowledgement or database commit response was lost.
     * A fresh COMPLETING claim is never inspected: another request can still be legitimately executing it.
     */
    internal fun reconcileCompleting(original: ResumableUploadSession): UploadFileResult {
        val now = context.clock.millis()
        if (now - original.updatedTime < COMPLETION_RECONCILIATION_DELAY_MILLIS) {
            throw ResumableUploadStateException(
                "Upload session ${original.id.value} is still completing.",
            )
        }
        val snapshot = try {
            context.transaction.execute {
                CompletionPersistenceSnapshot(
                    session = context.sessions.findById(original.tenantId, original.id),
                    fileObject = context.fileObjects.findById(original.tenantId, original.fileObjectId),
                    fileAsset = context.fileAssets.findById(original.tenantId, original.fileAssetId),
                )
            }
        } catch (failure: Throwable) {
            throw context.outcomeUnknown(failure, emptyList())
        }
        val current = snapshot.session
            ?: throw ResumableUploadNotFoundException(original.id)
        if (!context.isSamePersistedSession(current, original)) {
            throw context.outcomeUnknown(context.completionReconciliationMismatch(), emptyList())
        }
        if (current.status == ResumableUploadSessionStatus.COMPLETED) return completedResult(current)
        if (current.status != ResumableUploadSessionStatus.COMPLETING) {
            throw ResumableUploadStateException(
                "Upload session ${current.id.value} cannot be reconciled from ${current.status.name}.",
            )
        }
        if (snapshot.fileObject != null || snapshot.fileAsset != null) {
            throw context.outcomeUnknown(context.completionReconciliationMismatch(), emptyList())
        }
        val finalObjectExists = try {
            context.storageAdapter.exists(current.storageLocation)
        } catch (failure: Throwable) {
            throw context.outcomeUnknown(failure, emptyList())
        }
        if (!finalObjectExists) {
            // A stale caller or process may still be inside the remote completion call. Absence alone
            // cannot fence that operation, so never reactivate or delete from a later request.
            throw context.outcomeUnknown(ResumableUploadStateException(COMPLETION_NOT_YET_VISIBLE_MESSAGE), emptyList())
        }
        val stored = inspectCompletedObject(current)
        try {
            context.validateStored(current, stored)
            return persistCompleted(current, stored).also {
                context.recordMetric(FileWeftMetric.UPLOAD_COUNT, current.tenantId.value)
            }
        } catch (failure: Throwable) {
            return reconcileFailedCompletion(current, stored, failure)
        }
    }

    private fun inspectCompletedObject(session: ResumableUploadSession): StoredObject {
        try {
            val download = context.storageAdapter.download(session.storageLocation)
            val digest = MessageDigest.getInstance("SHA-256")
            var measuredLength = 0L
            download.content.use { content ->
                val buffer = ByteArray(RECONCILIATION_BUFFER_SIZE)
                while (true) {
                    val read = content.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    measuredLength = Math.addExact(measuredLength, read.toLong())
                }
            }
            require(download.contentLength == null || download.contentLength == measuredLength) {
                "Completed upload download length does not match the bytes read during reconciliation."
            }
            return StoredObject(
                location = session.storageLocation,
                contentLength = measuredLength,
                contentType = download.contentType ?: session.contentType,
                contentHash = "sha256:" + digest.digest().joinToString(separator = "") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                },
            )
        } catch (failure: Throwable) {
            throw context.outcomeUnknown(failure, emptyList())
        }
    }

    /**
     * Reconciles the durable side of a multipart completion before considering destructive compensation.
     * A final object is never deleted while the database outcome or any persisted reference is uncertain.
     */
    internal fun reconcileFailedCompletion(
        session: ResumableUploadSession,
        stored: StoredObject,
        failure: Throwable,
    ): UploadFileResult {
        if (stored.location != session.storageLocation) {
            context.recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
            throw context.outcomeUnknown(failure, listOf(context.untrustedCompletedLocation()))
        }
        val expectedFileObject = expectedFileObject(session, stored)
        val expectedFileAsset = expectedFileAsset(session)
        val snapshot = try {
            context.transaction.execute {
                CompletionPersistenceSnapshot(
                    session = context.sessions.findById(session.tenantId, session.id),
                    fileObject = context.fileObjects.findById(session.tenantId, session.fileObjectId),
                    fileAsset = context.fileAssets.findById(session.tenantId, session.fileAssetId),
                )
            }
        } catch (reconciliationFailure: Throwable) {
            context.recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
            throw context.outcomeUnknown(failure, listOf(reconciliationFailure))
        }

        val persistedSession = snapshot.session
        val persistedFileObject = snapshot.fileObject
        val persistedFileAsset = snapshot.fileAsset
        if (
            persistedSession != null &&
            context.isSamePersistedSession(persistedSession, session) &&
            persistedSession.status == ResumableUploadSessionStatus.COMPLETED &&
            persistedFileObject != null &&
            isSameFileObject(persistedFileObject, expectedFileObject) &&
            persistedFileAsset != null &&
            isSameFileAsset(persistedFileAsset, expectedFileAsset)
        ) {
            context.recordMetric(FileWeftMetric.UPLOAD_COUNT, session.tenantId.value)
            return UploadFileResult(persistedFileObject, persistedFileAsset)
        }

        val knownUncommittedWithoutReferences =
            failure !is ApplicationTransactionOutcomeUnknownException &&
                persistedSession != null &&
                context.isSamePersistedSession(persistedSession, session) &&
                persistedSession.status == ResumableUploadSessionStatus.COMPLETING &&
                persistedFileObject == null &&
                persistedFileAsset == null
        if (!knownUncommittedWithoutReferences) {
            context.recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
            throw context.outcomeUnknown(failure, listOf(context.completionReconciliationMismatch()))
        }

        context.compensateCompletedObject(session.storageLocation, failure)
        context.safeMarkFailed(session, failure)
        context.recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
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

    /** Persists, verifies and publishes a new session without ever committing an unverified ACTIVE row. */
    internal fun persistAndActivateStart(
        staged: ResumableUploadSession,
        intendedOwnerId: String,
    ): ResumableUploadSession {
        context.sessions.save(staged)
        return verifyAndActivateStagedStart(staged, intendedOwnerId)
    }

    private fun verifyAndActivateStagedStart(
        staged: ResumableUploadSession,
        intendedOwnerId: String,
    ): ResumableUploadSession {
        val stagedSnapshot = readStartPersistence(staged, intendedOwnerId, alreadyInTransaction = true)
        if (stagedSnapshot.failures.isNotEmpty()) throw stagedSnapshot.failures.first()
        if (
            stagedSnapshot.exactlyConfirmsStatus(
                staged,
                intendedOwnerId,
                ResumableUploadSessionStatus.ABORTING,
                START_CREATION_STAGING_MARKER,
            )
        ) {
            val activationTime = context.clock.millis()
            if (
                !context.requireStagingCapability().activateStaged(
                    staged.tenantId,
                    staged.id,
                    intendedOwnerId,
                    START_CREATION_STAGING_MARKER,
                    activationTime,
                )
            ) {
                throw context.startActivationRejected()
            }
            val activeSnapshot = readStartPersistence(staged, intendedOwnerId, alreadyInTransaction = true)
            if (activeSnapshot.failures.isNotEmpty()) throw activeSnapshot.failures.first()
            return activeSnapshot.confirmedSession(
                staged,
                intendedOwnerId,
                ResumableUploadSessionStatus.ACTIVE,
                expectedLastError = null,
            ) ?: throw context.startPersistenceContractFailure()
        }

        if (stagedSnapshot.deterministicallyUnusableForOwner(staged)) {
            val persistedOwnerId = stagedSnapshot.globalById!!.ownerId
            if (
                !context.requireQuarantineCapability().markQuarantined(
                    staged.tenantId,
                    staged.id,
                    START_PERSISTENCE_ISOLATION_MARKER,
                    context.clock.millis(),
                )
            ) {
                throw context.startFailureMarkRejected()
            }
            val quarantineSnapshot = readStartPersistence(staged, persistedOwnerId, alreadyInTransaction = true)
            if (quarantineSnapshot.failures.isNotEmpty()) throw quarantineSnapshot.failures.first()
            return quarantineSnapshot.confirmedSession(
                staged,
                persistedOwnerId,
                ResumableUploadSessionStatus.QUARANTINED,
                START_PERSISTENCE_ISOLATION_MARKER,
            ) ?: throw context.startPersistenceContractFailure()
        }
        throw context.startPersistenceContractFailure()
    }

    internal fun reconcileFailedStart(
        attempted: ResumableUploadSession,
        storageUpload: MultipartUpload,
        command: StartResumableUploadCommand,
        requestIdentity: ResumableUploadRequestIdentity,
        failure: Throwable,
    ): ResumableUploadSession {
        val snapshot = readStartPersistence(attempted, requestIdentity.ownerId)
        if (snapshot.failures.isNotEmpty()) throw context.outcomeUnknown(failure, snapshot.failures)
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
            val contractFailure = context.startPersistenceContractFailure().also { it.addSuppressed(failure) }
            context.abortFailedStart(storageUpload, contractFailure)
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
                context.transaction.execute { verifyAndActivateStagedStart(attempted, requestIdentity.ownerId) }
            } catch (activationFailure: Throwable) {
                val postActivation = readStartPersistence(attempted, requestIdentity.ownerId)
                if (postActivation.failures.isNotEmpty()) {
                    throw context.outcomeUnknown(activationFailure, postActivation.failures)
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
                    val contractFailure = context.startPersistenceContractFailure().also {
                        it.addSuppressed(failure)
                        it.addSuppressed(activationFailure)
                    }
                    context.abortFailedStart(storageUpload, contractFailure)
                    throw contractFailure
                }
                val contractFailure = context.startPersistenceContractFailure().also {
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
                throw context.outcomeUnknown(contractFailure, listOf(context.reconciliationMismatch()))
            }
            if (recovered.status == ResumableUploadSessionStatus.ACTIVE) return recovered
            val contractFailure = context.startPersistenceContractFailure().also { it.addSuppressed(failure) }
            context.abortFailedStart(storageUpload, contractFailure)
            throw contractFailure
        }
        if (snapshot.deterministicallyUnusableForOwner(attempted)) {
            val contractFailure = context.startPersistenceContractFailure().also { it.addSuppressed(failure) }
            quarantineUnusableSavedStart(
                attempted,
                snapshot.globalById!!.ownerId,
                storageUpload,
                contractFailure,
            )
        }

        val candidates = snapshot.candidates()
        if (candidates.any { !context.isDefinitelyDifferentUpload(it, attempted) }) {
            throw context.outcomeUnknown(failure, listOf(context.reconciliationMismatch()))
        }

        val globalKeyOccupant = snapshot.globalByKey
        val ownerKeyOccupant = snapshot.ownedByKey
        if (globalKeyOccupant != null) {
            if (globalKeyOccupant.ownerId == requestIdentity.ownerId) {
                if (
                    ownerKeyOccupant == null ||
                    !context.isSamePersistedSession(ownerKeyOccupant, globalKeyOccupant)
                ) {
                    throw context.outcomeUnknown(failure, listOf(context.reconciliationMismatch()))
                }
                context.abortFailedStart(storageUpload, failure)
                if (!context.isUserVisible(globalKeyOccupant)) throw context.idempotencyKeyUnavailable(failure)
                context.authorize(globalKeyOccupant, requestIdentity.user)
                context.requireEquivalent(globalKeyOccupant, command)
                return globalKeyOccupant
            }
            if (ownerKeyOccupant != null) {
                throw context.outcomeUnknown(failure, listOf(context.reconciliationMismatch()))
            }
            context.abortFailedStart(storageUpload, failure)
            throw context.idempotencyKeyUnavailable(failure)
        }
        if (ownerKeyOccupant != null) {
            throw context.outcomeUnknown(failure, listOf(context.reconciliationMismatch()))
        }
        if (failure is ApplicationTransactionOutcomeUnknownException) throw failure

        context.abortFailedStart(storageUpload, failure)
        throw failure
    }

    private fun readStartPersistence(
        attempted: ResumableUploadSession,
        ownerId: String?,
        alreadyInTransaction: Boolean = false,
    ): StartPersistenceSnapshot {
        val failures = ArrayList<Throwable>(4)
        fun <T> executeRead(action: () -> T): T =
            if (alreadyInTransaction) action() else context.transaction.execute(action)

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
                failures += context.reconciliationReadContractMismatch()
                return null
            }
            return candidate
        }

        val globalById = read(
            expected = { it.tenantId == attempted.tenantId && it.id == attempted.id },
        ) {
            executeRead { context.sessions.findById(attempted.tenantId, attempted.id) }
        }
        val globalByKey = read(
            expected = {
                it.tenantId == attempted.tenantId &&
                    it.idempotencyKey == attempted.idempotencyKey
            },
        ) {
            executeRead { context.sessions.findByIdempotencyKey(attempted.tenantId, attempted.idempotencyKey) }
        }
        val ownedById = if (ownerId == null) {
            null
        } else when (val repository = context.sessions) {
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
        } else when (val repository = context.sessions) {
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
        context.abortFailedStart(storageUpload, contractFailure)
        throw contractFailure
    }

    /**
     * First commits the exact ACTIVE/FAILED/STAGING row as hidden ABORTING, then attempts the
     * monotonic QUARANTINED transition in a second transaction. A quarantine rollback can therefore
     * never restore ACTIVE. Remote cleanup is allowed only after an authoritative Q confirmation.
     */
    internal fun quarantineClaimedSession(
        original: ResumableUploadSession,
        expectedOwnerId: String?,
        reason: Throwable,
        requiredCleanupNow: Long? = null,
    ): ResumableUploadSession {
        val quarantineRepository = context.requireQuarantineCapability()
        val claimed = try {
            context.transaction.execute {
                if (requiredCleanupNow != null) {
                    check(isEligibleCleanupCandidate(original, requiredCleanupNow)) {
                        "Upload cleanup repository returned an ineligible session."
                    }
                }
                context.sessions.claimForAbort(original.tenantId, original.id, context.clock.millis())?.also { candidate ->
                    context.requireClaimedSnapshot(
                        claimed = candidate,
                        original = original,
                        expectedOwnerId = expectedOwnerId,
                        expectedStatus = ResumableUploadSessionStatus.ABORTING,
                    )
                } ?: throw context.startFailureMarkRejected()
            }
        } catch (claimFailure: Throwable) {
            val persisted = readQuarantinePersistence(original, expectedOwnerId)
            persisted.confirmedSession(original, expectedOwnerId)?.let { return it }
            persisted.confirmedAbortingSession(original, expectedOwnerId)
                ?: throw context.outcomeUnknown(
                    reason,
                    listOf(claimFailure) + persisted.failures + context.reconciliationMismatch(),
                )
        }

        val transitionFailure: Throwable
        try {
            return context.transaction.execute {
                if (
                    !quarantineRepository.markQuarantined(
                        claimed.tenantId,
                        claimed.id,
                        START_PERSISTENCE_ISOLATION_MARKER,
                        context.clock.millis(),
                    )
                ) {
                    throw context.startFailureMarkRejected()
                }
                val byId = context.sessions.findById(claimed.tenantId, claimed.id)
                val byKey = context.sessions.findByIdempotencyKey(claimed.tenantId, claimed.idempotencyKey)
                QuarantinePersistenceSnapshot(byId, byKey, expectedOwnerId, emptyList())
                    .confirmedSession(original, expectedOwnerId)
                    ?: throw context.reconciliationMismatch()
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
        failures += context.reconciliationMismatch()
        throw context.outcomeUnknown(reason, failures)
    }

    private fun isEligibleCleanupCandidate(session: ResumableUploadSession, now: Long): Boolean =
        session.expiresAt <= now &&
            (
                session.status == ResumableUploadSessionStatus.ACTIVE ||
                    session.status == ResumableUploadSessionStatus.FAILED ||
                    session.status == ResumableUploadSessionStatus.ABORTING
                )

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
                failures += context.reconciliationReadContractMismatch()
                return null
            }
            return candidate
        }

        val globalById = read(
            expected = { it.tenantId == attempted.tenantId && it.id == attempted.id },
        ) {
            context.transaction.execute { context.sessions.findById(attempted.tenantId, attempted.id) }
        }
        val globalByKey = read(
            expected = {
                it.tenantId == attempted.tenantId &&
                    it.idempotencyKey == attempted.idempotencyKey
            },
        ) {
            context.transaction.execute {
                context.sessions.findByIdempotencyKey(attempted.tenantId, attempted.idempotencyKey)
            }
        }
        return QuarantinePersistenceSnapshot(globalById, globalByKey, persistedOwnerId, failures)
    }

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
                context.isSamePersistedSessionIgnoringOwner(globalById, attempted) &&
                globalById.ownerId == expectedOwnerId &&
                globalById.status == ResumableUploadSessionStatus.QUARANTINED &&
                globalById.lastError == START_PERSISTENCE_ISOLATION_MARKER &&
                globalByKey != null &&
                context.isSamePersistedSessionIgnoringOwner(globalByKey, attempted) &&
                globalByKey.ownerId == expectedOwnerId &&
                globalByKey.status == ResumableUploadSessionStatus.QUARANTINED &&
                globalByKey.lastError == START_PERSISTENCE_ISOLATION_MARKER
        }

        fun confirmedAbortingSession(
            attempted: ResumableUploadSession,
            expectedOwnerId: String?,
        ): ResumableUploadSession? = globalById?.takeIf {
            persistedOwnerId == expectedOwnerId &&
                context.isSamePersistedSessionIgnoringOwner(globalById, attempted) &&
                globalById.ownerId == expectedOwnerId &&
                globalById.status == ResumableUploadSessionStatus.ABORTING &&
                globalByKey != null &&
                context.isSamePersistedSessionIgnoringOwner(globalByKey, attempted) &&
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
            globalById != null && context.isSamePersistedSessionIgnoringOwner(globalById, attempted) &&
                globalByKey != null && context.isSamePersistedSessionIgnoringOwner(globalByKey, attempted) &&
                globalById.ownerId == globalByKey.ownerId &&
                (
                    ownedById == null || !context.isSamePersistedSession(ownedById, attempted) ||
                        ownedByKey == null || !context.isSamePersistedSession(ownedByKey, attempted)
                    )

        fun candidates(): List<ResumableUploadSession> =
            listOfNotNull(globalById, globalByKey, ownedById, ownedByKey)

        private fun ResumableUploadSession?.matches(
            attempted: ResumableUploadSession,
            expectedOwnerId: String?,
            expectedStatus: ResumableUploadSessionStatus,
            expectedLastError: String?,
        ): Boolean = this != null &&
            context.isSamePersistedSessionIgnoringOwner(this, attempted) &&
            ownerId == expectedOwnerId &&
            status == expectedStatus &&
            lastError == expectedLastError
    }
}
