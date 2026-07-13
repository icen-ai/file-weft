package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentSummaryView
import ai.icen.fw.application.workflow.DocumentWorkflowPageCursor
import ai.icen.fw.application.workflow.DocumentWorkflowPageRequest
import ai.icen.fw.application.workflow.DocumentWorkflowPageResult
import ai.icen.fw.application.workflow.DocumentWorkflowDecisionEvidencePageResult
import ai.icen.fw.application.workflow.WorkflowDecisionEvidenceView
import ai.icen.fw.application.workflow.WorkflowDecisionTaskEvidenceView
import ai.icen.fw.application.workflow.WorkflowHistoryTaskView
import ai.icen.fw.application.workflow.WorkflowQueryRepository
import ai.icen.fw.application.workflow.WorkflowTaskInboxItemView
import ai.icen.fw.application.workflow.WorkflowTaskPageCursor
import ai.icen.fw.application.workflow.WorkflowTaskPageRequest
import ai.icen.fw.application.workflow.WorkflowTaskPageResult
import ai.icen.fw.application.workflow.WorkflowTaskView
import ai.icen.fw.application.workflow.WorkflowView
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTaskState
import java.sql.Array as JdbcArray
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.LinkedHashMap

/**
 * PostgreSQL workflow read model using the caller-bound application transaction.
 *
 * Both queries apply the trusted tenant and optional folder scope in SQL. The
 * document-history query deliberately emits a visible-document sentinel from
 * the same statement: no row means hidden/missing, while a sentinel without a
 * workflow means a visible document whose history is empty.
 */
class JdbcWorkflowQueryRepository : WorkflowQueryRepository {
    override fun findPendingTaskPage(
        tenantId: Identifier,
        currentUserId: Identifier,
        request: WorkflowTaskPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): WorkflowTaskPageResult {
        val connection = JdbcConnectionContext.requireCurrent()
        val visibilityArray = connection.createFolderVisibilityArray(folderReadScope)
        return try {
            connection.prepareStatement(pendingTaskSql(request, folderReadScope)).use { statement ->
                statement.bindPendingTaskPage(tenantId, currentUserId, request, visibilityArray)
                statement.executeQuery().use { result -> mapPendingTaskPage(result, request.limit) }
            }
        } finally {
            (visibilityArray as? JdbcArray)?.free()
        }
    }

