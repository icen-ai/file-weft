package ai.icen.fw.application.doctor

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.task.BackgroundTaskLease
import ai.icen.fw.application.task.LeasedTaskHandler
import ai.icen.fw.application.task.TaskLeaseLostException
import ai.icen.fw.application.task.TaskMutationRepository
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorReport
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.task.TaskExecution
import ai.icen.fw.spi.task.TaskHandlingResult
import ai.icen.fw.spi.task.TaskHandlingStatus
import java.time.Clock

/** Persists read-only document diagnostics as recoverable background work. */
class DocumentDoctorTaskHandler private constructor(
    private val doctor: DoctorApplicationService,
    private val reports: DoctorReportRepository,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
    private val taskMutations: TaskMutationRepository?,
    private val fenced: Boolean,
) : LeasedTaskHandler {
    /** Retains the original handler ABI and its idempotent, unfenced behavior. */
    constructor(
        doctor: DoctorApplicationService,
        reports: DoctorReportRepository,
        transaction: ApplicationTransaction,
        clock: Clock,
    ) : this(doctor, reports, transaction, clock, null, false)

    /**
     * Strong production path. A report is written only while the exact task
     * lease remains current under a row lock.
     */
    constructor(
        doctor: DoctorApplicationService,
        reports: DoctorReportRepository,
        transaction: ApplicationTransaction,
        clock: Clock,
        taskMutations: TaskMutationRepository,
    ) : this(doctor, reports, transaction, clock, taskMutations, true)

    init {
        require(fenced == (taskMutations != null)) {
            "Doctor task projection fencing requires a task mutation repository."
        }
    }

    override fun supports(task: TaskExecution): Boolean = task.type == TASK_TYPE

    override fun handle(task: TaskExecution): TaskHandlingResult {
        if (fenced) {
            return TaskHandlingResult(
                TaskHandlingStatus.PERMANENT_FAILURE,
                "Doctor task projection requires the current persisted task lease.",
            )
        }
        return handleLegacy(task)
    }

    override fun handle(lease: BackgroundTaskLease): TaskHandlingResult {
        if (!fenced) return handleLegacy(lease.task.execution())
        if (lease.leaseToken == null) {
            throw TaskLeaseLostException("Doctor task projection requires a persisted lease token.")
        }
        val task = lease.task.execution()
        val documentId = task.businessId
            ?: return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "Doctor task does not contain a document business id.")
        val report = doctor.inspectDocumentAsSystem(task.tenantId, documentId)
        transaction.execute {
            val state = requireNotNull(taskMutations).findForMutation(task.tenantId, task.id)
                ?: throw TaskLeaseLostException("Doctor task no longer exists in the current tenant.")
            state.requireCurrentLease(lease)
            reports.save(task.tenantId, documentId, task.id, report)
        }
        return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
    }

    override fun onExhausted(task: TaskExecution, message: String) {
        if (fenced) return
        persistLegacyFailure(task, message)
    }

    override fun onExhausted(lease: BackgroundTaskLease, message: String) {
        if (!fenced) {
            persistLegacyFailure(lease.task.execution(), message)
            return
        }
        val task = lease.task.execution()
        val documentId = task.businessId ?: return
        val report = exhaustedReport(task.tenantId, documentId, task.id, message)
        transaction.execute {
            val state = requireNotNull(taskMutations).findForMutation(task.tenantId, task.id) ?: return@execute
            if (state.matchesFailedTask(lease)) {
                reports.save(task.tenantId, documentId, task.id, report)
            }
        }
    }

    private fun handleLegacy(task: TaskExecution): TaskHandlingResult {
        val documentId = task.businessId
            ?: return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "Doctor task does not contain a document business id.")
        val report = doctor.inspectDocumentAsSystem(task.tenantId, documentId)
        transaction.execute { reports.save(task.tenantId, documentId, task.id, report) }
        return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
    }

    private fun persistLegacyFailure(task: TaskExecution, message: String) {
        val documentId = task.businessId ?: return
        val report = exhaustedReport(task.tenantId, documentId, task.id, message)
        transaction.execute { reports.save(task.tenantId, documentId, task.id, report) }
    }

    private fun exhaustedReport(
        tenantId: Identifier,
        documentId: Identifier,
        taskId: Identifier,
        message: String,
    ): DoctorReport = DoctorReport(
        tenantId = tenantId,
        documentId = documentId,
        checks = listOf(
            DoctorCheckResult(
                checkerName = TASK_CHECKER_NAME,
                status = DoctorStatus.ERROR,
                reason = "Asynchronous doctor task could not complete.",
                evidence = mapOf("taskId" to taskId.value, "message" to message),
                repairSuggestion = "Inspect the task failure and retry the diagnostic request after its dependency is restored.",
            ),
        ),
        inspectedAt = clock.millis(),
    )

    companion object {
        const val TASK_TYPE = "document.doctor.requested"
        const val TASK_CHECKER_NAME = "doctor-task"
    }
}
