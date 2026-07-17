package ai.icen.fw.application.retention

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.retention.DeletionAuditEvidence
import ai.icen.fw.domain.retention.SecureDeletionPlan
import ai.icen.fw.domain.retention.SecureDeletionStage
import ai.icen.fw.spi.retention.SecureDeletionProviderReceipt
import ai.icen.fw.spi.retention.SecureDeletionProviderRequest
import ai.icen.fw.spi.retention.SecureDeletionProviderStatus
import ai.icen.fw.spi.retention.SecureDeletionTarget
import java.util.ArrayList
import java.util.Collections

enum class SecureDeletionExecutionStatus {
    PENDING,
    RECONCILING,
    RETRY,
    SUCCEEDED,
    FAILED,
}

/** Durable, redacted evidence for one provider attempt. */
class StoredSecureDeletionReceipt(
    val stage: SecureDeletionStage,
    val idempotencyKey: String,
    val providerReceipt: SecureDeletionProviderReceipt,
    val recordedAt: Long,
) {
    init {
        require(stage == SecureDeletionStage.PURGE_INDEX_PROJECTIONS || stage == SecureDeletionStage.PURGE_OBJECT_STORAGE) {
            "Provider receipts are only valid for external deletion stages."
        }
        require(idempotencyKey.isNotBlank()) { "Stored deletion idempotency key must not be blank." }
        require(recordedAt >= 0) { "Stored deletion receipt time must not be negative." }
        require(providerReceipt.target == targetFor(stage)) {
            "Stored deletion receipt target must match its stage."
        }
    }

    companion object {
        @JvmStatic
        fun targetFor(stage: SecureDeletionStage): SecureDeletionTarget = when (stage) {
            SecureDeletionStage.PURGE_INDEX_PROJECTIONS -> SecureDeletionTarget.INDEX
            SecureDeletionStage.PURGE_OBJECT_STORAGE -> SecureDeletionTarget.OBJECT_STORAGE
            else -> throw IllegalArgumentException("Deletion stage ${stage.name} has no external provider target.")
        }
    }
}

/**
 * Durable progress projection for the leased Outbox handler.
 *
 * The Outbox row owns concurrency. Every mutation of this projection must be
 * performed after locking it and validating the same current Outbox lease.
 */
