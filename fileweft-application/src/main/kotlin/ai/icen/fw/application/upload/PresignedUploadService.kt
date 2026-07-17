package ai.icen.fw.application.upload

import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.PresignedUploadFinalization
import ai.icen.fw.spi.storage.PresignedUploadFinalizeRequest
import ai.icen.fw.spi.storage.PresignedUploadGrant
import ai.icen.fw.spi.storage.PresignedUploadGrantRequest
import ai.icen.fw.spi.storage.PresignedUploadReissueRequest
import ai.icen.fw.spi.storage.PresignedUploadStorageAdapter
import ai.icen.fw.spi.tenant.TenantProvider
import java.time.Clock
import java.time.Duration

/**
 * Application-owned authority for constrained direct PUT grants.
 *
 * Every repository operation runs in its own short [ApplicationTransaction].
 * Signing, re-signing and provider finalization always happen after that
 * transaction has closed.
 */
class PresignedUploadService @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val storageAdapter: PresignedUploadStorageAdapter,
    private val repository: PresignedUploadSessionRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val finalizeGrace: Duration = Duration.ofMinutes(15),
    private val claimLease: Duration = Duration.ofMinutes(2),
    private val transaction: ApplicationTransaction = DirectPresignedUploadApplicationTransaction,
    private val claimTokenGenerator: IdentifierGenerator = identifierGenerator,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    init {
        requirePositiveDuration(finalizeGrace, "Presigned upload finalize grace")
        requirePositiveDuration(claimLease, "Presigned upload claim lease")
    }

    /**
     * Compatibility entry point. Each invocation is a new logical request;
     * callers that can retry must use [startWithCallerKey].
     */
    fun start(command: StartPresignedUploadCommand): PresignedUploadGrantResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        return startInternal(
            command = command,
            callerKey = null,
            preallocatedSessionId = identifierGenerator.nextId(),
        )
    }

    /** Owner-scoped idempotent start. The raw caller key is never persisted. */
    fun startWithCallerKey(
        callerKey: String,
        command: StartPresignedUploadCommand,
    ): PresignedUploadGrantResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        require(FORMAL_CALLER_KEY_PATTERN.matches(callerKey)) {
            "Presigned upload caller key has an invalid format."
        }
        return startInternal(command, callerKey, null)
    }

    fun complete(command: CompletePresignedUploadCommand): PresignedUploadCompletionResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val tenantId = tenantProvider.currentTenant().tenantId
        val principal = authorization.requireCurrentUser()
        var current = findOwned(tenantId, principal, command.sessionId)
        authorization.requireActionAs(tenantId, current.id, RESOURCE_TYPE, COMPLETE_ACTION, principal)
        current.finalization?.let { return PresignedUploadCompletionResult(current.id, it) }

        val now = monotonicNow(current, "claiming presigned upload finalization")
        if (now >= current.sessionExpiresAt) {
            val terminal = expireAndReconcile(current, now)
            terminal.finalization?.let { return PresignedUploadCompletionResult(terminal.id, it) }
            throw PresignedUploadStateException("Presigned upload session expired before finalization.")
        }
        val claimAttempt = claimForFinalization(current, now)
        claimAttempt.completed?.let { return PresignedUploadCompletionResult(current.id, it) }
        current = requireNotNull(claimAttempt.claimed)

        val finalization = try {
            val providerFinalization = storageAdapter.finalizeUpload(
                PresignedUploadFinalizeRequest(
                    bindingId = current.id,
                    tenantId = current.tenantId,
                    location = current.stagingLocation,
                    contentLength = current.contentLength,
                    contentType = current.contentType,
                    contentHash = current.contentHash,
                    checksum = current.checksum,
                    metadata = current.metadata,
                ),
            )
            validateFinalization(current, providerFinalization)

            // Provider verification may be slow. Rebind the operation to the
            // still-current trusted principal and obtain a fresh decision.
            requireFreshOwnerAction(current, COMPLETE_ACTION)
            providerFinalization
        } catch (failure: Throwable) {
            releaseClaimAfterFailure(current, failure)
            throw failure
        }

        val completedAt = monotonicNow(current, "committing presigned upload finalization")
        if (completedAt >= current.sessionExpiresAt) {
            val failure = PresignedUploadStateException(
                "Presigned upload session expired while provider evidence was being verified.",
            )
            releaseClaimAfterFailure(current, failure)
            throw failure
        }
        val completed = copyPresignedUploadSession(
            source = current,
            status = PresignedUploadSessionStatus.COMPLETED,
            version = Math.addExact(current.version, 1),
            claimTime = null,
            claimToken = null,
            claimExpiresAt = null,
            finalization = finalization,
            lastError = null,
            completedTime = completedAt,
            updatedTime = completedAt,
        )
        val committed = try {
            inTransaction {
                repository.compareAndSet(
                    current.tenantId,
                    current.id,
                    current.version,
                    current.claimToken,
                    completed,
                )
            }
        } catch (unknown: ApplicationTransactionOutcomeUnknownException) {
            return reconcileCompletion(current, finalization, unknown)
        } catch (failure: Throwable) {
            releaseClaimAfterFailure(current, failure)
            throw failure
        }
        if (!committed) return reconcileCompletion(current, finalization, null)
        return PresignedUploadCompletionResult(current.id, finalization)
    }

    /** Re-signs only the exact durable staging authority owned by the current principal. */
    fun reissue(command: ReissuePresignedUploadCommand): PresignedUploadGrantResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val tenantId = tenantProvider.currentTenant().tenantId
        val principal = authorization.requireCurrentUser()
        val current = findOwned(tenantId, principal, command.sessionId)
        authorization.requireActionAs(tenantId, current.id, RESOURCE_TYPE, START_ACTION, principal)
        return reissue(current)
    }

    /** Performs the fresh owner and completion-policy checks without invoking storage or changing state. */
    fun inspectForCompletion(command: CompletePresignedUploadCommand): PresignedUploadStatusResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val tenantId = tenantProvider.currentTenant().tenantId
        val principal = authorization.requireCurrentUser()
        val current = findOwned(tenantId, principal, command.sessionId)
        authorization.requireActionAs(tenantId, current.id, RESOURCE_TYPE, COMPLETE_ACTION, principal)
        return statusResult(current)
    }

    fun inspect(command: InspectPresignedUploadCommand): PresignedUploadStatusResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val tenantId = tenantProvider.currentTenant().tenantId
        val principal = authorization.requireCurrentUser()
        var current = findOwned(tenantId, principal, command.sessionId)
        authorization.requireActionAs(tenantId, current.id, RESOURCE_TYPE, READ_ACTION, principal)
        val now = monotonicNow(current, "inspecting a presigned upload")
        if (
            now >= current.sessionExpiresAt &&
            current.status != PresignedUploadSessionStatus.COMPLETED &&
            current.status != PresignedUploadSessionStatus.CANCELLED &&
            current.status != PresignedUploadSessionStatus.EXPIRED
        ) {
            current = expireAndReconcile(current, now)
        }
        return statusResult(current)
    }

    fun cancel(command: CancelPresignedUploadCommand): PresignedUploadStatusResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val tenantId = tenantProvider.currentTenant().tenantId
        val principal = authorization.requireCurrentUser()
        val current = findOwned(tenantId, principal, command.sessionId)
        authorization.requireActionAs(tenantId, current.id, RESOURCE_TYPE, CANCEL_ACTION, principal)
        if (current.status == PresignedUploadSessionStatus.COMPLETED) {
            throw PresignedUploadStateException("A completed presigned upload cannot be cancelled.")
        }
        if (
            current.status == PresignedUploadSessionStatus.CANCELLED ||
            current.status == PresignedUploadSessionStatus.EXPIRED
        ) {
            return statusResult(current)
        }
        val now = monotonicNow(current, "cancelling a presigned upload")
        val cancelled = copyPresignedUploadSession(
            source = current,
            status = PresignedUploadSessionStatus.CANCELLED,
            version = Math.addExact(current.version, 1),
            claimTime = null,
            claimToken = null,
            claimExpiresAt = null,
            finalization = null,
            lastError = null,
            cancelledTime = now,
            updatedTime = now,
        )
        val accepted = try {
            inTransaction {
                repository.compareAndSet(
                    current.tenantId,
                    current.id,
                    current.version,
                    current.claimToken,
                    cancelled,
                )
            }
        } catch (unknown: ApplicationTransactionOutcomeUnknownException) {
            return statusResult(reconcileCancellation(current, unknown))
        }
        if (accepted) return statusResult(cancelled)
        return statusResult(reconcileCancellation(current, null))
    }

    private fun startInternal(
        command: StartPresignedUploadCommand,
        callerKey: String?,
        preallocatedSessionId: Identifier?,
    ): PresignedUploadGrantResult {
        val tenantId = tenantProvider.currentTenant().tenantId
        val principal = authorization.requireCurrentUser()
        val keyDigest = ownerScopedKeyDigest(tenantId, principal, callerKey, preallocatedSessionId)
        val declarationDigest = declarationDigest(tenantId, principal, command)
        val existing = inTransaction {
            repository.findByIdempotencyKey(tenantId, principal.id.value, keyDigest)
        }
        if (existing != null) {
            authorization.requireActionAs(tenantId, existing.id, RESOURCE_TYPE, START_ACTION, principal)
            requireExactDeclaration(existing, command, keyDigest, declarationDigest)
            return reissue(existing)
        }

        val sessionId = preallocatedSessionId ?: identifierGenerator.nextId()
        authorization.requireActionAs(tenantId, sessionId, RESOURCE_TYPE, START_ACTION, principal)
        val beforeGrant = clock.millis()
        val grant = storageAdapter.createUploadGrant(
            PresignedUploadGrantRequest(
                bindingId = sessionId,
                tenantId = tenantId,
                objectName = command.fileName,
                contentLength = command.contentLength,
                contentType = command.contentType,
                contentHash = command.contentHash,
                checksum = command.checksum,
                metadata = command.metadata,
                expiresIn = command.grantDuration,
            ),
        )
        val afterGrant = clock.millis()
        requireClockOrder(beforeGrant, afterGrant, "creating a direct upload grant")
        val requestedExpiresAt = Math.addExact(afterGrant, command.grantDuration.toMillis())
        require(grant.expiresAt in Math.addExact(afterGrant, 1)..requestedExpiresAt) {
            "Storage returned a direct upload grant outside the requested expiration."
        }
        val session = PresignedUploadSession(
            id = sessionId,
            tenantId = tenantId,
            ownerId = principal.id.value,
            fileName = command.fileName,
            contentLength = command.contentLength,
            contentType = command.contentType,
            contentHash = command.contentHash,
            checksum = command.checksum,
            metadata = command.metadata,
            storageLocation = grant.location,
            grantExpiresAt = grant.expiresAt,
            sessionExpiresAt = Math.addExact(grant.expiresAt, finalizeGrace.toMillis()),
            createdTime = afterGrant,
            updatedTime = afterGrant,
            idempotencyKeyDigest = keyDigest,
            declarationDigest = declarationDigest,
            grantDurationMillis = command.grantDuration.toMillis(),
            requiredHeaders = grant.requiredHeaders,
        )
        requireFreshOwnerAction(session, START_ACTION)
        val created = try {
            inTransaction { repository.create(session) }
        } catch (unknown: ApplicationTransactionOutcomeUnknownException) {
            return reconcileStart(session, command, principal, unknown)
        }
        if (!created) return reconcileStart(session, command, principal, null)
        return grantResult(session.id, grant, true)
    }

    private fun reconcileStart(
        attempted: PresignedUploadSession,
        command: StartPresignedUploadCommand,
        principal: UserIdentity,
        unknown: ApplicationTransactionOutcomeUnknownException?,
    ): PresignedUploadGrantResult {
        val winner = inTransaction {
            repository.findByIdempotencyKey(
                attempted.tenantId,
                attempted.ownerId,
                attempted.idempotencyKeyDigest,
            )
        } ?: throw PresignedUploadStateException(
            "Presigned upload session creation did not produce reconcilable durable state.",
            unknown,
        )
        authorization.requireActionAs(
            winner.tenantId,
            winner.id,
            RESOURCE_TYPE,
            START_ACTION,
            principal,
        )
        requireExactDeclaration(
            winner,
            command,
            attempted.idempotencyKeyDigest,
            attempted.declarationDigest,
        )
        if (winner.id == attempted.id && !samePresignedUploadAuthority(winner, attempted)) {
            throw PresignedUploadStateException(
                "Presigned upload creation returned a different durable staging authority.",
                unknown,
            )
        }
        return reissue(winner)
    }

    private fun reissue(session: PresignedUploadSession): PresignedUploadGrantResult {
        if (session.status != PresignedUploadSessionStatus.READY || session.cleanupTime != null) {
            throw PresignedUploadStateException("Presigned upload grant cannot be reissued from its current state.")
        }
        val before = monotonicNow(session, "reissuing a presigned upload grant")
        if (before >= session.grantExpiresAt) {
            throw PresignedUploadStateException("Presigned upload grant has expired and cannot be extended.")
        }
        val grant = storageAdapter.reissueUploadGrant(
            PresignedUploadReissueRequest(
                bindingId = session.id,
                tenantId = session.tenantId,
                location = session.stagingLocation,
                contentLength = session.contentLength,
                contentType = session.contentType,
                contentHash = session.contentHash,
                checksum = session.checksum,
                metadata = session.metadata,
                requiredHeaders = session.requiredHeaders,
                expiresAt = session.grantExpiresAt,
            ),
        )
        val after = clock.millis()
        requireClockOrder(before, after, "reissuing a direct upload grant")
        if (after >= session.grantExpiresAt) {
            throw PresignedUploadStateException("Presigned upload grant expired while it was being reissued.")
        }
        require(
            grant.location == session.stagingLocation &&
                grant.expiresAt == session.grantExpiresAt &&
                grant.requiredHeaders == session.requiredHeaders,
        ) {
            "Storage changed durable authority while reissuing a direct upload grant."
        }
        requireFreshOwnerAction(session, START_ACTION)
        return grantResult(session.id, grant, false)
    }

    private fun claimForFinalization(current: PresignedUploadSession, now: Long): ClaimAttempt {
        when (current.status) {
            PresignedUploadSessionStatus.READY -> Unit
            PresignedUploadSessionStatus.FINALIZING -> {
                if (now < requireNotNull(current.claimExpiresAt)) {
                    throw PresignedUploadStateException("Presigned upload finalization is already in progress.")
                }
            }
            PresignedUploadSessionStatus.COMPLETED -> return ClaimAttempt(null, current.finalization)
            PresignedUploadSessionStatus.CANCELLED,
            PresignedUploadSessionStatus.EXPIRED,
            -> throw PresignedUploadStateException(
                "Presigned upload session cannot be finalized from its current state.",
            )
        }
        val leaseDeadline = minOf(
            current.sessionExpiresAt,
            Math.addExact(now, claimLease.toMillis()),
        )
        require(leaseDeadline > now) { "Presigned upload claim lease cannot cross session expiration." }
        val token = RequestFingerprint.sha256(
            CLAIM_TOKEN_DOMAIN,
            current.tenantId.value,
            current.id.value,
            Math.addExact(current.version, 1).toString(),
            claimTokenGenerator.nextId().value,
        )
        val claimed = copyPresignedUploadSession(
            source = current,
            status = PresignedUploadSessionStatus.FINALIZING,
            version = Math.addExact(current.version, 1),
            claimTime = now,
            claimToken = token,
            claimExpiresAt = leaseDeadline,
            finalization = null,
            lastError = current.lastError,
            updatedTime = now,
        )
        val accepted = try {
            inTransaction {
                repository.compareAndSet(
                    current.tenantId,
                    current.id,
                    current.version,
                    current.claimToken,
                    claimed,
                )
            }
        } catch (unknown: ApplicationTransactionOutcomeUnknownException) {
            return reconcileClaim(current, claimed, unknown)
        }
        if (accepted) return ClaimAttempt(claimed, null)
        return reconcileClaim(current, claimed, null)
    }

    private fun reconcileClaim(
        previous: PresignedUploadSession,
        claimed: PresignedUploadSession,
        unknown: ApplicationTransactionOutcomeUnknownException?,
    ): ClaimAttempt {
        val durable = inTransaction {
            repository.findById(previous.tenantId, previous.ownerId, previous.id)
        } ?: throw PresignedUploadNotFoundException(previous.id)
        if (!samePresignedUploadAuthority(durable, previous)) {
            throw PresignedUploadStateException("Presigned upload durable authority changed while claiming.", unknown)
        }
        durable.finalization?.let { return ClaimAttempt(null, it) }
        if (
            durable.status == PresignedUploadSessionStatus.FINALIZING &&
            durable.version == claimed.version &&
            durable.claimToken == claimed.claimToken &&
            durable.claimTime == claimed.claimTime &&
            durable.claimExpiresAt == claimed.claimExpiresAt
        ) {
            return ClaimAttempt(durable, null)
        }
        throw PresignedUploadStateException("Presigned upload finalization lost its durable claim.", unknown)
    }

    private fun releaseClaimAfterFailure(claimed: PresignedUploadSession, failure: Throwable) {
        try {
            val releasedAt = monotonicNow(claimed, "releasing a presigned upload claim")
            val expired = releasedAt >= claimed.sessionExpiresAt
            val replacement = copyPresignedUploadSession(
                source = claimed,
                status = if (expired) PresignedUploadSessionStatus.EXPIRED else PresignedUploadSessionStatus.READY,
                version = Math.addExact(claimed.version, 1),
                claimTime = null,
                claimToken = null,
                claimExpiresAt = null,
                finalization = null,
                lastError = if (expired) SESSION_EXPIRED_ERROR else safeError(failure),
                updatedTime = releasedAt,
            )
            val accepted = try {
                inTransaction {
                    repository.compareAndSet(
                        claimed.tenantId,
                        claimed.id,
                        claimed.version,
                        claimed.claimToken,
                        replacement,
                    )
                }
            } catch (unknown: ApplicationTransactionOutcomeUnknownException) {
                val durable = inTransaction {
                    repository.findById(claimed.tenantId, claimed.ownerId, claimed.id)
                }
                if (!sameReleasedClaim(durable, replacement)) throw unknown
                true
            }
            if (!accepted) {
                val durable = inTransaction {
                    repository.findById(claimed.tenantId, claimed.ownerId, claimed.id)
                }
                val cancelledWinner = durable != null &&
                    samePresignedUploadAuthority(durable, claimed) &&
                    durable.status == PresignedUploadSessionStatus.CANCELLED
                if (!sameReleasedClaim(durable, replacement) && !cancelledWinner) {
                    throw PresignedUploadStateException("Presigned upload failure could not release its durable claim.")
                }
            }
        } catch (releaseFailure: Throwable) {
            if (releaseFailure !== failure) failure.addSuppressed(releaseFailure)
        }
    }

    private fun expireAndReconcile(current: PresignedUploadSession, now: Long): PresignedUploadSession {
        if (
            current.status == PresignedUploadSessionStatus.COMPLETED ||
            current.status == PresignedUploadSessionStatus.CANCELLED ||
            current.status == PresignedUploadSessionStatus.EXPIRED
        ) {
            return current
        }
        val expired = copyPresignedUploadSession(
            source = current,
            status = PresignedUploadSessionStatus.EXPIRED,
            version = Math.addExact(current.version, 1),
            claimTime = null,
            claimToken = null,
            claimExpiresAt = null,
            finalization = null,
            lastError = SESSION_EXPIRED_ERROR,
            updatedTime = now,
        )
        var unknown: ApplicationTransactionOutcomeUnknownException? = null
        val accepted = try {
            inTransaction {
                repository.compareAndSet(
                    current.tenantId,
                    current.id,
                    current.version,
                    current.claimToken,
                    expired,
                )
            }
        } catch (failure: ApplicationTransactionOutcomeUnknownException) {
            unknown = failure
            false
        }
        if (accepted) return expired
        val durable = inTransaction { repository.findById(current.tenantId, current.ownerId, current.id) }
            ?: throw PresignedUploadNotFoundException(current.id)
        if (
            samePresignedUploadAuthority(durable, current) &&
            (
                durable.status == PresignedUploadSessionStatus.COMPLETED ||
                    durable.status == PresignedUploadSessionStatus.CANCELLED ||
                    durable.status == PresignedUploadSessionStatus.EXPIRED
                )
        ) {
            return durable
        }
        throw PresignedUploadStateException(
            "Presigned upload expiration did not produce a durable terminal state.",
            unknown,
        )
    }

    private fun reconcileCompletion(
        claimed: PresignedUploadSession,
        expected: PresignedUploadFinalization,
        unknown: ApplicationTransactionOutcomeUnknownException?,
    ): PresignedUploadCompletionResult {
        val durable = inTransaction {
            repository.findById(claimed.tenantId, claimed.ownerId, claimed.id)
        } ?: throw PresignedUploadNotFoundException(claimed.id)
        if (
            samePresignedUploadAuthority(durable, claimed) &&
            durable.status == PresignedUploadSessionStatus.COMPLETED &&
            sameFinalization(durable.finalization, expected)
        ) {
            return PresignedUploadCompletionResult(claimed.id, requireNotNull(durable.finalization))
        }
        throw PresignedUploadStateException(
            "Presigned upload finalization succeeded but durable completion is unknown.",
            unknown,
        )
    }

    private fun reconcileCancellation(
        previous: PresignedUploadSession,
        unknown: ApplicationTransactionOutcomeUnknownException?,
    ): PresignedUploadSession {
        val durable = inTransaction {
            repository.findById(previous.tenantId, previous.ownerId, previous.id)
        } ?: throw PresignedUploadNotFoundException(previous.id)
        if (
            samePresignedUploadAuthority(durable, previous) &&
            (
                durable.status == PresignedUploadSessionStatus.CANCELLED ||
                    durable.status == PresignedUploadSessionStatus.EXPIRED
                )
        ) {
            return durable
        }
        throw PresignedUploadStateException("Presigned upload cancellation lost its durable CAS.", unknown)
    }

    private fun findOwned(
        tenantId: Identifier,
        principal: UserIdentity,
        sessionId: Identifier,
    ): PresignedUploadSession = inTransaction {
        repository.findById(tenantId, principal.id.value, sessionId)
    } ?: throw PresignedUploadNotFoundException(sessionId)

    private fun requireFreshOwnerAction(session: PresignedUploadSession, action: String) {
        if (tenantProvider.currentTenant().tenantId != session.tenantId) {
            throw PresignedUploadNotFoundException(session.id)
        }
        val principal = authorization.requireCurrentUser()
        if (principal.id.value != session.ownerId) throw PresignedUploadNotFoundException(session.id)
        authorization.requireActionAs(
            session.tenantId,
            session.id,
            RESOURCE_TYPE,
            action,
            principal,
        )
    }

    private fun requireExactDeclaration(
        session: PresignedUploadSession,
        command: StartPresignedUploadCommand,
        keyDigest: String,
        expectedDeclarationDigest: String,
    ) {
        if (
            session.idempotencyKeyDigest != keyDigest ||
            session.declarationDigest != expectedDeclarationDigest ||
            session.fileName != command.fileName ||
            session.contentLength != command.contentLength ||
            session.contentType != command.contentType ||
            session.contentHash != command.contentHash ||
            session.checksum != command.checksum ||
            session.metadata != command.metadata ||
            session.grantDurationMillis != command.grantDuration.toMillis()
        ) {
            throw PresignedUploadStateException(
                "Presigned upload caller key is already bound to a different declaration.",
            )
        }
    }

    private fun validateFinalization(
        session: PresignedUploadSession,
        finalization: PresignedUploadFinalization,
    ) {
        require(
            finalization.tenantId == session.tenantId &&
                finalization.bindingId == session.id &&
                finalization.sourceLocation == session.stagingLocation &&
                finalization.storedObject.location != session.stagingLocation,
        ) {
            "Storage finalization authority must bind a distinct immutable location."
        }
        require(
            finalization.storedObject.contentLength == session.contentLength &&
                finalization.storedObject.contentType == session.contentType &&
                finalization.storedObject.contentHash == session.contentHash &&
                finalization.checksum == session.checksum &&
                finalization.metadata == session.metadata,
        ) {
            "Storage finalization evidence does not match the durable presigned upload declaration."
        }
    }

    private fun monotonicNow(session: PresignedUploadSession, operation: String): Long = clock.millis().also { now ->
        requireClockOrder(session.updatedTime, now, operation)
    }

    private fun <T> inTransaction(action: () -> T): T = transaction.execute(action)

    private companion object {
        const val RESOURCE_TYPE = "FILE_OBJECT"
        const val START_ACTION = "file:upload"
        const val COMPLETE_ACTION = "file:upload:complete"
        const val READ_ACTION = "file:upload:read"
        const val CANCEL_ACTION = "file:upload:cancel"
        const val CLAIM_TOKEN_DOMAIN = "flowweft-presigned-upload-claim-token-v1"
        const val SESSION_EXPIRED_ERROR = "PresignedUploadSessionExpired"

        fun requirePositiveDuration(value: Duration, label: String) {
            require(!value.isNegative && !value.isZero && value.toMillis() > 0) {
                "$label must be at least one millisecond."
            }
        }
    }
}

