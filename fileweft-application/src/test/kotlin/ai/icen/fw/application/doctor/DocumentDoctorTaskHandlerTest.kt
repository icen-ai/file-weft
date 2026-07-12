package ai.icen.fw.application.doctor

import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.BackgroundTaskLease
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.application.task.TaskLeaseLostException
import ai.icen.fw.application.task.TaskMutationRepository
import ai.icen.fw.application.task.TaskState
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorReport
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.task.TaskHandlingStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DocumentDoctorTaskHandlerTest {
    @Test
    fun `legacy four argument handler retains direct execution and terminal projection behavior`() {
        val transaction = TrackingTransaction()
        val reports = RecordingReports(transaction)
        val handler = DocumentDoctorTaskHandler(doctor(), reports, transaction, CLOCK)
        val execution = lease().task.execution()

        assertEquals(TaskHandlingStatus.SUCCEEDED, handler.handle(execution).status)
        assertEquals(TaskHandlingStatus.SUCCEEDED, handler.handle(lease()).status)
        handler.onExhausted(execution, "dependency unavailable")
        handler.onExhausted(lease(), "leased dependency unavailable")

        assertEquals(4, reports.saved.size)
        assertEquals(DoctorStatus.HEALTHY, reports.saved.first().report.status)
        assertEquals(DoctorStatus.ERROR, reports.saved.last().report.status)
        assertEquals("leased dependency unavailable", reports.saved.last().report.checks.single().evidence["message"])
        assertTrue(reports.saved.all { it.taskId == TASK_ID && it.documentId == DOCUMENT_ID })
    }

    @Test
    fun `strong handler locks and validates the exact current lease before saving a report`() {
        val transaction = TrackingTransaction()
        val order = mutableListOf<String>()
        val reports = RecordingReports(transaction, order)
        val mutations = RecordingMutations(transaction, runningState(), order)
        val handler = DocumentDoctorTaskHandler(doctor(), reports, transaction, CLOCK, mutations)
        val lease = lease()

        val result = handler.handle(lease)

        assertEquals(TaskHandlingStatus.SUCCEEDED, result.status)
        assertEquals(listOf("lock", "save"), order)
        assertEquals(1, reports.saved.size)
        assertEquals(TENANT_ID, mutations.tenantId)
        assertEquals(TASK_ID, mutations.taskId)
        assertEquals(DoctorStatus.HEALTHY, reports.saved.single().report.status)
    }

    @Test
    fun `strong handler fails closed when the mutation row is missing stale or entered without a lease`() {
        val missingTransaction = TrackingTransaction()
        val missingReports = RecordingReports(missingTransaction)
        val missingHandler = DocumentDoctorTaskHandler(
            doctor(),
            missingReports,
            missingTransaction,
            CLOCK,
            RecordingMutations(missingTransaction, null),
        )
        assertFailsWith<TaskLeaseLostException> { missingHandler.handle(lease()) }
        assertTrue(missingReports.saved.isEmpty())

        val staleTransaction = TrackingTransaction()
        val staleReports = RecordingReports(staleTransaction)
        val staleHandler = DocumentDoctorTaskHandler(
            doctor(),
            staleReports,
            staleTransaction,
            CLOCK,
            RecordingMutations(staleTransaction, runningState(token = "new-token")),
        )
        assertFailsWith<TaskLeaseLostException> { staleHandler.handle(lease()) }
        assertTrue(staleReports.saved.isEmpty())

        assertEquals(TaskHandlingStatus.PERMANENT_FAILURE, staleHandler.handle(lease().task.execution()).status)
        assertTrue(staleReports.saved.isEmpty())
    }

    @Test
    fun `strong handler rejects a tokenless lease before running technical checkers`() {
        var checkerCalls = 0
        val transaction = TrackingTransaction()
        val reports = RecordingReports(transaction)
        val mutations = RecordingMutations(transaction, runningState())
        val handler = DocumentDoctorTaskHandler(
            doctor { checkerCalls++ },
            reports,
            transaction,
            CLOCK,
            mutations,
        )

        assertFailsWith<TaskLeaseLostException> { handler.handle(lease(token = null)) }

        assertEquals(0, checkerCalls)
        assertEquals(0, mutations.calls)
        assertTrue(reports.saved.isEmpty())
    }

    @Test
    fun `strong terminal callback projects only the exact durably failed task`() {
        val transaction = TrackingTransaction()
        val reports = RecordingReports(transaction)
        val mutations = RecordingMutations(transaction, failedState())
        val handler = DocumentDoctorTaskHandler(doctor(), reports, transaction, CLOCK, mutations)
        val lease = lease()

        handler.onExhausted(lease, "retry limit reached")

        assertEquals(1, reports.saved.size)
        assertEquals(DocumentDoctorTaskHandler.TASK_CHECKER_NAME, reports.saved.single().report.checks.single().checkerName)

        mutations.state = runningState()
        handler.onExhausted(lease, "not terminal")
        mutations.state = failedState(type = "agent.analyze")
        handler.onExhausted(lease, "wrong task type")
        handler.onExhausted(lease.task.execution(), "legacy callback must fail closed")

        assertEquals(1, reports.saved.size)
    }

    @Test
    fun `rejects a Doctor task without a document business id without touching persistence`() {
        val transaction = TrackingTransaction()
        val reports = RecordingReports(transaction)
        val mutations = RecordingMutations(transaction, null)
        val handler = DocumentDoctorTaskHandler(doctor(), reports, transaction, CLOCK, mutations)
        val task = BackgroundTask(
            TASK_ID,
            TENANT_ID,
            DocumentDoctorTaskHandler.TASK_TYPE,
            "doctor:task-1",
            status = BackgroundTaskStatus.RUNNING,
        )
        val lease = BackgroundTaskLease(task, "worker-a", "token-a")

        assertEquals(TaskHandlingStatus.PERMANENT_FAILURE, handler.handle(lease).status)
        assertTrue(reports.saved.isEmpty())
        assertEquals(0, mutations.calls)
    }

    private fun doctor(onCheck: () -> Unit = {}): DoctorApplicationService = DoctorApplicationService(
        FixedTenantProvider(),
        PermissionDoctorChecker(
            FixedUserRealmProvider(),
            FixedAuthorizationProvider(AuthorizationDecision(true)),
        ),
        listOf(
            object : DoctorChecker {
                override fun name(): String = "technical"
                override fun check(context: DoctorCheckContext): DoctorCheckResult {
                    onCheck()
                    return DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Technical diagnosis completed.")
                }
            },
        ),
        CLOCK,
    )

    private fun lease(token: String? = "token-a"): BackgroundTaskLease = BackgroundTaskLease(
        BackgroundTask(
            TASK_ID,
            TENANT_ID,
            DocumentDoctorTaskHandler.TASK_TYPE,
            "doctor:task-1",
            DOCUMENT_ID,
            status = BackgroundTaskStatus.RUNNING,
        ),
        "worker-a",
        token,
    )

    private fun runningState(token: String = "token-a"): TaskState = TaskState(
        TASK_ID,
        TENANT_ID,
        DocumentDoctorTaskHandler.TASK_TYPE,
        BackgroundTaskStatus.RUNNING,
        DOCUMENT_ID,
        "worker-a",
        token,
    )

    private fun failedState(type: String = DocumentDoctorTaskHandler.TASK_TYPE): TaskState = TaskState(
        TASK_ID,
        TENANT_ID,
        type,
        BackgroundTaskStatus.FAILED,
        DOCUMENT_ID,
    )

    private class TrackingTransaction : ApplicationTransaction {
        var active: Boolean = false
        var executions: Int = 0

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested test transaction is not expected." }
            active = true
            executions++
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private class RecordingMutations(
        private val transaction: TrackingTransaction,
        var state: TaskState?,
        private val order: MutableList<String>? = null,
    ) : TaskMutationRepository {
        var calls: Int = 0
        var tenantId: Identifier? = null
        var taskId: Identifier? = null

        override fun findForMutation(tenantId: Identifier, taskId: Identifier): TaskState? {
            check(transaction.active) { "Task mutation lookup must run in the report transaction." }
            calls++
            this.tenantId = tenantId
            this.taskId = taskId
            order?.add("lock")
            return state
        }
    }

    private class RecordingReports(
        private val transaction: TrackingTransaction,
        private val order: MutableList<String>? = null,
    ) : DoctorReportRepository {
        val saved = mutableListOf<SavedReport>()

        override fun save(tenantId: Identifier, documentId: Identifier, taskId: Identifier, report: DoctorReport) {
            check(transaction.active) { "Doctor report must be saved in a local transaction." }
            order?.add("save")
            saved += SavedReport(tenantId, documentId, taskId, report)
        }
    }

    private data class SavedReport(
        val tenantId: Identifier,
        val documentId: Identifier,
        val taskId: Identifier,
        val report: DoctorReport,
    )

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val TASK_ID = Identifier("task-1")
        val DOCUMENT_ID = Identifier("document-1")
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
    }
}
