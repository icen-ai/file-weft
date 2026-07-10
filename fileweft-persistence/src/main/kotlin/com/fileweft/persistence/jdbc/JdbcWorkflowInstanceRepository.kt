package com.fileweft.persistence.jdbc

import com.fileweft.core.id.Identifier
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.domain.workflow.WorkflowState
import com.fileweft.domain.workflow.WorkflowTask
import com.fileweft.domain.workflow.WorkflowTaskState
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
            "SELECT id, tenant_id, workflow_id, assignee_id, task_state, comment_text FROM fw_workflow_task WHERE tenant_id = ? AND workflow_id = ? ORDER BY created_time, id",
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
                )
                tasks
            }
        }

    private fun saveTask(task: WorkflowTask, now: Long) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_workflow_task(id, tenant_id, workflow_id, assignee_id, task_state, comment_text, created_time, updated_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
            SET assignee_id = EXCLUDED.assignee_id,
                task_state = EXCLUDED.task_state,
                comment_text = EXCLUDED.comment_text,
                updated_time = EXCLUDED.updated_time
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, task.id.value)
            statement.setString(2, task.tenantId.value)
            statement.setString(3, task.workflowId.value)
            statement.setString(4, task.assigneeId?.value)
            statement.setString(5, task.state.name)
            statement.setString(6, task.comment)
            statement.setLong(7, now)
            statement.setLong(8, now)
            statement.executeUpdate()
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
