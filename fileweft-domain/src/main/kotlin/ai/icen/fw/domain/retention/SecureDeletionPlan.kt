package ai.icen.fw.domain.retention

import ai.icen.fw.core.id.Identifier
import java.util.ArrayList
import java.util.Collections

enum class SecureDeletionDecisionReason {
    ALLOWED,
    ACTIVE_LEGAL_HOLD,
    INCOMPLETE_LEGAL_HOLD_EVIDENCE,
    INCOMPLETE_RETENTION_POLICY,
    RETAIN_INDEFINITELY,
    RETENTION_PERIOD_ACTIVE,
    INCOMPLETE_AUTHORIZATION_EVIDENCE,
    AUTHORIZATION_DENIED,
    AUTHORIZATION_EXPIRED,
    LEGAL_HOLD_EVIDENCE_EXPIRED,
    RETENTION_POLICY_EVIDENCE_EXPIRED,
    EVIDENCE_FROM_FUTURE,
}

enum class SecureDeletionStage {
    PERSIST_TOMBSTONE,
    APPEND_DECISION_AUDIT,
    ENQUEUE_PURGE_OUTBOX,
    PURGE_INDEX_PROJECTIONS,
    PURGE_OBJECT_STORAGE,
    FINALIZE_DATABASE,
    APPEND_COMPLETION_AUDIT,
}

enum class DeletionExecutionBoundary {
    DATABASE_TRANSACTION,
    TRANSACTIONAL_OUTBOX,
    EXTERNAL_INDEX,
    EXTERNAL_OBJECT_STORAGE,
}

class DeletionIdempotencyKey(value: String) {
    val value: String = value.also {
        requireRetentionText(it, "Deletion idempotency key", MAX_LENGTH)
        require(it.length <= MAX_LENGTH) { "Deletion idempotency key must not exceed $MAX_LENGTH characters." }
    }

    override fun equals(other: Any?): Boolean =
        other is DeletionIdempotencyKey && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 1024
    }
}

class SecureDeletionStep(
    val sequence: Int,
    val stage: SecureDeletionStage,
    val boundary: DeletionExecutionBoundary,
    val idempotencyKey: DeletionIdempotencyKey,
    val prerequisite: SecureDeletionStage?,
) {
    init {
        require(sequence > 0) { "Deletion step sequence must be positive." }
        require(prerequisite != stage) { "Deletion step cannot depend on itself." }
    }

    fun isExternalSideEffect(): Boolean =
        boundary == DeletionExecutionBoundary.EXTERNAL_INDEX ||
            boundary == DeletionExecutionBoundary.EXTERNAL_OBJECT_STORAGE
}

/**
 * Durable read-denial fence written before any asynchronous purge starts.
 * Its resource revision lets consumers reject stale upserts after deletion.
 */
class DeletionTombstone(
    val id: Identifier,
    val planId: Identifier,
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val blockedAt: Long,
    val policyRevision: String,
    val legalHoldRevision: String,
    val authorizationRevision: String,
) {
    init {
        requireRetentionText(resourceType, "Deletion tombstone resource type", 64)
        require(resourceRevision >= 0) { "Deletion tombstone resource revision must not be negative." }
        require(blockedAt >= 0) { "Deletion tombstone time must not be negative." }
        requireRetentionText(policyRevision, "Deletion tombstone policy revision")
        requireRetentionText(legalHoldRevision, "Deletion tombstone legal-hold revision")
        requireRetentionText(authorizationRevision, "Deletion tombstone authorization revision")
    }
}

/** Immutable evidence for both allowed and blocked deletion decisions. */
class DeletionAuditEvidence(
    val id: Identifier,
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val requestedBy: Identifier,
    val decidedAt: Long,
    val reason: SecureDeletionDecisionReason,
    val policyRevision: String,
    val legalHoldRevision: String,
    val authorizationRevision: String,
    activeLegalHoldIds: List<Identifier>,
    val planId: Identifier?,
    val tombstoneId: Identifier?,
) {
    val activeLegalHoldIds: List<Identifier> =
        Collections.unmodifiableList(ArrayList(activeLegalHoldIds))

    init {
        requireRetentionText(resourceType, "Deletion audit resource type", 64)
        require(resourceRevision >= 0) { "Deletion audit resource revision must not be negative." }
        require(decidedAt >= 0) { "Deletion audit time must not be negative." }
        requireRetentionText(policyRevision, "Deletion audit policy revision")
        requireRetentionText(legalHoldRevision, "Deletion audit legal-hold revision")
        requireRetentionText(authorizationRevision, "Deletion audit authorization revision")
        require(this.activeLegalHoldIds.size <= MAX_RETENTION_EVIDENCE_ITEMS) {
            "Deletion audit contains too many active legal-hold identifiers."
        }
        require(this.activeLegalHoldIds.distinct().size == this.activeLegalHoldIds.size) {
            "Deletion audit legal-hold identifiers must be unique."
        }
        val allowed = reason == SecureDeletionDecisionReason.ALLOWED
        require(allowed == (planId != null && tombstoneId != null)) {
            "Only an allowed deletion decision may reference a plan and tombstone."
        }
        require(reason == SecureDeletionDecisionReason.ACTIVE_LEGAL_HOLD || this.activeLegalHoldIds.isEmpty()) {
            "Active legal-hold ids may only be recorded for an active-hold decision."
        }
        require(reason != SecureDeletionDecisionReason.ACTIVE_LEGAL_HOLD || this.activeLegalHoldIds.isNotEmpty()) {
            "An active-hold decision requires at least one active legal-hold id."
        }
    }
}

