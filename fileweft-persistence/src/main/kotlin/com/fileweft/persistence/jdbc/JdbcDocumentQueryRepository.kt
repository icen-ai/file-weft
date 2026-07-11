package com.fileweft.persistence.jdbc

import com.fileweft.application.document.DocumentDetailView
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentPageCursor
import com.fileweft.application.document.DocumentPageRequest
import com.fileweft.application.document.DocumentPageResult
import com.fileweft.application.document.DocumentQueryRepository
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.document.DocumentVersionView
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Array as JdbcArray

/**
 * PostgreSQL document read model using the caller-bound JDBC transaction.
 *
 * It intentionally selects only the fields represented by application-safe
 * views. In particular, asset identifiers, object identifiers, storage
 * locations, delivery IDs, errors, and outbox payloads never leave SQL.
 */
class JdbcDocumentQueryRepository : DocumentQueryRepository {
    override fun findDetail(
        tenantId: Identifier,
        documentId: Identifier,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentDetailView? {
        val connection = JdbcConnectionContext.requireCurrent()
        val visibilityArray = connection.createFolderVisibilityArray(folderReadScope)
        return try {
            connection.prepareStatement(detailSql(folderReadScope)).use { statement ->
                var index = 1
                statement.setString(index++, tenantId.value)
                statement.setString(index++, documentId.value)
                visibilityArray?.let { statement.setArray(index, it) }
                statement.executeQuery().use(::mapDetail)
            }
        } finally {
            visibilityArray?.free()
        }
    }

    override fun findPage(
        tenantId: Identifier,
        request: DocumentPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentPageResult {
        val connection = JdbcConnectionContext.requireCurrent()
        val visibilityArray = connection.createFolderVisibilityArray(folderReadScope)
        return try {
            val sql = pageSql(request, folderReadScope)
            connection.prepareStatement(sql).use { statement ->
                statement.bindPage(tenantId, request, visibilityArray)
                statement.executeQuery().use { result ->
                    val rows = ArrayList<DocumentSummaryView>()
                    while (result.next()) rows.add(result.toSummary())
                    val hasNext = rows.size > request.limit
                    val items = if (hasNext) rows.subList(0, request.limit) else rows
                    val nextCursor = if (hasNext) {
                        items.last().let { item -> DocumentPageCursor(item.updatedTime, item.id) }
                    } else {
                        null
                    }
                    DocumentPageResult(items, nextCursor)
                }
            }
        } finally {
            visibilityArray?.free()
        }
    }

    private fun mapDetail(result: ResultSet): DocumentDetailView? {
        var summary: DocumentSummaryView? = null
        val versions = ArrayList<DocumentVersionView>()
        while (result.next()) {
            if (summary == null) summary = result.toSummary()
            val versionId = result.getString("version_id") ?: continue
            val fileName = checkNotNull(result.getString("file_name")) {
                "Document version $versionId has no readable file metadata."
            }
            versions.add(
                DocumentVersionView(
                    id = Identifier(versionId),
                    versionNumber = result.getString("version_no"),
                    fileName = fileName,
                    contentLength = result.getLong("file_size"),
                    createdTime = result.getLong("version_created_time"),
                    updatedTime = result.getLong("version_updated_time"),
                    contentType = result.getString("content_type"),
                ),
            )
        }
        return summary?.let { DocumentDetailView(it, versions) }
    }

    private fun ResultSet.toSummary(): DocumentSummaryView = DocumentSummaryView(
        id = Identifier(getString("document_id")),
        documentNumber = getString("document_number"),
        title = getString("document_title"),
        lifecycleState = LifecycleState.valueOf(getString("lifecycle_state")),
        createdTime = getLong("document_created_time"),
        updatedTime = getLong("document_updated_time"),
        currentVersionId = getString("current_version_id")?.let(::Identifier),
        folderId = getString("folder_id"),
    )

    private fun pageSql(request: DocumentPageRequest, folderReadScope: DocumentFolderReadScope?): String = buildString {
        append(PAGE_SELECT_SQL)
        appendFolderVisibility(folderReadScope)
        if (request.lifecycleState != null) append(" AND document.lifecycle_state = ?")
        if (request.folderId != null) append(" AND ").append(FOLDER_ID_SQL).append(" = ?")
        request.cursor?.let {
            append(" AND (document.updated_time < ? OR (document.updated_time = ? AND document.id < ?))")
        }
        append(" ORDER BY document.updated_time DESC, document.id DESC LIMIT ?")
    }

    private fun PreparedStatement.bindPage(
        tenantId: Identifier,
        request: DocumentPageRequest,
        visibilityArray: JdbcArray?,
    ) {
        var index = 1
        setString(index++, tenantId.value)
        visibilityArray?.let { setArray(index++, it) }
        request.lifecycleState?.let { setString(index++, it.name) }
        request.folderId?.let { setString(index++, it) }
        request.cursor?.let { cursor ->
            setLong(index++, cursor.updatedTime)
            setLong(index++, cursor.updatedTime)
            setString(index++, cursor.id.value)
        }
        setInt(index, request.limit + 1)
    }

    private fun StringBuilder.appendFolderVisibility(folderReadScope: DocumentFolderReadScope?) {
        if (folderReadScope == null) return
        if (folderReadScope.isEmpty) {
            append(" AND 1 = 0")
        } else {
            append(" AND ").append(FOLDER_ID_SQL).append(" = ANY (?)")
        }
    }

    private fun java.sql.Connection.createFolderVisibilityArray(folderReadScope: DocumentFolderReadScope?): JdbcArray? =
        folderReadScope
            ?.takeIf { !it.isEmpty }
            ?.let { scope -> createArrayOf("text", scope.folderIds.toTypedArray()) }

    private fun detailSql(folderReadScope: DocumentFolderReadScope?): String = buildString {
        append(DETAIL_SQL)
        appendFolderVisibility(folderReadScope)
        append(" ORDER BY version.created_time ASC, version.version_no ASC, version.id ASC")
    }

    private companion object {
        // This expression intentionally mirrors the development catalog fallback.
        const val FOLDER_ID_SQL = "COALESCE(NULLIF(asset.metadata_json ->> 'catalog.folder-id', ''), 'inbox')"

        const val PAGE_SELECT_SQL = """
            SELECT document.id AS document_id,
                   document.doc_no AS document_number,
                   document.title AS document_title,
                   document.lifecycle_state,
                   document.current_version_id,
                   $FOLDER_ID_SQL AS folder_id,
                   document.created_time AS document_created_time,
                   document.updated_time AS document_updated_time
            FROM fw_document document
            JOIN fw_asset asset
              ON asset.id = document.asset_id
             AND asset.tenant_id = document.tenant_id
            WHERE document.tenant_id = ?
        """

        const val DETAIL_SQL = """
            SELECT document.id AS document_id,
                   document.doc_no AS document_number,
                   document.title AS document_title,
                   document.lifecycle_state,
                   document.current_version_id,
                   $FOLDER_ID_SQL AS folder_id,
                   document.created_time AS document_created_time,
                   document.updated_time AS document_updated_time,
                   version.id AS version_id,
                   version.version_no,
                   version.created_time AS version_created_time,
                   version.updated_time AS version_updated_time,
                   file.file_name,
                   file.content_type,
                   file.file_size
            FROM fw_document document
            JOIN fw_asset asset
              ON asset.id = document.asset_id
             AND asset.tenant_id = document.tenant_id
            LEFT JOIN fw_document_version version
              ON version.tenant_id = document.tenant_id
             AND version.document_id = document.id
            LEFT JOIN fw_file_object file
              ON file.tenant_id = version.tenant_id
             AND file.id = version.file_id
            WHERE document.tenant_id = ?
              AND document.id = ?
        """

    }
}
