package com.fileweft.application.doctor

import com.fileweft.application.document.DocumentDetailView
import com.fileweft.application.document.DocumentFolderReadAccess
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.document.DocumentPageRequest
import com.fileweft.application.document.DocumentPageResult
import com.fileweft.application.document.DocumentQueryRepository
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.security.ApplicationForbiddenException
import com.fileweft.application.task.BackgroundTaskStatus
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorReport
import com.fileweft.core.result.DoctorStatus
import com.fileweft.domain.document.LifecycleState
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.doctor.DoctorChecker
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DocumentDoctorQueryServicesTest {
    @Test
    fun `document diagnosis reauthorizes and rechecks catalog visibility after technical checks`() {
        val authorization = RecordingAuthorization()
        val documents = RecordingDocuments()
        val checker = CountingChecker()
        val service = DocumentDoctorQueryService(
            FixedTenant,
            FixedUsers,
            authorization,
            documents,
            DirectTransaction,
            doctor(authorization, checker),
            FixedFolderAccess,
        )

        val report = service.inspect(DOCUMENT_ID)

        assertEquals(TENANT_ID, report.tenantId)
        assertEquals(DOCUMENT_ID, report.documentId)
        assertEquals(listOf("permission", "storage"), report.checks.map { it.checkerName })
        assertEquals(1, checker.calls)
        assertEquals(2, documents.detailCalls)
        assertEquals(listOf(listOf("finance"), listOf("finance")), documents.scopes.map { it.orEmpty() })
        assertEquals(
            listOf("document:doctor", "document:read", "document:doctor", "document:read"),
            authorization.requests.map { request -> request.action.name },
        )
    }

    @Test
    fun `document diagnosis fails closed if visibility changes while checkers run`() {
        val authorization = RecordingAuthorization()
        val documents = RecordingDocuments(visibleCalls = 1)
        val service = DocumentDoctorQueryService(
            FixedTenant,
            FixedUsers,
            authorization,
            documents,
            DirectTransaction,
            doctor(authorization, CountingChecker()),
            FixedFolderAccess,
        )

        assertFailsWith<DocumentNotFoundException> { service.inspect(DOCUMENT_ID) }
        assertEquals(2, documents.detailCalls)
    }

    @Test
    fun `read denial happens before any document query or technical checker`() {
        val authorization = RecordingAuthorization { request ->
            AuthorizationDecision(request.action.name != "document:read", "read denied")
        }
        val documents = RecordingDocuments()
        val checker = CountingChecker()
        val service = DocumentDoctorQueryService(
            FixedTenant,
            FixedUsers,
            authorization,
            documents,
            DirectTransaction,
            doctor(authorization, checker),
            FixedFolderAccess,
        )

        assertFailsWith<ApplicationForbiddenException> { service.inspect(DOCUMENT_ID) }
        assertEquals(0, documents.detailCalls)
        assertEquals(0, checker.calls)
    }

    @Test
    fun `task polling passes exact tenant document task and folder scope`() {
        val authorization = RecordingAuthorization()
        val queries = RecordingTaskQueries(
            DocumentDoctorTaskView(
                TENANT_ID,
                TASK_ID,
                DOCUMENT_ID,
                BackgroundTaskStatus.PENDING,
                10,
                10,
            ),
        )
        val service = DocumentDoctorTaskQueryService(
            FixedTenant,
            FixedUsers,
            authorization,
            queries,
            DirectTransaction,
            FixedFolderAccess,
        )

        val result = service.find(DOCUMENT_ID, TASK_ID)

        assertEquals(TASK_ID, result.taskId)
        assertEquals(TENANT_ID, queries.tenantId)
        assertEquals(DOCUMENT_ID, queries.documentId)
        assertEquals(TASK_ID, queries.taskId)
        assertEquals(listOf("finance"), queries.scope?.folderIds)
        assertNull(result.report)
    }

    @Test
    fun `task polling hides a repository scope mismatch`() {
        val authorization = RecordingAuthorization()
        val queries = RecordingTaskQueries(
            DocumentDoctorTaskView(
                Identifier("tenant-other"),
                TASK_ID,
                DOCUMENT_ID,
                BackgroundTaskStatus.PENDING,
                1,
                1,
            ),
        )
        val service = DocumentDoctorTaskQueryService(
            FixedTenant,
            FixedUsers,
            authorization,
            queries,
            DirectTransaction,
        )

        assertFailsWith<DocumentNotFoundException> { service.find(DOCUMENT_ID, TASK_ID) }
    }

    @Test
    fun `task polling fails closed if permission is revoked while resolving folder visibility`() {
        var revoked = false
        val authorization = RecordingAuthorization { request ->
            AuthorizationDecision(!revoked, if (revoked) "revoked" else null)
        }
        val queries = RecordingTaskQueries(
            DocumentDoctorTaskView(
                TENANT_ID,
                TASK_ID,
                DOCUMENT_ID,
                BackgroundTaskStatus.PENDING,
                1,
                1,
            ),
        )
        val folderAccess = object : DocumentFolderReadAccess {
            override fun requireFolderForDocumentRead(folderId: String) = Unit

            override fun readableFolderIds(): Set<String> {
                revoked = true
                return setOf("finance")
            }
        }
        val service = DocumentDoctorTaskQueryService(
            FixedTenant,
            FixedUsers,
            authorization,
            queries,
            DirectTransaction,
            folderAccess,
        )

        assertFailsWith<ApplicationForbiddenException> { service.find(DOCUMENT_ID, TASK_ID) }
        assertNull(queries.tenantId)
        assertEquals(
            listOf("document:doctor", "document:read", "document:doctor"),
            authorization.requests.map { request -> request.action.name },
        )
        assertEquals(1, authorization.requests.map { request -> request.subject }.distinct().size)
    }

    @Test
    fun `system diagnosis uses its distinct tenant scoped permission`() {
        val authorization = RecordingAuthorization()
        val checker = CountingChecker()
        val service = SystemDoctorService(
            FixedTenant,
            FixedUsers,
            authorization,
            doctor(authorization, checker),
        )

        val report = service.inspect()

        assertEquals(TENANT_ID, report.tenantId)
        assertNull(report.documentId)
        assertEquals(1, checker.calls)
        val request = authorization.requests.first()
        assertEquals(SystemDoctorService.SYSTEM_DOCTOR_ACTION, request.action.name)
        assertEquals(SystemDoctorService.SYSTEM_RESOURCE_TYPE, request.resource.type)
        assertEquals(SystemDoctorService.SYSTEM_RESOURCE_ID, request.resource.id)
        assertEquals(2, authorization.requests.size)
        assertEquals(request.subject, authorization.requests.last().subject)
    }

    @Test
    fun `system diagnosis does not return a report after permission is revoked`() {
        var calls = 0
        val authorization = RecordingAuthorization {
            calls++
            AuthorizationDecision(calls == 1, if (calls == 1) null else "revoked")
        }
        val checker = CountingChecker()
        val service = SystemDoctorService(
            FixedTenant,
            FixedUsers,
            authorization,
            doctor(authorization, checker),
        )

        assertFailsWith<ApplicationForbiddenException> { service.inspect() }
        assertEquals(1, checker.calls)
        assertEquals(2, authorization.requests.size)
        assertEquals(authorization.requests.first().subject, authorization.requests.last().subject)
    }

    private fun doctor(
        authorization: AuthorizationProvider,
        checker: DoctorChecker,
    ) = DoctorApplicationService(
        FixedTenant,
        PermissionDoctorChecker(FixedUsers, authorization),
        listOf(checker),
        CLOCK,
    )

    private class CountingChecker : DoctorChecker {
        var calls: Int = 0
            private set

        override fun name(): String = "storage"
        override fun check(context: DoctorCheckContext): DoctorCheckResult {
            calls++
            return DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Storage is reachable.")
        }
    }

    private class RecordingDocuments(
        private val visibleCalls: Int = Int.MAX_VALUE,
    ) : DocumentQueryRepository {
        var detailCalls: Int = 0
            private set
        val scopes = mutableListOf<List<String>?>()

        override fun findDetail(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentDetailView? {
            detailCalls++
            scopes += folderReadScope?.folderIds
            return DETAIL.takeIf {
                detailCalls <= visibleCalls && tenantId == TENANT_ID && documentId == DOCUMENT_ID
            }
        }

        override fun findPage(
            tenantId: Identifier,
            request: DocumentPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentPageResult = DocumentPageResult(emptyList())
    }

    private class RecordingTaskQueries(
        private val result: DocumentDoctorTaskView?,
    ) : DocumentDoctorTaskQueryRepository {
        var tenantId: Identifier? = null
        var documentId: Identifier? = null
        var taskId: Identifier? = null
        var scope: DocumentFolderReadScope? = null

        override fun findTask(
            tenantId: Identifier,
            documentId: Identifier,
            taskId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentDoctorTaskView? {
            this.tenantId = tenantId
            this.documentId = documentId
            this.taskId = taskId
            scope = folderReadScope
            return result
        }
    }

    private class RecordingAuthorization(
        private val decision: (AuthorizationRequest) -> AuthorizationDecision = { AuthorizationDecision(true) },
    ) : AuthorizationProvider {
        val requests = mutableListOf<AuthorizationRequest>()
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return decision(request)
        }
    }

    private object FixedFolderAccess : DocumentFolderReadAccess {
        override fun requireFolderForDocumentRead(folderId: String) = Unit
        override fun readableFolderIds(): Set<String> = setOf("finance")
    }

    private object FixedTenant : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
    }

    private object FixedUsers : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "诊断员")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
        val TASK_ID = Identifier("task-1")
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
        val DETAIL = DocumentDetailView(
            DocumentSummaryView(
                id = DOCUMENT_ID,
                documentNumber = "DOC-1",
                title = "Doctor document",
                lifecycleState = LifecycleState.DRAFT,
                createdTime = 1,
                updatedTime = 1,
            ),
        )
    }
}
