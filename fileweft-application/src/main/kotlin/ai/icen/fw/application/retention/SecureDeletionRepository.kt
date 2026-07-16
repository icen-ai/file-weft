package ai.icen.fw.application.retention

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.retention.DeletionAuditEvidence
import ai.icen.fw.domain.retention.SecureDeletionPlan

/**
 * Local persistence port for secure-deletion orchestration.
 *
 * Every mutation is called inside a short ApplicationTransaction. External
 * provider calls are forbidden in implementations. [createIfAbsent] must
 * atomically persist the tombstone, allowed decision audit, immutable plan and
 * initial execution projection; returning false is valid only for the exact
 * same tenant-bound plan and dispatch event.
 */
interface SecureDeletionRepository {
    fun createIfAbsent(plan: SecureDeletionPlan, dispatchEventId: Identifier): Boolean

    /** Records a denied decision without creating a tombstone or execution; duplicate ids must match exactly. */
    fun appendDecisionAuditIfAbsent(evidence: DeletionAuditEvidence): Boolean

    fun findByPlanId(tenantId: Identifier, planId: Identifier): SecureDeletionExecution?

    /** Locks the execution row until the caller's current transaction ends. */
    fun findForMutation(tenantId: Identifier, planId: Identifier): SecureDeletionExecution?

    fun save(execution: SecureDeletionExecution)

    /** Duplicate ids are a no-op only when the complete evidence matches. */
    fun appendCompletionEvidenceIfAbsent(evidence: SecureDeletionCompletionEvidence): Boolean

    /** Duplicate ids are a no-op only when the complete evidence matches. */
    fun appendFailureEvidenceIfAbsent(evidence: SecureDeletionFailureEvidence): Boolean
}
