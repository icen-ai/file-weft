package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.audit.DocumentAuditLogPageCursor
import ai.icen.fw.application.audit.DocumentAuditLogPageRequest
import ai.icen.fw.application.audit.DocumentAuditLogPageResult
import ai.icen.fw.application.audit.DocumentAuditLogQueryRepository
import ai.icen.fw.application.audit.DocumentAuditLogView
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.core.id.Identifier
import java.sql.Array as JdbcArray
import java.sql.PreparedStatement
import java.sql.ResultSet

/** PostgreSQL projection for the formal, redacted document audit-log query. */
class JdbcDocumentAuditLogQueryRepository : DocumentAuditLogQueryRepository {
    override fun findPage(
        tenantId: Identifier,
        documentId: Identifier,
        request: DocumentAuditLogPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentAuditLogPageResult? {
        val connection = JdbcConnectionContext.requireCurrent()
        val visibilityArray = folderReadScope
            ?.takeIf { scope -> !scope.isEmpty }
            ?.let { scope -> connection.createArrayOf("text", scope.folderIds.toTypedArray()) }
        return try {
            connection.prepareStatement(querySql(request, folderReadScope)).use { statement ->
                statement.bind(tenantId, documentId, request, visibilityArray)
                statement.executeQuery().use { result -> map(result, request.limit) }
            }
        } finally {
            visibilityArray?.free()
        }
    }

    private fun map(result: ResultSet, limit: Int): DocumentAuditLogPageResult? {
        var visibleDocumentId: Identifier? = null
        val rows = ArrayList<DocumentAuditLogView>()
        while (result.next()) {
            val currentDocumentId = Identifier(result.getString("visible_document_id"))
            check(visibleDocumentId == null || visibleDocumentId == currentDocumentId) {
                "Document audit-log query returned more than one visible document."
            }
            visibleDocumentId = currentDocumentId
            val auditId = result.getString("audit_id") ?: continue
            rows += DocumentAuditLogView(
                id = Identifier(auditId),
                action = result.getString("audit_action"),
                createdTime = result.getLong("audit_created_time"),
                operatorId = result.getString("audit_operator_id")?.let(::Identifier),
                operatorName = result.getString("audit_operator_name"),
                traceId = result.getString("operation_trace_id")?.let(::Identifier),
            )
        }
        val documentId = visibleDocumentId ?: return null
        val hasNext = rows.size > limit
        val items = if (hasNext) ArrayList(rows.subList(0, limit)) else rows
        val nextCursor = if (hasNext) {
            items.last().let { item -> DocumentAuditLogPageCursor(item.createdTime, item.id) }
        } else {
            null
        }
        return DocumentAuditLogPageResult(documentId, items, nextCursor)
    }

    private fun PreparedStatement.bind(
        tenantId: Identifier,
        documentId: Identifier,
        request: DocumentAuditLogPageRequest,
        visibilityArray: JdbcArray?,
    ) {
        var index = 1
        setString(index++, tenantId.value)
        setString(index++, documentId.value)
        visibilityArray?.let { setArray(index++, it) }
        setString(index++, tenantId.value)
        request.cursor?.let { cursor ->
            setLong(index++, cursor.createdTime)
            setLong(index++, cursor.createdTime)
            setString(index++, cursor.id.value)
        }
        setInt(index, request.limit + 1)
    }

    private fun querySql(
        request: DocumentAuditLogPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): String = buildString {
        append(VISIBLE_DOCUMENT_CTE)
        when {
            folderReadScope == null -> Unit
            folderReadScope.isEmpty -> append(" AND 1 = 0")
            else -> append(" AND $FOLDER_ID_SQL = ANY (?)")
        }
        append(AUDIT_PAGE_CTE)
        request.cursor?.let {
            append(" AND (audit.created_time < ? OR (audit.created_time = ? AND audit.id < ?))")
        }
        append(AUDIT_PAGE_SELECT)
    }

    private companion object {
        const val FOLDER_ID_SQL = "COALESCE(NULLIF(asset.metadata_json ->> 'catalog.folder-id', ''), 'inbox')"

        const val VISIBLE_DOCUMENT_CTE = """
            WITH visible_document AS (
                SELECT document.id
                FROM fw_document document
                JOIN fw_asset asset
                  ON asset.tenant_id = document.tenant_id
                 AND asset.id = document.asset_id
                WHERE document.tenant_id = ?
                  AND document.id = ?
        """

        const val AUDIT_PAGE_CTE = """
            ), audit_page AS (
                SELECT audit.id,
                       audit.action,
                       audit.operator_id,
                       audit.operator_name,
                       NULLIF(operation.trace_id, '') AS trace_id,
                       audit.created_time
                FROM fw_audit_record audit
                JOIN visible_document document ON document.id = audit.resource_id
                LEFT JOIN fw_operation_log operation
                  ON operation.tenant_id = audit.tenant_id
                 AND operation.resource_type = audit.resource_type
                 AND operation.resource_id = audit.resource_id
                 AND operation.id = audit.id
                WHERE audit.tenant_id = ?
                  AND audit.resource_type = 'DOCUMENT'
                  AND audit.created_time >= 0
                  AND btrim(audit.action) <> ''
                  AND (audit.operator_name IS NULL OR btrim(audit.operator_name) <> '')
        """

        const val AUDIT_PAGE_SELECT = """
                ORDER BY audit.created_time DESC, audit.id DESC
                LIMIT ?
            )
            SELECT document.id AS visible_document_id,
                   audit.id AS audit_id,
                   audit.action AS audit_action,
                   audit.operator_id AS audit_operator_id,
                   audit.operator_name AS audit_operator_name,
                   audit.trace_id AS operation_trace_id,
                   audit.created_time AS audit_created_time
            FROM visible_document document
            LEFT JOIN audit_page audit ON TRUE
            ORDER BY audit.created_time DESC NULLS LAST, audit.id DESC NULLS LAST
        """
    }
}
