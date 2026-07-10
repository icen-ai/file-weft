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
    val folderId: String,
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
    val operatorName: String?,
    val details: String?,
    val createdTime: Long,
)

data class DevOperationLogView(
    val id: String,
    val action: String,
    val operatorId: String?,
    val operatorName: String?,
    val traceId: String?,
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

data class DevDeliveryView(
    val id: String,
    val profileId: String,
    val targetId: String,
    val displayName: String,
    val connectorId: String,
    val requirement: String,
    val ownerRef: String?,
    val status: String,
    val externalId: String?,
    val errorMessage: String?,
    val retryCount: Int,
    val updatedTime: Long,
)

data class DevBackgroundTaskView(
    val id: String,
    val type: String,
    val status: String,
    val retryCount: Int,
    val nextAttemptTime: Long,
    val lastError: String?,
    val createdTime: Long,
    val updatedTime: Long,
)

data class DevDoctorRecordView(
    val id: String,
    val taskId: String,
    val status: String,
    val report: String,
    val createdTime: Long,
    val updatedTime: Long,
)

data class DevAgentConfirmationView(
    val id: String,
    val suggestionId: String,
    val confirmedBy: String,
    val confirmedTime: Long,
)

data class DevAgentResultView(
    val id: String,
    val taskId: String,
    val capability: String,
    val sourceEventType: String,
    val status: String,
    val result: String,
    val createdTime: Long,
    val confirmations: List<DevAgentConfirmationView>,
)

data class DevDocumentDetail(
    val document: DevDocumentSummary,
    val versions: List<DevDocumentVersionView>,
    val workflows: List<DevWorkflowView>,
    val audits: List<DevAuditView>,
    val operationLogs: List<DevOperationLogView>,
    val deliveries: List<DevDeliveryView>,
    val tasks: List<DevBackgroundTaskView>,
    val doctorRecords: List<DevDoctorRecordView>,
    val agentResults: List<DevAgentResultView>,
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
            operationLogs = jdbcTemplate.query(OPERATION_LOGS_SQL, OPERATION_LOG_MAPPER, tenantId, documentId.value),
            deliveries = jdbcTemplate.query(DELIVERIES_SQL, DELIVERY_MAPPER, tenantId, documentId.value),
            tasks = jdbcTemplate.query(TASKS_SQL, BACKGROUND_TASK_MAPPER, tenantId, documentId.value),
            doctorRecords = jdbcTemplate.query(DOCTOR_RECORDS_SQL, DOCTOR_RECORD_MAPPER, tenantId, documentId.value),
            agentResults = loadAgentResults(tenantId, documentId.value),
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
            jdbcTemplate.query(WORKFLOW_TASKS_SQL, WORKFLOW_TASK_MAPPER, tenantId, workflowId),
        )
    }

    private fun loadAgentResults(tenantId: String, documentId: String): List<DevAgentResultView> = jdbcTemplate.query(
        AGENT_RESULTS_SQL,
        AGENT_RESULT_MAPPER,
        tenantId,
        documentId,
    ).map { result ->
        result.copy(confirmations = jdbcTemplate.query(AGENT_CONFIRMATIONS_SQL, AGENT_CONFIRMATION_MAPPER, tenantId, result.taskId))
    }

    private companion object {
        val SUMMARY_MAPPER = org.springframework.jdbc.core.RowMapper<DevDocumentSummary> { result, _ ->
            DevDocumentSummary(
                result.getString("id"), result.getString("doc_no"), result.getString("title"),
                result.getString("lifecycle_state"), result.getString("current_version_id"), result.getString("folder_id"),
                result.getLong("created_time"), result.getLong("updated_time"),
            )
        }
        val VERSION_MAPPER = org.springframework.jdbc.core.RowMapper<DevDocumentVersionView> { result, _ ->
            DevDocumentVersionView(
                result.getString("id"), result.getString("version_no"), result.getString("file_id"),
                result.getString("file_name"), result.getString("content_type"), result.getLong("file_size"), result.getString("content_hash"),
            )
        }
        val WORKFLOW_TASK_MAPPER = org.springframework.jdbc.core.RowMapper<DevWorkflowTaskView> { result, _ ->
            DevWorkflowTaskView(result.getString("id"), result.getString("assignee_id"), result.getString("task_state"), result.getString("comment_text"))
        }
        val AUDIT_MAPPER = org.springframework.jdbc.core.RowMapper<DevAuditView> { result, _ ->
            DevAuditView(
                result.getString("id"), result.getString("action"), result.getString("operator_id"),
                result.getString("operator_name"), result.getString("detail_json"), result.getLong("created_time"),
            )
        }
        val OPERATION_LOG_MAPPER = org.springframework.jdbc.core.RowMapper<DevOperationLogView> { result, _ ->
            DevOperationLogView(
                result.getString("id"), result.getString("action"), result.getString("operator_id"),
                result.getString("operator_name"), result.getString("trace_id"), result.getString("detail_json"),
                result.getLong("created_time"),
            )
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
        val DELIVERY_MAPPER = org.springframework.jdbc.core.RowMapper<DevDeliveryView> { result, _ ->
            DevDeliveryView(
                result.getString("id"), result.getString("profile_id"), result.getString("target_id"),
                result.getString("target_name"), result.getString("connector_id"), result.getString("delivery_requirement"),
                result.getString("owner_ref"), result.getString("delivery_status"), result.getString("external_id"),
                result.getString("error_message"), result.getInt("retry_count"), result.getLong("updated_time"),
            )
        }
        val BACKGROUND_TASK_MAPPER = org.springframework.jdbc.core.RowMapper<DevBackgroundTaskView> { result, _ ->
            DevBackgroundTaskView(
                result.getString("id"), result.getString("task_type"), result.getString("task_status"),
                result.getInt("retry_count"), result.getLong("next_attempt_time"), result.getString("last_error"),
                result.getLong("created_time"), result.getLong("updated_time"),
            )
        }
        val DOCTOR_RECORD_MAPPER = org.springframework.jdbc.core.RowMapper<DevDoctorRecordView> { result, _ ->
            DevDoctorRecordView(
                result.getString("id"), result.getString("task_id"), result.getString("doctor_status"),
                result.getString("report_json"), result.getLong("created_time"), result.getLong("updated_time"),
            )
        }
        val AGENT_RESULT_MAPPER = org.springframework.jdbc.core.RowMapper<DevAgentResultView> { result, _ ->
            DevAgentResultView(
                result.getString("id"), result.getString("task_id"), result.getString("capability"),
                result.getString("source_event_type"), result.getString("result_status"), result.getString("result_json"),
                result.getLong("created_time"), emptyList(),
            )
        }
        val AGENT_CONFIRMATION_MAPPER = org.springframework.jdbc.core.RowMapper<DevAgentConfirmationView> { result, _ ->
            DevAgentConfirmationView(
                result.getString("id"), result.getString("suggestion_id"), result.getString("confirmed_by"), result.getLong("confirmed_time"),
            )
        }

        const val PAGE_SQL = """
            SELECT document.id, document.doc_no, document.title, document.lifecycle_state, document.current_version_id,
                   COALESCE(asset.metadata_json ->> 'catalog.folder-id', 'inbox') AS folder_id,
                   document.created_time, document.updated_time
            FROM fw_document document
            JOIN fw_asset asset ON asset.id = document.asset_id AND asset.tenant_id = document.tenant_id
            WHERE document.tenant_id = ? ORDER BY document.updated_time DESC, document.id DESC LIMIT ?
        """
        const val PAGE_SQL_WITH_STATE = """
            SELECT document.id, document.doc_no, document.title, document.lifecycle_state, document.current_version_id,
                   COALESCE(asset.metadata_json ->> 'catalog.folder-id', 'inbox') AS folder_id,
                   document.created_time, document.updated_time
            FROM fw_document document
            JOIN fw_asset asset ON asset.id = document.asset_id AND asset.tenant_id = document.tenant_id
            WHERE document.tenant_id = ? AND document.lifecycle_state = ? ORDER BY document.updated_time DESC, document.id DESC LIMIT ?
        """
        const val DETAIL_SQL = """
            SELECT document.id, document.doc_no, document.title, document.lifecycle_state, document.current_version_id,
                   COALESCE(asset.metadata_json ->> 'catalog.folder-id', 'inbox') AS folder_id,
                   document.created_time, document.updated_time
            FROM fw_document document
            JOIN fw_asset asset ON asset.id = document.asset_id AND asset.tenant_id = document.tenant_id
            WHERE document.tenant_id = ? AND document.id = ?
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
            SELECT id, action, operator_id, operator_name, detail_json::text AS detail_json, created_time FROM fw_audit_record
            WHERE tenant_id = ? AND resource_type = 'DOCUMENT' AND resource_id = ? ORDER BY created_time DESC
        """
        const val OPERATION_LOGS_SQL = """
            SELECT id, action, operator_id, operator_name, trace_id, detail_json::text AS detail_json, created_time
            FROM fw_operation_log
            WHERE tenant_id = ? AND resource_type = 'DOCUMENT' AND resource_id = ?
            ORDER BY created_time DESC, id DESC
        """
        const val SYNC_SQL = """
            SELECT id, source_event_id, connector_name, sync_status, external_id, error_message, retry_count, updated_time
            FROM fw_sync_record WHERE tenant_id = ? AND document_id = ? ORDER BY updated_time DESC
        """
        const val DELIVERIES_SQL = """
            SELECT id, profile_id, target_id, target_name, connector_id, delivery_requirement, owner_ref,
                   delivery_status, external_id, error_message, retry_count, updated_time
            FROM fw_document_delivery_target WHERE tenant_id = ? AND document_id = ? ORDER BY created_time, id
        """
        const val TASKS_SQL = """
            SELECT id, task_type, task_status, retry_count, next_attempt_time, last_error, created_time, updated_time
            FROM fw_task WHERE tenant_id = ? AND business_id = ? ORDER BY created_time DESC, id DESC
        """
        const val DOCTOR_RECORDS_SQL = """
            SELECT id, task_id, doctor_status, report_json::text AS report_json, created_time, updated_time
            FROM fw_doctor_record WHERE tenant_id = ? AND document_id = ? ORDER BY created_time DESC, id DESC
        """
        const val AGENT_RESULTS_SQL = """
            SELECT result.id, result.task_id, result.capability, result.source_event_type, result.result_status,
                   result.result_json::text AS result_json, result.created_time
            FROM fw_agent_result result
            JOIN fw_task task ON task.id = result.task_id AND task.tenant_id = result.tenant_id
            WHERE result.tenant_id = ? AND task.business_id = ?
            ORDER BY result.created_time DESC, result.id DESC
        """
        const val AGENT_CONFIRMATIONS_SQL = """
            SELECT id, suggestion_id, confirmed_by, confirmed_time
            FROM fw_agent_suggestion_confirmation
            WHERE tenant_id = ? AND task_id = ?
            ORDER BY confirmed_time DESC, id DESC
        """
        const val OUTBOX_SQL = """
            SELECT id, event_type, event_status, retry_count, last_error, created_time, updated_time
            FROM fw_outbox_event WHERE tenant_id = ? AND payload_json ->> 'documentId' = ? ORDER BY created_time DESC
        """
    }
}