class SecureDeletionExecution(
    val planId: Identifier,
    val tenantId: Identifier,
    val dispatchEventId: Identifier,
    val tombstoneId: Identifier,
    val decisionEvidenceId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val requestedBy: Identifier,
    val indexIdempotencyKey: String,
    val objectIdempotencyKey: String,
    currentStage: SecureDeletionStage,
    status: SecureDeletionExecutionStatus,
    receipts: List<StoredSecureDeletionReceipt>,
    failureCount: Int,
    lastError: String?,
    val createdAt: Long,
    updatedAt: Long,
) {
    private val mutableReceipts = ArrayList<StoredSecureDeletionReceipt>(receipts)

    var currentStage: SecureDeletionStage = currentStage
        private set

    var status: SecureDeletionExecutionStatus = status
        private set

    val receipts: List<StoredSecureDeletionReceipt>
        get() = Collections.unmodifiableList(mutableReceipts)

    var failureCount: Int = failureCount
        private set

    var lastError: String? = normalizeMessage(lastError)
        private set

    var updatedAt: Long = updatedAt
        private set

    init {
        require(resourceType.isNotBlank()) { "Secure-deletion execution resource type must not be blank." }
        require(resourceRevision >= 0) { "Secure-deletion execution resource revision must not be negative." }
        require(indexIdempotencyKey.isNotBlank()) { "Index deletion idempotency key must not be blank." }
        require(objectIdempotencyKey.isNotBlank()) { "Object deletion idempotency key must not be blank." }
        require(indexIdempotencyKey != objectIdempotencyKey) { "External deletion stages require distinct idempotency keys." }
        require(failureCount >= 0) { "Secure-deletion failure count must not be negative." }
        require(createdAt >= 0 && updatedAt >= createdAt) { "Secure-deletion execution timestamps are inconsistent." }
        require(currentStage in SUPPORTED_PROGRESS_STAGES) { "Secure-deletion execution stage is not resumable." }
        require(status == SecureDeletionExecutionStatus.SUCCEEDED || currentStage != SecureDeletionStage.APPEND_COMPLETION_AUDIT) {
            "Only a successful deletion may reach completion-audit stage."
        }
        require(status != SecureDeletionExecutionStatus.SUCCEEDED || currentStage == SecureDeletionStage.APPEND_COMPLETION_AUDIT) {
            "A successful deletion must have reached completion-audit stage."
        }
        require(mutableReceipts.distinctBy { it.stage }.size == mutableReceipts.size) {
            "Secure-deletion provider receipts must be unique per external stage."
        }
        mutableReceipts.forEach { receipt ->
            require(receipt.idempotencyKey == idempotencyKeyFor(receipt.stage)) {
                "Stored provider receipt idempotency key does not match its deletion plan."
            }
            require(receipt.providerReceipt.requestBindingDigest == requestBindingDigestFor(receipt.stage)) {
                "Stored provider receipt does not bind the exact tenant-scoped deletion request."
            }
        }
    }

    fun matchesDispatch(eventId: Identifier, revision: Long): Boolean =
        dispatchEventId == eventId && resourceRevision == revision

    fun idempotencyKeyFor(stage: SecureDeletionStage): String = when (stage) {
        SecureDeletionStage.PURGE_INDEX_PROJECTIONS -> indexIdempotencyKey
        SecureDeletionStage.PURGE_OBJECT_STORAGE -> objectIdempotencyKey
        else -> throw IllegalArgumentException("Deletion stage ${stage.name} has no provider idempotency key.")
    }

    fun latestReceipt(stage: SecureDeletionStage): StoredSecureDeletionReceipt? =
        mutableReceipts.firstOrNull { it.stage == stage }

    fun requestBindingDigestFor(stage: SecureDeletionStage): String = SecureDeletionProviderRequest.bindingDigest(
        tenantId,
        planId,
        tombstoneId,
        resourceType,
        resourceId,
        resourceRevision,
        StoredSecureDeletionReceipt.targetFor(stage),
        idempotencyKeyFor(stage),
    )

    internal fun recordProviderReceipt(receipt: StoredSecureDeletionReceipt, now: Long) {
        requireMutableAt(receipt.stage)
        require(receipt.idempotencyKey == idempotencyKeyFor(receipt.stage)) {
            "Provider receipt idempotency key does not match the current deletion stage."
        }
        require(receipt.providerReceipt.requestBindingDigest == requestBindingDigestFor(receipt.stage)) {
            "Provider receipt does not bind the current tenant-scoped deletion request."
        }
        require(now >= updatedAt) { "Secure-deletion receipt time cannot move backwards." }
        mutableReceipts.removeAll {
            it.stage == receipt.stage && it.providerReceipt.providerId == receipt.providerReceipt.providerId
        }
        mutableReceipts += receipt
        updatedAt = now
        when (receipt.providerReceipt.status) {
            SecureDeletionProviderStatus.VERIFIED_ABSENT -> {
                status = SecureDeletionExecutionStatus.PENDING
                lastError = null
            }

            SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED -> {
                status = SecureDeletionExecutionStatus.RECONCILING
                lastError = normalizeMessage(receipt.providerReceipt.message)
            }

            SecureDeletionProviderStatus.RETRYABLE_FAILURE -> {
                failureCount++
                status = SecureDeletionExecutionStatus.RETRY
                lastError = normalizeMessage(receipt.providerReceipt.message) ?: "Deletion provider requested a retry."
            }

            SecureDeletionProviderStatus.PERMANENT_FAILURE -> {
                failureCount++
                status = SecureDeletionExecutionStatus.FAILED
                lastError = normalizeMessage(receipt.providerReceipt.message) ?: "Deletion provider reported a permanent failure."
            }
        }
    }

    internal fun recordRetryableFailure(stage: SecureDeletionStage, message: String, now: Long) {
        requireMutableAt(stage)
        require(now >= updatedAt) { "Secure-deletion retry time cannot move backwards." }
        failureCount++
        status = SecureDeletionExecutionStatus.RETRY
        lastError = normalizeMessage(message) ?: "Secure deletion should be retried."
        updatedAt = now
    }

    internal fun advanceVerifiedStage(now: Long) {
        require(status == SecureDeletionExecutionStatus.PENDING) {
            "Only verified provider absence may advance secure deletion."
        }
        val receipt = latestReceipt(currentStage)
        require(receipt?.providerReceipt?.isVerifiedAbsent() == true) {
            "Secure deletion cannot advance without a verified provider receipt."
        }
        require(now >= updatedAt) { "Secure-deletion stage time cannot move backwards." }
        currentStage = when (currentStage) {
            SecureDeletionStage.PURGE_INDEX_PROJECTIONS -> SecureDeletionStage.PURGE_OBJECT_STORAGE
            SecureDeletionStage.PURGE_OBJECT_STORAGE -> SecureDeletionStage.FINALIZE_DATABASE
            else -> throw IllegalStateException("Secure deletion has no further external stage.")
        }
        status = SecureDeletionExecutionStatus.PENDING
        lastError = null
        updatedAt = now
    }

    internal fun finalizeDatabase(now: Long) {
        require(status == SecureDeletionExecutionStatus.PENDING && currentStage == SecureDeletionStage.FINALIZE_DATABASE) {
            "Secure deletion can only finalize after verified object absence."
        }
        require(
            latestReceipt(SecureDeletionStage.PURGE_OBJECT_STORAGE)
                ?.providerReceipt?.isVerifiedAbsent() == true
        ) {
            "Secure deletion cannot finalize without verified object absence."
        }
        require(now >= updatedAt) { "Secure-deletion finalization time cannot move backwards." }
        currentStage = SecureDeletionStage.APPEND_COMPLETION_AUDIT
        status = SecureDeletionExecutionStatus.SUCCEEDED
        lastError = null
        updatedAt = now
    }

    internal fun markExhausted(message: String, now: Long) {
        if (status == SecureDeletionExecutionStatus.SUCCEEDED) return
        require(now >= updatedAt) { "Secure-deletion exhaustion time cannot move backwards." }
        failureCount++
        status = SecureDeletionExecutionStatus.FAILED
        lastError = normalizeMessage(message) ?: "Secure-deletion retry limit was reached."
        updatedAt = now
    }

    internal fun completionEvidence(completedAt: Long): SecureDeletionCompletionEvidence {
        require(status == SecureDeletionExecutionStatus.SUCCEEDED) {
            "Only a successful secure deletion can produce completion evidence."
        }
        return SecureDeletionCompletionEvidence(
            id = planId,
            tenantId = tenantId,
            planId = planId,
            tombstoneId = tombstoneId,
            resourceType = resourceType,
            resourceId = resourceId,
            resourceRevision = resourceRevision,
            completedAt = completedAt,
            receipts = receipts,
        )
    }

    internal fun failureEvidence(failedAt: Long): SecureDeletionFailureEvidence {
        require(status == SecureDeletionExecutionStatus.FAILED) {
            "Only a failed secure deletion can produce failure evidence."
        }
        return SecureDeletionFailureEvidence(
            id = planId,
            tenantId = tenantId,
            planId = planId,
            tombstoneId = tombstoneId,
            resourceType = resourceType,
            resourceId = resourceId,
            resourceRevision = resourceRevision,
            failedStage = currentStage,
            failedAt = failedAt,
            failureCount = failureCount,
            message = lastError ?: "Secure deletion failed without a diagnostic message.",
        )
    }

    private fun requireMutableAt(stage: SecureDeletionStage) {
        require(status != SecureDeletionExecutionStatus.SUCCEEDED && status != SecureDeletionExecutionStatus.FAILED) {
            "Terminal secure-deletion execution cannot be mutated."
        }
        require(currentStage == stage) { "Provider result belongs to a stale secure-deletion stage." }
    }

    companion object {
        private val SUPPORTED_PROGRESS_STAGES = setOf(
            SecureDeletionStage.PURGE_INDEX_PROJECTIONS,
            SecureDeletionStage.PURGE_OBJECT_STORAGE,
            SecureDeletionStage.FINALIZE_DATABASE,
            SecureDeletionStage.APPEND_COMPLETION_AUDIT,
        )

        @JvmStatic
        fun pending(plan: SecureDeletionPlan, dispatchEventId: Identifier): SecureDeletionExecution {
            val indexStep = plan.steps.single { it.stage == SecureDeletionStage.PURGE_INDEX_PROJECTIONS }
            val objectStep = plan.steps.single { it.stage == SecureDeletionStage.PURGE_OBJECT_STORAGE }
            return SecureDeletionExecution(
                planId = plan.id,
                tenantId = plan.tenantId,
                dispatchEventId = dispatchEventId,
                tombstoneId = plan.tombstone.id,
                decisionEvidenceId = plan.decisionAuditEvidence.id,
                resourceType = plan.resourceType,
                resourceId = plan.resourceId,
                resourceRevision = plan.resourceRevision,
                requestedBy = plan.requestedBy,
                indexIdempotencyKey = indexStep.idempotencyKey.value,
                objectIdempotencyKey = objectStep.idempotencyKey.value,
                currentStage = SecureDeletionStage.PURGE_INDEX_PROJECTIONS,
                status = SecureDeletionExecutionStatus.PENDING,
                receipts = emptyList(),
                failureCount = 0,
                lastError = null,
                createdAt = plan.createdAt,
                updatedAt = plan.createdAt,
            )
        }

        private fun normalizeMessage(message: String?): String? =
            message?.takeIf { it.isNotBlank() }?.take(MAX_DIAGNOSTIC_LENGTH)

        private const val MAX_DIAGNOSTIC_LENGTH = 1024
    }
}

