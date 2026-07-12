package ai.icen.fw.application.delivery

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
import ai.icen.fw.spi.delivery.DeliveryRequirement
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

class DocumentSyncStatusQueryServiceTest {
    @Test
    fun `authorizes a trusted document read and queries only the current tenant inside a transaction`() {
        val authorization = RecordingAuthorization()
        val users = RecordingUsers(user())
        val transaction = RecordingTransaction()
        val expected = status()
        val queries = RecordingQueries(expected, transaction)
        val service = service(users, authorization, queries, transaction)

        val result = service.status(Identifier("document-a"))

        assertSame(expected, result)
        assertEquals(1, users.currentUserCalls)
        assertEquals(1, authorization.requests.size)
        authorization.requests.single().also { request ->
            assertEquals(DocumentSyncStatusQueryService.READ_ACTION, request.action.name)
            assertEquals(Identifier("tenant-a"), request.resource.tenantId)
            assertEquals(Identifier("document-a"), request.resource.id)
            assertEquals("DOCUMENT", request.resource.type)
            assertEquals(Identifier("reader-a"), request.subject.id)
            assertEquals(mapOf("role" to "reader"), request.subject.attributes)
        }
        assertEquals(1, queries.calls)
        assertEquals(Identifier("tenant-a"), queries.tenantId)
        assertEquals(Identifier("document-a"), queries.documentId)
        assertNull(queries.folderScope)
        assertTrue(queries.calledInTransaction)
        assertEquals(1, transaction.executions)
        assertFalse(transaction.active)
    }

