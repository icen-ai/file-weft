package com.fileweft.application.doctor

import com.fileweft.application.task.BackgroundTask
import com.fileweft.application.task.TaskRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
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
