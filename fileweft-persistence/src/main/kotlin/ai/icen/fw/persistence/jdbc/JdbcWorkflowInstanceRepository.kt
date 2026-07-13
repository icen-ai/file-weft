package ai.icen.fw.persistence.jdbc

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.workflow.WorkflowInstance
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.domain.workflow.WorkflowState
import ai.icen.fw.domain.workflow.WorkflowTask
import ai.icen.fw.domain.workflow.WorkflowTaskState
import java.sql.ResultSet
import java.time.Clock

class JdbcWorkflowInstanceRepository(
    private val clock: Clock,
) : WorkflowInstanceRepository {
    override fun findById(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? =
        find(tenantId, "SELECT id, tenant_id, document_id, workflow_type, state FROM fw_workflow_instance WHERE tenant_id = ? AND id = ?") { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, workflowId.value)
        }

    override fun findForDecision(tenantId: Identifier, workflowId: Identifier): WorkflowInstance? =
        find(tenantId, "SELECT id, tenant_id, document_id, workflow_type, state FROM fw_workflow_instance WHERE tenant_id = ? AND id = ? FOR UPDATE") { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, workflowId.value)
        }

    override fun findActiveByDocument(tenantId: Identifier, documentId: Identifier): WorkflowInstance? =
        find(
            tenantId,
            "SELECT id, tenant_id, document_id, workflow_type, state FROM fw_workflow_instance WHERE tenant_id = ? AND document_id = ? AND state = 'PENDING' ORDER BY created_time DESC, id DESC LIMIT 1",
        ) { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, documentId.value)
        }

    override fun save(workflow: WorkflowInstance) {
        val now = clock.millis()
        val connection = JdbcConnectionContext.requireCurrent()
        val updated = connection.prepareStatement(
            "UPDATE fw_workflow_instance SET document_id = ?, workflow_type = ?, state = ?, updated_time = ? WHERE tenant_id = ? AND id = ?",
        ).use { statement ->
            statement.setString(1, workflow.documentId.value)
            statement.setString(2, workflow.workflowType)
            statement.setString(3, workflow.state.name)
            statement.setLong(4, now)
            statement.setString(5, workflow.tenantId.value)
            statement.setString(6, workflow.id.value)
            statement.executeUpdate()
        }
        if (updated == 0) {
            connection.prepareStatement(
                "INSERT INTO fw_workflow_instance(id, tenant_id, document_id, workflow_type, state, created_time, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, workflow.id.value)
                statement.setString(2, workflow.tenantId.value)
                statement.setString(3, workflow.documentId.value)
                statement.setString(4, workflow.workflowType)
                statement.setString(5, workflow.state.name)
                statement.setLong(6, now)
                statement.setLong(7, now)
                statement.executeUpdate()
            }
        }
        workflow.tasks.forEach { saveTask(it, now) }
    }

    private fun find(
        tenantId: Identifier,
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): WorkflowInstance? = JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { result ->
            if (!result.next()) return null
            return mapWorkflow(result, loadTasks(tenantId, Identifier(result.getString("id"))))
        }
    }

    private fun loadTasks(tenantId: Identifier, workflowId: Identifier): List<WorkflowTask> =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "SELECT id, tenant_id, workflow_id, assignee_id, task_state, comment_text, decision_operator_id, decision_operator_name FROM fw_workflow_task WHERE tenant_id = ? AND workflow_id = ? ORDER BY created_time, id",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, workflowId.value)
            statement.executeQuery().use { result ->
                val tasks = ArrayList<WorkflowTask>()
                while (result.next()) tasks += WorkflowTask(
                    id = Identifier(result.getString("id")),
                    tenantId = Identifier(result.getString("tenant_id")),
                    workflowId = Identifier(result.getString("workflow_id")),
                    assigneeId = result.getString("assignee_id")?.let(::Identifier),
                    state = WorkflowTaskState.valueOf(result.getString("task_state")),
                    comment = result.getString("comment_text"),
                    decisionOperatorId = result.getString("decision_operator_id")?.let(::Identifier),
                    decisionOperatorName = result.getString("decision_operator_name"),
                )
                tasks
            }
        }

    private fun saveTask(task: WorkflowTask, now: Long) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_workflow_task(
                id, tenant_id, workflow_id, assignee_id, task_state, comment_text,
                decision_operator_id, decision_operator_name, decided_time, created_time, updated_time
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
            SET task_state = EXCLUDED.task_state,
                comment_text = EXCLUDED.comment_text,
                decision_operator_id = CASE
                    WHEN fw_workflow_task.task_state = 'PENDING'
                        THEN EXCLUDED.decision_operator_id
                    ELSE fw_workflow_task.decision_operator_id
                END,
                decision_operator_name = CASE
                    WHEN fw_workflow_task.task_state = 'PENDING'
                        THEN EXCLUDED.decision_operator_name
                    ELSE fw_workflow_task.decision_operator_name
                END,
                decided_time = CASE
                    WHEN fw_workflow_task.decided_time IS NOT NULL
                        THEN fw_workflow_task.decided_time
                    WHEN fw_workflow_task.task_state = 'PENDING'
                        THEN EXCLUDED.decided_time
                    ELSE NULL
                END,
                updated_time = CASE
                    WHEN ROW(
                        fw_workflow_task.task_state,
                        fw_workflow_task.comment_text,
                        fw_workflow_task.decision_operator_id,
                        fw_workflow_task.decision_operator_name
                    ) IS DISTINCT FROM ROW(
                        EXCLUDED.task_state,
                        EXCLUDED.comment_text,
                        EXCLUDED.decision_operator_id,
                        EXCLUDED.decision_operator_name
                    ) THEN EXCLUDED.updated_time
                    ELSE fw_workflow_task.updated_time
                END
            WHERE fw_workflow_task.tenant_id = EXCLUDED.tenant_id
              AND fw_workflow_task.workflow_id = EXCLUDED.workflow_id
              AND fw_workflow_task.assignee_id IS NOT DISTINCT FROM EXCLUDED.assignee_id
              AND (
                  fw_workflow_task.task_state = 'PENDING'
                  OR (
                      fw_workflow_task.task_state = EXCLUDED.task_state
                      AND fw_workflow_task.comment_text IS NOT DISTINCT FROM EXCLUDED.comment_text
                      AND fw_workflow_task.decision_operator_id IS NOT DISTINCT FROM EXCLUDED.decision_operator_id
                      AND fw_workflow_task.decision_operator_name IS NOT DISTINCT FROM EXCLUDED.decision_operator_name
                  )
              )
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, task.id.value)
            statement.setString(2, task.tenantId.value)
            statement.setString(3, task.workflowId.value)
            statement.setString(4, task.assigneeId?.value)
            statement.setString(5, task.state.name)
            statement.setString(6, task.comment)
            statement.setString(7, task.decisionOperatorId?.value)
            statement.setString(8, task.decisionOperatorName)
            if (task.decisionOperatorId == null) statement.setNull(9, java.sql.Types.BIGINT) else statement.setLong(9, now)
            statement.setLong(10, now)
            statement.setLong(11, now)
            check(statement.executeUpdate() == 1) {
                "Workflow task identity or completed decision changed while saving ${task.id.value}."
            }
        }
    }

    private fun mapWorkflow(result: ResultSet, tasks: List<WorkflowTask>): WorkflowInstance = WorkflowInstance(
        id = Identifier(result.getString("id")),
        tenantId = Identifier(result.getString("tenant_id")),
        documentId = Identifier(result.getString("document_id")),
        workflowType = result.getString("workflow_type"),
        state = WorkflowState.valueOf(result.getString("state")),
        tasks = tasks,
    )
}
