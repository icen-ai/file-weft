package ai.icen.fw.application.upload

import ai.icen.fw.application.transaction.ApplicationTransactionBoundary

internal class ResumableUploadCleanupService(
    private val context: ResumableUploadContext,
    private val reconciler: ResumableUploadReconciler,
) {
    /** System-only maintenance operation. It never needs a request tenant or a current user. */
    fun cleanupExpired(limit: Int): ExpiredResumableUploadCleanupResult {
        ApplicationTransactionBoundary.requireInactive(context.transaction)
        require(limit in 1..MAX_CLEANUP_LIMIT) {
            "Upload cleanup limit must be between 1 and $MAX_CLEANUP_LIMIT."
        }
        val now = context.clock.millis()
        val candidates = context.transaction.execute { context.sessions.findExpired(now, limit) }
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
                    reconciler.quarantineClaimedSession(
                        original = candidate,
                        expectedOwnerId = candidate.ownerId,
                        reason = ResumableUploadStateException(CLEANUP_QUARANTINE_MESSAGE),
                        requiredCleanupNow = now,
                    )
                } else {
                    context.transaction.execute {
                        check(isEligibleCleanupCandidate(candidate, now)) {
                            "Upload cleanup repository returned an ineligible session."
                        }
                        context.sessions.claimForAbort(candidate.tenantId, candidate.id, now)?.also { claimedSession ->
                            context.requireClaimedSnapshot(
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
                context.abortStorage(context.storageUpload(claimed), null)
                if (mustRemainQuarantined) {
                    expired++
                } else {
                    val marked = context.transaction.execute {
                        context.sessions.markAborted(
                            claimed.tenantId,
                            claimed.id,
                            expired = true,
                            updatedAt = context.clock.millis(),
                        )
                    }
                    if (marked) expired++
                }
            } catch (failure: Throwable) {
                if (!mustRemainQuarantined) context.safeMarkFailed(claimed, failure)
                failed++
            }
        }
        return ExpiredResumableUploadCleanupResult(candidates.size, expired, failed)
    }

    /** Returns a redacted, cross-tenant maintenance view without destructive reconciliation. */
    fun inspectStalledCompletionsAsSystem(limit: Int): List<StalledResumableUploadSession> =
        inspectStalledCompletions(limit) { now -> context.sessions.findExpiredCompleting(now, limit) }

    /** Tenant-safe maintenance view for an authorized tenant administrator. */
    fun inspectStalledCompletions(limit: Int): List<StalledResumableUploadSession> {
        requireMaintenanceLimit(limit)
        val tenantId = context.tenantProvider.currentTenant().tenantId
        context.requireMaintenanceAction(tenantId)
        return context.transaction.execute {
            context.sessions.findExpiredCompleting(tenantId, context.clock.millis(), limit).map(::stalledView)
        }
    }

    private fun inspectStalledCompletions(
        limit: Int,
        query: (Long) -> List<ResumableUploadSession>,
    ): List<StalledResumableUploadSession> {
        requireMaintenanceLimit(limit)
        return context.transaction.execute { query(context.clock.millis()).map(::stalledView) }
    }

    private fun requireMaintenanceLimit(limit: Int) {
        require(limit in 1..MAX_CLEANUP_LIMIT) {
            "Upload maintenance limit must be between 1 and $MAX_CLEANUP_LIMIT."
        }
    }

    private fun isEligibleCleanupCandidate(session: ResumableUploadSession, now: Long): Boolean =
        session.expiresAt <= now &&
            (
                session.status == ResumableUploadSessionStatus.ACTIVE ||
                    session.status == ResumableUploadSessionStatus.FAILED ||
                    session.status == ResumableUploadSessionStatus.ABORTING
                )

    private fun stalledView(session: ResumableUploadSession): StalledResumableUploadSession =
        StalledResumableUploadSession(
            id = session.id,
            tenantId = session.tenantId,
            fileName = session.fileName,
            contentLength = session.contentLength,
            expiresAt = session.expiresAt,
            updatedTime = session.updatedTime,
            lastError = session.lastError,
        )

}
