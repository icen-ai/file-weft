package com.fileweft.dev.api.service

import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import org.springframework.jdbc.core.JdbcTemplate

data class DevDocumentSummary(
    val id: String,
    val documentNumber: String,
    val title: String,
    val lifecycleState: String,
    val currentVersionId: String?,
    val createdTime: Long,
    val updatedTime: Long,
)

data class DevDocumentVersionView(
    val id: String,
    val versionNumber: String,
    val fileObjectId: String,
    val fileName: String,
    val contentType: String?,
    val contentLength: Long,
    val contentHash: String?,
)

data class DevWorkflowTaskView(
    val id: String,
    val assigneeId: String?,
    val state: String,
    val comment: String?,
)

data class DevWorkflowView(
    val id: String,
    val type: String,
    val state: String,
    val tasks: List<DevWorkflowTaskView>,
)

data class DevAuditView(
    val id: String,
    val action: String,
    val operatorId: String?,
    val details: String?,
    val createdTime: Long,
)

data class DevSyncView(
    val id: String,
    val sourceEventId: String,
    val connectorName: String,
    val status: String,
    val externalId: String?,
    val errorMessage: String?,
    val retryCount: Int,
    val updatedTime: Long,
)

data class DevOutboxView(
    val id: String,
    val type: String,
    val status: String,
    val retryCount: Int,
    val lastError: String?,
    val createdTime: Long,
    val updatedTime: Long,
)

data class DevDocumentDetail(
    val document: DevDocumentSummary,
    val versions: List<DevDocumentVersionView>,
    val workflows: List<DevWorkflowView>,
    val audits: List<DevAuditView>,
    val syncRecords: List<DevSyncView>,
    val outboxEvents: List<DevOutboxView>,
)

