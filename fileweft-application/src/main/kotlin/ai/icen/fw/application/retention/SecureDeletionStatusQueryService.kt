package ai.icen.fw.application.retention

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.retention.SecureDeletionStage
import ai.icen.fw.spi.retention.SecureDeletionProviderStatus
import ai.icen.fw.spi.retention.SecureDeletionTarget
import java.util.ArrayList
import java.util.Collections

/** Redacted operator-facing status; provider evidence payloads are not exposed. */
class SecureDeletionStatusView(
    val planId: Identifier,
    val tenantId: Identifier,
    val tombstoneId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val currentStage: SecureDeletionStage,
    val status: SecureDeletionExecutionStatus,
    val failureCount: Int,
    val lastError: String?,
    val updatedAt: Long,
    providerReceipts: List<SecureDeletionProviderStatusView>,
) {
    val providerReceipts: List<SecureDeletionProviderStatusView> =
        Collections.unmodifiableList(ArrayList(providerReceipts))

    init {
        require(resourceType.isNotBlank()) { "Secure-deletion status resource type must not be blank." }
        require(resourceRevision >= 0) { "Secure-deletion status resource revision must not be negative." }
        require(failureCount >= 0) { "Secure-deletion status failure count must not be negative." }
        require(lastError == null || lastError.isNotBlank()) {
            "Secure-deletion status error must not be blank when provided."
        }
        require(updatedAt >= 0) { "Secure-deletion status update time must not be negative." }
    }
}

class SecureDeletionProviderStatusView(
    val providerId: String,
    val target: SecureDeletionTarget,
    val status: SecureDeletionProviderStatus,
    val recordedAt: Long,
) {
    init {
        require(providerId.isNotBlank()) { "Secure-deletion status provider id must not be blank." }
        require(recordedAt >= 0) { "Secure-deletion provider status time must not be negative." }
    }
}

/**
 * Tenant-scoped diagnostic query. Authorization remains the responsibility of
 * the trusted application use case that exposes this service to an operator.
 */
class SecureDeletionStatusQueryService(
    private val deletions: SecureDeletionRepository,
) {
    fun find(tenantId: Identifier, planId: Identifier): SecureDeletionStatusView? {
        val execution = deletions.findByPlanId(tenantId, planId) ?: return null
        require(execution.tenantId == tenantId && execution.planId == planId) {
            "Secure-deletion repository returned execution outside the requested tenant and plan."
        }
        return SecureDeletionStatusView(
            planId = execution.planId,
            tenantId = execution.tenantId,
            tombstoneId = execution.tombstoneId,
            resourceType = execution.resourceType,
            resourceId = execution.resourceId,
            resourceRevision = execution.resourceRevision,
            currentStage = execution.currentStage,
            status = execution.status,
            failureCount = execution.failureCount,
            lastError = execution.lastError,
            updatedAt = execution.updatedAt,
            providerReceipts = execution.receipts.map { stored ->
                SecureDeletionProviderStatusView(
                    providerId = stored.providerReceipt.providerId,
                    target = stored.providerReceipt.target,
                    status = stored.providerReceipt.status,
                    recordedAt = stored.recordedAt,
                )
            },
        )
    }
}