class SecureDeletionCompletionEvidence(
    val id: Identifier,
    val tenantId: Identifier,
    val planId: Identifier,
    val tombstoneId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val completedAt: Long,
    receipts: List<StoredSecureDeletionReceipt>,
) {
    val receipts: List<StoredSecureDeletionReceipt> = Collections.unmodifiableList(ArrayList(receipts))

    init {
        require(resourceType.isNotBlank()) { "Secure-deletion completion resource type must not be blank." }
        require(resourceRevision >= 0) { "Secure-deletion completion resource revision must not be negative." }
        require(completedAt >= 0) { "Secure-deletion completion time must not be negative." }
        require(this.receipts.map { it.providerReceipt.target }.toSet() == SecureDeletionTarget.values().toSet()) {
            "Secure-deletion completion requires receipts for index and object storage."
        }
        require(this.receipts.all { it.providerReceipt.isVerifiedAbsent() }) {
            "Secure-deletion completion requires verified-absence receipts."
        }
    }
}

class SecureDeletionFailureEvidence(
    val id: Identifier,
    val tenantId: Identifier,
    val planId: Identifier,
    val tombstoneId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val failedStage: SecureDeletionStage,
    val failedAt: Long,
    val failureCount: Int,
    val message: String,
) {
    init {
        require(resourceType.isNotBlank()) { "Secure-deletion failure resource type must not be blank." }
        require(resourceRevision >= 0) { "Secure-deletion failure resource revision must not be negative." }
        require(failedAt >= 0) { "Secure-deletion failure time must not be negative." }
        require(failureCount > 0) { "Secure-deletion failure evidence requires at least one failure." }
        require(message.isNotBlank()) { "Secure-deletion failure message must not be blank." }
    }
}