    override fun findDocumentWorkflowPage(
        tenantId: Identifier,
        documentId: Identifier,
        request: DocumentWorkflowPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentWorkflowPageResult? {
        val connection = JdbcConnectionContext.requireCurrent()
        val visibilityArray = connection.createFolderVisibilityArray(folderReadScope)
        return try {
            connection.prepareStatement(documentHistorySql(request, folderReadScope)).use { statement ->
                statement.bindDocumentHistory(tenantId, documentId, request, visibilityArray)
                statement.executeQuery().use { result -> mapDocumentHistory(result, request.limit) }
            }
        } finally {
            (visibilityArray as? JdbcArray)?.free()
        }
    }

    fun findDocumentWorkflowDecisionEvidencePage(
        tenantId: Identifier,
        documentId: Identifier,
        request: DocumentWorkflowPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentWorkflowDecisionEvidencePageResult? {
        val connection = JdbcConnectionContext.requireCurrent()
        val visibilityArray = connection.createFolderVisibilityArray(folderReadScope)
        return try {
            connection.prepareStatement(documentDecisionEvidenceSql(request, folderReadScope)).use { statement ->
                statement.bindDocumentHistory(tenantId, documentId, request, visibilityArray)
                statement.executeQuery().use { result ->
                    mapDocumentDecisionEvidence(result, documentId, request.limit)
                }
            }
        } finally {
            (visibilityArray as? JdbcArray)?.free()
        }
    }

    private fun mapPendingTaskPage(result: ResultSet, limit: Int): WorkflowTaskPageResult {
        val rows = ArrayList<WorkflowTaskInboxItemView>()
        while (result.next()) {
            val task = WorkflowTaskView(
                id = Identifier(result.getString("task_id")),
                workflowId = Identifier(result.getString("workflow_id")),
                state = WorkflowTaskState.PENDING,
                createdTime = result.getLong("task_created_time"),
                updatedTime = result.getLong("task_updated_time"),
                assignedToCurrentUser = result.getBoolean("assigned_to_current_user"),
            )
            rows += WorkflowTaskInboxItemView(
                task = task,
                document = DocumentSummaryView(
                    id = Identifier(result.getString("document_id")),
                    documentNumber = result.getString("document_number"),
                    title = result.getString("document_title"),
                    lifecycleState = LifecycleState.PENDING_REVIEW,
                    createdTime = result.getLong("document_created_time"),
                    updatedTime = result.getLong("document_updated_time"),
                    currentVersionId = result.getString("current_version_id")?.let(::Identifier),
                    folderId = result.getString("folder_id"),
                ),
                workflowType = result.getString("workflow_type"),
                workflowState = WorkflowState.PENDING,
            )
        }
        val hasNext = rows.size > limit
        val items = if (hasNext) ArrayList(rows.subList(0, limit)) else rows
        val nextCursor = if (hasNext) {
            items.last().task.let { task -> WorkflowTaskPageCursor(task.createdTime, task.id) }
        } else {
            null
        }
        return WorkflowTaskPageResult(items, nextCursor)
    }

    private fun mapDocumentHistory(result: ResultSet, limit: Int): DocumentWorkflowPageResult? {
        var visibleDocument = false
        val workflows = LinkedHashMap<String, WorkflowAccumulator>()
        while (result.next()) {
            visibleDocument = true
            val workflowId = result.getString("workflow_id") ?: continue
            val workflow = workflows.getOrPut(workflowId) {
                WorkflowAccumulator(
                    id = Identifier(workflowId),
                    documentId = Identifier(result.getString("workflow_document_id")),
                    workflowType = result.getString("workflow_type"),
                    state = WorkflowState.valueOf(result.getString("workflow_state")),
                    createdTime = result.getLong("workflow_created_time"),
                    updatedTime = result.getLong("workflow_updated_time"),
                )
            }
            workflow.tasks += WorkflowHistoryTaskView(
                id = Identifier(checkNotNull(result.getString("task_id")) {
                    "A queryable workflow history row is missing its task identifier."
                }),
                state = WorkflowTaskState.valueOf(result.getString("task_state")),
                createdTime = result.getLong("task_created_time"),
                updatedTime = result.getLong("task_updated_time"),
            )
        }
        if (!visibleDocument) return null

        val rows = workflows.values.map { workflow -> workflow.toView() }
        val hasNext = rows.size > limit
        val items = if (hasNext) ArrayList(rows.subList(0, limit)) else rows
        val nextCursor = if (hasNext) {
            items.last().let { workflow -> DocumentWorkflowPageCursor(workflow.createdTime, workflow.id) }
        } else {
            null
        }
        return DocumentWorkflowPageResult(items, nextCursor)
    }

    private fun mapDocumentDecisionEvidence(
        result: ResultSet,
        documentId: Identifier,
        limit: Int,
    ): DocumentWorkflowDecisionEvidencePageResult? {
        var visibleDocument = false
        val workflows = LinkedHashMap<String, WorkflowDecisionEvidenceAccumulator>()
        while (result.next()) {
            visibleDocument = true
            val workflowId = result.getString("workflow_id") ?: continue
            val workflow = workflows.getOrPut(workflowId) {
                WorkflowDecisionEvidenceAccumulator(
                    id = Identifier(workflowId),
                    documentId = Identifier(result.getString("workflow_document_id")),
                    workflowType = result.getString("workflow_type"),
                    state = WorkflowState.valueOf(result.getString("workflow_state")),
                    createdTime = result.getLong("workflow_created_time"),
                    updatedTime = result.getLong("workflow_updated_time"),
                )
            }
            workflow.tasks += WorkflowDecisionTaskEvidenceView(
                id = Identifier(checkNotNull(result.getString("task_id")) {
                    "A workflow decision evidence row is missing its task identifier."
                }),
                state = WorkflowTaskState.valueOf(result.getString("task_state")),
                createdTime = result.getLong("task_created_time"),
                updatedTime = result.getLong("task_updated_time"),
                decisionOperatorId = result.getString("decision_operator_id")?.let(::Identifier),
                decisionOperatorName = result.getString("decision_operator_name"),
                decidedTime = result.getLong("decided_time").takeUnless { result.wasNull() },
            )
        }
        if (!visibleDocument) return null

        val rows = workflows.values.map { workflow -> workflow.toView() }
        val hasNext = rows.size > limit
        val items = if (hasNext) ArrayList(rows.subList(0, limit)) else rows
        val nextCursor = if (hasNext) {
            items.last().let { workflow -> DocumentWorkflowPageCursor(workflow.createdTime, workflow.id) }
        } else {
            null
        }
        return DocumentWorkflowDecisionEvidencePageResult(documentId, items, nextCursor)
    }

    private fun PreparedStatement.bindPendingTaskPage(
        tenantId: Identifier,
        currentUserId: Identifier,
        request: WorkflowTaskPageRequest,
        visibilityArray: Any?,
    ) {
        var index = 1
        setString(index++, currentUserId.value)
        setString(index++, tenantId.value)
        setString(index++, currentUserId.value)
        setFolderVisibilityParameter(index++, visibilityArray)
        request.cursor?.let { cursor ->
            setLong(index++, cursor.createdTime)
            setLong(index++, cursor.createdTime)
            setString(index++, cursor.id.value)
        }
        setInt(index, request.limit + 1)
    }

    private fun PreparedStatement.bindDocumentHistory(
        tenantId: Identifier,
        documentId: Identifier,
        request: DocumentWorkflowPageRequest,
        visibilityArray: Any?,
    ) {
        var index = 1
        setString(index++, tenantId.value)
        setString(index++, documentId.value)
        setFolderVisibilityParameter(index++, visibilityArray)
        setString(index++, tenantId.value)
        request.cursor?.let { cursor ->
            setLong(index++, cursor.createdTime)
            setLong(index++, cursor.createdTime)
            setString(index++, cursor.id.value)
        }
        setInt(index, request.limit + 1)
    }

    private fun pendingTaskSql(
        request: WorkflowTaskPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): String = buildString {
        val folderExpression = folderIdExpression()
        append(pendingTaskSelect(folderExpression))
        appendFolderVisibility(folderReadScope, folderExpression)
        request.cursor?.let {
            append(" AND (task.created_time < ? OR (task.created_time = ? AND task.id < ?))")
        }
        append(" ORDER BY task.created_time DESC, task.id DESC LIMIT ?")
    }

    private fun documentHistorySql(
        request: DocumentWorkflowPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): String = buildString {
        val folderExpression = folderIdExpression()
        append(documentHistoryVisibleCte(folderExpression))
        appendFolderVisibility(folderReadScope, folderExpression)
        append(DOCUMENT_HISTORY_WORKFLOW_CTE)
        request.cursor?.let {
            append(" AND (workflow.created_time < ? OR (workflow.created_time = ? AND workflow.id < ?))")
        }
        append(documentHistorySelect())
    }

    private fun documentDecisionEvidenceSql(
        request: DocumentWorkflowPageRequest,
        folderReadScope: DocumentFolderReadScope?,
    ): String = buildString {
        val folderExpression = folderIdExpression()
        append(documentHistoryVisibleCte(folderExpression))
        appendFolderVisibility(folderReadScope, folderExpression)
        append(DOCUMENT_HISTORY_WORKFLOW_CTE)
        append(DOCUMENT_DECISION_EVIDENCE_FILTER)
        request.cursor?.let {
            append(" AND (workflow.created_time < ? OR (workflow.created_time = ? AND workflow.id < ?))")
        }
        append(documentDecisionEvidenceSelect())
    }

    private fun StringBuilder.appendFolderVisibility(
        folderReadScope: DocumentFolderReadScope?,
        folderExpression: String,
    ) {
        if (folderReadScope == null) return
        if (folderReadScope.isEmpty) {
            append(" AND 1 = 0")
        } else {
            append(" AND ").append(JdbcConnectionContext.requireDialect().arrayContainsAny(folderExpression, "?"))
        }
    }

    private fun folderIdExpression(): String =
        "COALESCE(NULLIF(${JdbcConnectionContext.requireDialect().jsonExtractText("asset.metadata_json", "catalog.folder-id")}, ''), 'inbox')"

    private fun java.sql.Connection.createFolderVisibilityArray(
        folderReadScope: DocumentFolderReadScope?,
    ): Any? = folderReadScope
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

    private class WorkflowAccumulator(
        val id: Identifier,
        val documentId: Identifier,
        val workflowType: String,
        val state: WorkflowState,
        val createdTime: Long,
        val updatedTime: Long,
    ) {
        val tasks = ArrayList<WorkflowHistoryTaskView>()

        fun toView(): WorkflowView = WorkflowView(
            id = id,
            documentId = documentId,
            workflowType = workflowType,
            state = state,
            createdTime = createdTime,
            updatedTime = updatedTime,
            tasks = tasks,
        )
    }

    private class WorkflowDecisionEvidenceAccumulator(
        val id: Identifier,
        val documentId: Identifier,
        val workflowType: String,
        val state: WorkflowState,
        val createdTime: Long,
        val updatedTime: Long,
    ) {
        val tasks = ArrayList<WorkflowDecisionTaskEvidenceView>()

        fun toView(): WorkflowDecisionEvidenceView = WorkflowDecisionEvidenceView(
            id = id,
            documentId = documentId,
            workflowType = workflowType,
            state = state,
            createdTime = createdTime,
            updatedTime = updatedTime,
            tasks = tasks,
        )
    }

    private companion object {
        private val DOCUMENT_HISTORY_WORKFLOW_CTE = """
            ), workflow_page AS (
                SELECT workflow.id,
                       workflow.tenant_id,
                       workflow.document_id,
                       workflow.workflow_type,
                       workflow.state,
                       workflow.created_time,
                       workflow.updated_time
                FROM fw_workflow_instance workflow
                JOIN visible_document document ON document.id = workflow.document_id
                WHERE workflow.tenant_id = ?
                  AND workflow.state IN ('PENDING', 'APPROVED', 'REJECTED')
                  AND workflow.created_time >= 0
                  AND workflow.updated_time >= workflow.created_time
                  AND ${dialectTrim("workflow.workflow_type")} <> ''
                  AND EXISTS (
                      SELECT 1
                      FROM fw_workflow_task task
                      WHERE task.tenant_id = workflow.tenant_id
                        AND task.workflow_id = workflow.id
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM fw_workflow_task task
                      WHERE task.tenant_id = workflow.tenant_id
                        AND task.workflow_id = workflow.id
                        AND (
                            task.task_state NOT IN ('PENDING', 'APPROVED', 'REJECTED')
                            OR task.created_time < 0
                            OR task.updated_time < task.created_time
                        )
                  )
                  AND (
                      (
                          workflow.state = 'PENDING'
                          AND EXISTS (
                              SELECT 1 FROM fw_workflow_task task
                              WHERE task.tenant_id = workflow.tenant_id
                                AND task.workflow_id = workflow.id
                                AND task.task_state = 'PENDING'
                          )
                          AND NOT EXISTS (
                              SELECT 1 FROM fw_workflow_task task
                              WHERE task.tenant_id = workflow.tenant_id
                                AND task.workflow_id = workflow.id
                                AND task.task_state = 'REJECTED'
                          )
                      )
                      OR (
                          workflow.state = 'APPROVED'
                          AND NOT EXISTS (
                              SELECT 1 FROM fw_workflow_task task
                              WHERE task.tenant_id = workflow.tenant_id
                                AND task.workflow_id = workflow.id
                                AND task.task_state <> 'APPROVED'
                          )
                      )
                      OR (
                          workflow.state = 'REJECTED'
                          AND EXISTS (
                              SELECT 1 FROM fw_workflow_task task
                              WHERE task.tenant_id = workflow.tenant_id
                                AND task.workflow_id = workflow.id
                                AND task.task_state = 'REJECTED'
                          )
                      )
                  )
        """

        const val DOCUMENT_DECISION_EVIDENCE_FILTER = """
                  AND NOT EXISTS (
                      SELECT 1
                      FROM fw_workflow_task evidence_task
                      WHERE evidence_task.tenant_id = workflow.tenant_id
                        AND evidence_task.workflow_id = workflow.id
                        AND (
                            (
                                evidence_task.task_state = 'PENDING'
                                AND (
                                    evidence_task.decision_operator_id IS NOT NULL
                                    OR evidence_task.decision_operator_name IS NOT NULL
                                    OR evidence_task.decided_time IS NOT NULL
                                )
                            )
                            OR (
                                evidence_task.decision_operator_name IS NOT NULL
                                AND evidence_task.decision_operator_id IS NULL
                            )
                            OR (
                                (evidence_task.decision_operator_id IS NULL)
                                <> (evidence_task.decided_time IS NULL)
                            )
                            OR (
                                evidence_task.decision_operator_id IS NOT NULL
                                AND evidence_task.task_state NOT IN ('APPROVED', 'REJECTED')
                            )
                            OR evidence_task.decided_time < evidence_task.created_time
                            OR evidence_task.decided_time > evidence_task.updated_time
                        )
                  )
        """

        private fun pendingTaskSelect(folderExpression: String): String = """
            SELECT task.id AS task_id,
                   task.workflow_id,
                   task.created_time AS task_created_time,
                   task.updated_time AS task_updated_time,
                   (task.assignee_id = ?) AS assigned_to_current_user,
                   workflow.workflow_type,
                   document.id AS document_id,
                   document.doc_no AS document_number,
                   document.title AS document_title,
                   document.current_version_id,
                   document.created_time AS document_created_time,
                   document.updated_time AS document_updated_time,
                   $folderExpression AS folder_id
            FROM fw_workflow_task task
            JOIN fw_workflow_instance workflow
              ON workflow.tenant_id = task.tenant_id
             AND workflow.id = task.workflow_id
            JOIN fw_document document
              ON document.tenant_id = workflow.tenant_id
             AND document.id = workflow.document_id
            JOIN fw_asset asset
              ON asset.tenant_id = document.tenant_id
             AND asset.id = document.asset_id
            WHERE task.tenant_id = ?
              AND task.task_state = 'PENDING'
              AND workflow.state = 'PENDING'
              AND document.lifecycle_state = 'PENDING_REVIEW'
              AND (task.assignee_id IS NULL OR task.assignee_id = ?)
              AND task.created_time >= 0
              AND task.updated_time >= task.created_time
              AND workflow.created_time >= 0
              AND workflow.updated_time >= workflow.created_time
              AND document.created_time >= 0
              AND document.updated_time >= document.created_time
              AND ${dialectTrim("workflow.workflow_type")} <> ''
              AND ${dialectTrim("document.doc_no")} <> ''
              AND ${dialectTrim("document.title")} <> ''
              AND NOT EXISTS (
                  SELECT 1
                  FROM fw_workflow_task sibling
                  WHERE sibling.tenant_id = workflow.tenant_id
                    AND sibling.workflow_id = workflow.id
                    AND (
                        sibling.task_state NOT IN ('PENDING', 'APPROVED')
                        OR sibling.created_time < 0
                        OR sibling.updated_time < sibling.created_time
                    )
              )
        """

        private fun documentHistoryVisibleCte(folderExpression: String): String = """
            WITH visible_document AS (
                SELECT document.id
                FROM fw_document document
                JOIN fw_asset asset
                  ON asset.tenant_id = document.tenant_id
                 AND asset.id = document.asset_id
                WHERE document.tenant_id = ?
                  AND document.id = ?
        """

        private fun documentHistorySelect(): String {
            val dialect = JdbcConnectionContext.requireDialect()
            return """
                ORDER BY workflow.created_time DESC, workflow.id DESC
                LIMIT ?
            )
            SELECT document.id AS visible_document_id,
                   workflow.id AS workflow_id,
                   workflow.document_id AS workflow_document_id,
                   workflow.workflow_type,
                   workflow.state AS workflow_state,
                   workflow.created_time AS workflow_created_time,
                   workflow.updated_time AS workflow_updated_time,
                   task.id AS task_id,
                   task.task_state,
                   task.created_time AS task_created_time,
                   task.updated_time AS task_updated_time
            FROM visible_document document
            LEFT JOIN workflow_page workflow ON TRUE
            LEFT JOIN fw_workflow_task task
              ON task.tenant_id = workflow.tenant_id
             AND task.workflow_id = workflow.id
            ORDER BY ${dialect.nullsLastOrderBy("workflow.created_time")},
                     ${dialect.nullsLastOrderBy("workflow.id")},
                     task.created_time,
                     task.id
            """
        }

        private fun documentDecisionEvidenceSelect(): String {
            val dialect = JdbcConnectionContext.requireDialect()
            return """
                ORDER BY workflow.created_time DESC, workflow.id DESC
                LIMIT ?
            )
            SELECT document.id AS visible_document_id,
                   workflow.id AS workflow_id,
                   workflow.document_id AS workflow_document_id,
                   workflow.workflow_type,
                   workflow.state AS workflow_state,
                   workflow.created_time AS workflow_created_time,
                   workflow.updated_time AS workflow_updated_time,
                   task.id AS task_id,
                   task.task_state,
                   task.created_time AS task_created_time,
                   task.updated_time AS task_updated_time,
                   task.decision_operator_id,
                   task.decision_operator_name,
                   task.decided_time
            FROM visible_document document
            LEFT JOIN workflow_page workflow ON TRUE
            LEFT JOIN fw_workflow_task task
              ON task.tenant_id = workflow.tenant_id
             AND task.workflow_id = workflow.id
            ORDER BY ${dialect.nullsLastOrderBy("workflow.created_time")},
                     ${dialect.nullsLastOrderBy("workflow.id")},
                     task.created_time,
                     task.id
            """
        }

        private fun dialectTrim(expression: String): String = JdbcConnectionContext.requireDialect().trim(expression)
    }
}
