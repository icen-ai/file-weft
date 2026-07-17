package ai.icen.fw.domain.retention

import ai.icen.fw.core.id.Identifier

/**
 * Immutable retention-policy evidence used for one deletion decision.
 *
 * [UNKNOWN] is an explicit fail-closed state for hosts that could not resolve
 * inherited or external policy. It is never treated as "no retention".
 */
enum class RetentionPolicyMode {
    RETAIN_UNTIL,
    RETAIN_INDEFINITELY,
    UNKNOWN,
}

class RetentionPolicySnapshot(
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val policyId: String,
    val policyRevision: String,
    val mode: RetentionPolicyMode,
    val effectiveAt: Long,
    val capturedAt: Long,
    val expiresAt: Long,
    val retainUntil: Long?,
) {
    init {
        requireRetentionText(resourceType, "Retention resource type", 64)
        requireRetentionText(policyId, "Retention policy id")
        requireRetentionText(policyRevision, "Retention policy revision")
        require(effectiveAt >= 0) { "Retention policy effective time must not be negative." }
        require(capturedAt >= effectiveAt) {
            "Retention policy snapshot cannot predate the policy effective time."
        }
        require(expiresAt > capturedAt) {
            "Retention policy evidence expiry must follow its capture time."
        }
        when (mode) {
            RetentionPolicyMode.RETAIN_UNTIL -> require(retainUntil != null && retainUntil >= 0) {
                "A retain-until policy requires a non-negative retention deadline."
            }

            RetentionPolicyMode.RETAIN_INDEFINITELY,
            RetentionPolicyMode.UNKNOWN,
            -> require(retainUntil == null) {
                "Only a retain-until policy may carry a retention deadline."
            }
        }
    }

    fun isComplete(): Boolean = mode != RetentionPolicyMode.UNKNOWN
}
