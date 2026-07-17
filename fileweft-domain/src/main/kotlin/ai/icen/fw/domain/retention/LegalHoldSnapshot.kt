package ai.icen.fw.domain.retention

import ai.icen.fw.core.id.Identifier
import java.util.ArrayList
import java.util.Collections

enum class LegalHoldStatus {
    ACTIVE,
    RELEASED,
}

/** Immutable evidence for one hold; released holds remain auditable. */
class LegalHoldSnapshot(
    val id: Identifier,
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val revision: String,
    val status: LegalHoldStatus,
    val appliedAt: Long,
    val releasedAt: Long?,
) {
    init {
        requireRetentionText(resourceType, "Legal-hold resource type", 64)
        requireRetentionText(revision, "Legal-hold revision")
        require(appliedAt >= 0) { "Legal-hold application time must not be negative." }
        when (status) {
            LegalHoldStatus.ACTIVE -> require(releasedAt == null) {
                "An active legal hold cannot have a release time."
            }

            LegalHoldStatus.RELEASED -> require(releasedAt != null && releasedAt >= appliedAt) {
                "A released legal hold requires a release time at or after application."
            }
        }
    }
}

/**
 * Tenant-bound result of loading every legal hold that applies to a resource.
 *
 * A host must set [complete] to false whenever inheritance, an external hold
 * registry, or persistence could not be evaluated conclusively. An empty but
 * incomplete set blocks deletion.
 */
class LegalHoldSetSnapshot(
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val snapshotRevision: String,
    val observedAt: Long,
    val expiresAt: Long,
    val complete: Boolean,
    holds: List<LegalHoldSnapshot>,
) {
    val holds: List<LegalHoldSnapshot> = Collections.unmodifiableList(ArrayList(holds))

    init {
        requireRetentionText(resourceType, "Legal-hold resource type", 64)
        requireRetentionText(snapshotRevision, "Legal-hold set revision")
        require(observedAt >= 0) { "Legal-hold observation time must not be negative." }
        require(expiresAt > observedAt) { "Legal-hold evidence expiry must follow its observation time." }
        require(this.holds.size <= MAX_RETENTION_EVIDENCE_ITEMS) {
            "Legal-hold snapshot contains too many entries."
        }
        require(this.holds.map { it.id }.distinct().size == this.holds.size) {
            "Legal-hold identifiers must be unique within a snapshot."
        }
        this.holds.forEach { hold ->
            require(hold.tenantId == tenantId) { "Legal-hold tenant must match its set." }
            require(hold.resourceType == resourceType) { "Legal-hold resource type must match its set." }
            require(hold.resourceId == resourceId) { "Legal-hold resource id must match its set." }
            require(hold.appliedAt <= observedAt) { "Legal-hold evidence cannot postdate its set observation." }
            require(hold.releasedAt == null || hold.releasedAt <= observedAt) {
                "Legal-hold release evidence cannot postdate its set observation."
            }
        }
    }

    fun hasActiveHold(): Boolean = holds.any { it.status == LegalHoldStatus.ACTIVE }
}
