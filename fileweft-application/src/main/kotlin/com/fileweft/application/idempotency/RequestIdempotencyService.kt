package com.fileweft.application.idempotency

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import java.time.Clock

fun interface IdempotentCommand<T> {
    fun execute(): IdempotentCommandResult<T>
}

fun interface IdempotencyReplayMapper<T> {
    fun map(result: IdempotencyResult): T
}

class IdempotentCommandResult<T>(
    val value: T,
    val idempotencyResult: IdempotencyResult,
)

class IdempotentExecution<T>(
    val value: T,
    val replayed: Boolean,
)

/**
 * Coordinates durable replay without wrapping external preparation in a
 * database transaction. Callers must authenticate, authorize, and validate
 * catalog visibility before [findCompleted], then finish all remote policy
 * preparation before [execute]. The command passed to [execute] must contain
 * local repository work only; claim, business state, audit/outbox, and result
 * completion are committed by the same final transaction.
 */
class RequestIdempotencyService(
    private val repository: RequestIdempotencyRepository,
    private val transaction: ApplicationTransaction,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
) {
    fun findCompleted(request: RequestIdempotency): IdempotencyResult? = transaction.execute {
        val record = repository.findByKeyDigest(request.tenantId, request.keyDigest) ?: return@execute null
        validateStoredBinding(request, record)
        when (record.status) {
            RequestIdempotencyStatus.COMPLETED -> record.result
                ?: throw IdempotencyStoreException("Completed idempotency record is missing its result.")
            RequestIdempotencyStatus.IN_PROGRESS -> throw IdempotencyInProgressException()
        }
    }

    fun <T> execute(
        request: RequestIdempotency,
        replayMapper: IdempotencyReplayMapper<T>,
        command: IdempotentCommand<T>,
    ): IdempotentExecution<T> = transaction.execute {
        val candidateId = identifierGenerator.nextId()
        val claimedAt = nonNegativeNow()
        val claim = repository.claim(request, candidateId, claimedAt)
        validateStoredBinding(request, claim.record)
        if (claim.acquired) {
            if (
                claim.record.id != candidateId ||
                claim.record.status != RequestIdempotencyStatus.IN_PROGRESS ||
                claim.record.createdTime != claimedAt ||
                claim.record.updatedTime != claimedAt
            ) {
                throw IdempotencyStoreException("Idempotency repository returned an invalid acquired claim.")
            }
            executeClaimed(request, claim.record, command)
        } else {
            when (claim.record.status) {
                RequestIdempotencyStatus.COMPLETED -> {
                    val result = claim.record.result
                        ?: throw IdempotencyStoreException("Completed idempotency record is missing its result.")
                    IdempotentExecution(replayMapper.map(result), replayed = true)
                }
                RequestIdempotencyStatus.IN_PROGRESS -> throw IdempotencyInProgressException()
            }
        }
    }

    private fun <T> executeClaimed(
        request: RequestIdempotency,
        record: RequestIdempotencyRecord,
        command: IdempotentCommand<T>,
    ): IdempotentExecution<T> {
        val fresh = command.execute()
        val completedAt = maxOf(nonNegativeNow(), record.createdTime)
        val completed = repository.complete(
            recordId = record.id,
            tenantId = request.tenantId,
            keyDigest = request.keyDigest,
            result = fresh.idempotencyResult,
            completedAt = completedAt,
        )
        validateStoredBinding(request, completed)
        if (
            completed.id != record.id ||
            completed.createdTime != record.createdTime ||
            completed.status != RequestIdempotencyStatus.COMPLETED ||
            completed.completedTime != completedAt ||
            completed.updatedTime != completedAt ||
            completed.result?.let { persisted -> sameResult(persisted, fresh.idempotencyResult) } != true
        ) {
            throw IdempotencyStoreException("Idempotency repository returned an invalid completed record.")
        }
        return IdempotentExecution(fresh.value, replayed = false)
    }

    private fun validateStoredBinding(request: RequestIdempotency, record: RequestIdempotencyRecord) {
        if (record.tenantId != request.tenantId || record.keyDigest != request.keyDigest) {
            throw IdempotencyStoreException("Idempotency repository returned a record outside the requested key scope.")
        }
        if (
            record.operatorId != request.operatorId ||
            record.action != request.action ||
            record.resourceType != request.resourceType ||
            record.resourceId != request.resourceId ||
            record.subresourceId != request.subresourceId ||
            record.requestFingerprint != request.requestFingerprint
        ) {
            throw IdempotencyKeyConflictException()
        }
    }

    private fun nonNegativeNow(): Long {
        val now = clock.millis()
        if (now < 0) {
            throw IdempotencyStoreException("System clock returned an invalid idempotency timestamp.")
        }
        return now
    }
}
