package com.fileweft.application.document

import com.fileweft.application.security.ApplicationForbiddenException
import com.fileweft.application.security.ApplicationUnauthenticatedException
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.LifecycleState
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DocumentQueryServiceTest {
    @Test
    fun `reads a redacted detail through trusted tenant user and document authorization`() {
        val queries = RecordingQueries(detail = detail())
        val authorization = RecordingAuthorization()
        val transaction = RecordingTransaction()
        val service = service(queries, authorization, transaction)

        val result = service.detail(Identifier("document-a"))

        assertEquals("DOC-document-a", result.document.documentNumber)
        assertEquals("inbox", result.document.folderId)
        assertEquals("version-a", result.versions.single().id.value)
        assertEquals(Identifier("tenant-a"), queries.lastDetailTenantId)
        assertEquals(Identifier("document-a"), queries.lastDetailDocumentId)
        assertEquals(1, transaction.executions)
        assertEquals(Identifier("user-a"), authorization.lastRequest?.subject?.id)
        assertEquals("USER", authorization.lastRequest?.subject?.type)
        assertEquals(Identifier("document-a"), authorization.lastRequest?.resource?.id)
        assertEquals(DocumentQueryService.DOCUMENT_RESOURCE_TYPE, authorization.lastRequest?.resource?.type)
        assertEquals(Identifier("tenant-a"), authorization.lastRequest?.resource?.tenantId)
        assertEquals(DocumentQueryService.DOCUMENT_READ_ACTION, authorization.lastRequest?.action?.name)
    }

    @Test
    fun `rejects a denied detail before it reaches the read port or transaction`() {
        val queries = RecordingQueries()
        val transaction = RecordingTransaction()
        val service = service(queries, RecordingAuthorization(allowed = false), transaction)

        assertThrows<ApplicationForbiddenException> { service.detail(Identifier("document-a")) }

        assertEquals(0, queries.detailCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `uses the document page authorization resource before a tenant scoped query`() {
        val page = DocumentPageResult(
            listOf(summary("document-b", updatedTime = 100), summary("document-a", updatedTime = 90)),
            DocumentPageCursor(90, Identifier("document-a")),
        )
        val queries = RecordingQueries(page = page)
        val authorization = RecordingAuthorization()
        val transaction = RecordingTransaction()
        val folderAccess = RecordingFolderReadAccess(
            transaction = transaction,
            readableFolderIds = setOf("finance"),
        )
        val request = DocumentPageRequest(
            cursor = DocumentPageCursor(110, Identifier("document-c")),
            limit = 20,
            lifecycleState = LifecycleState.PUBLISHED,
            folderId = "finance",
        )
        val service = service(queries, authorization, transaction, folderReadAccess = folderAccess)

        val result = service.page(request)

        assertSame(page, result)
        assertSame(request, queries.lastPageRequest)
        assertEquals(Identifier("tenant-a"), queries.lastPageTenantId)
        assertEquals(listOf("finance"), queries.lastPageFolderReadScope?.folderIds)
        assertEquals(1, transaction.executions)
        assertEquals("finance", folderAccess.lastFolderId)
        assertFalse(folderAccess.calledInTransaction)
        assertEquals(DocumentQueryService.DOCUMENT_PAGE_RESOURCE_ID, authorization.lastRequest?.resource?.id)
        assertEquals(DocumentQueryService.DOCUMENT_PAGE_RESOURCE_TYPE, authorization.lastRequest?.resource?.type)
        assertEquals(DocumentQueryService.DOCUMENT_READ_ACTION, authorization.lastRequest?.action?.name)
    }

    @Test
    fun `rejects a denied page before it reaches the read port or transaction`() {
        val queries = RecordingQueries()
        val transaction = RecordingTransaction()
        val service = service(queries, RecordingAuthorization(allowed = false), transaction)

        assertThrows<ApplicationForbiddenException> { service.page(DocumentPageRequest()) }

        assertEquals(0, queries.pageCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `allows an unfiltered page without a host catalog integration`() {
        val queries = RecordingQueries(page = DocumentPageResult(listOf(summary("document-a"))))
        val transaction = RecordingTransaction()
        val service = service(queries, RecordingAuthorization(), transaction)

        val page = service.page(DocumentPageRequest())

        assertEquals(listOf("document-a"), page.items.map { it.id.value })
        assertEquals(1, queries.pageCalls)
        assertEquals(1, transaction.executions)
    }

    @Test
    fun `constrains unfiltered pages and document detail reads to the trusted catalog folder scope`() {
        val queries = RecordingQueries(
            detail = detail(),
            page = DocumentPageResult(listOf(summary("document-a"))),
        )
        val transaction = RecordingTransaction()
        val folderAccess = RecordingFolderReadAccess(
            transaction = transaction,
            readableFolderIds = setOf("finance", "contracts"),
        )
        val service = service(queries, RecordingAuthorization(), transaction, folderReadAccess = folderAccess)

        service.detail(Identifier("document-a"))
        service.page(DocumentPageRequest())

        assertEquals(listOf("finance", "contracts"), queries.lastDetailFolderReadScope?.folderIds)
        assertEquals(listOf("finance", "contracts"), queries.lastPageFolderReadScope?.folderIds)
        assertEquals(2, folderAccess.readableFolderScopeCalls)
        assertFalse(folderAccess.readableScopeCalledInTransaction)
    }

    @Test
    fun `does not fall back to unfiltered reads when the trusted catalog grants no folder visibility`() {
        val queries = RecordingQueries(detail = detail(), page = DocumentPageResult(listOf(summary("document-a"))))
        val transaction = RecordingTransaction()
        val service = service(
            queries,
            RecordingAuthorization(),
            transaction,
            folderReadAccess = RecordingFolderReadAccess(readableFolderIds = emptySet()),
        )

        assertThrows<DocumentNotFoundException> { service.detail(Identifier("document-a")) }
        val page = service.page(DocumentPageRequest())

        assertEquals(emptyList(), page.items)
        assertEquals(0, queries.detailCalls)
        assertEquals(0, queries.pageCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `normalizes a folder query before both catalog access and persistence filtering`() {
        val queries = RecordingQueries(page = DocumentPageResult(listOf(summary("document-a"))))
        val transaction = RecordingTransaction()
        val folderAccess = RecordingFolderReadAccess(readableFolderIds = setOf("finance"))
        val service = service(queries, RecordingAuthorization(), transaction, folderReadAccess = folderAccess)

        service.page(DocumentPageRequest(folderId = " finance "))

        assertEquals("finance", folderAccess.lastFolderId)
        assertEquals("finance", queries.lastPageRequest?.folderId)
        assertEquals(listOf("finance"), queries.lastPageFolderReadScope?.folderIds)
    }

    @Test
    fun `rejects a folder filter without catalog access before it reaches the read port or transaction`() {
        val queries = RecordingQueries()
        val transaction = RecordingTransaction()
        val service = service(queries, RecordingAuthorization(), transaction)

        val failure = assertThrows<DocumentFolderReadAccessUnavailableException> {
            service.page(DocumentPageRequest(folderId = "finance"))
        }

        assertEquals(DocumentFolderReadAccessUnavailableException.DEFAULT_MESSAGE, failure.message)
        assertEquals(0, queries.pageCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `does not query a folder filtered page when host catalog access hides or denies the folder`() {
        val hiddenQueries = RecordingQueries()
        val hiddenTransaction = RecordingTransaction()
        val hiddenService = service(
            hiddenQueries,
            RecordingAuthorization(),
            hiddenTransaction,
            folderReadAccess = RecordingFolderReadAccess(IllegalArgumentException("Folder is hidden.")),
        )

        assertThrows<IllegalArgumentException> { hiddenService.page(DocumentPageRequest(folderId = "hidden")) }
        assertEquals(0, hiddenQueries.pageCalls)
        assertEquals(0, hiddenTransaction.executions)

        val deniedQueries = RecordingQueries()
        val deniedTransaction = RecordingTransaction()
        val deniedService = service(
            deniedQueries,
            RecordingAuthorization(),
            deniedTransaction,
            folderReadAccess = RecordingFolderReadAccess(ApplicationForbiddenException()),
        )

        assertThrows<ApplicationForbiddenException> { deniedService.page(DocumentPageRequest(folderId = "finance")) }
        assertEquals(0, deniedQueries.pageCalls)
        assertEquals(0, deniedTransaction.executions)
    }

    @Test
    fun `uses the same not found result for an absent current tenant document`() {
        val queries = RecordingQueries(detail = null)
        val service = service(queries, RecordingAuthorization(), RecordingTransaction())

        assertThrows<DocumentNotFoundException> { service.detail(Identifier("document-a")) }

        assertEquals(Identifier("tenant-a"), queries.lastDetailTenantId)
        assertEquals(Identifier("document-a"), queries.lastDetailDocumentId)
    }

    @Test
    fun `requires a current trusted user before any public document query`() {
        val queries = RecordingQueries()
        val authorization = RecordingAuthorization()
        val transaction = RecordingTransaction()
        val service = DocumentQueryService(
            tenantProvider = tenantProvider(),
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity? = null
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = authorization,
            queries = queries,
            transaction = transaction,
        )

        assertThrows<ApplicationUnauthenticatedException> { service.page(DocumentPageRequest()) }

        assertEquals(null, authorization.lastRequest)
        assertEquals(0, queries.pageCalls)
        assertEquals(0, transaction.executions)
    }

    @Test
    fun `keeps public projections immutable and omits persistence sensitive properties`() {
        val detail = detail()
        val page = DocumentPageResult(listOf(detail.document))

        @Suppress("UNCHECKED_CAST")
        val versions = detail.versions as MutableList<DocumentVersionView>
        @Suppress("UNCHECKED_CAST")
        val items = page.items as MutableList<DocumentSummaryView>
        assertFailsWith<UnsupportedOperationException> { versions.add(version("version-b")) }
        assertFailsWith<UnsupportedOperationException> { items.add(summary("document-b")) }

        val forbiddenGetterNames = setOf(
            "getTenantId", "getAssetId", "getStoragePath", "getStorageType",
            "getFileObjectId", "getExternalId", "getError", "getPayload", "getContentHash",
        )
        val publicViewTypes = listOf(
            DocumentPageRequest::class.java,
            DocumentPageCursor::class.java,
            DocumentSummaryView::class.java,
            DocumentVersionView::class.java,
            DocumentDetailView::class.java,
            DocumentPageResult::class.java,
        )
        val getterNames = publicViewTypes.flatMap { type ->
            type.methods.filter { method -> method.parameterCount == 0 }.map { method -> method.name }
        }.toSet()
        assertTrue(forbiddenGetterNames.none(getterNames::contains))

        val publicServiceMethods = DocumentQueryService::class.java.declaredMethods
            .filter { method -> method.name == "detail" || method.name == "page" }
        assertEquals(2, publicServiceMethods.size)
        assertTrue(publicServiceMethods.none { method ->
            method.parameterTypes.any { type -> type == TenantProvider::class.java || type == UserRealmProvider::class.java }
        })
    }

    @Test
    fun `validates bounded page inputs without accepting blank folder filters`() {
        assertFailsWith<IllegalArgumentException> { DocumentPageRequest(limit = 0) }
        assertFailsWith<IllegalArgumentException> { DocumentPageRequest(limit = 101) }
        assertFailsWith<IllegalArgumentException> { DocumentPageRequest(folderId = " ") }
        assertFailsWith<IllegalArgumentException> { DocumentPageRequest(folderId = "finance\nprivate") }
        assertFailsWith<IllegalArgumentException> { DocumentPageRequest(folderId = "a".repeat(257)) }
        assertFailsWith<IllegalArgumentException> { DocumentPageCursor(-1, Identifier("document-a")) }
        assertFailsWith<IllegalArgumentException> {
            DocumentDetailView(summary("document-a"), listOf(version("version-a"), version("version-a")))
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentDetailView(summary("document-a", currentVersionId = Identifier("missing")), listOf(version("version-a")))
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentPageResult(List(DocumentPageRequest.MAX_LIMIT + 1) { summary("document-$it") })
        }
    }

    private fun service(
        queries: DocumentQueryRepository,
        authorization: AuthorizationProvider,
        transaction: ApplicationTransaction,
        folderReadAccess: DocumentFolderReadAccess? = null,
    ) = DocumentQueryService(
        tenantProvider = tenantProvider(),
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-a"), "User A")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = authorization,
        queries = queries,
        transaction = transaction,
        folderReadAccess = folderReadAccess,
    )

    private fun tenantProvider(): TenantProvider = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
    }

    private fun detail() = DocumentDetailView(
        document = summary("document-a", updatedTime = 100),
        versions = listOf(version("version-a")),
    )

    private fun summary(
        id: String,
        updatedTime: Long = 100,
        currentVersionId: Identifier? = Identifier("version-a"),
    ) = DocumentSummaryView(
        id = Identifier(id),
        documentNumber = "DOC-$id",
        title = "Document $id",
        lifecycleState = LifecycleState.DRAFT,
        createdTime = 10,
        updatedTime = updatedTime,
        currentVersionId = currentVersionId,
        folderId = "inbox",
    )

    private fun version(id: String) = DocumentVersionView(
        id = Identifier(id),
        versionNumber = "1.0",
        fileName = "contract.pdf",
        contentLength = 12,
        createdTime = 10,
        updatedTime = 20,
        contentType = "application/pdf",
    )

    private class RecordingQueries(
        private val detail: DocumentDetailView? = null,
        private val page: DocumentPageResult = DocumentPageResult(emptyList()),
    ) : DocumentQueryRepository {
        var detailCalls: Int = 0
        var pageCalls: Int = 0
        var lastDetailTenantId: Identifier? = null
        var lastDetailDocumentId: Identifier? = null
        var lastDetailFolderReadScope: DocumentFolderReadScope? = null
        var lastPageTenantId: Identifier? = null
        var lastPageRequest: DocumentPageRequest? = null
        var lastPageFolderReadScope: DocumentFolderReadScope? = null

        override fun findDetail(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentDetailView? {
            detailCalls++
            lastDetailTenantId = tenantId
            lastDetailDocumentId = documentId
            lastDetailFolderReadScope = folderReadScope
            return detail
        }

        override fun findPage(
            tenantId: Identifier,
            request: DocumentPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentPageResult {
            pageCalls++
            lastPageTenantId = tenantId
            lastPageRequest = request
            lastPageFolderReadScope = folderReadScope
            return page
        }
    }

    private class RecordingAuthorization(
        private val allowed: Boolean = true,
    ) : AuthorizationProvider {
        var lastRequest: AuthorizationRequest? = null

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            lastRequest = request
            return AuthorizationDecision(allowed, "denied")
        }
    }

    private class RecordingFolderReadAccess(
        private val failure: RuntimeException? = null,
        private val transaction: RecordingTransaction? = null,
        private val readableFolderIds: Set<String> = setOf("inbox"),
    ) : DocumentFolderReadAccess {
        var lastFolderId: String? = null
        var calledInTransaction: Boolean = false
        var readableFolderScopeCalls: Int = 0
        var readableScopeCalledInTransaction: Boolean = false

        override fun requireFolderForDocumentRead(folderId: String) {
            lastFolderId = folderId
            calledInTransaction = transaction?.active == true
            failure?.let { throw it }
        }

        override fun readableFolderIds(): Set<String> {
            readableFolderScopeCalls++
            readableScopeCalledInTransaction = transaction?.active == true
            failure?.let { throw it }
            return readableFolderIds
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
