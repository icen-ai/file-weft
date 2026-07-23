package ai.icen.fw.application.doctor

import ai.icen.fw.application.idempotency.IdempotencyKeyConflictException
import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyStoreException
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyClaim
import ai.icen.fw.application.idempotency.RequestIdempotencyRecord
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.idempotency.RequestIdempotencyStatus
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
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
import kotlin.test.assertTrue

class IdempotentScheduleDocumentDoctorServiceTest {
    @Test
    fun `fresh request and replay return one stable pending task`() {
        val fixture = fixture()

        val fresh = fixture.service.schedule(DOCUMENT_ID, "doctor-key")
        val replay = fixture.service.schedule(DOCUMENT_ID, "doctor-key")

        assertEquals(fresh.taskId, replay.taskId)
        assertEquals(DOCUMENT_ID, fresh.documentId)
        assertEquals(BackgroundTaskStatus.PENDING, fresh.status)
        val task = fixture.tasks.tasks.single()
        assertEquals(fresh.taskId, task.id)
        assertEquals(DocumentDoctorTaskHandler.TASK_TYPE, task.type)
        assertEquals(DOCUMENT_ID, task.businessId)
        assertEquals("operator-1", task.payload["requestedBy"])
        assertEquals(100, task.nextAttemptTime)
        assertEquals(1, fixture.documents.mutationReads)
        val actions = fixture.authorization.requests.map { request -> request.action.name }
        assertEquals(10, actions.size)
        assertEquals(5, actions.count { it == "document:doctor" })
        assertEquals(5, actions.count { it == "document:read" })
    }

    @Test
    fun `same key cannot be rebound to another document`() {
        val fixture = fixture()
        fixture.service.schedule(DOCUMENT_ID, "bound-key")

        assertFailsWith<IdempotencyKeyConflictException> {
            fixture.service.schedule(OTHER_DOCUMENT_ID, "bound-key")
        }

        assertEquals(1, fixture.tasks.tasks.size)
        assertEquals(1, fixture.documents.mutationReads)
    }

    @Test
    fun `read denial happens before idempotency lookup and task creation`() {
        val fixture = fixture { request ->
            AuthorizationDecision(request.action.name != "document:read", "read denied")
        }

        assertFailsWith<ApplicationForbiddenException> {
            fixture.service.schedule(DOCUMENT_ID, "denied-key")
        }

        assertEquals(listOf("document:doctor", "document:read"), fixture.authorization.requests.map { it.action.name })
        assertEquals(0, fixture.idempotency.findCalls)
        assertEquals(emptyList(), fixture.tasks.tasks)
        assertEquals(0, fixture.documents.mutationReads)
    }

    @Test
    fun `permission revoked after preparation cannot enqueue a fresh task`() {
        var authorizationCalls = 0
        val fixture = fixture { request ->
            authorizationCalls++
            val allowed = authorizationCalls < 6
            AuthorizationDecision(allowed, if (allowed) null else "revoked during preparation")
        }

        assertFailsWith<ApplicationForbiddenException> {
            fixture.service.schedule(DOCUMENT_ID, "revoked-key")
        }

        assertEquals("document:read", fixture.authorization.requests.last().action.name)
        assertEquals(1, fixture.idempotency.findCalls)
        assertEquals(emptyList(), fixture.tasks.tasks)
        assertEquals(0, fixture.documents.mutationReads)
    }

    @Test
    fun `missing task after idempotent enqueue cannot complete the request receipt`() {
        val fixture = fixture()
        fixture.tasks.hidePersistedTask = true

        val failure = assertFailsWith<IdempotencyStoreException> {
            fixture.service.schedule(DOCUMENT_ID, "missing-task-key")
        }

        assertTrue(failure.message.orEmpty().contains("could not be verified"))
        assertEquals(0, fixture.idempotency.completeCalls)
    }

    @Test
    fun `task identifier collision with unrelated durable work fails closed`() {
        val fixture = fixture()
        fixture.tasks.persistedOverride = { tenantId, taskId ->
            BackgroundTask(
                id = taskId,
                tenantId = tenantId,
                type = "agent.execute",
                idempotencyKey = "unrelated-key",
                businessId = OTHER_DOCUMENT_ID,
            )
        }

        assertFailsWith<IdempotencyStoreException> {
            fixture.service.schedule(DOCUMENT_ID, "colliding-task-key")
        }

        assertEquals(0, fixture.idempotency.completeCalls)
    }

    private fun fixture(
        decision: (AuthorizationRequest) -> AuthorizationDecision = { AuthorizationDecision(true) },
    ): Fixture {
        val transaction = DirectTransaction
        val idempotencyRepository = MemoryIdempotencyRepository()
        val tasks = RecordingTasks()
        val documents = MemoryDocuments()
        val authorization = RecordingAuthorization(decision)
        val service = IdempotentScheduleDocumentDoctorService(
            tenants = FixedTenant,
            users = FixedUsers,
            authorization = authorization,
            documents = documents,
            tasks = tasks,
            identifiers = PrefixIds("task"),
            clock = CLOCK,
            idempotency = RequestIdempotencyService(
                idempotencyRepository,
                transaction,
                PrefixIds("request"),
                CLOCK,
            ),
        )
        return Fixture(service, tasks, documents, idempotencyRepository, authorization)
    }

