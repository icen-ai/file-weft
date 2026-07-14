package ai.icen.fw.application.upload

import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.core.id.Identifier

internal class ResumableUploadAbortHandler(
    private val context: ResumableUploadContext,
    private val reconciler: ResumableUploadReconciler,
) {
    fun abort(sessionId: Identifier): ResumableUploadSession {
        ApplicationTransactionBoundary.requireInactive(context.transaction)
        val requestIdentity = context.currentRequestIdentity()
        return abort(sessionId, requestIdentity)
    }

    /** Aborts a session and returns its final checkpoint without taking a second trusted identity snapshot. */
    fun abortAndInspect(sessionId: Identifier): ResumableUploadSessionView {
        ApplicationTransactionBoundary.requireInactive(context.transaction)
        val requestIdentity = context.currentRequestIdentity()
        val session = abort(sessionId, requestIdentity)
        return try {
            reconciler.stableOwnedView(session.tenantId, requestIdentity.ownerId, session.id)
        } catch (_: RuntimeException) {
            // The abort is already durable; a secondary checkpoint read must not make it appear to have failed.
            ResumableUploadSessionView(session, emptyList())
        }
    }

    private fun abort(
        sessionId: Identifier,
        requestIdentity: ResumableUploadRequestIdentity,
    ): ResumableUploadSession {
        val existing = context.requiredOwnedSession(requestIdentity.tenantId, requestIdentity.ownerId, sessionId)
        context.authorize(existing, requestIdentity.user)
        if (existing.status.isTerminal()) return existing
        val claimed = context.transaction.execute {
            context.sessions.claimForAbort(existing.tenantId, existing.id, context.clock.millis())?.also { candidate ->
                context.requireClaimedSnapshot(
                    claimed = candidate,
                    original = existing,
                    expectedOwnerId = requestIdentity.ownerId,
                    expectedStatus = ResumableUploadSessionStatus.ABORTING,
                )
            }
        } ?: return abortClaimFailure(existing, requestIdentity.ownerId)
        try {
            context.abortStorage(context.storageUpload(claimed), null)
        } catch (failure: Throwable) {
            context.safeMarkFailed(claimed, failure)
            throw ResumableUploadUnavailableException(failure)
        }
        try {
            return context.transaction.execute {
                require(
                    context.sessions.markAborted(
                        claimed.tenantId,
                        claimed.id,
                        expired = false,
                        updatedAt = context.clock.millis(),
                    ),
                ) { "Upload session abort state was changed concurrently." }
                context.requiredOwnedSessionInTransaction(claimed.tenantId, requestIdentity.ownerId, claimed.id)
            }
        } catch (failure: Throwable) {
            context.safeMarkFailed(claimed, failure)
            throw context.outcomeUnknown(failure, emptyList())
        }
    }

    private fun abortClaimFailure(
        original: ResumableUploadSession,
        ownerId: String,
    ): ResumableUploadSession {
        val current = context.requiredOwnedSession(original.tenantId, ownerId, original.id)
        if (current.status.isTerminal()) return current
        throw ResumableUploadStateException(
            "Upload session ${current.id.value} cannot be aborted from ${current.status.name}; " +
                "its final object state may still be changing.",
        )
    }
}
