package com.fileweft.application.workflow;

import com.fileweft.application.document.DocumentFolderReadScope;
import com.fileweft.application.document.DocumentSummaryView;
import com.fileweft.application.transaction.ApplicationTransaction;
import com.fileweft.core.context.TenantContext;
import com.fileweft.core.id.Identifier;
import com.fileweft.domain.document.LifecycleState;
import com.fileweft.domain.workflow.WorkflowState;
import com.fileweft.domain.workflow.WorkflowTaskState;
import com.fileweft.spi.authorization.AuthorizationDecision;
import com.fileweft.spi.authorization.AuthorizationProvider;
import com.fileweft.spi.identity.UserIdentity;
import com.fileweft.spi.identity.UserRealmProvider;
import com.fileweft.spi.tenant.TenantProvider;
import kotlin.jvm.functions.Function0;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowQueryInteropTest {

    @Test
    void constructsReadsAndExecutesWorkflowQueryContractsFromJava() {
        Identifier tenantId = new Identifier("tenant-java");
        Identifier userId = new Identifier("reviewer-java");
        WorkflowTaskPageCursor taskCursor = new WorkflowTaskPageCursor(100L, new Identifier("task-cursor"));
        WorkflowTaskPageRequest defaultTaskRequest = new WorkflowTaskPageRequest();
        WorkflowTaskPageRequest taskRequest = new WorkflowTaskPageRequest(taskCursor, 10);
        WorkflowTaskView task = new WorkflowTaskView(
            new Identifier("task-java"),
            new Identifier("workflow-java"),
            WorkflowTaskState.PENDING,
            100L,
            110L,
            true
        );
        DocumentSummaryView document = new DocumentSummaryView(
            new Identifier("document-java"),
            "DOC-JAVA",
            "Java document",
            LifecycleState.PENDING_REVIEW,
            90L,
            110L
        );
        WorkflowTaskInboxItemView inboxItem = new WorkflowTaskInboxItemView(
            task,
            document,
            "DOCUMENT_REVIEW",
            WorkflowState.PENDING
        );
        WorkflowTaskPageResult taskPage = new WorkflowTaskPageResult(
            Collections.singletonList(inboxItem),
            taskCursor
        );

        WorkflowHistoryTaskView historyTask = new WorkflowHistoryTaskView(
            new Identifier("task-java"),
            WorkflowTaskState.APPROVED,
            100L,
            120L
        );
        WorkflowView workflow = new WorkflowView(
            new Identifier("workflow-java"),
            new Identifier("document-java"),
            "DOCUMENT_REVIEW",
            WorkflowState.APPROVED,
            100L,
            120L,
            Collections.singletonList(historyTask)
        );
        DocumentWorkflowPageCursor historyCursor = new DocumentWorkflowPageCursor(
            100L,
            new Identifier("workflow-java")
        );
        DocumentWorkflowPageRequest defaultHistoryRequest = new DocumentWorkflowPageRequest();
        DocumentWorkflowPageRequest historyRequest = new DocumentWorkflowPageRequest(historyCursor, 10);
        DocumentWorkflowPageResult historyPage = new DocumentWorkflowPageResult(
            Collections.singletonList(workflow),
            historyCursor
        );

        WorkflowQueryRepository repository = new WorkflowQueryRepository() {
            @Override
            public WorkflowTaskPageResult findPendingTaskPage(
                Identifier queryTenantId,
                Identifier currentUserId,
                WorkflowTaskPageRequest request,
                DocumentFolderReadScope folderReadScope
            ) {
                assertEquals(tenantId, queryTenantId);
                assertEquals(userId, currentUserId);
                assertNull(folderReadScope);
                return taskPage;
            }

            @Override
            public DocumentWorkflowPageResult findDocumentWorkflowPage(
                Identifier queryTenantId,
                Identifier documentId,
                DocumentWorkflowPageRequest request,
                DocumentFolderReadScope folderReadScope
            ) {
                assertEquals(tenantId, queryTenantId);
                assertEquals("document-java", documentId.getValue());
                assertNull(folderReadScope);
                return historyPage;
            }
        };
        TenantProvider tenants = new TenantProvider() {
            @Override
            public TenantContext currentTenant() {
                return new TenantContext(tenantId);
            }
        };
        UserRealmProvider users = new UserRealmProvider() {
            @Override
            public UserIdentity currentUser() {
                return new UserIdentity(userId, "Java reviewer", Collections.emptyMap());
            }

            @Override
            public UserIdentity findUser(Identifier requestedUserId) {
                return userId.equals(requestedUserId) ? currentUser() : null;
            }
        };
        AuthorizationProvider authorization = request -> new AuthorizationDecision(true, null);
        ApplicationTransaction transaction = new ApplicationTransaction() {
            @Override
            public <T> T execute(Function0<? extends T> action) {
                return action.invoke();
            }
        };
        WorkflowQueryService service = new WorkflowQueryService(
            tenants,
            users,
            authorization,
            repository,
            transaction
        );

        WorkflowTaskPageResult loadedTasks = service.pendingTasks(taskRequest);
        DocumentWorkflowPageResult loadedHistory = service.documentHistory(
            new Identifier("document-java"),
            historyRequest
        );

        assertEquals(20, defaultTaskRequest.getLimit());
        assertEquals(20, defaultHistoryRequest.getLimit());
        assertEquals("task-cursor", taskRequest.getCursor().getId().getValue());
        assertEquals("workflow-java", historyRequest.getCursor().getId().getValue());
        assertEquals("task-java", loadedTasks.getItems().get(0).getTask().getId().getValue());
        assertTrue(task.getAssignedToCurrentUser());
        assertTrue(task.getActionableByCurrentUser());
        assertEquals("document-java", inboxItem.getDocument().getId().getValue());
        assertEquals("workflow-java", loadedHistory.getItems().get(0).getId().getValue());
        assertEquals(WorkflowTaskState.APPROVED, workflow.getTasks().get(0).getState());
        assertFalse(workflow.getTasks().isEmpty());
        assertNull(new WorkflowTaskPageResult(Collections.emptyList()).getNextCursor());
        assertNull(new DocumentWorkflowPageResult(Collections.emptyList()).getNextCursor());
    }
}