internal object DirectPresignedUploadApplicationTransaction : ApplicationTransaction {
    override fun <T> execute(action: () -> T): T = action()
}

internal fun copyPresignedUploadSession(
    source: PresignedUploadSession,
    status: PresignedUploadSessionStatus = source.status,
    version: Long = source.version,
    claimTime: Long? = source.claimTime,
    claimToken: String? = source.claimToken,
    claimExpiresAt: Long? = source.claimExpiresAt,
    finalization: PresignedUploadFinalization? = source.finalization,
    lastError: String? = source.lastError,
    completedTime: Long? = source.completedTime,
    cancelledTime: Long? = source.cancelledTime,
    cleanupTime: Long? = source.cleanupTime,
    updatedTime: Long = source.updatedTime,
): PresignedUploadSession = PresignedUploadSession(
    id = source.id,
    tenantId = source.tenantId,
    ownerId = source.ownerId,
    fileName = source.fileName,
    contentLength = source.contentLength,
    contentType = source.contentType,
    contentHash = source.contentHash,
    checksum = source.checksum,
    metadata = source.metadata,
    storageLocation = source.stagingLocation,
    grantExpiresAt = source.grantExpiresAt,
    sessionExpiresAt = source.sessionExpiresAt,
    status = status,
    version = version,
    claimTime = claimTime,
    finalization = finalization,
    lastError = lastError,
    createdTime = source.createdTime,
    updatedTime = updatedTime,
    idempotencyKeyDigest = source.idempotencyKeyDigest,
    declarationDigest = source.declarationDigest,
    grantDurationMillis = source.grantDurationMillis,
    requiredHeaders = source.requiredHeaders,
    claimToken = claimToken,
    claimExpiresAt = claimExpiresAt,
    completedTime = completedTime,
    cancelledTime = cancelledTime,
    cleanupTime = cleanupTime,
)

