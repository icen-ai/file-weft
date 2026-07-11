package com.fileweft.web.runtime.v1.document

import com.fileweft.application.document.DocumentDetailView
import com.fileweft.application.document.DocumentFolderReadAccess
import com.fileweft.application.document.DocumentFolderReadScope
import com.fileweft.application.document.DocumentPageCursor
import com.fileweft.application.document.DocumentPageRequest
import com.fileweft.application.document.DocumentPageResult
import com.fileweft.application.document.DocumentQueryRepository
import com.fileweft.application.document.DocumentQueryService
import com.fileweft.application.document.DocumentSummaryView
import com.fileweft.application.document.DocumentVersionView
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
import com.fileweft.web.api.v1.document.DocumentDetailDto
import com.fileweft.web.api.v1.document.DocumentDto
import com.fileweft.web.api.v1.document.DocumentPageQuery
import com.fileweft.web.api.v1.document.DocumentVersionDto
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentApiReadFacadeTest {
    @Test
    fun `maps an authorized application detail into public document and version DTOs`() {
        val repository = RecordingQueries(
            detail = DocumentDetailView(
                document = summary("document-1", LifecycleState.PUBLISHED, currentVersionId = Identifier("version-2")),
                versions = listOf(version("version-1"), version("version-2", "2.0")),
            ),
        )
        val facade = facade(repository)

        val detail = facade.detail("document-1")

        assertEquals("document-1", detail.document.id)
        assertEquals("DOC-document-1", detail.document.documentNumber)
        assertEquals("PUBLISHED", detail.document.lifecycleState)
        assertEquals("version-2", detail.document.currentVersionId)
        assertEquals(listOf("version-1", "version-2"), detail.versions.map { it.id })
        assertEquals("2.0", detail.versions.last().versionNumber)
        assertEquals("application/pdf", detail.versions.first().contentType)
        assertEquals(Identifier("document-1"), repository.lastDetailDocumentId)

        val forbiddenGetters = setOf(
            "getTenantId", "getAssetId", "getFileObjectId", "getStoragePath", "getContentHash", "getContentLocation",
        )
        val getterNames = listOf(DocumentDetailDto::class.java, DocumentDto::class.java, DocumentVersionDto::class.java)
            .flatMap { type -> type.methods.filter { method -> method.parameterCount == 0 }.map { method -> method.name } }
            .toSet()
        assertTrue(forbiddenGetters.none(getterNames::contains))
    }

    @Test
    fun `maps page filters and round trips an opaque versioned cursor`() {
        val repository = RecordingQueries(
            page = DocumentPageResult(
                items = listOf(summary("document-2", LifecycleState.PUBLISHED)),
                nextCursor = DocumentPageCursor(200, Identifier("文档-2")),
            ),
        )
        val folderAccess = RecordingFolderAccess()
        val facade = facade(repository, folderAccess)

        val first = facade.page(DocumentPageQuery(limit = 5, lifecycleState = "PUBLISHED", folderId = "finance"))
        val cursor = assertNotNull(first.nextCursor)
        val second = facade.page(DocumentPageQuery(cursor = cursor, limit = 5, lifecycleState = "PUBLISHED", folderId = "finance"))

        assertEquals(listOf("document-2"), first.items.map { it.id })
        assertTrue(cursor.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals("finance", folderAccess.lastFolderId)
        assertEquals(LifecycleState.PUBLISHED, repository.lastPageRequest?.lifecycleState)
        assertEquals(5, repository.lastPageRequest?.limit)
        assertEquals("finance", repository.lastPageRequest?.folderId)
        assertEquals(200, repository.lastPageRequest?.cursor?.updatedTime)
        assertEquals("文档-2", repository.lastPageRequest?.cursor?.id?.value)
        assertEquals(first.items.map { it.id }, second.items.map { it.id })
    }

    @Test
    fun `rejects unsafe path identifiers lifecycle values and malformed cursors before a query runs`() {
        val repository = RecordingQueries()
        val facade = facade(repository)

        assertFailsWith<IllegalArgumentException> { facade.detail(" ") }
        assertFailsWith<IllegalArgumentException> { facade.detail("document\u0000-1") }
        assertFailsWith<IllegalArgumentException> { facade.detail("d".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { facade.page(DocumentPageQuery(lifecycleState = "UNKNOWN")) }
        assertFailsWith<IllegalArgumentException> { facade.page(DocumentPageQuery(cursor = "***")) }

        assertEquals(0, repository.detailCalls)
        assertEquals(0, repository.pageCalls)
    }

    @Test
    fun `public facade API does not accept tenant or user inputs`() {
        val constructor = DocumentApiReadFacade::class.java.constructors.single()
        val publicMethods = DocumentApiReadFacade::class.java.declaredMethods
            .filter { method -> Modifier.isPublic(method.modifiers) && !method.isSynthetic }

        assertEquals(listOf(DocumentQueryService::class.java), constructor.parameterTypes.toList())
        assertEquals(setOf("detail", "page"), publicMethods.map { it.name }.toSet())
        assertTrue(publicMethods.none { method ->
            method.parameterTypes.any { type ->
                type == TenantProvider::class.java || type == UserRealmProvider::class.java || type == Identifier::class.java
            }
        })
    }

    private fun facade(
        repository: RecordingQueries,
        folderAccess: DocumentFolderReadAccess? = null,
    ): DocumentApiReadFacade = DocumentApiReadFacade(
        DocumentQueryService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
            },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "User One")
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
            },
            queries = repository,
            transaction = DirectTransaction,
            folderReadAccess = folderAccess,
        ),
    )

    private fun summary(
        id: String,
        lifecycleState: LifecycleState = LifecycleState.DRAFT,
        currentVersionId: Identifier? = Identifier("version-1"),
    ): DocumentSummaryView = DocumentSummaryView(
        id = Identifier(id),
        documentNumber = "DOC-$id",
        title = "Document $id",
        lifecycleState = lifecycleState,
        createdTime = 100,
        updatedTime = 200,
        currentVersionId = currentVersionId,
        folderId = "finance",
    )

    private fun version(id: String, number: String = "1.0"): DocumentVersionView = DocumentVersionView(
        id = Identifier(id),
        versionNumber = number,
        fileName = "$number.pdf",
        contentLength = 64,
        createdTime = 100,
        updatedTime = 200,
        contentType = "application/pdf",
    )

    private class RecordingQueries(
        private val detail: DocumentDetailView? = null,
        private val page: DocumentPageResult = DocumentPageResult(emptyList()),
    ) : DocumentQueryRepository {
        var detailCalls: Int = 0
        var pageCalls: Int = 0
        var lastDetailDocumentId: Identifier? = null
        var lastPageRequest: DocumentPageRequest? = null

        override fun findDetail(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentDetailView? {
            detailCalls++
            lastDetailDocumentId = documentId
            return detail
        }

        override fun findPage(
            tenantId: Identifier,
            request: DocumentPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentPageResult {
            pageCalls++
            lastPageRequest = request
            return page
        }
    }

    private class RecordingFolderAccess : DocumentFolderReadAccess {
        var lastFolderId: String? = null

        override fun requireFolderForDocumentRead(folderId: String) {
            lastFolderId = folderId
        }

        override fun readableFolderIds(): Set<String> = setOf("finance")
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
