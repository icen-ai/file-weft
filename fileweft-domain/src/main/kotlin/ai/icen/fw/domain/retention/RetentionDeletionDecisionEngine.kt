package ai.icen.fw.domain.retention

import ai.icen.fw.core.id.Identifier
import java.time.Clock
import java.util.Locale

/** All evidence and identifiers required to make one tenant-bound decision. */
class SecureDeletionRequest(
    val decisionEvidenceId: Identifier,
    val planId: Identifier,
    val tombstoneId: Identifier,
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val resourceRevision: Long,
    val requestedBy: Identifier,
    val policy: RetentionPolicySnapshot,
    val legalHolds: LegalHoldSetSnapshot,
    val authorization: DeletionAuthorizationSnapshot,
) {
    init {
        requireRetentionText(resourceType, "Secure-deletion resource type", 64)
        require(resourceRevision >= 0) { "Secure-deletion resource revision must not be negative." }
        require(policy.tenantId == tenantId && policy.resourceId == resourceId) {
            "Retention policy must be bound to the deletion tenant and resource."
        }
        require(policy.resourceType == resourceType) {
            "Retention policy resource type must match the deletion request."
        }
        require(legalHolds.tenantId == tenantId && legalHolds.resourceId == resourceId) {
            "Legal-hold set must be bound to the deletion tenant and resource."
        }
        require(legalHolds.resourceType == resourceType) {
            "Legal-hold resource type must match the deletion request."
        }
        require(authorization.tenantId == tenantId && authorization.resourceId == resourceId) {
            "Deletion authorization must be bound to the deletion tenant and resource."
        }
        require(authorization.resourceType == resourceType) {
            "Deletion authorization resource type must match the deletion request."
        }
        require(authorization.principalId == requestedBy) {
            "Deletion authorization principal must match the trusted requester."
        }
        require(decisionEvidenceId != planId && decisionEvidenceId != tombstoneId && planId != tombstoneId) {
            "Deletion decision, plan, and tombstone identifiers must be distinct."
        }
    }
}

/**
 * Pure, deterministic retention and legal-hold evaluator.
 *
 * The supplied [Clock] is the only time source. Known active holds have the
 * highest policy priority, while every incomplete or stale input blocks plan
 * creation. The evaluator performs no persistence or provider call.
 */