    private class Fixture(
        val service: IdempotentScheduleDocumentDoctorService,
        val tasks: RecordingTasks,
        val documents: MemoryDocuments,
        val idempotency: MemoryIdempotencyRepository,
        val authorization: RecordingAuthorization,
    )

    private class RecordingTasks : TaskRepository {
        val tasks = mutableListOf<BackgroundTask>()
        var hidePersistedTask: Boolean = false
        var persistedOverride: ((Identifier, Identifier) -> BackgroundTask?)? = null
        override fun enqueue(task: BackgroundTask) {
            if (tasks.none { current ->
                    current.tenantId == task.tenantId && current.idempotencyKey == task.idempotencyKey
                }
            ) {
                tasks += task
            }
        }

        override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? =
            persistedOverride?.invoke(tenantId, taskId)
                ?: if (hidePersistedTask) null else tasks.firstOrNull { it.tenantId == tenantId && it.id == taskId }

        override fun findByBusiness(
            tenantId: Identifier,
            businessId: Identifier,
            limit: Int,
        ): List<BackgroundTask> = tasks
            .filter { it.tenantId == tenantId && it.businessId == businessId }
            .take(limit)
    }

    private class MemoryDocuments : DocumentMutationRepository {
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null

        private val documents = listOf(document(DOCUMENT_ID), document(OTHER_DOCUMENT_ID))
        var mutationReads: Int = 0
            private set

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            documents.firstOrNull { it.tenantId == tenantId && it.id == documentId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            mutationReads++
            return findById(tenantId, documentId)
        }

        override fun save(document: Document) = Unit
    }

    private class MemoryIdempotencyRepository : RequestIdempotencyRepository {
        private val records = linkedMapOf<String, RequestIdempotencyRecord>()
        var findCalls: Int = 0
            private set
        var completeCalls: Int = 0
            private set

        override fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord? {
            findCalls++
            return records[key(tenantId, keyDigest)]
        }

        override fun claim(
            request: RequestIdempotency,
            newRecordId: Identifier,
            now: Long,
        ): RequestIdempotencyClaim {
            records[key(request.tenantId, request.keyDigest)]?.let { current ->
                return RequestIdempotencyClaim(current, acquired = false)
            }
            val created = RequestIdempotencyRecord(
                id = newRecordId,
                tenantId = request.tenantId,
                keyDigest = request.keyDigest,
                operatorId = request.operatorId,
                action = request.action,
                resourceType = request.resourceType,
                resourceId = request.resourceId,
                subresourceId = request.subresourceId,
                requestFingerprint = request.requestFingerprint,
                status = RequestIdempotencyStatus.IN_PROGRESS,
                result = null,
                completedTime = null,
                createdTime = now,
                updatedTime = now,
            )
            records[key(request.tenantId, request.keyDigest)] = created
            return RequestIdempotencyClaim(created, acquired = true)
        }

        override fun complete(
            recordId: Identifier,
            tenantId: Identifier,
            keyDigest: String,
            result: IdempotencyResult,
            completedAt: Long,
        ): RequestIdempotencyRecord {
            completeCalls++
            val current = requireNotNull(records[key(tenantId, keyDigest)])
            val completed = RequestIdempotencyRecord(
                id = current.id,
                tenantId = current.tenantId,
                keyDigest = current.keyDigest,
                operatorId = current.operatorId,
                action = current.action,
                resourceType = current.resourceType,
                resourceId = current.resourceId,
                subresourceId = current.subresourceId,
                requestFingerprint = current.requestFingerprint,
                status = RequestIdempotencyStatus.COMPLETED,
                result = result,
                completedTime = completedAt,
                createdTime = current.createdTime,
                updatedTime = completedAt,
            )
            records[key(tenantId, keyDigest)] = completed
            return completed
        }

        private fun key(tenantId: Identifier, digest: String): String = "${tenantId.value}\u0000$digest"
    }

    private class RecordingAuthorization(
        private val decision: (AuthorizationRequest) -> AuthorizationDecision,
    ) : AuthorizationProvider {
        val requests = mutableListOf<AuthorizationRequest>()
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return decision(request)
        }
    }

    private class PrefixIds(private val prefix: String) : IdentifierGenerator {
        private var sequence: Int = 0
        override fun nextId(): Identifier = Identifier("$prefix-${++sequence}")
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private object FixedTenant : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
    }

    private object FixedUsers : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("operator-1"), "诊断员")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
        val OTHER_DOCUMENT_ID = Identifier("document-2")
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)

        fun document(id: Identifier) = Document(
            id = id,
            tenantId = TENANT_ID,
            assetId = Identifier("asset-${id.value}"),
            documentNumber = "DOC-${id.value}",
            title = "Doctor document",
        )
    }
}
