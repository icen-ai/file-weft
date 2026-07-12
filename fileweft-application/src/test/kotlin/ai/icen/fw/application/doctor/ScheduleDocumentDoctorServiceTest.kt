package ai.icen.fw.application.doctor

import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScheduleDocumentDoctorServiceTest {
    @Test
    fun `authorizes then queues a tenant scoped Doctor task`() {
        val tasks = RecordingTasks()
        var action: String? = null
        val service = service(tasks) { request ->
            action = request.action.name
            AuthorizationDecision(true)
        }

        val task = service.schedule(Identifier("document-1"))

        assertEquals(ScheduleDocumentDoctorService.DOCTOR_ACTION, action)
        assertEquals(DocumentDoctorTaskHandler.TASK_TYPE, task.type)
        assertEquals("tenant-1", task.tenantId.value)
        assertEquals("document-1", task.businessId?.value)
        assertEquals("operator-1", task.payload["requestedBy"])
        assertEquals(task, tasks.tasks.single())
    }

    @Test
    fun `does not enqueue a Doctor task when authorization is denied`() {
        val tasks = RecordingTasks()
        val service = service(tasks) { AuthorizationDecision(false, "missing doctor permission") }

        assertFailsWith<SecurityException> { service.schedule(Identifier("document-1")) }
        assertEquals(emptyList(), tasks.tasks)
    }

    private fun service(
        tasks: RecordingTasks,
        authorization: (AuthorizationRequest) -> AuthorizationDecision,
    ) = ScheduleDocumentDoctorService(
        tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(Identifier("tenant-1")) },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser() = UserIdentity(Identifier("operator-1"), "诊断操作员")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider { override fun authorize(request: AuthorizationRequest) = authorization(request) },
        documents = object : DocumentRepository {
            private val document = Document(Identifier("document-1"), Identifier("tenant-1"), Identifier("asset-1"), "DOC-001", "诊断文档")
            override fun findById(tenantId: Identifier, documentId: Identifier) = document.takeIf { it.tenantId == tenantId && it.id == documentId }
            override fun save(document: Document) = Unit
        },
        tasks = tasks,
        identifiers = object : IdentifierGenerator { override fun nextId() = Identifier("task-1") },
        transaction = DirectTransaction,
        clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
    )

    private class RecordingTasks : TaskRepository {
        val tasks = mutableListOf<BackgroundTask>()
        override fun enqueue(task: BackgroundTask) { tasks += task }
        override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? = tasks.firstOrNull { it.tenantId == tenantId && it.id == taskId }
        override fun findByBusiness(tenantId: Identifier, businessId: Identifier, limit: Int): List<BackgroundTask> =
            tasks.filter { it.tenantId == tenantId && it.businessId == businessId }.take(limit)
    }

    private object DirectTransaction : ApplicationTransaction { override fun <T> execute(action: () -> T): T = action() }
}
