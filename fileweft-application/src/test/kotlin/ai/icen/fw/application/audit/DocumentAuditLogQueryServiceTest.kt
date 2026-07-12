package ai.icen.fw.application.audit

import ai.icen.fw.application.document.DocumentFolderReadAccess
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentAuditLogQueryServiceTest {
    @Test
    fun `uses one trusted identity to authorize audit then read before querying the current tenant`() {
        val users = RecordingUsers(user())
        val authorization = RecordingAuthorization()
        val transaction = RecordingTransaction()
        val request = DocumentAuditLogPageRequest(DocumentAuditLogPageCursor(90, Identifier("audit-old")), 17)
        val expected = page("document-a")
        val queries = RecordingQueries(expected).also { it.transaction = transaction }

        val result = service(users, authorization, queries, transaction).page(Identifier("document-a"), request)

        assertSame(expected, result)
        assertEquals(1, users.calls)
        assertEquals(
            listOf(DocumentAuditLogQueryService.AUDIT_ACTION, DocumentAuditLogQueryService.READ_ACTION),
            authorization.requests.map { it.action.name },
        )
        authorization.requests.forEach { current ->
            assertEquals(Identifier("reviewer-a"), current.subject.id)
            assertEquals(mapOf("role" to "reviewer"), current.subject.attributes)
            assertEquals(Identifier("tenant-a"), current.resource.tenantId)
            assertEquals(Identifier("document-a"), current.resource.id)
            assertEquals("DOCUMENT", current.resource.type)
        }
        assertEquals(Identifier("tenant-a"), queries.tenantId)
        assertEquals(Identifier("document-a"), queries.documentId)
        assertSame(request, queries.request)
        assertNull(queries.folderScope)
        assertTrue(queries.calledInTransaction)
        assertEquals(1, transaction.executions)
        assertFalse(transaction.active)
    }

    @Test
    fun `requires authentication before authorization catalog and persistence`() {
        val authorization = RecordingAuthorization()
        val folders = RecordingFolders(setOf("finance"))
        val queries = RecordingQueries(page("document-a"))
        val transaction = RecordingTransaction()

        assertThrows<ApplicationUnauthenticatedException> {
            service(RecordingUsers(null), authorization, queries, transaction, folders)
                .page(Identifier("document-a"), DocumentAuditLogPageRequest())
        }

        assertTrue(authorization.requests.isEmpty())
        assertEquals(0, folders.calls)
        assertEquals(0, queries.calls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `fails closed before catalog and persistence when audit or read is denied`() {
        listOf(
            DocumentAuditLogQueryService.AUDIT_ACTION to listOf(DocumentAuditLogQueryService.AUDIT_ACTION),
            DocumentAuditLogQueryService.READ_ACTION to listOf(
                DocumentAuditLogQueryService.AUDIT_ACTION,
                DocumentAuditLogQueryService.READ_ACTION,
            ),
        ).forEach { (deniedAction, expectedActions) ->
            val authorization = RecordingAuthorization(deniedAction)
            val folders = RecordingFolders(setOf("finance"))
            val queries = RecordingQueries(page("document-a"))
            val transaction = RecordingTransaction()

            assertThrows<ApplicationForbiddenException> {
                service(RecordingUsers(user()), authorization, queries, transaction, folders)
                    .page(Identifier("document-a"), DocumentAuditLogPageRequest())
            }

            assertEquals(expectedActions, authorization.requests.map { it.action.name })
            assertEquals(0, folders.calls)
            assertEquals(0, queries.calls)
            assertEquals(0, transaction.executions)
        }
    }

    @Test
    fun `derives the trusted folder scope outside the database transaction`() {
        val transaction = RecordingTransaction()
        val folders = RecordingFolders(linkedSetOf("finance", "legal"), transaction)
        val queries = RecordingQueries(page("document-a")).also { it.transaction = transaction }

        service(RecordingUsers(user()), RecordingAuthorization(), queries, transaction, folders)
            .page(Identifier("document-a"), DocumentAuditLogPageRequest())

        assertEquals(1, folders.calls)
        assertFalse(folders.calledInTransaction)
        assertEquals(listOf("finance", "legal"), queries.folderScope?.folderIds)
        assertTrue(queries.calledInTransaction)
    }

    @Test
    fun `maps empty folder scope and invisible persistence results to the same not found response`() {
        val hiddenId = Identifier("document-hidden")
        val emptyQueries = RecordingQueries(page("document-hidden"))
        val emptyTransaction = RecordingTransaction()
        assertThrows<DocumentNotFoundException> {
            service(
                RecordingUsers(user()),
                RecordingAuthorization(),
                emptyQueries,
                emptyTransaction,
                RecordingFolders(emptySet()),
            ).page(hiddenId, DocumentAuditLogPageRequest())
        }
        assertEquals(0, emptyQueries.calls)
        assertEquals(0, emptyTransaction.executions)

        val invisibleQueries = RecordingQueries(null)
        val invisibleTransaction = RecordingTransaction()
        assertThrows<DocumentNotFoundException> {
            service(RecordingUsers(user()), RecordingAuthorization(), invisibleQueries, invisibleTransaction)
                .page(hiddenId, DocumentAuditLogPageRequest())
        }
        assertEquals(1, invisibleQueries.calls)
        assertEquals(1, invisibleTransaction.executions)
    }

    @Test
    fun `rejects a repository page for a different document`() {
        val queries = RecordingQueries(page("document-other"))

        val failure = assertThrows<IllegalStateException> {
            service(RecordingUsers(user()), RecordingAuthorization(), queries, RecordingTransaction())
                .page(Identifier("document-a"), DocumentAuditLogPageRequest())
        }

        assertEquals("Document audit-log query returned a page outside the requested document.", failure.message)
    }

    @Test
    fun `propagates repository failures and always leaves the transaction boundary`() {
        val expected = IllegalStateException("audit projection unavailable")
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(failure = expected).also { it.transaction = transaction }

        val observed = assertThrows<IllegalStateException> {
            service(RecordingUsers(user()), RecordingAuthorization(), queries, transaction)
                .page(Identifier("document-a"), DocumentAuditLogPageRequest())
        }

        assertSame(expected, observed)
        assertEquals(1, transaction.executions)
        assertFalse(transaction.active)
    }

    @Test
    fun `models keep pages bounded immutable and free of raw detail fields`() {
        assertThrows<IllegalArgumentException> { DocumentAuditLogPageRequest(limit = 0) }
        assertThrows<IllegalArgumentException> { DocumentAuditLogPageRequest(limit = 101) }
        assertThrows<IllegalArgumentException> {
            DocumentAuditLogView(Identifier("audit-a"), "unsafe\naction", 1)
        }
        val source = mutableListOf(log("audit-a", 10))
        val result = DocumentAuditLogPageResult(Identifier("document-a"), source)
        source.clear()

        assertEquals(listOf("audit-a"), result.items.map { it.id.value })
        assertTrue(DocumentAuditLogView::class.java.declaredFields.none { field ->
            field.name.contains("detail", ignoreCase = true) || field.name.contains("tenant", ignoreCase = true)
        })
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.items as MutableList<DocumentAuditLogView>).clear()
        }
    }

    private fun service(
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentAuditLogQueryRepository,
        transaction: ApplicationTransaction,
        folders: DocumentFolderReadAccess? = null,
    ): DocumentAuditLogQueryService = DocumentAuditLogQueryService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
        },
        userRealmProvider = users,
        authorizationProvider = authorization,
        queries = queries,
        transaction = transaction,
        folderReadAccess = folders,
    )

    private fun page(documentId: String): DocumentAuditLogPageResult = DocumentAuditLogPageResult(
        Identifier(documentId),
        listOf(log("audit-a", 100)),
    )

    private fun log(id: String, createdTime: Long): DocumentAuditLogView = DocumentAuditLogView(
        id = Identifier(id),
        action = "document:create",
        createdTime = createdTime,
        operatorId = Identifier("reviewer-a"),
        operatorName = "Reviewer A",
        traceId = Identifier("trace-a"),
    )

    private fun user(): UserIdentity = UserIdentity(
        Identifier("reviewer-a"),
        "Reviewer A",
        mapOf("role" to "reviewer"),
    )

    private class RecordingUsers(private val user: UserIdentity?) : UserRealmProvider {
        var calls: Int = 0
        override fun currentUser(): UserIdentity? {
            calls++
            return user
        }
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingAuthorization(
        private val deniedAction: String? = null,
    ) : AuthorizationProvider {
        val requests = ArrayList<AuthorizationRequest>()
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return AuthorizationDecision(request.action.name != deniedAction)
        }
    }

    private class RecordingFolders(
        private val values: Set<String>,
        private val transaction: RecordingTransaction? = null,
    ) : DocumentFolderReadAccess {
        var calls: Int = 0
        var calledInTransaction: Boolean = false
        override fun requireFolderForDocumentRead(folderId: String) = Unit
        override fun readableFolderIds(): Set<String> {
            calls++
            calledInTransaction = transaction?.active == true
            return values
        }
    }

    private class RecordingQueries(
        private val result: DocumentAuditLogPageResult? = null,
        private val failure: RuntimeException? = null,
    ) : DocumentAuditLogQueryRepository {
        var calls: Int = 0
        var tenantId: Identifier? = null
        var documentId: Identifier? = null
        var request: DocumentAuditLogPageRequest? = null
        var folderScope: DocumentFolderReadScope? = null
        var calledInTransaction: Boolean = false
        var transaction: RecordingTransaction? = null

        override fun findPage(
            tenantId: Identifier,
            documentId: Identifier,
            request: DocumentAuditLogPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentAuditLogPageResult? {
            calls++
            this.tenantId = tenantId
            this.documentId = documentId
            this.request = request
            folderScope = folderReadScope
            calledInTransaction = transaction?.active == true
            failure?.let { throw it }
            return result
        }
    }

    private class RecordingTransaction : ApplicationTransaction {
        var executions: Int = 0
        var active: Boolean = false
        override fun <T> execute(action: () -> T): T {
            executions++
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }
}
