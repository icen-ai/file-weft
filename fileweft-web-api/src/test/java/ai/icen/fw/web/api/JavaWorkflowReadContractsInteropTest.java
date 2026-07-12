package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.document.DocumentDto;
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowDto;
import ai.icen.fw.web.api.v1.workflow.DocumentWorkflowPageQuery;
import ai.icen.fw.web.api.v1.workflow.WorkflowHistoryTaskDto;
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskDto;
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskInboxItemDto;
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskPageQuery;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowReadContractsInteropTest {

    @Test
    void constructsAndReadsWorkflowContractsFromJava() {
        DocumentDto document = new DocumentDto(
            "document-1", "DOC-1", "Tax certificate", "PENDING_REVIEW", 1L, 2L,
            "version-1", "finance"
        );
        WorkflowTaskDto unassigned = new WorkflowTaskDto("task-1", "workflow-1", "PENDING", 1L, 2L);
        WorkflowTaskDto assigned = new WorkflowTaskDto("task-2", "workflow-1", "PENDING", 1L, 2L, true);
        WorkflowTaskInboxItemDto inbox = new WorkflowTaskInboxItemDto(
            assigned, document, "DUAL_REVIEW", "PENDING"
        );
        WorkflowHistoryTaskDto historyTask = new WorkflowHistoryTaskDto("task-2", "APPROVED", 1L, 2L);
        DocumentWorkflowDto history = new DocumentWorkflowDto(
            "workflow-1", "document-1", "DUAL_REVIEW", "APPROVED", 1L, 2L,
            Collections.singletonList(historyTask)
        );
        WorkflowTaskPageQuery defaultInboxQuery = new WorkflowTaskPageQuery();
        WorkflowTaskPageQuery inboxQuery = new WorkflowTaskPageQuery("cursor-1", 25);
        DocumentWorkflowPageQuery defaultHistoryQuery = new DocumentWorkflowPageQuery();
        DocumentWorkflowPageQuery historyQuery = new DocumentWorkflowPageQuery("cursor-2", 30);

        assertFalse(unassigned.getAssignedToCurrentUser());
        assertTrue(assigned.getAssignedToCurrentUser());
        assertTrue(inbox.getActionableByCurrentUser());
        assertEquals("document-1", inbox.getDocument().getId());
        assertEquals("DUAL_REVIEW", inbox.getWorkflowType());
        assertEquals("APPROVED", history.getState());
        assertEquals("task-2", history.getTasks().get(0).getId());
        assertEquals(20, defaultInboxQuery.getLimit());
        assertNull(defaultInboxQuery.getCursor());
        assertEquals("cursor-1", inboxQuery.getCursor());
        assertEquals(25, inboxQuery.getLimit());
        assertEquals(20, defaultHistoryQuery.getLimit());
        assertEquals("cursor-2", historyQuery.getCursor());
        assertEquals(30, historyQuery.getLimit());
    }
}