internal fun statusResult(session: PresignedUploadSession): PresignedUploadStatusResult =
    PresignedUploadStatusResult(
        sessionId = session.id,
        fileName = session.fileName,
        contentLength = session.contentLength,
        contentType = session.contentType,
        contentHash = session.contentHash,
        status = session.status,
        grantExpiresAt = session.grantExpiresAt,
        sessionExpiresAt = session.sessionExpiresAt,
        createdTime = session.createdTime,
        updatedTime = session.updatedTime,
        completedTime = session.completedTime,
        cancelledTime = session.cancelledTime,
        cleanupTime = session.cleanupTime,
    )

private fun ownerScopedKeyDigest(
    tenantId: Identifier,
    principal: UserIdentity,
    callerKey: String?,
    preallocatedSessionId: Identifier?,
): String = RequestFingerprint.sha256(
    "flowweft-presigned-upload-idempotency-key-v1",
    tenantId.value,
    principal.id.value,
    if (callerKey == null) "compatibility-new-request" else "caller-key",
    callerKey ?: requireNotNull(preallocatedSessionId).value,
)

private fun declarationDigest(
    tenantId: Identifier,
    principal: UserIdentity,
    command: StartPresignedUploadCommand,
): String {
    val components = ArrayList<String?>(16 + command.metadata.size * 2)
    components += "flowweft-presigned-upload-declaration-v1"
    components += tenantId.value
    components += principal.id.value
    components += command.fileName
    components += command.contentLength.toString()
    components += command.contentType
    components += command.contentHash
    components += command.checksum.algorithm
    components += command.checksum.value
    components += command.grantDuration.toMillis().toString()
    command.metadata.toSortedMap().forEach { (key, value) ->
        components += key
        components += value
    }
    return RequestFingerprint.sha256(*components.toTypedArray())
}

