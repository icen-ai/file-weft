package com.fileweft.application.doctor

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorReport
import com.fileweft.core.result.DoctorStatus
import com.fileweft.spi.task.FileWeftTaskHandler
import com.fileweft.spi.task.TaskExecution
import com.fileweft.spi.task.TaskHandlingResult
import com.fileweft.spi.task.TaskHandlingStatus
import java.time.Clock

/** Persists read-only document diagnostics as recoverable background work. */
class DocumentDoctorTaskHandler(
    private val doctor: DoctorApplicationService,
    private val reports: DoctorReportRepository,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
) : FileWeftTaskHandler {
    override fun supports(task: TaskExecution): Boolean = task.type == TASK_TYPE

    override fun handle(task: TaskExecution): TaskHandlingResult {
        val documentId = task.businessId
            ?: return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "Doctor task does not contain a document business id.")
        val report = doctor.inspectDocumentAsSystem(task.tenantId, documentId)
        transaction.execute { reports.save(task.tenantId, documentId, task.id, report) }
        return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
    }

    override fun onExhausted(task: TaskExecution, message: String) {
        val documentId = task.businessId ?: return
        val report = DoctorReport(
            tenantId = task.tenantId,
            documentId = documentId,
            checks = listOf(
                DoctorCheckResult(
                    checkerName = TASK_CHECKER_NAME,
                    status = DoctorStatus.ERROR,
                    reason = "Asynchronous doctor task could not complete.",
                    evidence = mapOf("taskId" to task.id.value, "message" to message),
                    repairSuggestion = "Inspect the task failure and retry the diagnostic request after its dependency is restored.",
                ),
            ),
            inspectedAt = clock.millis(),
        )
        transaction.execute { reports.save(task.tenantId, documentId, task.id, report) }
    }

    companion object {
        const val TASK_TYPE = "document.doctor.requested"
        const val TASK_CHECKER_NAME = "doctor-task"
    }
}
