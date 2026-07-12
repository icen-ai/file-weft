package com.fileweft.web.runtime.v1.workflow;

import com.fileweft.application.workflow.WorkflowQueryService;
import com.fileweft.web.api.ApiPage;
import com.fileweft.web.api.v1.workflow.DocumentWorkflowDto;
import com.fileweft.web.api.v1.workflow.DocumentWorkflowPageQuery;
import com.fileweft.web.api.v1.workflow.WorkflowTaskInboxItemDto;
import com.fileweft.web.api.v1.workflow.WorkflowTaskPageQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowApiReadFacadeInteropTest {

    @Test
    void readsWorkflowInboxAndHistoryThroughTheStableJavaSurface() throws NoSuchMethodException {
        WorkflowApiReadFacade facade = JavaWorkflowRuntimeFixtures.facade();

        ApiPage<WorkflowTaskInboxItemDto> inbox = facade.pendingTasks(new WorkflowTaskPageQuery());
        ApiPage<DocumentWorkflowDto> history = facade.documentHistory(
            "document-java", new DocumentWorkflowPageQuery()
        );

        assertEquals("task-java", inbox.getItems().get(0).getTask().getId());
        assertEquals("document-java", inbox.getItems().get(0).getDocument().getId());
        assertTrue(inbox.getItems().get(0).getActionableByCurrentUser());
        assertEquals("workflow-java", history.getItems().get(0).getId());
        assertEquals("APPROVED", history.getItems().get(0).getTasks().get(0).getState());
        assertNotNull(inbox.getNextCursor());
        assertNotNull(history.getNextCursor());
        assertNotNull(WorkflowApiReadFacade.class.getConstructor(WorkflowQueryService.class));
        assertEquals(
            ApiPage.class,
            WorkflowApiReadFacade.class.getMethod("pendingTasks", WorkflowTaskPageQuery.class).getReturnType()
        );
        assertEquals(
            ApiPage.class,
            WorkflowApiReadFacade.class.getMethod(
                "documentHistory", String.class, DocumentWorkflowPageQuery.class
            ).getReturnType()
        );
    }
}