private fun grantResult(
    sessionId: Identifier,
    grant: PresignedUploadGrant,
    created: Boolean,
): PresignedUploadGrantResult = PresignedUploadGrantResult(
    sessionId = sessionId,
    uploadUri = grant.uploadUri,
    requiredHeaders = grant.requiredHeaders,
    expiresAt = grant.expiresAt,
    created = created,
)

private fun sameReleasedClaim(
    actual: PresignedUploadSession?,
    expected: PresignedUploadSession,
): Boolean = actual != null &&
    samePresignedUploadAuthority(actual, expected) &&
    actual.version == expected.version &&
    actual.status == expected.status &&
    actual.claimTime == null &&
    actual.claimToken == null &&
    actual.claimExpiresAt == null

private fun samePresignedUploadAuthority(
    actual: PresignedUploadSession,
    expected: PresignedUploadSession,
): Boolean =
    actual.id == expected.id &&
        actual.tenantId == expected.tenantId &&
        actual.ownerId == expected.ownerId &&
        actual.idempotencyKeyDigest == expected.idempotencyKeyDigest &&
        actual.declarationDigest == expected.declarationDigest &&
        actual.fileName == expected.fileName &&
        actual.contentLength == expected.contentLength &&
        actual.contentType == expected.contentType &&
        actual.contentHash == expected.contentHash &&
        actual.checksum == expected.checksum &&
        actual.metadata == expected.metadata &&
        actual.stagingLocation == expected.stagingLocation &&
        actual.requiredHeaders == expected.requiredHeaders &&
        actual.grantDurationMillis == expected.grantDurationMillis &&
        actual.grantExpiresAt == expected.grantExpiresAt &&
        actual.sessionExpiresAt == expected.sessionExpiresAt &&
        actual.createdTime == expected.createdTime

private fun sameFinalization(
    actual: PresignedUploadFinalization?,
    expected: PresignedUploadFinalization,
): Boolean = actual != null &&
    actual.tenantId == expected.tenantId &&
    actual.bindingId == expected.bindingId &&
    actual.sourceLocation == expected.sourceLocation &&
    actual.storedObject.location == expected.storedObject.location &&
    actual.storedObject.contentLength == expected.storedObject.contentLength &&
    actual.storedObject.contentType == expected.storedObject.contentType &&
    actual.storedObject.contentHash == expected.storedObject.contentHash &&
    actual.revision == expected.revision &&
    actual.checksum == expected.checksum &&
    actual.metadata == expected.metadata

private fun safeError(failure: Throwable): String =
    failure.javaClass.simpleName.takeIf(String::isNotBlank)?.take(256) ?: "StorageFailure"

private fun requireClockOrder(previous: Long, current: Long, operation: String) {
    if (current < previous) {
        throw PresignedUploadStateException("Clock moved backwards while $operation.")
    }
}

private class ClaimAttempt(
    val claimed: PresignedUploadSession?,
    val completed: PresignedUploadFinalization?,
) {
    init {
        require((claimed == null) != (completed == null)) { "A claim attempt must have exactly one outcome." }
    }
}
