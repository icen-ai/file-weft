package ai.icen.fw.application.upload

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.spi.storage.PresignedUploadCleanupRequest
import ai.icen.fw.spi.storage.PresignedUploadStorageAdapter
import java.time.Clock

/** Recovers abandoned finalize leases without calling external storage. */
class PresignedUploadRecoveryService @JvmOverloads constructor(
    private val repository: PresignedUploadSessionRepository,
    private val clock: Clock,
    private val transaction: ApplicationTransaction = DirectPresignedUploadApplicationTransaction,
) {
    fun recover(limit: Int = DEFAULT_MAINTENANCE_BATCH_SIZE): PresignedUploadMaintenanceResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        requireMaintenanceLimit(limit)
        val now = clock.millis()
        val candidates = transaction.execute { repository.findRecoveryCandidates(now, limit) }
        var succeeded = 0
        var failed = 0
        var skipped = 0
        candidates.forEach { candidate ->
            if (
                candidate.status != PresignedUploadSessionStatus.FINALIZING ||
                candidate.claimToken == null ||
                candidate.claimExpiresAt == null ||
                candidate.claimExpiresAt > now ||
                candidate.sessionExpiresAt <= now ||
                now < candidate.updatedTime
            ) {
                skipped += 1
                return@forEach
            }
            val recovered = copyPresignedUploadSession(
                source = candidate,
                status = PresignedUploadSessionStatus.READY,
                version = Math.addExact(candidate.version, 1),
                claimTime = null,
                claimToken = null,
                claimExpiresAt = null,
                finalization = null,
                lastError = CLAIM_LEASE_EXPIRED_ERROR,
                updatedTime = now,
            )
            try {
                val accepted = try {
                    transaction.execute {
                        repository.compareAndSet(
                            candidate.tenantId,
                            candidate.id,
                            candidate.version,
                            candidate.claimToken,
                            recovered,
                        )
                    }
                } catch (_: ApplicationTransactionOutcomeUnknownException) {
                    false
                }
                if (accepted || sameRecovered(repositoryRead(candidate), recovered)) {
                    succeeded += 1
                } else {
                    skipped += 1
                }
            } catch (_: Exception) {
                failed += 1
            }
        }
        return PresignedUploadMaintenanceResult(candidates.size, succeeded, failed, skipped)
    }

    private fun repositoryRead(candidate: PresignedUploadSession): PresignedUploadSession? =
        transaction.execute { repository.findById(candidate.tenantId, candidate.id) }
}

/**
 * Expires abandoned sessions and idempotently removes only their staging
 * authority after the original signed PUT deadline has passed.
 */
