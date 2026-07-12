package ai.icen.fw.application.upload

import ai.icen.fw.core.id.Identifier

/** Persistence port for tenant-scoped resumable upload sessions and acknowledged parts. */
interface ResumableUploadSessionRepository {
    fun save(session: ResumableUploadSession)

    fun findById(tenantId: Identifier, sessionId: Identifier): ResumableUploadSession?

    fun findByIdempotencyKey(tenantId: Identifier, idempotencyKey: String): ResumableUploadSession?

    fun findParts(tenantId: Identifier, sessionId: Identifier): List<ResumableUploadPart>

    fun savePart(part: ResumableUploadPart)

    /** Atomically transitions an active, unexpired session to COMPLETING. */
    fun claimForCompletion(tenantId: Identifier, sessionId: Identifier, now: Long): ResumableUploadSession?

    /** Returns a failed storage completion to ACTIVE only when no final object was observed. */
    fun reactivateAfterCompletionFailure(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean

    fun markFailed(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean

    fun markCompleted(tenantId: Identifier, sessionId: Identifier, completedAt: Long): Boolean

    /**
     * Claims a session that has no in-flight completion for abort or expiry cleanup.
     * A COMPLETING session must be reconciled separately: deleting its object could race a successful completion.
     */
    fun claimForAbort(tenantId: Identifier, sessionId: Identifier, updatedAt: Long): ResumableUploadSession?

    fun markAborted(tenantId: Identifier, sessionId: Identifier, expired: Boolean, updatedAt: Long): Boolean

    /** Includes safely abortable expired sessions across all tenants, excluding an in-flight completion. */
    fun findExpired(now: Long, limit: Int): List<ResumableUploadSession>

    /** Read-only operational query for expired completions that must never be deleted automatically. */
    fun findExpiredCompleting(now: Long, limit: Int): List<ResumableUploadSession>

    /** Tenant-scoped form for an administrator who is not a platform-wide operator. */
    fun findExpiredCompleting(tenantId: Identifier, now: Long, limit: Int): List<ResumableUploadSession>
}
