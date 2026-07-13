package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalStatus
import ai.icen.fw.application.delivery.DocumentDeliveryStatus
import ai.icen.fw.application.delivery.DocumentDeliveryStatusView
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryRepository
import ai.icen.fw.application.delivery.DocumentSyncStatusView
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.delivery.DeliveryRequirement
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
            (visibilityArray as? JdbcArray)?.free()
        }
    }

    private fun PreparedStatement.bindStatusQuery(
        tenantId: Identifier,
        documentId: Identifier,
        visibilityArray: Any?,
    ) {
        var index = 1
        setString(index++, DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE)
        setString(index++, DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE)
        setString(index++, tenantId.value)
        setString(index++, documentId.value)
        setFolderVisibilityParameter(index, visibilityArray)
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
        val dialect = JdbcConnectionContext.requireDialect()
        val folderExpression = folderIdExpression()
        append(statusSelectSql(folderExpression))
        appendFolderVisibility(folderReadScope, folderExpression)
        append(" ORDER BY ${dialect.nullsLastOrderBy("target.created_time", false)}, ${dialect.nullsLastOrderBy("target.id", false)}")
    }

    private fun StringBuilder.appendFolderVisibility(folderReadScope: DocumentFolderReadScope?, folderExpression: String) {
        if (folderReadScope == null) return
        if (folderReadScope.isEmpty) {
            append(" AND 1 = 0")
        } else {
            append(" AND ").append(JdbcConnectionContext.requireDialect().arrayContainsAny(folderExpression, "?"))
        }
    }

    private fun folderIdExpression(): String =
        "COALESCE(NULLIF(${JdbcConnectionContext.requireDialect().jsonExtractText("asset.metadata_json", "catalog.folder-id")}, ''), 'inbox')"

    private fun Connection.createFolderVisibilityArray(folderReadScope: DocumentFolderReadScope?): Any? =
        folderReadScope
            ?.takeIf { scope -> !scope.isEmpty }
            ?.let { scope -> JdbcConnectionContext.requireDialect().createStringArrayParameter(this, scope.folderIds) }

    private fun PreparedStatement.setFolderVisibilityParameter(index: Int, parameter: Any?) {
        when (parameter) {
            is JdbcArray -> setArray(index, parameter)
            is String -> setString(index, parameter)
            null -> Unit
            else -> throw IllegalArgumentException("Unsupported folder visibility parameter type ${parameter.javaClass.name}")
        }
    }

    private companion object {
        private fun statusSelectSql(folderExpression: String): String = """
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