class PresignedUploadCleanupService @JvmOverloads constructor(
    private val repository: PresignedUploadSessionRepository,
    private val storageAdapter: PresignedUploadStorageAdapter,
    private val clock: Clock,
    private val transaction: ApplicationTransaction = DirectPresignedUploadApplicationTransaction,
) {
    fun cleanup(limit: Int = DEFAULT_MAINTENANCE_BATCH_SIZE): PresignedUploadMaintenanceResult {
        ApplicationTransactionBoundary.requireInactive(transaction)
        requireMaintenanceLimit(limit)
        val scanTime = clock.millis()
        val candidates = transaction.execute { repository.findCleanupCandidates(scanTime, limit) }
        var succeeded = 0
        var failed = 0
        var skipped = 0
        candidates.forEach { candidate ->
            try {
                val terminal = claimCleanupCandidate(candidate, scanTime)
                if (terminal == null) {
                    skipped += 1
                    return@forEach
                }
                storageAdapter.cleanupUpload(
                    PresignedUploadCleanupRequest(
                        bindingId = terminal.id,
                        tenantId = terminal.tenantId,
                        location = terminal.stagingLocation,
                    ),
                )
                val cleanedAt = clock.millis()
                if (cleanedAt < scanTime || cleanedAt < terminal.updatedTime) {
                    throw PresignedUploadStateException("Clock moved backwards while recording upload cleanup.")
                }
                val cleaned = copyPresignedUploadSession(
                    source = terminal,
                    version = Math.addExact(terminal.version, 1),
                    lastError = null,
                    cleanupTime = cleanedAt,
                    updatedTime = cleanedAt,
                )
                val accepted = try {
                    transaction.execute {
                        repository.compareAndSet(
                            terminal.tenantId,
                            terminal.id,
                            terminal.version,
                            terminal.claimToken,
                            cleaned,
                        )
                    }
                } catch (_: ApplicationTransactionOutcomeUnknownException) {
                    false
                }
                val durable = if (accepted) cleaned else repositoryRead(terminal)
                if (
                    durable != null &&
                    sameMaintenanceAuthority(durable, terminal) &&
                    durable.status in CLEANUP_TERMINAL_STATUSES &&
                    durable.version == cleaned.version &&
                    durable.cleanupTime != null
                ) {
                    succeeded += 1
                } else {
                    failed += 1
                }
            } catch (failure: Exception) {
                recordCleanupFailure(candidate, failure)
                failed += 1
            }
        }
        return PresignedUploadMaintenanceResult(candidates.size, succeeded, failed, skipped)
    }

    private fun claimCleanupCandidate(
        candidate: PresignedUploadSession,
        now: Long,
    ): PresignedUploadSession? {
        if (
            candidate.cleanupTime != null ||
            candidate.finalization != null ||
            candidate.status == PresignedUploadSessionStatus.COMPLETED ||
            now < candidate.grantExpiresAt ||
            now < candidate.updatedTime
        ) {
            return null
        }
        if (
            candidate.status == PresignedUploadSessionStatus.CANCELLED ||
            candidate.status == PresignedUploadSessionStatus.EXPIRED
        ) {
            return candidate
        }
        if (candidate.sessionExpiresAt > now) return null
        val expired = copyPresignedUploadSession(
            source = candidate,
            status = PresignedUploadSessionStatus.EXPIRED,
            version = Math.addExact(candidate.version, 1),
            claimTime = null,
            claimToken = null,
            claimExpiresAt = null,
            finalization = null,
            lastError = SESSION_EXPIRED_ERROR,
            updatedTime = now,
        )
        val accepted = try {
            transaction.execute {
                repository.compareAndSet(
                    candidate.tenantId,
                    candidate.id,
                    candidate.version,
                    candidate.claimToken,
                    expired,
                )
            }
        } catch (_: ApplicationTransactionOutcomeUnknownException) {
            false
        }
        if (accepted) return expired
        val durable = repositoryRead(candidate) ?: return null
        return durable.takeIf {
            sameMaintenanceAuthority(it, candidate) &&
                it.cleanupTime == null &&
                it.finalization == null &&
                it.status in CLEANUP_TERMINAL_STATUSES &&
                now >= it.grantExpiresAt
        }
    }

    private fun recordCleanupFailure(candidate: PresignedUploadSession, failure: Throwable) {
        try {
            val durable = repositoryRead(candidate) ?: return
            if (
                !sameMaintenanceAuthority(durable, candidate) ||
                durable.cleanupTime != null ||
                durable.status !in CLEANUP_TERMINAL_STATUSES
            ) return
            val failedAt = clock.millis()
            if (failedAt < durable.updatedTime) return
            val failed = copyPresignedUploadSession(
                source = durable,
                version = Math.addExact(durable.version, 1),
                lastError = maintenanceError(failure),
                updatedTime = failedAt,
            )
            transaction.execute {
                repository.compareAndSet(
                    durable.tenantId,
                    durable.id,
                    durable.version,
                    durable.claimToken,
                    failed,
                )
            }
        } catch (_: Exception) {
            // Cleanup remains retryable because cleanup_time was not written.
        }
    }

    private fun repositoryRead(candidate: PresignedUploadSession): PresignedUploadSession? =
        transaction.execute { repository.findById(candidate.tenantId, candidate.id) }
}

private fun sameRecovered(
    actual: PresignedUploadSession?,
    expected: PresignedUploadSession,
): Boolean = actual != null &&
    sameMaintenanceAuthority(actual, expected) &&
    actual.status == PresignedUploadSessionStatus.READY &&
    actual.version == expected.version &&
    actual.claimTime == null &&
    actual.claimToken == null &&
    actual.claimExpiresAt == null

private fun sameMaintenanceAuthority(
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

private fun maintenanceError(failure: Throwable): String =
    failure.javaClass.simpleName.takeIf(String::isNotBlank)?.take(256) ?: "StorageFailure"

private fun requireMaintenanceLimit(limit: Int) {
    require(limit in 1..MAX_MAINTENANCE_BATCH_SIZE) {
        "Presigned upload maintenance limit must be between 1 and $MAX_MAINTENANCE_BATCH_SIZE."
    }
}

private const val DEFAULT_MAINTENANCE_BATCH_SIZE = 100
private const val MAX_MAINTENANCE_BATCH_SIZE = 1_000
private const val CLAIM_LEASE_EXPIRED_ERROR = "PresignedUploadClaimLeaseExpired"
private const val SESSION_EXPIRED_ERROR = "PresignedUploadSessionExpired"
private val CLEANUP_TERMINAL_STATUSES = setOf(
    PresignedUploadSessionStatus.CANCELLED,
    PresignedUploadSessionStatus.EXPIRED,
)