/**
 * Provider-neutral purge plan. Database and outbox stages are durable; index
 * and object stages are explicitly external and therefore cannot be executed
 * inside the business transaction that creates the tombstone.
 */
class SecureDeletionPlan internal constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val requestedBy: Identifier,
    val createdAt: Long,
    val tombstone: DeletionTombstone,
    val decisionAuditEvidence: DeletionAuditEvidence,
    steps: List<SecureDeletionStep>,
) {
    val steps: List<SecureDeletionStep> = Collections.unmodifiableList(ArrayList(steps))

    init {
        requireRetentionText(resourceType, "Deletion plan resource type", 64)
        require(resourceRevision >= 0) { "Deletion plan resource revision must not be negative." }
        require(createdAt >= 0) { "Deletion plan creation time must not be negative." }
        require(tombstone.planId == id) { "Deletion tombstone must belong to its plan." }
        require(tombstone.tenantId == tenantId && tombstone.resourceId == resourceId) {
            "Deletion tombstone resource must match its plan."
        }
        require(tombstone.resourceType == resourceType && tombstone.resourceRevision == resourceRevision) {
            "Deletion tombstone resource revision must match its plan."
        }
        require(decisionAuditEvidence.planId == id && decisionAuditEvidence.tombstoneId == tombstone.id) {
            "Deletion audit evidence must reference its plan and tombstone."
        }
        require(decisionAuditEvidence.tenantId == tenantId && decisionAuditEvidence.resourceId == resourceId) {
            "Deletion audit evidence resource must match its plan."
        }
        require(this.steps.map { it.sequence } == (1..this.steps.size).toList()) {
            "Deletion plan steps must use contiguous one-based sequence numbers."
        }
        require(this.steps.map { it.stage } == REQUIRED_STAGE_ORDER) {
            "Deletion plan stages must preserve the secure purge order."
        }
        require(this.steps.map { it.idempotencyKey }.distinct().size == this.steps.size) {
            "Deletion plan step idempotency keys must be unique."
        }
        this.steps.forEachIndexed { index, step ->
            val expectedPrerequisite = if (index == 0) null else this.steps[index - 1].stage
            require(step.prerequisite == expectedPrerequisite) {
                "Each deletion step must depend on the immediately preceding stage."
            }
        }
    }

    companion object {
        internal val REQUIRED_STAGE_ORDER: List<SecureDeletionStage> = listOf(
            SecureDeletionStage.PERSIST_TOMBSTONE,
            SecureDeletionStage.APPEND_DECISION_AUDIT,
            SecureDeletionStage.ENQUEUE_PURGE_OUTBOX,
            SecureDeletionStage.PURGE_INDEX_PROJECTIONS,
            SecureDeletionStage.PURGE_OBJECT_STORAGE,
            SecureDeletionStage.FINALIZE_DATABASE,
            SecureDeletionStage.APPEND_COMPLETION_AUDIT,
        )
    }
}

class SecureDeletionDecision internal constructor(
    val reason: SecureDeletionDecisionReason,
    val auditEvidence: DeletionAuditEvidence,
    val plan: SecureDeletionPlan?,
) {
    init {
        require((reason == SecureDeletionDecisionReason.ALLOWED) == (plan != null)) {
            "Only an allowed deletion decision may contain an executable plan."
        }
        require(auditEvidence.reason == reason) { "Deletion decision and audit reason must match." }
        require(plan == null || plan.decisionAuditEvidence === auditEvidence) {
            "Deletion plan must carry the decision's audit evidence."
        }
    }

    fun isAllowed(): Boolean = reason == SecureDeletionDecisionReason.ALLOWED
}
