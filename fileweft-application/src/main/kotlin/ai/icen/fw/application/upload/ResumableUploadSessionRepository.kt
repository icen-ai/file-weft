package ai.icen.fw.application.upload

import ai.icen.fw.core.id.Identifier

/** Persistence port for tenant-scoped resumable upload sessions and acknowledged parts. */
interface ResumableUploadSessionRepository {
    fun save(session: ResumableUploadSession)

    fun findById(tenantId: Identifier, sessionId: Identifier): ResumableUploadSession?

    fun findByIdempotencyKey(tenantId: Identifier, idempotencyKey: String): ResumableUploadSession?

    fun findParts(tenantId: Identifier, sessionId: Identifier): List<ResumableUploadPart>

    /**
     * Persists one acknowledgement only while its session is ACTIVE and unexpired.
     *
     * The session eligibility check, acknowledgement upsert, and advancement of the session's
     * observable [ResumableUploadSession.updatedTime] to [ResumableUploadPart.updatedTime] must be
     * atomic. This operation must serialize with [claimForCompletion], so a late part can neither
     * enter COMPLETING/COMPLETED nor escape stable checkpoint reads.
     */
    fun savePart(part: ResumableUploadPart)

    /** Atomically transitions an active, unexpired session to COMPLETING. */
    fun claimForCompletion(tenantId: Identifier, sessionId: Identifier, now: Long): ResumableUploadSession?

    /** Returns a pre-Storage completion validation failure to ACTIVE. */
    fun reactivateAfterCompletionFailure(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean

    fun markFailed(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean

    fun markCompleted(tenantId: Identifier, sessionId: Identifier, completedAt: Long): Boolean

    /**
     * Claims a session that has no in-flight completion for abort or expiry cleanup.
     * A COMPLETING session must be reconciled separately: deleting its object could race a successful completion.
     * The transition must preserve [ResumableUploadSession.lastError] so isolation markers survive cleanup.
     */
    fun claimForAbort(tenantId: Identifier, sessionId: Identifier, updatedAt: Long): ResumableUploadSession?

    /**
     * Finalizes an ABORTING session without clearing [ResumableUploadSession.lastError].
     * Implementations must preserve that field because it can contain an application isolation marker
     * that must remain effective across ABORTED and EXPIRED terminal transitions.
     */
    fun markAborted(tenantId: Identifier, sessionId: Identifier, expired: Boolean, updatedAt: Long): Boolean

    /** Includes safely abortable expired sessions across all tenants, excluding an in-flight completion. */
    fun findExpired(now: Long, limit: Int): List<ResumableUploadSession>

    /** Read-only operational query for expired completions that must never be deleted automatically. */
    fun findExpiredCompleting(now: Long, limit: Int): List<ResumableUploadSession>

    /** Tenant-scoped form for an administrator who is not a platform-wide operator. */
    fun findExpiredCompleting(tenantId: Identifier, now: Long, limit: Int): List<ResumableUploadSession>
}

/**
 * Additive capability for recovering from a Storage adapter's definitive multipart-completion rejection.
 *
 * Implementations must atomically transition only COMPLETING to ACTIVE, renew the session to the supplied
 * future expiration, and delete every acknowledged part for that session before returning `true`. Clearing
 * the checkpoint forces the client to upload and persist fresh acknowledgements instead of replaying an
 * ETag that Storage has already superseded, while the renewed deadline guarantees a usable retry window.
 */
interface CompletionRejectionResettableResumableUploadSessionRepository : ResumableUploadSessionRepository {
    fun resetAfterCompletionRejection(
        tenantId: Identifier,
        sessionId: Identifier,
        message: String,
        expiresAt: Long,
        updatedAt: Long,
    ): Boolean
}

/**
 * Optional additive capability for repositories that can enforce session ownership in the query itself.
 *
 * The original [ResumableUploadSessionRepository] methods remain unchanged for binary compatibility.
 * Application services still fail closed when a legacy repository does not implement this capability:
 * they perform the tenant-scoped read and accept only an exact, non-null owner match.
 */
interface OwnerScopedResumableUploadSessionRepository : ResumableUploadSessionRepository {
    fun findById(tenantId: Identifier, ownerId: String, sessionId: Identifier): ResumableUploadSession?

    fun findByIdempotencyKey(
        tenantId: Identifier,
        ownerId: String,
        idempotencyKey: String,
    ): ResumableUploadSession?
}

/**
 * Additive capability for repositories that can durably and monotonically fence an unusable
 * upload session. Keeping this separate preserves the released repository ABI while allowing
 * new upload creation to fail fast when safe ownership quarantine is unavailable.
 */
interface QuarantinableResumableUploadSessionRepository : ResumableUploadSessionRepository {
    /** Transitions only an ABORTING session to QUARANTINED and records a stable diagnostic. */
    fun markQuarantined(
        tenantId: Identifier,
        sessionId: Identifier,
        message: String,
        updatedAt: Long,
    ): Boolean
}

/**
 * Additive capability for publishing a newly persisted, owner-verified session. New sessions are
 * first stored as an invisible ABORTING staging row; only this guarded transition may expose them
 * as ACTIVE after all authoritative repository views agree.
 */
interface StagedResumableUploadSessionRepository : QuarantinableResumableUploadSessionRepository {
    fun activateStaged(
        tenantId: Identifier,
        sessionId: Identifier,
        expectedOwnerId: String,
        stagingMarker: String,
        activatedAt: Long,
    ): Boolean
}
