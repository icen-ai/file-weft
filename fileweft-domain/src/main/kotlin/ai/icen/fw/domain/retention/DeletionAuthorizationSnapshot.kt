package ai.icen.fw.domain.retention

import ai.icen.fw.core.id.Identifier

/**
 * Bound authorization evidence supplied by the application layer.
 *
 * The domain never derives this from request parameters. Incomplete, denied,
 * future-dated, or expired evidence cannot authorize a deletion.
 */
class DeletionAuthorizationSnapshot(
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val principalId: Identifier,
    val authorizationRevision: String,
    val evaluatedAt: Long,
    val expiresAt: Long,
    val complete: Boolean,
    val authorized: Boolean,
) {
    init {
        requireRetentionText(resourceType, "Deletion authorization resource type", 64)
        requireRetentionText(authorizationRevision, "Deletion authorization revision")
        require(evaluatedAt >= 0) { "Deletion authorization evaluation time must not be negative." }
        require(expiresAt >= evaluatedAt) {
            "Deletion authorization expiry must not predate its evaluation."
        }
        require(complete || !authorized) {
            "Incomplete deletion authorization cannot be marked authorized."
        }
    }
}
