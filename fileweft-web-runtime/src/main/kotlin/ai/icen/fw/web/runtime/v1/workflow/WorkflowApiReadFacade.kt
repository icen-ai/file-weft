package ai.icen.fw.web.runtime.v1.workflow

import ai.icen.fw.application.document.DocumentSummaryView
import ai.icen.fw.application.workflow.DocumentWorkflowPageCursor
import ai.icen.fw.application.workflow.DocumentWorkflowPageRequest
import ai.icen.fw.application.workflow.WorkflowTaskPageCursor
import ai.icen.fw.application.workflow.WorkflowTaskPageRequest
import ai.icen.fw.application.workflow.WorkflowQueryService
import ai.icen.fw.application.workflow.WorkflowTaskInboxItemView
import ai.icen.fw.application.workflow.WorkflowView
import ai.icen.fw.web.api.ApiPage
import ai.icen.fw.web.api.v1.document.DocumentDto
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDto
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowPageQuery
import ai.icen.fw.web.api.v1.workflow.WorkflowHistoryTaskDto
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskDto
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskInboxItemDto
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskPageQuery
import ai.icen.fw.web.runtime.v1.document.DocumentApiInputs

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
