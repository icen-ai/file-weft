package com.fileweft.web.runtime.v1.workflow

import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.workflow.DocumentWorkflowPageCursor
import com.fileweft.application.workflow.DocumentWorkflowPageRequest
import com.fileweft.application.workflow.WorkflowTaskPageCursor
import com.fileweft.application.workflow.WorkflowTaskPageRequest
import com.fileweft.application.workflow.WorkflowQueryService
import com.fileweft.application.workflow.WorkflowTaskInboxItemView
import com.fileweft.application.workflow.WorkflowView
import com.fileweft.web.api.ApiPage
import com.fileweft.web.api.v1.document.DocumentDto
import com.fileweft.web.api.v1.workflow.DocumentWorkflowDto
import com.fileweft.web.api.v1.workflow.DocumentWorkflowPageQuery
import com.fileweft.web.api.v1.workflow.WorkflowHistoryTaskDto
import com.fileweft.web.api.v1.workflow.WorkflowTaskDto
import com.fileweft.web.api.v1.workflow.WorkflowTaskInboxItemDto
import com.fileweft.web.api.v1.workflow.WorkflowTaskPageQuery
import com.fileweft.web.runtime.v1.document.DocumentApiInputs

/** Pure-JVM mapping boundary for formal workflow inbox and history reads. */
class WorkflowApiReadFacade(
    private val workflows: WorkflowQueryService,
) {
    private val taskCursors = WorkflowPageCursorCodec(WorkflowPageCursorCodec.TASK_KIND)
    private val historyCursors = WorkflowPageCursorCodec(WorkflowPageCursorCodec.HISTORY_KIND)

    fun pendingTasks(query: WorkflowTaskPageQuery): ApiPage<WorkflowTaskInboxItemDto> {
        val result = workflows.pendingTasks(
            WorkflowTaskPageRequest(
                cursor = query.cursor?.let(taskCursors::decode)?.let { cursor ->
                    WorkflowTaskPageCursor(cursor.createdTime, cursor.id)
                },
                limit = query.limit,
            ),
        )
        return ApiPage(
            items = result.items.map { item -> item.toDto() },
            nextCursor = result.nextCursor?.let { cursor -> taskCursors.encode(cursor.createdTime, cursor.id) },
        )
    }

    fun documentHistory(
        documentId: String,
        query: DocumentWorkflowPageQuery,
    ): ApiPage<DocumentWorkflowDto> {
        val result = workflows.documentHistory(
            DocumentApiInputs.documentId(documentId),
            DocumentWorkflowPageRequest(
                cursor = query.cursor?.let(historyCursors::decode)?.let { cursor ->
                    DocumentWorkflowPageCursor(cursor.createdTime, cursor.id)
                },
                limit = query.limit,
            ),
        )
        return ApiPage(
            items = result.items.map { workflow -> workflow.toDto() },
            nextCursor = result.nextCursor?.let { cursor -> historyCursors.encode(cursor.createdTime, cursor.id) },
        )
    }

    private fun WorkflowTaskInboxItemView.toDto(): WorkflowTaskInboxItemDto = WorkflowTaskInboxItemDto(
        task = WorkflowTaskDto(
            id = task.id.value,
            workflowId = task.workflowId.value,
            state = task.state.name,
            createdTime = task.createdTime,
            updatedTime = task.updatedTime,
            assignedToCurrentUser = task.assignedToCurrentUser,
        ),
        document = document.toDto(),
        workflowType = workflowType,
        workflowState = workflowState.name,
    )

    private fun WorkflowView.toDto(): DocumentWorkflowDto = DocumentWorkflowDto(
        id = id.value,
        documentId = documentId.value,
        workflowType = workflowType,
        state = state.name,
        createdTime = createdTime,
        updatedTime = updatedTime,
        tasks = tasks.map { task ->
            WorkflowHistoryTaskDto(
                id = task.id.value,
                state = task.state.name,
                createdTime = task.createdTime,
                updatedTime = task.updatedTime,
            )
        },
    )

    private fun DocumentSummaryView.toDto(): DocumentDto = DocumentDto(
        id = id.value,
        documentNumber = documentNumber,
        title = title,
        lifecycleState = lifecycleState.name,
        createdTime = createdTime,
        updatedTime = updatedTime,
        currentVersionId = currentVersionId?.value,
        folderId = folderId,
    )
}
