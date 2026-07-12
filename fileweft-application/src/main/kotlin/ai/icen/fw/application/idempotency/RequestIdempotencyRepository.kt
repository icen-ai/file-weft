package ai.icen.fw.application.idempotency

import ai.icen.fw.core.id.Identifier

/** Application-owned persistence port for formal request idempotency. */
interface RequestIdempotencyRepository {
    fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord?

    /**
     * Inserts an IN_PROGRESS record or locks and returns the existing tenant/key row.
     * This operation must run at the start of the caller's final local transaction.
     */
    fun claim(
        request: RequestIdempotency,
        newRecordId: Identifier,
        now: Long,
    ): RequestIdempotencyClaim

    /** Completes only the matching IN_PROGRESS row and returns its persisted representation. */
    fun complete(
        recordId: Identifier,
        tenantId: Identifier,
        keyDigest: String,
        result: IdempotencyResult,
        completedAt: Long,
    ): RequestIdempotencyRecord
}
