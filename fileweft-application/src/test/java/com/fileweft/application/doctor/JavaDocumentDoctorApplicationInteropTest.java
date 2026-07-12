package com.fileweft.application.doctor;

import com.fileweft.application.document.DocumentFolderReadScope;
import com.fileweft.application.task.BackgroundTaskStatus;
import com.fileweft.core.id.Identifier;
import com.fileweft.core.result.DoctorReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaDocumentDoctorApplicationInteropTest {
    @Test
    void exposesStableJavaFriendlyDoctorReceiptsViewsAndOperations() throws Exception {
        Identifier tenantId = new Identifier("tenant-1");
        Identifier documentId = new Identifier("document-1");
        Identifier taskId = new Identifier("task-1");

        DocumentDoctorTaskReceipt receipt = new DocumentDoctorTaskReceipt(taskId, documentId);
        DocumentDoctorTaskView view = new DocumentDoctorTaskView(
            tenantId,
            taskId,
            documentId,
            BackgroundTaskStatus.PENDING,
            10L,
            10L
        );
        DocumentDoctorTaskQueryRepository repository = (
            tenant,
            document,
            task,
            folderScope
        ) -> view;

        assertEquals(taskId, receipt.getTaskId());
        assertEquals(BackgroundTaskStatus.PENDING, receipt.getStatus());
        assertNull(view.getReport());
        assertEquals(view, repository.findTask(tenantId, documentId, taskId, null));
        assertNotNull(
            DocumentDoctorTaskReceipt.class.getConstructor(Identifier.class, Identifier.class)
        );
        assertNotNull(
            DocumentDoctorTaskReceipt.class.getConstructor(
                Identifier.class,
                Identifier.class,
                BackgroundTaskStatus.class
            )
        );
        assertNotNull(
            DocumentDoctorTaskView.class.getConstructor(
                Identifier.class,
                Identifier.class,
                Identifier.class,
                BackgroundTaskStatus.class,
                long.class,
                long.class
            )
        );
        assertNotNull(
            DocumentDoctorTaskView.class.getConstructor(
                Identifier.class,
                Identifier.class,
                Identifier.class,
                BackgroundTaskStatus.class,
                long.class,
                long.class,
                DoctorReport.class
            )
        );
        assertNotNull(
            DocumentDoctorTaskQueryRepository.class.getMethod(
                "findTask",
                Identifier.class,
                Identifier.class,
                Identifier.class,
                DocumentFolderReadScope.class
            )
        );
        assertNotNull(
            IdempotentScheduleDocumentDoctorService.class.getMethod(
                "schedule",
                Identifier.class,
                String.class
            )
        );
        assertNotNull(
            IdempotentScheduleDocumentCatalogDoctorService.class.getMethod(
                "schedule",
                Identifier.class,
                String.class
            )
        );
        assertNotNull(DocumentDoctorQueryService.class.getMethod("inspect", Identifier.class));
        assertNotNull(
            DocumentDoctorTaskQueryService.class.getMethod("find", Identifier.class, Identifier.class)
        );
        assertNotNull(SystemDoctorService.class.getMethod("inspect"));
    }
}
