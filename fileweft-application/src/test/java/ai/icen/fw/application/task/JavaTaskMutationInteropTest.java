package ai.icen.fw.application.task;

import ai.icen.fw.application.doctor.DoctorApplicationService;
import ai.icen.fw.application.doctor.DoctorReportRepository;
import ai.icen.fw.application.doctor.DocumentDoctorTaskHandler;
import ai.icen.fw.application.transaction.ApplicationTransaction;
import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.spi.task.TaskExecution;
import ai.icen.fw.spi.task.TaskHandlingResult;
import ai.icen.fw.spi.task.TaskHandlingStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTaskMutationInteropTest {
    @Test
    void exposesJavaFriendlyStateRepositoryAndLeasedHandlerContracts() throws Exception {
        Identifier taskId = new Identifier("task-1");
        Identifier tenantId = new Identifier("tenant-1");
        Identifier documentId = new Identifier("document-1");
        BackgroundTask task = new BackgroundTask(
            taskId,
            tenantId,
            "document.doctor.requested",
            "doctor:task-1",
            documentId,
            java.util.Collections.emptyMap(),
            BackgroundTaskStatus.RUNNING,
            0,
            0L,
            null
        );
        BackgroundTaskLease lease = new BackgroundTaskLease(task, "worker-a", "token-a");
        TaskState running = new TaskState(
            taskId,
            tenantId,
            task.getType(),
            BackgroundTaskStatus.RUNNING,
            documentId,
            "worker-a",
            "token-a"
        );
        TaskState failed = new TaskState(
            taskId,
            tenantId,
            task.getType(),
            BackgroundTaskStatus.FAILED,
            documentId
        );
        TaskMutationRepository repository = (tenant, id) -> running;
        LeasedTaskHandler handler = new LeasedTaskHandler() {
            @Override
            public boolean supports(TaskExecution execution) {
                return true;
            }

            @Override
            public TaskHandlingResult handle(TaskExecution execution) {
                return new TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "lease required");
            }

            @Override
            public TaskHandlingResult handle(BackgroundTaskLease currentLease) {
                currentLease.getLeaseToken();
                return new TaskHandlingResult(TaskHandlingStatus.SUCCEEDED);
            }

            @Override
            public void onExhausted(TaskExecution execution, String message) {
                // Legacy callback remains implementable from Java.
            }

            @Override
            public void onExhausted(BackgroundTaskLease currentLease, String message) {
                // Strong callback receives the exact lease.
            }
        };

        assertSame(running, repository.findForMutation(tenantId, taskId));
        assertSame(running, running.requireCurrentLease(lease));
        assertTrue(failed.matchesFailedTask(lease));
        assertEquals(TaskHandlingStatus.SUCCEEDED, handler.handle(lease).getStatus());
        assertNotNull(TaskMutationRepository.class.getMethod("findForMutation", Identifier.class, Identifier.class));
        assertNotNull(LeasedTaskHandler.class.getMethod("handle", BackgroundTaskLease.class));
        assertNotNull(LeasedTaskHandler.class.getMethod("onExhausted", BackgroundTaskLease.class, String.class));
        assertNotNull(
            TaskState.class.getConstructor(
                Identifier.class,
                Identifier.class,
                String.class,
                BackgroundTaskStatus.class
            )
        );
        assertNotNull(
            DocumentDoctorTaskHandler.class.getConstructor(
                DoctorApplicationService.class,
                DoctorReportRepository.class,
                ApplicationTransaction.class,
                Clock.class
            )
        );
        assertNotNull(
            DocumentDoctorTaskHandler.class.getConstructor(
                DoctorApplicationService.class,
                DoctorReportRepository.class,
                ApplicationTransaction.class,
                Clock.class,
                TaskMutationRepository.class
            )
        );
    }
}
