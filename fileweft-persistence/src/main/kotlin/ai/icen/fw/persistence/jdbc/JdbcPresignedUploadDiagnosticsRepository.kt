package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.upload.PresignedUploadDiagnosticsRepository
import ai.icen.fw.application.upload.PresignedUploadDiagnosticsSnapshot
import ai.icen.fw.core.id.Identifier
import java.sql.PreparedStatement

/** Portable aggregate query for PostgreSQL, MySQL and KingbaseES. */
class JdbcPresignedUploadDiagnosticsRepository : PresignedUploadDiagnosticsRepository {
    override fun snapshot(tenantId: Identifier?, now: Long): PresignedUploadDiagnosticsSnapshot {
        require(now >= 0) { "Presigned upload diagnostic time must not be negative." }
        val tenantPredicate = if (tenantId == null) "" else " WHERE tenant_id = ?"
        val sql = """
            SELECT
                COALESCE(SUM(CASE WHEN session_status IN ('READY', 'FINALIZING') THEN 1 ELSE 0 END), 0) active_count,
                COALESCE(SUM(CASE WHEN session_status = 'FINALIZING' AND claim_expires_at <= ? THEN 1 ELSE 0 END), 0) stuck_claim_count,
                COALESCE(SUM(CASE WHEN cleanup_time IS NULL AND session_status <> 'COMPLETED'
                    AND grant_expires_at <= ?
                    AND (session_status IN ('CANCELLED', 'EXPIRED') OR session_expires_at <= ?)
                    THEN 1 ELSE 0 END), 0) cleanup_due_count,
                COALESCE(SUM(CASE WHEN cleanup_time IS NULL AND session_status IN ('CANCELLED', 'EXPIRED')
                    AND last_error_class IS NOT NULL AND last_error_class <> 'PresignedUploadSessionExpired'
                    THEN 1 ELSE 0 END), 0) cleanup_failure_count,
                COALESCE(SUM(CASE WHEN session_status = 'COMPLETED' AND asset_file_object_id IS NULL
                    THEN 1 ELSE 0 END), 0) orphan_risk_count,
                MIN(CASE WHEN
                    (session_status = 'FINALIZING' AND claim_expires_at <= ?)
                    OR (cleanup_time IS NULL AND session_status <> 'COMPLETED' AND grant_expires_at <= ?
                        AND (session_status IN ('CANCELLED', 'EXPIRED') OR session_expires_at <= ?))
                    THEN updated_time ELSE NULL END) oldest_maintenance_time
            FROM fw_presigned_upload_session$tenantPredicate
        """.trimIndent()
        return JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
            bind(statement, tenantId, now)
            statement.executeQuery().use { result ->
                check(result.next()) { "Presigned upload diagnostic aggregate returned no row." }
                val oldest = result.getLong("oldest_maintenance_time").let { value ->
                    if (result.wasNull()) null else value
                }
                PresignedUploadDiagnosticsSnapshot(
                    activeCount = result.getLong("active_count"),
                    stuckClaimCount = result.getLong("stuck_claim_count"),
                    cleanupDueCount = result.getLong("cleanup_due_count"),
                    cleanupFailureCount = result.getLong("cleanup_failure_count"),
                    orphanRiskCount = result.getLong("orphan_risk_count"),
                    oldestMaintenanceAgeSeconds = oldest?.let { timestamp ->
                        Math.max(0L, now - timestamp) / 1_000L
                    } ?: 0L,
                )
            }
        }
    }

    private fun bind(statement: PreparedStatement, tenantId: Identifier?, now: Long) {
        statement.setLong(1, now)
        statement.setLong(2, now)
        statement.setLong(3, now)
        statement.setLong(4, now)
        statement.setLong(5, now)
        statement.setLong(6, now)
        tenantId?.let { statement.setString(7, it.value) }
    }
}
