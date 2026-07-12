package com.fileweft.persistence.jdbc

import com.fileweft.application.delivery.DocumentDeliveryPlanner
import com.fileweft.application.delivery.DocumentDeliveryRemovalPlanner
import com.fileweft.application.delivery.DocumentDeliveryRemovalStatus
import com.fileweft.application.delivery.DocumentDeliveryStatus
import com.fileweft.application.delivery.DocumentDeliveryStatusView
import com.fileweft.application.delivery.DocumentSyncStatusQueryRepository
import com.fileweft.application.delivery.DocumentSyncStatusView
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.core.id.Identifier
import com.fileweft.spi.delivery.DeliveryRequirement
import java.sql.Array as JdbcArray
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/** PostgreSQL projection for one visible document's current delivery generation. */
class JdbcDocumentSyncStatusQueryRepository : DocumentSyncStatusQueryRepository {
    override fun findByDocument(
        tenantId: Identifier,
        documentId: Identifier,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentSyncStatusView? {
        val connection = JdbcConnectionContext.requireCurrent()
        val visibilityArray = connection.createFolderVisibilityArray(folderReadScope)
        return try {
            connection.prepareStatement(statusSql(folderReadScope)).use { statement ->
                statement.bindStatusQuery(tenantId, documentId, visibilityArray)
                statement.executeQuery().use { result -> mapStatus(result, documentId) }
            }
        } finally {
            visibilityArray?.free()
        }
    }

    private fun PreparedStatement.bindStatusQuery(
        tenantId: Identifier,
        documentId: Identifier,
        visibilityArray: JdbcArray?,
    ) {
        var index = 1
        setString(index++, DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE)
        setString(index++, DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE)
        setString(index++, tenantId.value)
        setString(index++, documentId.value)
        visibilityArray?.let { setArray(index, it) }
    }

    private fun mapStatus(result: ResultSet, expectedDocumentId: Identifier): DocumentSyncStatusView? {
        if (!result.next()) return null
        val documentId = Identifier(result.getString("document_id"))
        check(documentId == expectedDocumentId) {
            "Synchronization query returned a document outside the requested identifier."
        }
        val targets = ArrayList<DocumentDeliveryStatusView>()
        do {
            val deliveryId = result.getString("delivery_id") ?: continue
            targets += DocumentDeliveryStatusView(
                deliveryId = Identifier(deliveryId),
                targetId = result.getString("target_id"),
                displayName = result.getString("target_name"),
                requirement = DeliveryRequirement.valueOf(result.getString("delivery_requirement")),
                deliveryStatus = DocumentDeliveryStatus.valueOf(result.getString("delivery_status")),
                deliveryRetryCount = result.getInt("delivery_retry_count"),
                removalStatus = DocumentDeliveryRemovalStatus.valueOf(result.getString("removal_status")),
                removalRetryCount = result.getInt("removal_retry_count"),
                deliveryRetryable = result.getBoolean("delivery_retryable"),
                removalRetryable = result.getBoolean("removal_retryable"),
                updatedTime = result.getLong("delivery_updated_time"),
            )
        } while (result.next())
        return DocumentSyncStatusView(documentId, targets)
    }

    private fun statusSql(folderReadScope: DocumentFolderReadScope?): String = buildString {
        append(STATUS_SELECT_SQL)
        appendFolderVisibility(folderReadScope)
        append(" ORDER BY target.created_time ASC NULLS LAST, target.id ASC NULLS LAST")
    }

    private fun StringBuilder.appendFolderVisibility(folderReadScope: DocumentFolderReadScope?) {
        if (folderReadScope == null) return
        if (folderReadScope.isEmpty) {
            append(" AND 1 = 0")
        } else {
            append(" AND ").append(FOLDER_ID_SQL).append(" = ANY (?)")
        }
    }

    private fun Connection.createFolderVisibilityArray(folderReadScope: DocumentFolderReadScope?): JdbcArray? =
        folderReadScope
            ?.takeIf { scope -> !scope.isEmpty }
            ?.let { scope -> createArrayOf("text", scope.folderIds.toTypedArray()) }

    private companion object {
        const val FOLDER_ID_SQL: String =
            "COALESCE(NULLIF(asset.metadata_json ->> 'catalog.folder-id', ''), 'inbox')"

        const val STATUS_SELECT_SQL: String = """
            SELECT document.id AS document_id,
                   target.id AS delivery_id,
                   target.target_id,
                   target.target_name,
                   target.delivery_requirement,
                   target.delivery_status,
                   target.retry_count AS delivery_retry_count,
                   target.removal_status,
                   target.removal_retry_count,
                   target.updated_time AS delivery_updated_time,
                   COALESCE(
                       target.delivery_status IN ('PENDING', 'RETRYING', 'FAILED')
                       AND target.current_operation = 'DELIVERY'
                       AND current_event.event_type = ?
                       AND current_event.event_status = 'FAILED',
                       FALSE
                   ) AS delivery_retryable,
                   COALESCE(
                       target.removal_status IN ('PENDING', 'RETRYING', 'FAILED')
                       AND target.current_operation = 'REMOVAL'
                       AND current_event.event_type = ?
                       AND current_event.event_status = 'FAILED',
                       FALSE
                   ) AS removal_retryable
            FROM fw_document document
            JOIN fw_asset asset
              ON asset.tenant_id = document.tenant_id
             AND asset.id = document.asset_id
            LEFT JOIN fw_document_delivery_target target
              ON target.tenant_id = document.tenant_id
             AND target.document_id = document.id
             AND target.delivery_generation = document.delivery_generation
            LEFT JOIN fw_outbox_event current_event
              ON current_event.tenant_id = target.tenant_id
             AND current_event.id = target.current_event_id
            WHERE document.tenant_id = ?
              AND document.id = ?
        """
    }
}
