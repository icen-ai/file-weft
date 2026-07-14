package ai.icen.fw.application.upload

import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.storage.MultipartCompletionRejectedException

/** Owns completion claiming, remote completion and definitive-rejection recovery. */
internal class ResumableUploadCompletionHandler(
    private val context: ResumableUploadContext,
    private val reconciler: ResumableUploadReconciler,
) {
    fun complete(sessionId: Identifier): UploadFileResult {
        ApplicationTransactionBoundary.requireInactive(context.transaction)
        val requestIdentity = context.currentRequestIdentity()
        return complete(sessionId, requestIdentity, requireCompletionResetCapability = false)
    }

    /**
     * Completes an upload and obtains its public receipt timestamp without consulting a mutable identity provider twice.
     * Failure of the post-commit timestamp read never changes a successful completion into an apparent command failure.
     */
    fun completeAndInspect(sessionId: Identifier): ResumableUploadCompletionResult {
        ApplicationTransactionBoundary.requireInactive(context.transaction)
        val requestIdentity = context.currentRequestIdentity()
        val result = complete(sessionId, requestIdentity, requireCompletionResetCapability = true)
        val completedAt = try {
            reconciler.stableOwnedView(requestIdentity.tenantId, requestIdentity.ownerId, sessionId).session
                .takeIf { session -> session.status == ResumableUploadSessionStatus.COMPLETED }
                ?.completedAt
        } catch (_: RuntimeException) {
            null
        }
        return ResumableUploadCompletionResult(result, completedAt)
    }

    private fun complete(
        sessionId: Identifier,
        requestIdentity: ResumableUploadRequestIdentity,
        requireCompletionResetCapability: Boolean,
    ): UploadFileResult {
        val existing = context.requiredOwnedSession(requestIdentity.tenantId, requestIdentity.ownerId, sessionId)
        context.authorize(existing, requestIdentity.user)
        if (existing.status == ResumableUploadSessionStatus.COMPLETED) return reconciler.completedResult(existing)
        if (existing.status == ResumableUploadSessionStatus.COMPLETING) return reconciler.reconcileCompleting(existing)
        if (requireCompletionResetCapability) requireCompletionRejectionResetCapability()
        context.requireActive(existing, context.clock.millis())
        context.validateParts(
            existing,
            context.transaction.execute { context.sessions.findParts(existing.tenantId, existing.id) },
        )
        val session = context.transaction.execute {
            context.sessions.claimForCompletion(existing.tenantId, existing.id, context.clock.millis())?.also { claimed ->
                context.requireClaimedSnapshot(
                    claimed = claimed,
                    original = existing,
                    expectedOwnerId = requestIdentity.ownerId,
                    expectedStatus = ResumableUploadSessionStatus.COMPLETING,
                )
            }
        } ?: return completionClaimFailure(existing, requestIdentity.ownerId)
        val parts = context.transaction.execute { context.sessions.findParts(session.tenantId, session.id) }
        try {
            context.validateParts(session, parts)
        } catch (failure: Throwable) {
            context.transaction.execute {
                context.sessions.reactivateAfterCompletionFailure(
                    session.tenantId,
                    session.id,
                    context.completionFailureMessage(failure),
                    context.clock.millis(),
                )
            }
            throw failure
        }
        val stored = try {
            context.storageAdapter.completeMultipartUpload(
                context.storageUpload(session),
                parts.sortedBy { it.partNumber }.map {
                    ai.icen.fw.spi.storage.MultipartPart(it.partNumber, it.eTag)
                },
            )
        } catch (failure: Throwable) {
            val classified = recoverStorageCompletionFailure(session, failure)
            context.recordMetric(FileWeftMetric.UPLOAD_FAILURE, session.tenantId.value)
            throw classified
        }
        try {
            context.validateStored(session, stored)
            val result = reconciler.persistCompleted(session, stored)
            context.recordMetric(FileWeftMetric.UPLOAD_COUNT, session.tenantId.value)
            return result
        } catch (failure: Throwable) {
            return reconciler.reconcileFailedCompletion(session, stored, failure)
        }
    }

    private fun completionClaimFailure(original: ResumableUploadSession, ownerId: String): UploadFileResult {
        val current = context.requiredOwnedSession(original.tenantId, ownerId, original.id)
        if (current.status == ResumableUploadSessionStatus.COMPLETED) return reconciler.completedResult(current)
        throw ResumableUploadStateException(
            "Upload session ${current.id.value} cannot be completed from ${current.status.name}.",
        )
    }

    private fun recoverStorageCompletionFailure(session: ResumableUploadSession, failure: Throwable): Throwable {
        if (failure is MultipartCompletionRejectedException) {
            return recoverRejectedStorageCompletion(session, failure)
        }
        try {
            context.storageAdapter.exists(session.storageLocation)
        } catch (existenceFailure: Throwable) {
            return context.outcomeUnknown(failure, listOf(existenceFailure))
        }
        // A transport exception does not prove that a remote multipart completion stopped. Even an
        // immediate absence probe may race with a request still executing behind a timed-out client.
        // Keep the COMPLETING fence for every unclassified Storage failure; a later stale retry may
        // reconcile a visible final object, but must never reactivate based on one exists=false result.
        return context.outcomeUnknown(failure, emptyList())
    }

    private fun recoverRejectedStorageCompletion(
        session: ResumableUploadSession,
        failure: MultipartCompletionRejectedException,
    ): Throwable {
        val resettable = context.sessions as? CompletionRejectionResettableResumableUploadSessionRepository
            ?: return context.outcomeUnknown(failure, listOf(context.completionRejectionResetUnavailable()))
        val reset = try {
            context.transaction.execute {
                val resetAt = context.clock.millis()
                resettable.resetAfterCompletionRejection(
                    session.tenantId,
                    session.id,
                    context.completionFailureMessage(failure),
                    Math.addExact(resetAt, context.sessionTtl.toMillis()),
                    resetAt,
                )
            }
        } catch (stateFailure: Throwable) {
            return context.outcomeUnknown(failure, listOf(stateFailure))
        }
        if (!reset) return context.outcomeUnknown(failure, listOf(context.completionReconciliationMismatch()))
        return ResumableUploadStateException(
            "Multipart completion was rejected; upload parts must be acknowledged again.",
            failure,
        )
    }

    private fun requireCompletionRejectionResetCapability() {
        if (context.sessions !is CompletionRejectionResettableResumableUploadSessionRepository) {
            throw ResumableUploadUnavailableException(
                ResumableUploadStateException(COMPLETION_REJECTION_RESET_CAPABILITY_MESSAGE),
            )
        }
    }
}