class DevDocumentQueryService(
    private val jdbcTemplate: JdbcTemplate,
    private val access: DevAccessService,
    private val tenantProvider: com.fileweft.spi.tenant.TenantProvider,
) {
    fun page(limit: Int, lifecycleState: String?): List<DevDocumentSummary> {
        require(limit in 1..100) { "Document page limit must be between 1 and 100." }
        access.requireAction(Identifier("document-page"), "DOCUMENT_PAGE", "document:read")
        val tenantId = tenantProvider.currentTenant().tenantId.value
        val requestedState = lifecycleState?.takeIf { it.isNotBlank() }?.let { value ->
            LifecycleState.valueOf(value).name
        }
        return if (requestedState == null) {
            jdbcTemplate.query(PAGE_SQL, SUMMARY_MAPPER, tenantId, limit)
        } else {
            jdbcTemplate.query("$PAGE_SQL_WITH_STATE", SUMMARY_MAPPER, tenantId, requestedState, limit)
        }
    }

    fun detail(documentId: Identifier): DevDocumentDetail {
        access.requireDocumentAction(documentId, "document:read")
        val tenantId = tenantProvider.currentTenant().tenantId.value
        val document = jdbcTemplate.query(DETAIL_SQL, SUMMARY_MAPPER, tenantId, documentId.value).firstOrNull()
            ?: throw NoSuchElementException("Document ${documentId.value} was not found in the current tenant.")
        return DevDocumentDetail(
            document = document,
            versions = jdbcTemplate.query(VERSIONS_SQL, VERSION_MAPPER, tenantId, documentId.value),
            workflows = loadWorkflows(tenantId, documentId.value),
            audits = jdbcTemplate.query(AUDITS_SQL, AUDIT_MAPPER, tenantId, documentId.value),
            syncRecords = jdbcTemplate.query(SYNC_SQL, SYNC_MAPPER, tenantId, documentId.value),
            outboxEvents = jdbcTemplate.query(OUTBOX_SQL, OUTBOX_MAPPER, tenantId, documentId.value),
        )
    }

    private fun loadWorkflows(tenantId: String, documentId: String): List<DevWorkflowView> = jdbcTemplate.query(
        WORKFLOWS_SQL,
        { result, _ -> Triple(result.getString("id"), result.getString("workflow_type"), result.getString("state")) },
        tenantId,
        documentId,
    ).map { (workflowId, type, state) ->
        DevWorkflowView(
            workflowId,
            type,
            state,
            jdbcTemplate.query(WORKFLOW_TASKS_SQL, TASK_MAPPER, tenantId, workflowId),
        )
    }

    private companion object {
        val SUMMARY_MAPPER = org.springframework.jdbc.core.RowMapper<DevDocumentSummary> { result, _ ->
            DevDocumentSummary(
                result.getString("id"), result.getString("doc_no"), result.getString("title"),
                result.getString("lifecycle_state"), result.getString("current_version_id"),
                result.getLong("created_time"), result.getLong("updated_time"),
            )
        }
        val VERSION_MAPPER = org.springframework.jdbc.core.RowMapper<DevDocumentVersionView> { result, _ ->
            DevDocumentVersionView(
                result.getString("id"), result.getString("version_no"), result.getString("file_id"),
                result.getString("file_name"), result.getString("content_type"), result.getLong("file_size"), result.getString("content_hash"),
            )
        }
        val TASK_MAPPER = org.springframework.jdbc.core.RowMapper<DevWorkflowTaskView> { result, _ ->
            DevWorkflowTaskView(result.getString("id"), result.getString("assignee_id"), result.getString("task_state"), result.getString("comment_text"))
        }
        val AUDIT_MAPPER = org.springframework.jdbc.core.RowMapper<DevAuditView> { result, _ ->
            DevAuditView(result.getString("id"), result.getString("action"), result.getString("operator_id"), result.getString("detail_json"), result.getLong("created_time"))
        }
        val SYNC_MAPPER = org.springframework.jdbc.core.RowMapper<DevSyncView> { result, _ ->
            DevSyncView(
                result.getString("id"), result.getString("source_event_id"), result.getString("connector_name"),
                result.getString("sync_status"), result.getString("external_id"), result.getString("error_message"),
                result.getInt("retry_count"), result.getLong("updated_time"),
            )
        }
        val OUTBOX_MAPPER = org.springframework.jdbc.core.RowMapper<DevOutboxView> { result, _ ->
            DevOutboxView(
                result.getString("id"), result.getString("event_type"), result.getString("event_status"), result.getInt("retry_count"),
                result.getString("last_error"), result.getLong("created_time"), result.getLong("updated_time"),
            )
        }

        const val PAGE_SQL = """
            SELECT id, doc_no, title, lifecycle_state, current_version_id, created_time, updated_time
            FROM fw_document WHERE tenant_id = ? ORDER BY updated_time DESC, id DESC LIMIT ?
        """
        const val PAGE_SQL_WITH_STATE = """
            SELECT id, doc_no, title, lifecycle_state, current_version_id, created_time, updated_time
            FROM fw_document WHERE tenant_id = ? AND lifecycle_state = ? ORDER BY updated_time DESC, id DESC LIMIT ?
        """
        const val DETAIL_SQL = """
            SELECT id, doc_no, title, lifecycle_state, current_version_id, created_time, updated_time
            FROM fw_document WHERE tenant_id = ? AND id = ?
        """
        const val VERSIONS_SQL = """
            SELECT version.id, version.version_no, version.file_id, file.file_name, file.content_type, file.file_size, file.content_hash
            FROM fw_document_version version
            JOIN fw_file_object file ON file.id = version.file_id AND file.tenant_id = version.tenant_id
            WHERE version.tenant_id = ? AND version.document_id = ? ORDER BY version.created_time, version.version_no
        """
        const val WORKFLOWS_SQL = """
            SELECT id, workflow_type, state FROM fw_workflow_instance
            WHERE tenant_id = ? AND document_id = ? ORDER BY created_time DESC
        """
        const val WORKFLOW_TASKS_SQL = """
            SELECT id, assignee_id, task_state, comment_text FROM fw_workflow_task
            WHERE tenant_id = ? AND workflow_id = ? ORDER BY created_time, id
        """
        const val AUDITS_SQL = """
            SELECT id, action, operator_id, detail_json::text AS detail_json, created_time FROM fw_audit_record
            WHERE tenant_id = ? AND resource_type = 'DOCUMENT' AND resource_id = ? ORDER BY created_time DESC
        """
        const val SYNC_SQL = """
            SELECT id, source_event_id, connector_name, sync_status, external_id, error_message, retry_count, updated_time
            FROM fw_sync_record WHERE tenant_id = ? AND document_id = ? ORDER BY updated_time DESC
        """
        const val OUTBOX_SQL = """
            SELECT id, event_type, event_status, retry_count, last_error, created_time, updated_time
            FROM fw_outbox_event WHERE tenant_id = ? AND payload_json ->> 'documentId' = ? ORDER BY created_time DESC
        """
    }
}
