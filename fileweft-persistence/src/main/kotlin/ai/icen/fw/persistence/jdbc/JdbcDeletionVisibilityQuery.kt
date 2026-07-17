package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.retention.DeletionVisibilityFence
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.core.id.Identifier
import java.sql.ResultSet
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** JDBC projection for the durable tombstone used by every normal read fence. */
class JdbcDeletionVisibilityQuery : DeletionVisibilityQuery {
    override fun findFence(
        tenantId: Identifier,
        resourceType: String,
        resourceId: Identifier,
    ): DeletionVisibilityFence? = JdbcConnectionContext.requireCurrent().prepareStatement(FIND_ONE_SQL).use { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, resourceType)
        statement.setString(3, resourceId.value)
        statement.executeQuery().use { rows ->
            if (!rows.next()) {
                null
            } else {
                rows.toFence().also {
                    check(!rows.next()) { "Secure-deletion tombstone resource projection is not unique." }
                }
            }
        }
    }

    override fun findFences(
        tenantId: Identifier,
        resourceType: String,
        resourceIds: Collection<Identifier>,
    ): Map<Identifier, DeletionVisibilityFence> {
        require(resourceIds.size <= DeletionVisibilityQuery.MAX_BATCH_SIZE) {
            "Deletion visibility batch is too large."
        }
        val uniqueIds = LinkedHashSet(resourceIds)
        require(uniqueIds.size == resourceIds.size) {
            "Deletion visibility batch resource identifiers must be unique."
        }
        if (uniqueIds.isEmpty()) return emptyMap()

        val placeholders = uniqueIds.joinToString(",") { "?" }
        val sql = "$FIND_BATCH_PREFIX ($placeholders)"
        return JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, resourceType)
            uniqueIds.forEachIndexed { index, resourceId -> statement.setString(index + 3, resourceId.value) }
            statement.executeQuery().use { rows ->
                val result = LinkedHashMap<Identifier, DeletionVisibilityFence>()
                while (rows.next()) {
                    val fence = rows.toFence()
                    check(fence.resourceId in uniqueIds) {
                        "Secure-deletion tombstone batch returned an unrequested resource."
                    }
                    check(result.put(fence.resourceId, fence) == null) {
                        "Secure-deletion tombstone resource projection is not unique."
                    }
                }
                Collections.unmodifiableMap(result)
            }
        }
    }

    private fun ResultSet.toFence(): DeletionVisibilityFence = DeletionVisibilityFence(
        tombstoneId = Identifier(requireNotNull(getString("id")) { "Persisted tombstone id is null." }),
        planId = Identifier(requireNotNull(getString("plan_id")) { "Persisted tombstone plan id is null." }),
        tenantId = Identifier(requireNotNull(getString("tenant_id")) { "Persisted tombstone tenant id is null." }),
        resourceType = requireNotNull(getString("resource_type")) { "Persisted tombstone resource type is null." },
        resourceId = Identifier(requireNotNull(getString("resource_id")) { "Persisted tombstone resource id is null." }),
        resourceRevision = getLong("resource_revision").also {
            check(!wasNull()) { "Persisted tombstone resource revision is null." }
        },
        blockedAt = getLong("blocked_time").also {
            check(!wasNull()) { "Persisted tombstone blocked time is null." }
        },
    )

    private companion object {
        const val PROJECTION_COLUMNS =
            "id, tenant_id, plan_id, resource_type, resource_id, resource_revision, blocked_time"
        const val FIND_ONE_SQL =
            "SELECT $PROJECTION_COLUMNS FROM fw_secure_deletion_tombstone " +
                "WHERE tenant_id = ? AND resource_type = ? AND resource_id = ?"
        const val FIND_BATCH_PREFIX =
            "SELECT $PROJECTION_COLUMNS FROM fw_secure_deletion_tombstone " +
                "WHERE tenant_id = ? AND resource_type = ? AND resource_id IN"
    }
}