class RetentionDeletionDecisionEngine(
    private val clock: Clock,
) {
    fun evaluate(request: SecureDeletionRequest): SecureDeletionDecision {
        val now = clock.millis()
        require(now >= 0) { "Secure-deletion decision time must not be negative." }

        val activeHoldIds = request.legalHolds.holds
            .filter { hold -> hold.status == LegalHoldStatus.ACTIVE }
            .map { hold -> hold.id }
        val reason = decide(request, now, activeHoldIds.isNotEmpty())
        if (reason != SecureDeletionDecisionReason.ALLOWED) {
            return SecureDeletionDecision(
                reason,
                auditEvidence(request, now, reason, activeHoldIds, null, null),
                null,
            )
        }

        val tombstone = DeletionTombstone(
            id = request.tombstoneId,
            planId = request.planId,
            tenantId = request.tenantId,
            resourceType = request.resourceType,
            resourceId = request.resourceId,
            resourceRevision = request.resourceRevision,
            blockedAt = now,
            policyRevision = request.policy.policyRevision,
            legalHoldRevision = request.legalHolds.snapshotRevision,
            authorizationRevision = request.authorization.authorizationRevision,
        )
        val auditEvidence = auditEvidence(
            request,
            now,
            SecureDeletionDecisionReason.ALLOWED,
            emptyList(),
            request.planId,
            request.tombstoneId,
        )
        val plan = SecureDeletionPlan(
            id = request.planId,
            tenantId = request.tenantId,
            resourceType = request.resourceType,
            resourceId = request.resourceId,
            resourceRevision = request.resourceRevision,
            requestedBy = request.requestedBy,
            createdAt = now,
            tombstone = tombstone,
            decisionAuditEvidence = auditEvidence,
            steps = buildSteps(request),
        )
        return SecureDeletionDecision(SecureDeletionDecisionReason.ALLOWED, auditEvidence, plan)
    }

    private fun decide(
        request: SecureDeletionRequest,
        now: Long,
        hasActiveHold: Boolean,
    ): SecureDeletionDecisionReason {
        if (hasActiveHold) {
            return SecureDeletionDecisionReason.ACTIVE_LEGAL_HOLD
        }
        if (!request.legalHolds.complete) {
            return SecureDeletionDecisionReason.INCOMPLETE_LEGAL_HOLD_EVIDENCE
        }
        if (!request.policy.isComplete()) {
            return SecureDeletionDecisionReason.INCOMPLETE_RETENTION_POLICY
        }
        if (
            request.legalHolds.observedAt > now ||
            request.policy.effectiveAt > now ||
            request.policy.capturedAt > now ||
            request.authorization.evaluatedAt > now
        ) {
            return SecureDeletionDecisionReason.EVIDENCE_FROM_FUTURE
        }
        if (now >= request.legalHolds.expiresAt) {
            return SecureDeletionDecisionReason.LEGAL_HOLD_EVIDENCE_EXPIRED
        }
        if (now >= request.policy.expiresAt) {
            return SecureDeletionDecisionReason.RETENTION_POLICY_EVIDENCE_EXPIRED
        }
        if (!request.authorization.complete) {
            return SecureDeletionDecisionReason.INCOMPLETE_AUTHORIZATION_EVIDENCE
        }
        if (!request.authorization.authorized) {
            return SecureDeletionDecisionReason.AUTHORIZATION_DENIED
        }
        if (now >= request.authorization.expiresAt) {
            return SecureDeletionDecisionReason.AUTHORIZATION_EXPIRED
        }
        return when (request.policy.mode) {
            RetentionPolicyMode.RETAIN_INDEFINITELY -> SecureDeletionDecisionReason.RETAIN_INDEFINITELY
            RetentionPolicyMode.RETAIN_UNTIL -> if (now < checkNotNull(request.policy.retainUntil)) {
                SecureDeletionDecisionReason.RETENTION_PERIOD_ACTIVE
            } else {
                SecureDeletionDecisionReason.ALLOWED
            }

            RetentionPolicyMode.UNKNOWN -> SecureDeletionDecisionReason.INCOMPLETE_RETENTION_POLICY
        }
    }

    private fun auditEvidence(
        request: SecureDeletionRequest,
        now: Long,
        reason: SecureDeletionDecisionReason,
        activeHoldIds: List<Identifier>,
        planId: Identifier?,
        tombstoneId: Identifier?,
    ): DeletionAuditEvidence = DeletionAuditEvidence(
        id = request.decisionEvidenceId,
        tenantId = request.tenantId,
        resourceType = request.resourceType,
        resourceId = request.resourceId,
        resourceRevision = request.resourceRevision,
        requestedBy = request.requestedBy,
        decidedAt = now,
        reason = reason,
        policyRevision = request.policy.policyRevision,
        legalHoldRevision = request.legalHolds.snapshotRevision,
        authorizationRevision = request.authorization.authorizationRevision,
        activeLegalHoldIds = activeHoldIds,
        planId = planId,
        tombstoneId = tombstoneId,
    )

    private fun buildSteps(request: SecureDeletionRequest): List<SecureDeletionStep> =
        SecureDeletionPlan.REQUIRED_STAGE_ORDER.mapIndexed { index, stage ->
            SecureDeletionStep(
                sequence = index + 1,
                stage = stage,
                boundary = boundaryFor(stage),
                idempotencyKey = DeletionIdempotencyKey(
                    "secure-delete/${request.tenantId.value}/${request.planId.value}/" +
                        stage.name.lowercase(Locale.ROOT),
                ),
                prerequisite = if (index == 0) null else SecureDeletionPlan.REQUIRED_STAGE_ORDER[index - 1],
            )
        }

    private fun boundaryFor(stage: SecureDeletionStage): DeletionExecutionBoundary = when (stage) {
        SecureDeletionStage.ENQUEUE_PURGE_OUTBOX -> DeletionExecutionBoundary.TRANSACTIONAL_OUTBOX
        SecureDeletionStage.PURGE_INDEX_PROJECTIONS -> DeletionExecutionBoundary.EXTERNAL_INDEX
        SecureDeletionStage.PURGE_OBJECT_STORAGE -> DeletionExecutionBoundary.EXTERNAL_OBJECT_STORAGE
        SecureDeletionStage.PERSIST_TOMBSTONE,
        SecureDeletionStage.APPEND_DECISION_AUDIT,
        SecureDeletionStage.FINALIZE_DATABASE,
        SecureDeletionStage.APPEND_COMPLETION_AUDIT,
        -> DeletionExecutionBoundary.DATABASE_TRANSACTION
    }
}
