package ai.icen.fw.application.retention

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.retention.RetentionDeletionDecisionEngine
import ai.icen.fw.domain.retention.SecureDeletionDecision
import ai.icen.fw.domain.retention.SecureDeletionRequest

/**
 * Commits the secure-deletion fence before any external provider is invoked.
 *
 * [request] must already contain trusted tenant/principal authorization
 * evidence. Controllers must never construct it directly from request tenant
 * or user fields.
 */
class SecureDeletionApplicationService(
    private val decisions: RetentionDeletionDecisionEngine,
    private val deletions: SecureDeletionRepository,
    private val outbox: OutboxEventRepository,
    private val transaction: ApplicationTransaction,
) {
    fun request(request: SecureDeletionRequest): SecureDeletionDecision {
        val decision = decisions.evaluate(request)
        if (!decision.isAllowed()) {
            transaction.execute {
                deletions.appendDecisionAuditIfAbsent(decision.auditEvidence)
            }
            return decision
        }

        val plan = requireNotNull(decision.plan)
        val dispatchEventId = dispatchEventId(plan.id)
        transaction.execute {
            if (deletions.createIfAbsent(plan, dispatchEventId)) {
                outbox.append(
                    OutboxEvent(
                        id = dispatchEventId,
                        tenantId = plan.tenantId,
                        type = SECURE_DELETION_REQUESTED_EVENT_TYPE,
                        payload = linkedMapOf(
                            PLAN_ID_PAYLOAD_KEY to plan.id.value,
                            TOMBSTONE_ID_PAYLOAD_KEY to plan.tombstone.id.value,
                            RESOURCE_TYPE_PAYLOAD_KEY to plan.resourceType,
                            RESOURCE_ID_PAYLOAD_KEY to plan.resourceId.value,
                            RESOURCE_REVISION_PAYLOAD_KEY to plan.resourceRevision.toString(),
                        ),
                        timestamp = plan.createdAt,
                    ),
                )
            } else {
                val existing = deletions.findByPlanId(plan.tenantId, plan.id)
                    ?: throw IllegalStateException("Secure-deletion repository reported a replay without durable execution state.")
                require(existing.matchesDispatch(dispatchEventId, plan.resourceRevision)) {
                    "Secure-deletion replay does not match the durable dispatch fence."
                }
                require(
                    existing.tenantId == plan.tenantId &&
                        existing.planId == plan.id &&
                        existing.tombstoneId == plan.tombstone.id &&
                        existing.decisionEvidenceId == plan.decisionAuditEvidence.id &&
                        existing.resourceType == plan.resourceType &&
                        existing.resourceId == plan.resourceId
                ) {
                    "Secure-deletion replay conflicts with the existing tenant-bound plan."
                }
            }
        }
        return decision
    }

    companion object {
        const val SECURE_DELETION_REQUESTED_EVENT_TYPE = "flowweft.secure-deletion.requested"
        const val PLAN_ID_PAYLOAD_KEY = "planId"
        const val TOMBSTONE_ID_PAYLOAD_KEY = "tombstoneId"
        const val RESOURCE_TYPE_PAYLOAD_KEY = "resourceType"
        const val RESOURCE_ID_PAYLOAD_KEY = "resourceId"
        const val RESOURCE_REVISION_PAYLOAD_KEY = "resourceRevision"

        @JvmStatic
        fun dispatchEventId(planId: Identifier): Identifier =
            planId
    }
}