    @Test
    fun `requires authentication before authorization catalog and persistence access`() {
        val users = RecordingUsers(null)
        val authorization = RecordingAuthorization()
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(status(), transaction)
        val folders = RecordingFolderAccess(setOf("finance"), transaction)

        assertThrows<ApplicationUnauthenticatedException> {
            service(users, authorization, queries, transaction, folders).status(Identifier("document-a"))
        }

        assertEquals(1, users.currentUserCalls)
        assertTrue(authorization.requests.isEmpty())
        assertEquals(0, folders.calls)
        assertEquals(0, queries.calls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `stops a denied document read before catalog and persistence access`() {
        val authorization = RecordingAuthorization(allowed = false)
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(status(), transaction)
        val folders = RecordingFolderAccess(setOf("finance"), transaction)

        assertThrows<ApplicationForbiddenException> {
            service(RecordingUsers(user()), authorization, queries, transaction, folders)
                .status(Identifier("document-a"))
        }

        assertEquals(1, authorization.requests.size)
        assertEquals(0, folders.calls)
        assertEquals(0, queries.calls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `derives folder visibility outside the transaction and passes the immutable scope to persistence`() {
        val transaction = RecordingTransaction()
        val folders = RecordingFolderAccess(linkedSetOf("finance", "legal"), transaction)
        val queries = RecordingQueries(status(), transaction)

        service(RecordingUsers(user()), RecordingAuthorization(), queries, transaction, folders)
            .status(Identifier("document-a"))

        assertEquals(1, folders.calls)
        assertFalse(folders.calledInTransaction)
        assertEquals(listOf("finance", "legal"), queries.folderScope?.folderIds)
        assertTrue(queries.calledInTransaction)
    }

    @Test
    fun `hides an empty catalog scope as not found without opening a transaction`() {
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(status(), transaction)

        assertThrows<DocumentNotFoundException> {
            service(
                RecordingUsers(user()), RecordingAuthorization(), queries, transaction,
                RecordingFolderAccess(emptySet(), transaction),
            ).status(Identifier("document-hidden"))
        }

        assertEquals(0, queries.calls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `maps missing cross tenant and folder hidden repository results to the same not found failure`() {
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(null, transaction)

        assertThrows<DocumentNotFoundException> {
            service(RecordingUsers(user()), RecordingAuthorization(), queries, transaction)
                .status(Identifier("document-invisible"))
        }

        assertEquals(1, queries.calls)
        assertEquals(1, transaction.executions)
        assertFalse(transaction.active)
    }

    @Test
    fun `preserves a visible document with no current delivery targets`() {
        val expected = DocumentSyncStatusView(Identifier("document-empty"))
        val queries = RecordingQueries(expected)

        val result = service(
            RecordingUsers(user()), RecordingAuthorization(), queries, RecordingTransaction(),
        ).status(Identifier("document-empty"))

        assertSame(expected, result)
        assertTrue(result.deliveryTargets.isEmpty())
    }

    @Test
    fun `fails closed when a custom repository returns another document`() {
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(DocumentSyncStatusView(Identifier("document-other")), transaction)

        assertThrows<IllegalStateException> {
            service(RecordingUsers(user()), RecordingAuthorization(), queries, transaction)
                .status(Identifier("document-a"))
        }

        assertEquals(1, queries.calls)
        assertFalse(transaction.active)
    }

    @Test
    fun `propagates repository failure and always closes the transaction boundary`() {
        val failure = IllegalStateException("query backend unavailable")
        val transaction = RecordingTransaction()
        val queries = RecordingQueries(status(), transaction, failure)

        val thrown = assertThrows<IllegalStateException> {
            service(RecordingUsers(user()), RecordingAuthorization(), queries, transaction)
                .status(Identifier("document-a"))
        }

        assertSame(failure, thrown)
        assertEquals(1, queries.calls)
        assertEquals(1, transaction.executions)
        assertFalse(transaction.active)
    }

    private fun service(
        users: UserRealmProvider,
        authorization: AuthorizationProvider,
        queries: DocumentSyncStatusQueryRepository,
        transaction: ApplicationTransaction,
        folders: DocumentFolderReadAccess? = null,
    ): DocumentSyncStatusQueryService = DocumentSyncStatusQueryService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
        },
        userRealmProvider = users,
        authorizationProvider = authorization,
        queries = queries,
        transaction = transaction,
        folderReadAccess = folders,
    )

    private fun user(): UserIdentity = UserIdentity(
        Identifier("reader-a"),
        "Reader A",
        mapOf("role" to "reader"),
    )

    private fun status(): DocumentSyncStatusView = DocumentSyncStatusView(
        Identifier("document-a"),
        listOf(
            DocumentDeliveryStatusView(
                Identifier("delivery-a"), "archive", "Archive", DeliveryRequirement.REQUIRED,
                DocumentDeliveryStatus.FAILED, 3, DocumentDeliveryRemovalStatus.NOT_REQUESTED, 0,
                deliveryRetryable = true, removalRetryable = false, updatedTime = 100,
            ),
        ),
    )

    private class RecordingUsers(private val user: UserIdentity?) : UserRealmProvider {
        var currentUserCalls: Int = 0

        override fun currentUser(): UserIdentity? {
            currentUserCalls++
            return user
        }

        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingAuthorization(private val allowed: Boolean = true) : AuthorizationProvider {
        val requests = ArrayList<AuthorizationRequest>()

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            requests += request
            return AuthorizationDecision(allowed, if (allowed) null else "private host policy")
        }
    }

    private class RecordingFolderAccess(
        private val folders: Set<String>,
        private val transaction: RecordingTransaction,
    ) : DocumentFolderReadAccess {
        var calls: Int = 0
        var calledInTransaction: Boolean = false

        override fun requireFolderForDocumentRead(folderId: String) = Unit

        override fun readableFolderIds(): Set<String> {
            calls++
            calledInTransaction = transaction.active
            return folders
        }
    }

    private class RecordingQueries(
        private val result: DocumentSyncStatusView?,
        private val transaction: RecordingTransaction? = null,
        private val failure: RuntimeException? = null,
    ) : DocumentSyncStatusQueryRepository {
        var calls: Int = 0
        var tenantId: Identifier? = null
        var documentId: Identifier? = null
        var folderScope: DocumentFolderReadScope? = null
        var calledInTransaction: Boolean = false

        override fun findByDocument(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentSyncStatusView? {
            calls++
            this.tenantId = tenantId
            this.documentId = documentId
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
            check(!active) { "Nested test transaction." }
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
